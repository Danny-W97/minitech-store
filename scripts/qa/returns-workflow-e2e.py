#!/usr/bin/env python3
"""A16.1: verified intake and versioned decisions; financial completion remains separate."""
import concurrent.futures
import copy
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import http.client as http_client
import importlib.util
import json
from pathlib import Path
import secrets
import threading
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]

def run(logs,call,sql,poll,tokens,customer,operator,urls,http,control,processes,start,ready):
    import sys
    gate_spec=importlib.util.spec_from_file_location('return_contract',ROOT/'scripts/qa/quality_gates.py')
    gates=importlib.util.module_from_spec(gate_spec);sys.modules[gate_spec.name]=gates;gate_spec.loader.exec_module(gates)
    gates.check_document(call('returns','/v3/api-docs'),json.loads(gates.CONTRACT.read_text())['services']['returns'],'returns')
    legacy=call('returns','/returns/9001');terminal=call('returns-b','/returns/9002')
    assert legacy['reason']=='Old reason' and legacy['moderationNote']=='Original note' and legacy['orderId']==900001
    assert legacy['requiresReconciliation'] and legacy['version']==0 and legacy['items']==[]
    assert terminal['status']=='COMPLETED' and terminal['effectsState']=='LEGACY_UNVERIFIED'
    imported=call('returns','/returns/9001/history');assert len(imported)==1 and imported[0]['action']=='LEGACY_SNAPSHOT' and imported[0]['actorRef']=='unknown-legacy-actor'
    assert sql('returns',"SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1")=='3'
    print('PASS: actual V2 to V3 upgrade preserves old status/reason/note without inventing verification, items or decision actors',flush=True)

    def purchase(quantity=3):
        key='a16-order-'+str(uuid.uuid4())
        payload=dict(items=[dict(productId=1,quantity=quantity)],customerId=17,customerEmail='owner@example.test',
            deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street 1',postalCode='00-001',city='City',country='PL'),
            deliveryMethod='STANDARD_COURIER',invoiceRequested=False)
        def attempt():
            status,value=raw('order','/orders','POST',payload,customer,{'Idempotency-Key':key})
            assert status==201 or status==409 and value.get('details',{}).get('code')=='ORDER_IN_PROGRESS',(status,value)
            return value if status==201 else None
        return poll(attempt,bool)
    def paid(value):
        source=value['id'];payment=call('payment-a','/payments','POST',dict(orderId=source,paymentMethod='CASH_ON_DELIVERY',simulateFailure=False,invoiceRequested=False,customerId=17),token=customer,expected=201)
        poll(lambda:sql('delivery',f'SELECT count(*) FROM shipments WHERE order_id={source}'),lambda count:count=='1')
        call('delivery',f'/deliveries/orders/{source}/tracking','PATCH',dict(status='DELIVERED'))
        poll(lambda:call('order',f'/orders/{source}',token=customer),lambda row:row['status']=='COMPLETED')
        call('payment-a',f'/payments/{payment["id"]}/collect','POST',dict(amount=value['totalPrice'],currency='PLN',receiptReference='A16-'+str(source)))
        return poll(lambda:call('order',f'/orders/{source}',token=customer),lambda row:row['paymentState']=='PAID')
    def intake(order,kind='RETURN',quantity=2):
        return dict(orderId=order['id'],customerId=17,customerEmail='owner@example.test',requestType=kind,reason='Audit reason',description='Audit description',
            items=[dict(orderItemId=order['items'][0]['id'],quantity=quantity)])
    def patch(case,status,version,key=None,note='Audited decision',node='returns',expected=200,token=operator):
        command=dict(status=status,expectedVersion=version,operationId=key or str(uuid.uuid4()),note=note)
        return call(node,f'/returns/{case}/status','PATCH',command,token=token,expected=expected)
    def raw(node,path,method,body,token=operator,headers=None):
        request=urllib.request.Request(urls[node]+path,data=json.dumps(body).encode(),headers={'Authorization':'Bearer '+token,'Content-Type':'application/json',**(headers or {})},method=method)
        try:
            with urllib.request.urlopen(request,timeout=20) as response:status,value=response.status,json.loads(response.read())
        except urllib.error.HTTPError as error:status,value=error.code,json.loads(error.read())
        http.CHECKS+=1;return status,value

    order=purchase();draft=intake(order)
    for kind in ['RETURN','COMPLAINT']:call('returns','/returns','POST',dict(draft,requestType=kind),token=customer,expected=409)
    call('returns','/returns','POST',dict(draft,orderId=999999),token=customer,expected=404)
    call('returns','/returns','POST',dict(draft,customerId=18,customerEmail='other@example.test'),expected=403)
    control['unavailable']=True
    try:call('returns','/returns','POST',draft,token=customer,expected=503)
    finally:control['unavailable']=False
    assert sql('returns','SELECT count(*) FROM return_requests')=='2'
    order=paid(order);draft=intake(order)
    for item,status in [(dict(orderItemId=999999,quantity=1),409),(dict(orderItemId=order['items'][0]['id'],quantity=4),409),(dict(orderItemId=order['items'][0]['id'],quantity=0),400)]:
        call('returns','/returns','POST',dict(draft,items=[item]),token=customer,expected=status)
    assert sql('returns','SELECT count(*) FROM return_requests')=='2'
    print('PASS: nonexistent/foreign/unpaid purchases, unavailable Order, foreign items and excess quantities never create a case',flush=True)

    control['validation_barrier']=threading.Barrier(2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(call,'returns','/returns','POST',draft,customer,201);b=pool.submit(call,'returns-b','/returns','POST',draft,customer,201);results=[a.result(30),b.result(30)]
    case=results[0]['id'];assert results[1]['id']==case and results[0]['items'][0]['quantity']==2 and results[0]['items'][0]['orderedQuantity']==3
    assert sql('returns',f'SELECT count(*) FROM return_events WHERE return_id={case}')=='1'
    call('returns-b','/returns','POST',dict(draft,description='Changed'),token=customer,expected=409)
    control['unavailable']=True
    try:assert call('returns-b','/returns','POST',draft,token=customer,expected=201)['id']==case
    finally:control['unavailable']=False
    print('PASS: two Returns replicas create one immutable partial intake and one journal entry; replay survives an Order outage',flush=True)

    command=dict(status='UNDER_REVIEW',expectedVersion=0,operationId=str(uuid.uuid4()),note='Review after lost reply')
    receipt=[]
    class LostReply(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_PATCH(self):
            raw_body=self.rfile.read(int(self.headers['Content-Length']))
            request=urllib.request.Request(urls['returns']+self.path,data=raw_body,headers={'Authorization':'Bearer '+operator,'Content-Type':'application/json'},method='PATCH')
            with urllib.request.urlopen(request,timeout=20) as response:receipt.append(json.loads(response.read()))
            self.connection.close()
    proxy=ThreadingHTTPServer(('127.0.0.1',0),LostReply);threading.Thread(target=proxy.serve_forever,daemon=True).start()
    try:
        request=urllib.request.Request(f'http://127.0.0.1:{proxy.server_port}/returns/{case}/status',data=json.dumps(command).encode(),headers={'Content-Type':'application/json'},method='PATCH')
        try:
            with urllib.request.urlopen(request,timeout=20) as response:response.read();raise AssertionError('Reply should be lost')
        except (OSError,urllib.error.URLError,http_client.HTTPException):pass
        assert receipt and receipt[0]['version']==1
    finally:proxy.shutdown();proxy.server_close()
    processes['returns'].kill();processes['returns'].wait(10)
    control['unavailable']=True
    try:
        reviewed=call('returns-b',f'/returns/{case}/status','PATCH',command);assert reviewed['version']==1 and reviewed['status']=='UNDER_REVIEW'
    finally:control['unavailable']=False
    assert sql('returns',f'SELECT count(*) FROM return_events WHERE return_id={case}')=='2'
    call('returns-b',f'/returns/{case}/status','PATCH',dict(command,note='Different operation'),expected=409)
    start('returns');ready('returns')
    print('PASS: a lost decision reply followed by process death replays once from SQL without Order or duplicate audit entries',flush=True)

    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(raw,'returns',f'/returns/{case}/status','PATCH',dict(status='APPROVED',expectedVersion=1,operationId=str(uuid.uuid4()),note='Approved by replica A'))
        b=pool.submit(raw,'returns-b',f'/returns/{case}/status','PATCH',dict(status='APPROVED',expectedVersion=1,operationId=str(uuid.uuid4()),note='Approved by replica B'))
        results=[a.result(30),b.result(30)]
    assert sorted(status for status,_ in results)==[200,409],results
    approved=call('returns',f'/returns/{case}');assert approved['version']==2 and approved['effectsState']=='DOMAIN_RECONCILIATION_REQUIRED'
    patch(case,'COMPLETED',2,expected=409)
    assert sql('returns',f'SELECT count(*) FROM return_events WHERE return_id={case}')=='3'
    rows=call('returns',f'/returns/{case}/history');assert [r['revision'] for r in rows]==[2,1,0]
    assert rows[0]['actorRef']=='configured-operator' and rows[0]['paymentState']=='PAID'
    call('returns',f'/returns/{case}/history?size=201',expected=400)
    call('returns',f'/returns/{case}/status','PATCH',dict(status='UNDER_REVIEW',note='No version'),expected=400)
    print('PASS: one stale-version competitor is rejected; approval is explicit pending reconciliation and cannot pretend to complete money/stock effects',flush=True)

    other=secrets.token_urlsafe(32);control['other_customer']=other
    for token,status in [(None,401),(customer,403),(other,403),(tokens['payment-service'],403)]:
        call('returns',f'/returns/{case}/history',token=token,expected=status)
        call('returns',f'/returns/{case}/reconcile','POST',dict(expectedVersion=2,operationId=str(uuid.uuid4()),note='Unauthorized'),token=token,expected=status)
        patch(case,'REJECTED',2,expected=status,token=token)
    call('returns',f'/returns/{case}',token=other,expected=403)
    call('returns',f'/returns/{case}',token=customer)
    page=call('returns','/returns?size=1');assert len(page)==1 and 'items' in page[0]
    print('PASS: customer ownership remains enforced; decision history and reconciliation are operator-only and all pages are bounded',flush=True)

    complaint=call('returns','/returns','POST',intake(order,'COMPLAINT',1),token=customer,expected=201)
    patch(complaint['id'],'APPROVED',0,expected=409)
    patch(complaint['id'],'UNDER_REVIEW',0)
    sql('order',f"UPDATE orders SET lifecycle_review_required=true,refund_payment_id=99999,refund_state='REFUND_PENDING' WHERE id={order['id']}")
    patch(complaint['id'],'APPROVED',1,expected=409)
    rejected=patch(complaint['id'],'REJECTED',1);assert rejected['effectsState']=='NOT_APPLICABLE'
    patch(complaint['id'],'UNDER_REVIEW',2,expected=409)
    sql('order',f"UPDATE orders SET lifecycle_review_required=false,refund_payment_id=NULL,refund_state=NULL WHERE id={order['id']}")

    legacy_order=paid(purchase());source=legacy_order['id']
    sql('returns',f'UPDATE return_requests SET order_id={source} WHERE id=9001')
    old_input=dict(orderId=source,customerId=17,customerEmail='owner@example.test',requestType='RETURN',reason='Old reason',description='Old description')
    call('returns','/returns','POST',old_input,token=customer,expected=409)
    patch(9001,'UNDER_REVIEW',0,expected=409)
    reconcile=dict(expectedVersion=0,operationId=str(uuid.uuid4()),note='Canonical legacy verification')
    verified=call('returns','/returns/9001/reconcile','POST',reconcile)
    assert verified['version']==1 and not verified['requiresReconciliation'] and len(verified['items'])==1
    assert call('returns-b','/returns/9001/reconcile','POST',reconcile)['version']==1
    patch(9001,'UNDER_REVIEW',1)
    history=call('returns','/returns/9001/history');assert [row['action'] for row in history]==['STATUS_CHANGED','LEGACY_VERIFIED','LEGACY_SNAPSHOT']
    terminal_order=paid(purchase());sql('returns',f"UPDATE return_requests SET order_id={terminal_order['id']} WHERE id=9002")
    call('returns','/returns/9002/reconcile','POST',dict(expectedVersion=0,operationId=str(uuid.uuid4()),note='Cannot invent historical proof'),expected=409)
    assert call('returns','/returns/9002')['effectsState']=='LEGACY_UNVERIFIED'
    assert sql('payment','SELECT count(*) FROM payment_refunds')=='0'
    assert sql('inventory',"SELECT sum(sold_quantity) FROM inventory_items WHERE product_id=1")=='9'
    for domain in ['returns','order','payment']:
        assert sql(domain,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{domain}_db' AND state='idle in transaction'")=='0'
    report=dict(result='PASSED',httpChecks=http.CHECKS,returnsReplicas=2,legacyMigration=True,canonicalOwner=True,paidLifecycle=True,
        partialIntake=True,createRace=True,decisionRace=True,lostReplyAndProcess=True,immutableHistory=True,operatorOnly=True,legacyVerification=True,
        financialCompletionBlocked=True,openApiContract=True,refundsInitiated=0,scope='A16.1 intake and review; financial/stock/document reconciliation remains separate')
    (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS A16.1: verified return intake, versioned audited decisions, replica races, migration and safe pending effects',flush=True)

def main():
    spec=importlib.util.spec_from_file_location('returns_fixture',ROOT/'scripts/qa/deferred-payments-e2e.py')
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture);fixture.main(returns=True)

if __name__=='__main__':main()
