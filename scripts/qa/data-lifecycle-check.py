#!/usr/bin/env python3
"""Check reviewed data inventory against source metadata. Never connects to a data store."""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import re

ROOT=Path(__file__).resolve().parents[2]
CATALOG='scripts/qa/contracts/data-lifecycle.json'
MAX_BYTES=2*1024*1024
REDIS_USE=re.compile(r'\b(?:StringRedisTemplate|RedisTemplate|RedisEventStreams|RedisScript|DefaultRedisScript|RedisConnection|RedisClient|LettuceConnectionFactory|RedissonClient|Jedis)\b|\bio\.lettuce\.|\bredis\.clients\.')
KINDS={'personal','credential','business','idempotency','telemetry','reference'}
EXPORT={'pending','pending-safe-projection','partial-safe-projection','credential-excluded','not-account-scoped','excluded-with-reviewed-procedure'}
ERASE={'pending-reviewed-plan','pending-fence-design','not-account-scoped'}

def require(value,message):
    if not value:raise ValueError(message)

def source(root,name):
    require(isinstance(name,str) and name and '\\' not in name,'Invalid source reference')
    path=Path(name)
    require(not path.is_absolute() and not re.match(r'^[A-Za-z]:',name) and '..' not in path.parts,'Source reference escapes workspace')
    result=(root/path).resolve()
    require(result.is_relative_to(root.resolve()) and result.is_file(),'Missing or unsafe source: '+name)
    require(result.stat().st_size<=MAX_BYTES,'Source exceeds review budget: '+name)
    return result

def digest(path):return hashlib.sha256(path.read_bytes()).hexdigest()

def document(path):
    def unique(pairs):
        result={}
        for key,value in pairs:
            require(key not in result,'Duplicate JSON key: '+key);result[key]=value
        return result
    require(path.stat().st_size<=MAX_BYTES,'Inventory exceeds review budget')
    return json.loads(path.read_text(encoding='utf-8'),object_pairs_hook=unique,
                      parse_constant=lambda _: (_ for _ in ()).throw(ValueError('Invalid JSON number')))

def tokens(sql):
    """Skip comments/literals, preserve quoted identifiers; reject opaque table DDL."""
    result=[];i=0
    while i<len(sql):
        if sql[i].isspace():i+=1;continue
        if sql.startswith('--',i):
            end=sql.find('\n',i);i=len(sql) if end<0 else end+1;continue
        if sql.startswith('/*',i):
            depth=1;i+=2
            while depth and i<len(sql):
                if sql.startswith('/*',i):depth+=1;i+=2
                elif sql.startswith('*/',i):depth-=1;i+=2
                else:i+=1
            require(depth==0,'Unterminated SQL comment');continue
        if sql[i] in "'\"":
            quote=sql[i];i+=1;value=[];closed=False
            while i<len(sql):
                if sql[i]==quote:
                    if i+1<len(sql) and sql[i+1]==quote:value.append(quote);i+=2;continue
                    i+=1;closed=True;break
                if quote=="'" and sql[i]=='\\' and i+1<len(sql):i+=2;continue
                value.append(sql[i]);i+=1
            require(closed,'Unterminated SQL literal/identifier')
            if quote=='"':result.append('@'+''.join(value))
            continue
        tag=re.match(r'\$(?:[A-Za-z_][A-Za-z_0-9]*)?\$',sql[i:])
        if tag:
            marker=tag.group();start=i+len(marker);end=sql.find(marker,start)
            require(end>=0,'Unterminated SQL block')
            require(not re.search(r'\b(?:CREATE|ALTER|DROP)\s+(?:(?:UNLOGGED|TEMPORARY|TEMP)\s+)?TABLE\b',sql[start:end],re.I),
                    'Opaque table DDL needs explicit parser review')
            i=end+len(marker);continue
        word=re.match(r'[A-Za-z_][A-Za-z_0-9$]*',sql[i:])
        if word:result.append(word.group().lower());i+=len(word.group())
        else:result.append(sql[i]);i+=1
    return result

def identifier(words,index):
    require(index<len(words),'Missing SQL identifier');value=words[index];index+=1
    value=value[1:] if value.startswith('@') else value
    require(re.fullmatch(r'[A-Za-z_][A-Za-z_0-9]*',value),'Unsupported SQL identifier')
    if index<len(words) and words[index]=='.':
        require(value=='public','Non-public table needs parser review');return identifier(words,index+1)
    return value,index

def apply_tables(tables,sql):
    words=tokens(sql);i=0
    while i<len(words):
        word=words[i];i+=1
        if word=='create':
            require(words[i:i+2]!=['foreign','table'] and words[i:i+2]!=['materialized','view'],'Persistent foreign/materialized storage needs parser review')
            if i<len(words) and words[i] in {'temporary','temp','unlogged'}:i+=1
            if i>=len(words) or words[i]!='table':continue
            i+=1;optional=words[i:i+3]==['if','not','exists']
            if optional:i+=3
            name,i=identifier(words,i)
            require(name not in tables or optional,'Duplicate CREATE TABLE: '+name);tables.add(name)
        elif word=='drop' and i<len(words) and words[i]=='table':
            i+=1;optional=words[i:i+2]==['if','exists']
            if optional:i+=2
            while True:
                name,i=identifier(words,i);require(name in tables or optional,'Unknown DROP TABLE: '+name);tables.discard(name)
                if i>=len(words) or words[i]!=',':break
                i+=1
        elif word=='alter' and i<len(words) and words[i]=='table':
            i+=1
            if words[i:i+2]==['if','exists']:i+=2
            if i<len(words) and words[i]=='only':i+=1
            name,i=identifier(words,i)
            if words[i:i+2]==['rename','to']:
                renamed,i=identifier(words,i+2);require(name in tables and renamed not in tables,'Invalid table rename');tables.remove(name);tables.add(renamed)
    return tables

def discover(root,components):
    schema={};review={};redis_refs={}
    owners=[c['name'] for c in components if c.get('database')]
    for owner in owners:
        tables=set();folder=root/owner/'src/main/resources/db/migration'
        paths=list(folder.glob('*.sql'))
        require(paths,'Missing migrations: '+owner)
        def version(path):
            found=re.fullmatch(r'V([0-9]+(?:_[0-9]+)*)__.+\.sql',path.name);require(found,'Unsupported migration name')
            return tuple(map(int,found[1].split('_')))
        versions=[version(p) for p in paths];require(len(set(versions))==len(versions),'Duplicate migration version: '+owner)
        for path in sorted(paths,key=version):
            name=path.relative_to(root).as_posix();safe=source(root,name)
            apply_tables(tables,safe.read_text(encoding='utf-8'));review[name]=digest(safe)
        schema[owner]=tables
    for owner in [*(c['name'] for c in components if c.get('runtime')=='jvm'),'platform-security']:
        for path in (root/owner/'src/main/java').rglob('*.java'):
            name=path.relative_to(root).as_posix();safe=source(root,name)
            text=safe.read_text(encoding='utf-8')
            require(not re.search(r'\b(?:BaseJavaMigration|JavaMigration)\b',text),'Java migration needs explicit inventory support: '+name)
            if REDIS_USE.search(text):redis_refs[name]=digest(safe)
    return schema,review,redis_refs

def describe(entry,mechanisms):
    require(isinstance(entry,dict),'Invalid inventory entry')
    require(isinstance(entry.get('classification'),list) and entry['classification'] and set(entry['classification'])<=KINDS,'Missing/invalid data classification')
    require(isinstance(entry.get('subject'),str) and entry['subject'].strip(),'Missing account relationship')
    require(entry.get('export') in EXPORT and entry.get('erase') in ERASE,'Invalid/premature account action')
    if 'credential' in entry['classification']:require(entry['export'] in {'credential-excluded','pending-safe-projection','partial-safe-projection'},'Credentials require an excluded/safe projection')
    require(isinstance(entry.get('mechanisms'),list) and set(entry['mechanisms'])<=mechanisms,'Unknown retention mechanism')
    require(isinstance(entry.get('limitation'),str) and entry['limitation'].strip(),'Missing retention limitation/remaining scope')

def strings(value):
    return isinstance(value,list) and bool(value) and all(isinstance(s,str) and s.strip() for s in value) and len(set(value))==len(value)

def reviewed(actual,expected,label):
    require(isinstance(expected,dict),label+' review is missing')
    changed=sorted(k for k in set(actual)|set(expected) if actual.get(k)!=expected.get(k))
    require(not changed,label+' review changed: '+', '.join(changed[:10])+(' ...' if len(changed)>10 else ''))

def check(root=ROOT,catalog_path=CATALOG):
    root=Path(root).resolve();catalog=document(source(root,catalog_path));components=document(source(root,'deploy/components.json'))['components']
    require(isinstance(components,list) and components,'Missing component inventory')
    names=[c.get('name') for c in components]
    require(all(isinstance(n,str) and re.fullmatch(r'[a-z][a-z0-9-]*',n) for n in names) and len(set(names))==len(names),'Invalid/duplicate component owner')
    dbs=[c['database'] for c in components if c.get('database')];require(len(set(dbs))==len(dbs),'Duplicate database owner')
    require(catalog.get('schemaVersion')==1,'Unsupported lifecycle inventory version')
    require(catalog.get('capabilities')=={'customerExport':'pending','customerErase':'pending','backupErasure':'pending','retentionActivation':'operator-decision'},
            'This inventory is not proof of completed account operations')
    schema,review,redis_refs=discover(root,components)
    reviewed(review,catalog.get('schemaReview'),'Migration')
    mechanisms=catalog.get('mechanisms');require(isinstance(mechanisms,dict) and mechanisms,'Missing retention mechanisms')
    for key,entry in mechanisms.items():
        require(re.fullmatch(r'A[0-9]{2}(?:\.[0-9]+)?',key) and entry.get('scope') and strings(entry.get('sources')),'Invalid mechanism reference')
        for name in entry['sources']:source(root,name)
    services=catalog.get('services');require(isinstance(services,list),'Missing service inventory')
    require(len({s['owner'] for s in services})==len(services) and {s['owner'] for s in services}==set(schema),'Database inventory owners do not match components')
    actions=Counter();classes=Counter();count=0
    for service in services:
        owner=service['owner'];expected=next(c['database'] for c in components if c['name']==owner)
        require(service.get('database')==expected,'Wrong database alias: '+owner)
        entries=service.get('tables');require(isinstance(entries,list),'Missing table inventory')
        require(len({t['name'] for t in entries})==len(entries) and {t['name'] for t in entries}==schema[owner],'Uncovered or stale table: '+owner)
        for entry in entries:
            describe(entry,set(mechanisms));count+=1;actions[entry['erase']]+=1;classes.update(set(entry['classification']))
    redis=catalog.get('redis',{});reviewed(redis_refs,redis.get('javaReferences'),'Redis usage')
    redis_owners={c['name'] for c in components if c.get('redis')}
    require(all(name.split('/')[0] in redis_owners|{'platform-security'} for name in redis_refs),'Redis source owner is not declared in components')
    stores=redis.get('stores');require(isinstance(stores,list) and stores,'Missing Redis stores')
    ids=set();covered=set()
    for entry in stores:
        require(entry.get('id') and entry['id'] not in ids,'Duplicate/missing Redis store');ids.add(entry['id'])
        require(strings(entry.get('owners')) and set(entry['owners'])<=redis_owners,'Invalid Redis owners');covered.update(entry['owners'])
        require(strings(entry.get('patterns')),'Missing Redis namespace/stream description');describe(entry,set(mechanisms))
    require(covered==redis_owners,'Uncovered Redis component')
    copies=catalog.get('copies');require(isinstance(copies,list) and copies,'Missing copied-data inventory')
    ids=set();copy_kinds=set()
    for entry in copies:
        require(entry.get('id') and entry['id'] not in ids,'Duplicate/missing data copy');ids.add(entry['id']);copy_kinds.add(entry.get('kind'))
        require(strings(entry.get('locations')) and strings(entry.get('sources')) and strings(entry.get('owners')),'Incomplete data-copy evidence')
        require(set(entry['owners'])<=set(names)|{'platform-operations'},'Unknown copy owner')
        for name in entry['sources']:source(root,name)
        describe(entry,set(mechanisms))
    require(copy_kinds=={'backup','runtime','browser','external'},'Missing copy category')
    return {'status':'PASS','databaseOwners':len(schema),'tables':count,'migrationsReviewed':len(review),
            'redisJavaReferences':len(redis_refs),'redisOwners':len(redis_owners),'redisStores':len(stores),'copyStores':len(copies),
            'mechanisms':len(mechanisms),'tableClassifications':dict(sorted(classes.items())),'tableErasurePlans':dict(sorted(actions.items())),
            'capabilities':catalog['capabilities'],'executedDataOperations':0,'fullA43Complete':False}

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--root',type=Path,default=ROOT)
    parser.add_argument('--catalog',default=CATALOG);parser.add_argument('--output',type=Path)
    args=parser.parse_args()
    try:
        result=check(args.root,args.catalog)
        if args.output:
            output=args.output.resolve();require(output.is_relative_to(args.root.resolve()),'Report must stay in workspace')
            require(not output.exists(),'Report destination already exists');output.parent.mkdir(parents=True,exist_ok=True)
            with output.open('x',encoding='utf-8') as stream:json.dump(result,stream,ensure_ascii=False,indent=2);stream.write('\n')
    except (ValueError,OSError,KeyError,TypeError) as error:parser.exit(1,'Data lifecycle check failed: '+str(error)+'\n')
    print(json.dumps(result,ensure_ascii=False))

if __name__=='__main__':main()
