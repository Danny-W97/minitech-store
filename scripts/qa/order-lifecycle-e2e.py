#!/usr/bin/env python3
"""A06: two Order replicas, actual services and disposable PostgreSQL crash/repair tests."""
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import signal
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]


def run(logs,call,create,accept,sql,processes,start,ready,poll,tokens,customer,operator,urls,http):
    def current(order,node='order'):return call(node,f'/orders/{order["id"]}')
    def post(node,key):
        payload=dict(items=[dict(productId=1,quantity=1)],customerId=17,customerEmail='owner@example.test',
                deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street 1',postalCode='00-001',city='City',country='PL'),
                deliveryMethod='STANDARD_COURIER',invoiceRequested=False)
        def attempt():
            req=urllib.request.Request(urls[node]+'/orders',data=json.dumps(payload).encode(),
                    headers={'Authorization':'Bearer '+customer,'Content-Type':'application/json','Idempotency-Key':key},method='POST')
            try:
                with urllib.request.urlopen(req,timeout=25) as response:status,value=response.status,json.loads(response.read())
            except urllib.error.HTTPError as error:status,value=error.code,json.loads(error.read())
            http.CHECKS+=1
            assert status==201 or status==409 and value.get('details',{}).get('code')=='ORDER_IN_PROGRESS',(status,value)
            return value if status==201 else None
        return poll(attempt,bool)
    def ignore_disconnect(future):
        try:future.result(15)
        except (OSError,urllib.error.URLError):pass
    def expire_owner(source):
        sql('order',f"UPDATE order_sagas SET processing_until=now()-interval '1 second',next_attempt_at=now() WHERE order_id={source}")

    # The order INSERT is already executed in this transaction when the saga
    # trigger sleeps. A second connection must still see neither record.
    sql('order',"CREATE FUNCTION pause_saga() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF EXISTS (SELECT 1 FROM orders WHERE id=NEW.order_id AND idempotency_key='A06-ROLLBACK') THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_saga BEFORE INSERT ON order_sagas FOR EACH ROW EXECUTE FUNCTION pause_saga()")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending=pool.submit(post,'order','A06-ROLLBACK')
        poll(lambda:sql('order',"SELECT count(*) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'"),lambda count:count!='0')
        assert sql('order',"SELECT count(*) FROM orders WHERE idempotency_key='A06-ROLLBACK'")=='0'
        processes['order'].kill();processes['order'].wait(10)
        sql('order',"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'")
        sql('order','DROP TRIGGER pause_saga ON order_sagas; DROP FUNCTION pause_saga()')
        ignore_disconnect(pending)
    assert sql('order',"SELECT count(*) FROM orders WHERE idempotency_key='A06-ROLLBACK'")=='0'
    retried=post('order-b','A06-ROLLBACK')
    assert sql('order',f"SELECT count(*) FROM order_sagas WHERE order_id={retried['id']}")=='1'
    start('order');ready('order')
    print('PASS: process death between order and saga INSERT rolls both back; the other replica safely retries',flush=True)

    # Both rows commit before outbound inventory. Kill the caller before any
    # response and let the other process resume the durable work.
    processes['inventory'].send_signal(signal.SIGSTOP)
    processes['order-b'].send_signal(signal.SIGSTOP)
    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(post,'order','A06-AFTER-COMMIT')
            source=int(poll(lambda:sql('order',"SELECT o.id FROM orders o JOIN order_sagas s ON s.order_id=o.id WHERE o.idempotency_key='A06-AFTER-COMMIT' AND s.processing_token IS NOT NULL"),bool))
            assert sql('order',f"SELECT status FROM orders WHERE id={source}")=='CREATED'
            call('order',f'/orders/{source}/status','PATCH',dict(status='CONFIRMED'),expected=409)
            processes['order'].kill();processes['order'].wait(10);ignore_disconnect(pending)
    finally:
        processes['inventory'].send_signal(signal.SIGCONT)
        processes['order-b'].send_signal(signal.SIGCONT)
    expire_owner(source)
    poll(lambda:call('order-b',f'/orders/{source}'),lambda row:row['status']=='CONFIRMED')
    assert post('order-b','A06-AFTER-COMMIT')['id']==source
    assert sql('inventory',f'SELECT count(*) FROM inventory_reservations WHERE order_id={source}')=='1'
    start('order');ready('order')
    print('PASS: death after atomic commit is recovered by the second replica without duplicate reservation or order',flush=True)

    draft=create();source=draft['id']
    sql('order',f"DELETE FROM order_sagas WHERE order_id={source}; UPDATE orders SET status='CREATED',inventory_reservation_id=NULL,version=version+1 WHERE id={source}")
    poll(lambda:current(draft),lambda row:row['status']=='CONFIRMED' and row['inventoryReservationId'] is not None)
    assert sql('order',f'SELECT count(*) FROM order_sagas WHERE order_id={source}')=='1'
    assert sql('inventory',f'SELECT count(*) FROM inventory_reservations WHERE order_id={source}')=='1'
    print('PASS: bounded two-replica scan restores a historical draft without consuming stock twice',flush=True)

    legacy=create();source=legacy['id'];sql('order',f'DELETE FROM order_sagas WHERE order_id={source}')
    flagged=poll(lambda:current(legacy),lambda row:row['lifecycleReviewRequired'])
    assert flagged['status']=='CONFIRMED' and flagged['totalPrice']==legacy['totalPrice']
    accept(legacy,expected=409)
    assert sql('payment',f'SELECT count(*) FROM payments WHERE order_id={source}')=='0'
    for actor,expected in [(None,401),(customer,403),(tokens['payment-service'],403)]:
        call('order-b',f'/orders/{source}/reconcile','POST',token=actor,expected=expected)
    verified=call('order-b',f'/orders/{source}/reconcile','POST')
    assert not verified['lifecycleReviewRequired'] and verified['totalPrice']==legacy['totalPrice']
    assert not call('order',f'/orders/{source}/reconcile','POST')['lifecycleReviewRequired']
    print('PASS: verified reservation restores only unpaid history; flagged orders cannot reach payment',flush=True)

    flagged_orders=[]
    for status in ['PAID','CANCELLED']:
        order=create();source=order['id']
        sql('order',f"DELETE FROM order_sagas WHERE order_id={source}; UPDATE orders SET status='{status}',version=version+1 WHERE id={source}")
        poll(lambda:current(order),lambda row:row['lifecycleReviewRequired'])
        assert current(order)['status']==status
        call('order',f'/orders/{source}/reconcile','POST',expected=409);flagged_orders.append(order)
    rows=call('order','/orders/sagas?state=RECONCILIATION_REQUIRED&size=1&cursor=0')
    assert len(rows)==1 and rows[0]['orderId'] in [row['id'] for row in flagged_orders]
    call('order','/orders/sagas',token=customer,expected=403)
    paid=current(flagged_orders[0]);source=paid['id']
    buyer=dict(company=False,buyerName='Audit Buyer',street='Audit Street',postalCode='00-001',city='City',country='PL')
    items=[{key:item[key] for key in ['productId','sku','productName','quantity','unitPrice','totalPrice']} for item in paid['items']]
    call('billing','/invoices','POST',dict(orderId=source,customerId=17,customerEmail='owner@example.test',grossAmount=paid['totalPrice'],
            currency='PLN',deliveryCost=paid['deliveryCost'],discountAmount=paid['discountAmount'],items=items,billingData=buyer),expected=409)
    call('review','/reviews','POST',dict(customerId=17,orderId=source,productId=1,rating=5,title='Review',content='Content'),token=customer,expected=400)
    print('PASS: paid/cancelled historical facts are preserved and queued, without fabricated invoices or purchase proof',flush=True)

    races=6
    for _ in range(races):
        order=create();source=order['id']
        def transition(node,status):
            try:call(node,f'/orders/{source}/status','PATCH',dict(status=status));return 200
            except AssertionError as failure:
                if 'got 409;' not in str(failure):raise
                return 409
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            paid=pool.submit(transition,'order','PAID');cancel=pool.submit(transition,'order-b','CANCELLED')
            assert sorted([paid.result(30),cancel.result(30)])==[200,409]
        actual=current(order);assert actual['status'] in ['PAID','CANCELLED']
        saga=call('order',f'/orders/{source}/saga')
        assert saga['state']==('COMPLETED' if actual['status']=='PAID' else 'FAILED')
    assert sql('order',"SELECT count(*) FROM orders o WHERE NOT EXISTS(SELECT 1 FROM order_sagas s WHERE s.order_id=o.id)")=='0'
    assert sql('order',"SELECT count(*) FROM pg_stat_activity WHERE datname='order_db' AND state='idle in transaction'")=='0'
    report=dict(result='PASSED',orderReplicas=2,atomicRollback=True,crashAfterCommit=True,legacyDraftRecovery=True,manualReviewPreservesFacts=True,lifecycleRaces=races,httpChecks=http.CHECKS)
    (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS A06: atomic creation, replica crash recovery, bounded orphan repair and serialized lifecycle transitions',flush=True)


if __name__=='__main__':
    spec=importlib.util.spec_from_file_location('order_lifecycle_setup',ROOT/'scripts/qa/deferred-payments-e2e.py')
    setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(setup);setup.main(lifecycle=True)
