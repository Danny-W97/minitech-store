#!/usr/bin/env python3
"""A43.30: verify reviewed field/export boundaries from source metadata; never connects to a store."""
import argparse
from collections import Counter
import hashlib
import importlib.util
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('lifecycle_inventory',Path(__file__).with_name('data-lifecycle-check.py'))
lifecycle=importlib.util.module_from_spec(spec);spec.loader.exec_module(lifecycle)
require=lifecycle.require
CONTRACT='scripts/qa/contracts/technical-data-classification.json'
DECISIONS={'exported','qualification-only','covered-copy','manual-review','excluded-secret','excluded-operational','not-account-scoped','separate-copy-procedure'}
SECRETS={'password_hash','verification_token_hash','guest_access_token_hash','access_token_hash','token_hash','status_token_hash','code_hash','secret_ciphertext','credential_id','public_key','attestation_object','attestation_client_data_json','checkout_url','checkout_session_id'}
MANUAL={'metadata','moderation_note','note','label','authenticator_transports','payload','error','last_error','failure_reason','stripe_error','sync_error','requested_by'}
CORE_DEDUPE={
    **{owner+'/commerce_privacy_locks':['order_id'] for owner in ('order-service','inventory-service','promotion-service','delivery-service','returns-service')},
    'review-service/review_privacy_keys':['customer_id','order_id','product_id'],
    'customer-service/account_export_jobs':['customer_id','request_id'],
    'customer-service/account_export_tasks':['job_id','kind','parent_id','detail_id','cursor_value'],
    'customer-service/account_export_chunks':['task_id'],
    'customer-service/account_privacy_requests':['customer_id'],
    'customer-service/account_privacy_services':['request_id','service'],
    'billing-service/event_inbox':['event_id'],'billing-service/invoice_outbox_events':['event_id'],
    'chat-service/chat_notification_outbox':['entry_id'],'notification-service/event_inbox':['event_id'],
    'notification-service/notification_dead_letters':['notification_id'],'order-service/order_sagas':['order_id'],
    'payment-service/payment_follow_ups':['order_id','kind'],'payment-service/payment_order_gates':['order_id'],
    'payment-service/payment_outbox_events':['event_key'],'payment-service/stripe_webhook_receipts':['event_id']}

def chunks(words):
    """Split top-level commas without splitting numeric precision or constraints."""
    result=[];start=0;depth=0
    for i,word in enumerate(words):
        if word=='(':depth+=1
        elif word==')':depth-=1;require(depth>=0,'Unbalanced SQL parentheses')
        elif word==',' and depth==0:result.append(words[start:i]);start=i+1
    require(depth==0,'Unbalanced SQL parentheses');result.append(words[start:]);return result

def apply_columns(tables,sql):
    words=lifecycle.tokens(sql);statements=[];start=0
    for i,word in enumerate(words):
        if word==';':statements.append(words[start:i]);start=i+1
    statements.append(words[start:])
    for statement in statements:
        if not statement:continue
        w=statement;i=1
        if w[0]=='create':
            if w[i:i+1] in (['temporary'],['temp'],['unlogged']):i+=1
            if w[i:i+1]!=['table']:continue
            i+=1
            if w[i:i+3]==['if','not','exists']:i+=3
            name,i=lifecycle.identifier(w,i);require(w[i:i+1]==['('] and w[-1:]==[')'],'Opaque CREATE TABLE needs field parser review')
            require(name not in tables,'Duplicate table in field inventory: '+name);columns=set()
            for part in chunks(w[i+1:-1]):
                require(part,'Empty table definition')
                if part[0] in {'constraint','primary','foreign','unique','check','exclude'}:continue
                require(part[0]!='like','CREATE TABLE LIKE needs explicit field review')
                column,end=lifecycle.identifier(part,0);require(end<len(part),'Missing column type');require(column not in columns,'Duplicate column');columns.add(column)
            tables[name]=columns
        elif w[:2]==['alter','table']:
            i=2
            if w[i:i+2]==['if','exists']:i+=2
            if w[i:i+1]==['only']:i+=1
            name,i=lifecycle.identifier(w,i);require(name in tables,'Unknown ALTER table: '+name)
            for part in chunks(w[i:]):
                require(part,'Empty ALTER action');action=part[0];j=1
                if action=='add':
                    if part[j:j+1]==['column']:j+=1
                    if part[j:j+1] and part[j] in {'constraint','primary','foreign','unique','check','exclude'}:continue
                    optional=part[j:j+3]==['if','not','exists']
                    if optional:j+=3
                    column,j=lifecycle.identifier(part,j);require(j<len(part),'Missing ADD column type')
                    require(column not in tables[name] or optional,'Duplicate ADD column: '+column);tables[name].add(column)
                elif action=='drop':
                    if part[j:j+1]==['constraint']:continue
                    if part[j:j+1]==['column']:j+=1
                    optional=part[j:j+2]==['if','exists']
                    if optional:j+=2
                    column,j=lifecycle.identifier(part,j);require(column in tables[name] or optional,'Unknown DROP column');tables[name].discard(column)
                elif action=='alter':
                    if part[j:j+1]==['column']:j+=1
                    column,j=lifecycle.identifier(part,j);require(column in tables[name],'Unknown ALTER column: '+column)
                    require(part[j:j+1] and part[j] in {'type','set','drop'},'Unsupported ALTER COLUMN action')
                elif action=='rename':
                    if part[j:j+1]==['to']:
                        renamed,_=lifecycle.identifier(part,j+1);require(renamed not in tables,'Duplicate table rename');tables[renamed]=tables.pop(name);name=renamed
                    else:
                        if part[j:j+1]==['column']:j+=1
                        old,j=lifecycle.identifier(part,j);require(part[j:j+1]==['to'],'Unsupported column rename');new,_=lifecycle.identifier(part,j+1)
                        require(old in tables[name] and new not in tables[name],'Invalid column rename');tables[name].remove(old);tables[name].add(new)
                elif action=='validate' and part[j:j+1]==['constraint']:continue
                else:raise ValueError('Unsupported ALTER TABLE action needs field review: '+action)
        elif w[:2]==['drop','table']:
            i=2;optional=w[i:i+2]==['if','exists']
            if optional:i+=2
            for part in chunks(w[i:]):
                name,_=lifecycle.identifier(part,0);require(name in tables or optional,'Unknown DROP table');tables.pop(name,None)
    return tables

def schema(root):
    components=lifecycle.document(lifecycle.source(root,'deploy/components.json'))['components'];result={}
    for component in components:
        if not component.get('database'):continue
        owner=component['name'];tables={}
        paths=sorted((root/owner/'src/main/resources/db/migration').glob('V*.sql'),key=lambda p:tuple(map(int,re.match(r'V([\d_]+)__',p.name)[1].split('_'))))
        for path in paths:apply_columns(tables,path.read_text(encoding='utf-8'))
        for table,columns in tables.items():result[owner+'/'+table]=sorted(columns)
    return dict(sorted(result.items()))

def omissions(root):
    result={}
    for folder in sorted(root.glob('*-service/src/main/java')):
        for path in sorted(folder.rglob('*.java')):
            if '/export/' not in path.as_posix():continue
            text=path.read_text(encoding='utf-8')
            pairs=sorted(set(re.findall(r'new\s+(?:[\w.]+\.)?Omission\(\s*"([^"\n]+)"\s*,\s*"([^"\n]+)"\s*\)',text)))
            dynamic=re.findall(r'new\s+(?:[\w.]+\.)?Omission\(([^)]*)\)',text)
            for args in dynamic:
                if re.fullmatch(r'\s*"[^"\n]+"\s*,\s*"[^"\n]+"\s*',args):continue
                require(re.sub(r'\s','',args)=='area,reason' and re.search(r'\bomissions\(\s*String\s+area\s*,\s*String\s+reason\s*\)',text),'Dynamic omission needs explicit parser review: '+str(path))
            for args in re.findall(r'\bomissions\(([^)]*)\)',text):
                require(re.fullmatch(r'\s*String\s+area\s*,\s*String\s+reason\s*',args) or re.fullmatch(r'\s*"[^"\n]+"\s*,\s*"[^"\n]+"\s*',args),'Dynamic omission factory call needs review: '+str(path))
            pairs=sorted(set(pairs)|set(re.findall(r'\bomissions\(\s*"([^"\n]+)"\s*,\s*"([^"\n]+)"\s*\)',text)))
            if pairs:result[path.relative_to(root).as_posix()]=[list(p) for p in pairs]
    return result

def projection_sources(root):
    return {p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes().replace(b'\r\n',b'\n')).hexdigest()
            for folder in sorted(root.glob('*-service/src/main/java')) for p in sorted(folder.rglob('*.java')) if '/export/' in p.as_posix()}

def check(root=ROOT,contract_path=CONTRACT):
    root=Path(root).resolve();base=lifecycle.check(root);contract=lifecycle.document(lifecycle.source(root,contract_path))
    require(contract.get('schemaVersion')==1 and contract.get('stage')=='A43.30','Wrong classification contract')
    require(contract.get('decision')=='existing-business-projections-plus-manual-review-no-technical-state-export','Missing human scope decision')
    rules=contract.get('rules',{});require(rules and isinstance(rules,dict),'Missing classification rules')
    for key,rule in rules.items():
        require(rule.get('decision') in DECISIONS and rule.get('reason') and rule.get('ownership') and rule.get('procedure'),'Incomplete rule: '+key)
        for name in rule.get('sources',[]):lifecycle.source(root,name)
        require(lifecycle.strings(rule.get('sources')),'Missing evidence sources: '+key)
        lifecycle.source(root,rule['procedure'])
    actual=schema(root);tables=contract.get('tables',{});require(set(tables)==set(actual),'Unclassified/stale table')
    counter=Counter()
    for table,columns in actual.items():
        entry=tables[table];require(entry.get('owner')==table.split('/')[0] and entry.get('ownership'),'Wrong table owner/proof: '+table)
        require(lifecycle.strings(entry.get('sources')),'Missing table evidence: '+table)
        for name in entry['sources']:lifecycle.source(root,name)
        reviewed=entry.get('columns',{});require(set(reviewed)==set(columns),'Unclassified/stale column: '+table)
        for column,rule in reviewed.items():
            require(rule in rules,'Unknown field rule: '+table+'.'+column);decision=rules[rule]['decision'];counter[decision]+=1
            if column in SECRETS:require(decision=='excluded-secret','Authentication material cannot be exported: '+table+'.'+column)
            if column in MANUAL:require(decision=='manual-review','Free text requires manual review: '+table+'.'+column)
            if column in {'event_key','payload_hash','processing_token','actor_ref'}:require(decision=='excluded-operational','Protected workflow key cannot be exported: '+table+'.'+column)
    catalog=lifecycle.document(lifecycle.source(root,lifecycle.CATALOG))
    for key,entries in [('redis',catalog['redis']['stores']),('copies',catalog['copies'])]:
        reviewed=contract.get(key,{});require(set(reviewed)=={e['id'] for e in entries},'Unclassified '+key)
        for name,entry in reviewed.items():
            require(entry.get('rule') in rules and entry.get('ownership') and entry.get('reason'),'Incomplete '+key+' decision: '+name)
            require(set(entry.get('owners',[]))==set(next(e['owners'] for e in entries if e['id']==name)),'Wrong '+key+' owners: '+name)
            original=next(e for e in entries if e['id']==name)
            if original['export']=='credential-excluded':require(rules[entry['rule']]['decision']=='excluded-secret','Raw credential store cannot be exported: '+name)
    found=omissions(root);reviewed=contract.get('omissions',{});require(set(reviewed)==set(found),'Unclassified omission source')
    omission_count=0
    for path,pairs in found.items():
        entries=reviewed[path];require([e.get('pair') for e in entries]==pairs,'Changed/unclassified omission: '+path)
        for entry in entries:require(entry.get('rule') in rules and entry.get('reason'),'Unresolved omission: '+path)
        omission_count+=len(entries)
    require(contract.get('projectionSources')==projection_sources(root),'Unreviewed export projection source change')
    migration=contract.get('identityMigration',{})
    require(migration.get('stage')=='A43.36' and isinstance(migration.get('implemented'),bool),'Premature identity migration')
    if migration['implemented']:
        compatibility=migration.get('compatibility',{})
        require(compatibility.get('producerDefaultEnabled') is False and compatibility.get('backlogProtocol')=='unchanged-v1'
                and compatibility.get('fingerprints')=='exact-v1-and-v2-wire' and compatibility.get('aliasRetention')=='pending-A43.40'
                and lifecycle.strings(compatibility.get('tests')) and len(compatibility['tests'])>=4,'Premature identity migration')
        for name in compatibility['tests']:lifecycle.source(root,name)
    lifecycle.source(root,contract['identityMigration']['plan'])
    protected=contract.get('deduplicationConstraints');require(isinstance(protected,dict) and protected,'Missing deduplication obligations')
    for table,keys in protected.items():
        require(table in actual and isinstance(keys,list) and keys,'Invalid deduplication table')
        for columns in keys:require(lifecycle.strings(columns) and set(columns)<=set(actual[table]),'Invalid deduplication columns')
    for table,columns in CORE_DEDUPE.items():
        if table in actual:require(columns in protected.get(table,[]),'Core deduplication obligation missing: '+table)
    for table in actual:
        if table.endswith('/account_privacy_fences'):
            require(['customer_id'] in protected.get(table,[]) and ['request_id'] in protected.get(table,[]),'Privacy fence identity obligation missing: '+table)
        if table.endswith('/chat_privacy_requests'):
            require(['conversation_id'] in protected.get(table,[]) and ['request_id'] in protected.get(table,[]),'Chat privacy identity obligation missing: '+table)
        if table.endswith('/commerce_privacy_locks'):
            require(['order_id'] in protected.get(table,[]),'Commerce privacy identity obligation missing: '+table)
    expressions=contract.get('deduplicationExpressions',{})
    for table,index in expressions.items():
        require(table in actual and isinstance(index,dict) and index.get('name') and lifecycle.strings(index.get('sources')),'Invalid identity expression index')
        for name in index['sources']:lifecycle.source(root,name)
        require(any(re.search(r'CREATE\s+UNIQUE\s+INDEX\s+'+re.escape(index['name'])+r'\b',lifecycle.source(root,name).read_text(encoding='utf-8'),re.I) for name in index['sources']),'Missing identity expression index')
    if migration['implemented']:
        for table in ('payment-service/payment_outbox_events','notification-service/notifications'):
            if table in actual:require(table in expressions,'Missing compatible identity expression obligation')
    for name,digest in contract.get('identitySources',{}).items():require(hashlib.sha256(lifecycle.source(root,name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==digest,'Unreviewed deduplication identity change: '+name)
    require(len(contract.get('identitySources',{}))>=4,'Missing identity compatibility evidence')
    replay=contract.get('restoreReplay')
    if (root/'scripts/backup/privacy-restore.py').is_file():require(replay is not None,'Missing restore replay evidence')
    if replay is not None:
        require(replay.get('stage')=='A43.37' and replay.get('releaseAllowed') is False and replay.get('retentionDefault')=='observe','Premature restore release or retention activation')
        require(isinstance(replay.get('sources'),dict) and replay['sources'],'Missing reviewed restore policy sources')
        for name,digest in replay['sources'].items():require(hashlib.sha256(lifecycle.source(root,name).read_bytes().replace(b'\r\n',b'\n')).hexdigest()==digest,'Unreviewed restore policy change: '+name)
    return {'status':'PASS','tables':len(actual),'columns':sum(map(len,actual.values())),'decisions':dict(sorted(counter.items())),
            'redisStores':len(contract['redis']),'copyStores':len(contract['copies']),'omissionSources':len(found),'omissions':omission_count,
            'migrationsReviewed':base['migrationsReviewed'],'executedDataOperations':0,'identityMigrationImplemented':migration['implemented'],'fullA43Complete':False}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT);parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    try:
        result=check(args.root)
        if args.output:
            path=args.output.resolve();require(path.is_relative_to(args.root.resolve()) and not path.exists(),'Unsafe/existing report destination');path.parent.mkdir(parents=True,exist_ok=True);path.write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    except (ValueError,OSError,KeyError,TypeError,IndexError) as error:parser.exit(1,'Technical classification failed: '+str(error)+'\n')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
