#!/usr/bin/env python3
"""A15: identical intent, owner binding and recovery across two real Order replicas."""
import concurrent.futures
import copy
from datetime import datetime,timedelta,timezone
import importlib.util
import json
from pathlib import Path
import secrets
import threading
import urllib.error
import urllib.request

ROOT=Path(__file__).resolve().parents[2]

def run(logs,call,sql,poll,tokens,customer,operator,urls,http,control,processes):
    migration=(ROOT/'order-service/src/main/resources/db/migration/V20__checkout_request_identity.sql').read_text()
    assert sql('order',"BEGIN; CREATE TEMP TABLE orders(id bigint,total_price numeric(19,2)); INSERT INTO orders VALUES(1,10); "+migration+
        " SELECT total_price=10 AND checkout_request_hash IS NULL AND checkout_principal IS NULL FROM orders WHERE id=1; ROLLBACK;")=='t'
    document=call('order','/v3/api-docs')
    header=next(item for item in document['paths']['/orders']['post']['parameters'] if item.get('in')=='header' and item['name']=='Idempotency-Key')
    assert header['schema']['minLength']==1 and header['schema']['maxLength']==100 and header['required'] is False
    def body(quantity=1):return dict(items=[dict(productId=1,quantity=quantity)],customerId=17,customerEmail='owner@example.test',
        deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street 1',postalCode='00-001',city='City',country='PL'),
        deliveryMethod='STANDARD_COURIER',invoiceRequested=False)
    def submit(payload,key,node='order',token=customer,cap=None):
        headers={'Content-Type':'application/json'}
        if token is not None:headers['Authorization']='Bearer '+token
        if key is not None:headers['Idempotency-Key']=key
        if cap:headers['X-Order-Access-Token']=cap
        request=urllib.request.Request(urls[node]+'/orders',data=json.dumps(payload).encode(),headers=headers,method='POST')
        try:
            with urllib.request.urlopen(request,timeout=25) as response:status,value=response.status,json.loads(response.read())
        except urllib.error.HTTPError as error:status,value=error.code,json.loads(error.read())
        http.CHECKS+=1;return status,value
    def accepted(payload,key,node='order',token=customer,cap=None):
        def attempt():
            status,value=submit(payload,key,node,token,cap)
            assert status==201 or status==409 and value.get('details',{}).get('code')=='ORDER_IN_PROGRESS',(status,value)
            return value if status==201 else None
        value=poll(attempt,bool);assert value['status']!='CREATED';return value
    def code(value):return value.get('details',{}).get('code')
    def one(key):
        assert sql('order',f"SELECT count(*) FROM orders WHERE idempotency_key='{key}'")=='1'
        source=int(sql('order',f"SELECT id FROM orders WHERE idempotency_key='{key}'"))
        assert sql('order',f'SELECT count(*) FROM order_sagas WHERE order_id={source}')=='1'
        assert sql('inventory',f'SELECT count(*) FROM inventory_reservations WHERE order_id={source}')=='1'
        return source

    control['product_barrier']=threading.Barrier(2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(submit,body(),'same-race','order');b=pool.submit(submit,body(),'same-race','order-b');results=[a.result(30),b.result(30)]
    assert any(status==201 for status,_ in results),results
    canonical=accepted(body(),'same-race','order-b');source=one('same-race')
    for status,value in results:
        assert status==201 and value['id']==source or status==409 and code(value)=='ORDER_IN_PROGRESS',results
    assert canonical['id']==source
    print('PASS: concurrent first inserts across two Order replicas produce one Order, saga and stock reservation; busy replay is retryable',flush=True)

    control['product_barrier']=threading.Barrier(2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(submit,body(1),'different-race','order');b=pool.submit(submit,body(2),'different-race','order-b');results=[a.result(30),b.result(30)]
    assert sorted(status for status,_ in results)==[201,409],results
    assert code(next(value for status,value in results if status==409))=='ORDER_IDEMPOTENCY_CONFLICT'
    one('different-race')
    print('PASS: different concurrent payloads sharing a key have exactly one winner and a conflict before a second stock effect',flush=True)

    original=body(3);original['items']=[dict(productId=1,quantity=2),dict(productId=1,quantity=1)]
    equivalent=accepted(original,'normalized')
    legacy=body(3);legacy.pop('items');legacy.update(productId=1,quantity=3,customerEmail='OWNER@example.test',deliveryMethod='standard_courier',fulfillmentType='HOME_DELIVERY',deliveryCost=999)
    control['product_unavailable']=True;before=control['product_requests']
    try:assert accepted(legacy,'normalized','order-b')['id']==equivalent['id']
    finally:control['product_unavailable']=False
    assert control['product_requests']==before
    mutations=[]
    for path,value in [('items',[dict(productId=1,quantity=4)]),('invoiceRequested',True),
            ('deliveryMethod','STORE_PICKUP'),('deliveryDate','2027-01-01'),('promotionCode','OTHER'),('pickupLocation','OTHER'),('deliverySlot','OTHER')]:
        changed=copy.deepcopy(original);changed[path]=value;mutations.append(changed)
    changed=copy.deepcopy(original);changed['deliveryAddress']['street']='Changed';mutations.append(changed)
    changed=copy.deepcopy(original);changed['deliveryAddress']['phone']='123';mutations.append(changed)
    before=control['product_requests']
    for changed in mutations:
        status,value=submit(changed,'normalized','order-b');assert status==409 and code(value)=='ORDER_IDEMPOTENCY_CONFLICT',(status,value)
    assert control['product_requests']==before;one('normalized')
    changed=copy.deepcopy(original);changed['customerEmail']='changed@example.test'
    assert submit(changed,'normalized')[0]==403
    print('PASS: normalized cart/email/defaults replay the stored quote during a pricing outage; changed business fields never reprice or reserve',flush=True)

    other=secrets.token_urlsafe(32);control['other_customer']=other
    claimed=body();claimed.update(customerId=18,customerEmail='other@example.test')
    status,value=submit(claimed,'same-race',token=other);assert status==403 and 'id' not in value
    cap=secrets.token_urlsafe(32);guest=body();guest.update(customerId=None,customerEmail='guest@example.test')
    guest_order=accepted(guest,'guest-key',token=None,cap=cap)
    assert accepted(guest,'guest-key','order-b',token=None,cap=cap)['id']==guest_order['id']
    changed=copy.deepcopy(guest);changed['customerEmail']='changed@example.test'
    status,value=submit(changed,'guest-key',token=None,cap=cap);assert status==409 and code(value)=='ORDER_IDEMPOTENCY_CONFLICT'
    status,value=submit(guest,'guest-key',token=None,cap=secrets.token_urlsafe(32));assert status==401
    status,value=submit(guest,'guest-key',token=operator,cap=secrets.token_urlsafe(32));assert status==409 and code(value)=='ORDER_IDEMPOTENCY_CONFLICT'
    print('PASS: a key is not ownership; other customers/guest capabilities cannot replay an order and operator access cannot change its bound guest identity',flush=True)

    for key in ['', 'x'*101,'bad key','é']:
        status,value=submit(body(),key);assert status==400,(status,value)
    assert accepted(body(),'x'*100)['id']
    left=accepted(body(),None);right=accepted(body(),None);assert left['id']!=right['id']
    historical=accepted(body(),'legacy-review');source=historical['id']
    sql('order',f'UPDATE orders SET checkout_request_hash=NULL,checkout_principal=NULL WHERE id={source}')
    status,value=submit(body(),'legacy-review','order-b');assert status==409 and code(value)=='ORDER_IDEMPOTENCY_LEGACY_REVIEW'
    assert call('order-b',f'/orders/{source}',token=customer)['id']==source;one('legacy-review')
    print('PASS: header bounds fail before writes; optional missing keys remain independent; V20 legacy records require explicit review while owner GET still works',flush=True)

    today=datetime.now(timezone.utc).date()
    call('promotion','/promotions/codes','POST',dict(code='INTENT-ONE',description='A15',discountPercent=10,maxRedemptions=1,validFrom=str(today-timedelta(days=1)),validTo=str(today+timedelta(days=1)),active=True,minimumOrderValue=0),expected=201)
    discounted=body();discounted['promotionCode']='INTENT-ONE'
    purchase=accepted(discounted,'one-coupon');source=purchase['id']
    assert not call('promotion','/promotions/codes/validate','POST',dict(code='INTENT-ONE',subtotal=10))['valid']
    assert accepted(discounted,'one-coupon','order-b')['id']==source
    payment_body=dict(orderId=source,paymentMethod='CASH_ON_DELIVERY',simulateFailure=False,invoiceRequested=False,customerId=17)
    first=call('payment-a','/payments','POST',payment_body,token=customer,expected=201)
    poll(lambda:sql('promotion',f'SELECT count(*) FROM promotion_redemptions WHERE order_id={source}'),lambda count:count=='1')
    assert accepted(discounted,'one-coupon','order-b')['id']==source
    assert call('payment-b','/payments','POST',payment_body,token=customer,expected=201)['id']==first['id']
    assert sql('promotion',"SELECT redeemed_count FROM promotion_codes WHERE code='INTENT-ONE'")=='1'
    assert sql('payment',f'SELECT count(*) FROM payments WHERE order_id={source}')=='1';one('one-coupon')
    print('PASS: replay preserves a reserved/consumed last-use coupon and accepted cash purchase without another order or payment',flush=True)

    control['drop_inventory']=True
    status,value=submit(body(),'lost-reply');assert status==503,(status,value)
    source=int(sql('order',"SELECT id FROM orders WHERE idempotency_key='lost-reply'"))
    assert sql('order',f"SELECT status FROM orders WHERE id={source}")=='CREATED';one('lost-reply')
    processes['order'].kill();processes['order'].wait(10)
    control['product_unavailable']=True;before=control['product_requests']
    recovered=accepted(body(),'lost-reply','order-b');assert recovered['id']==source and recovered['status']=='CONFIRMED'
    assert control['product_requests']==before;one('lost-reply')
    for domain in ['order','inventory','payment','promotion']:
        assert sql(domain,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{domain}_db' AND state='idle in transaction'")=='0'
    report=dict(result='PASSED',httpChecks=http.CHECKS,orderReplicas=2,identicalRace=True,differentRace=True,ownerBinding=True,
        canonicalIntent=True,replayWithoutRepricing=True,legacyReview=True,legacyMigration=True,openApiHeader=True,headerBounds=True,couponAndCashReplay=True,lostReserveReplyAndProcess=True)
    (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS A15: immutable owner-bound checkout, replica races, legacy handling and recovery after lost reply/process death',flush=True)

def main():
    spec=importlib.util.spec_from_file_location('intent_fixture',ROOT/'scripts/qa/deferred-payments-e2e.py')
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture);fixture.main(idempotency=True)

if __name__=='__main__':main()
