#!/usr/bin/env python3
"""A14 acceptance on the shared disposable fulfillment fixture, with two Order/Promotion replicas."""
import concurrent.futures
from datetime import datetime,timedelta,timezone
import importlib.util
import json
from pathlib import Path
import threading
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]

def run(logs,call,sql,poll,tokens,customer,operator,urls,http,control):
    contract_spec=importlib.util.spec_from_file_location('coupon_contract',ROOT/'scripts/qa/quality_gates.py')
    # dataclass resolves its module in sys.modules when imported outside normal import machinery.
    import sys
    gates=importlib.util.module_from_spec(contract_spec);sys.modules[contract_spec.name]=gates;contract_spec.loader.exec_module(gates)
    contract=json.loads(gates.CONTRACT.read_text())
    for domain in ['order','promotion']:gates.check_document(call(domain,'/v3/api-docs'),contract['services'][domain],domain)
    today=datetime.now(timezone.utc).date()
    def code(name,limit=1):
        return call('promotion','/promotions/codes','POST',dict(code=name,description='Audit capacity',discountPercent=10,maxRedemptions=limit,
            validFrom=str(today-timedelta(days=1)),validTo=str(today+timedelta(days=2)),active=True,minimumOrderValue=0),expected=201)
    def capacity(name):return call('promotion-b',f'/promotions/codes/{name}/capacity')
    def hold(order):return call('promotion-b',f'/internal/promotions/orders/{order}/coupon',token=tokens['payment-service'])
    def order(order_id):return call('order',f'/orders/{order_id}',token=customer)
    def body(name):return dict(items=[dict(productId=1,quantity=1)],customerId=17,customerEmail='owner@example.test',
        deliveryAddress=dict(firstName='Jan',lastName='Audit',street='Street 1',postalCode='00-001',city='City',country='PL'),
        deliveryMethod='STANDARD_COURIER',invoiceRequested=False,promotionCode=name)
    def submit(name,node='order',key=None):
        key=key or uuid.uuid4().hex
        request=urllib.request.Request(urls[node]+'/orders',data=json.dumps(body(name)).encode(),
            headers={'Authorization':'Bearer '+customer,'Content-Type':'application/json','Idempotency-Key':key},method='POST')
        try:
            with urllib.request.urlopen(request,timeout=25) as response:status,payload=response.status,json.loads(response.read())
        except urllib.error.HTTPError as error:status,payload=error.code,json.loads(error.read())
        http.CHECKS+=1;return status,payload,key
    def create(name,node='order'):
        status,value,key=submit(name,node);assert status==201,(status,value)
        assert value['status']=='CONFIRMED' and value['couponReservationVerified'] is True,value
        return value
    def cancel(order_id,node='order'):
        return call(node,f'/orders/{order_id}/status','PATCH',dict(status='CANCELLED'))
    def due(order_id):sql('order',f"UPDATE orders SET coupon_expires_at=now()-interval '1 second',coupon_next_attempt_at=now()-interval '1 second' WHERE id={order_id}")
    def cash(value,expected=201):return call('payment-a','/payments','POST',dict(orderId=value['id'],paymentMethod='CASH_ON_DELIVERY',simulateFailure=False,customerId=17,invoiceRequested=False),token=customer,expected=expected)

    code('LAST-ONE');control['quote_barrier']=threading.Barrier(2)
    with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
        a=pool.submit(submit,'LAST-ONE','order');b=pool.submit(submit,'LAST-ONE','order-b');results=[a.result(30),b.result(30)]
    assert sorted(row[0] for row in results)==[201,409],results
    winner=next(row[1] for row in results if row[0]==201)
    assert winner['couponReservationVerified'] and winner['discountAmount']==1
    loser_key=next(row[2] for row in results if row[0]==409)
    loser=int(sql('order',f"SELECT id FROM orders WHERE idempotency_key='{loser_key}'"))
    poll(lambda:order(loser),lambda row:row['status']=='CANCELLED')
    assert not order(loser)['couponReservationVerified']
    assert sql('payment',f"SELECT count(*) FROM payments WHERE order_id IN ({winner['id']},{loser})")=='0'
    assert capacity('LAST-ONE')['reservedCount']==1 and capacity('LAST-ONE')['redeemedCount']==0
    assert hold(loser)['state']=='RELEASED'
    page=call('promotion-b','/promotions/codes/reservations?code=LAST-ONE&state=RESERVED&size=1')
    assert len(page)==1 and page[0]['orderId']==winner['id']
    call('promotion-b','/promotions/codes/reservations?size=201',expected=400)
    for path in ['/promotions/codes/LAST-ONE/capacity','/promotions/codes/reservations']:
        call('promotion-b',path,token=customer,expected=403)
    for suffix,method in [('', 'GET'),('/reconcile','POST')]:call('order',f'/orders/{winner["id"]}/coupon{suffix}',method,token=customer,expected=403)
    print('PASS: both previews succeed but two Order/Promotion replicas reserve the last use exactly once; loser never confirms or charges',flush=True)

    reserve=dict(code='LAST-ONE',subtotal=10,discountAmount=1,acceptedAt=winner['createdAt'],expiresAt=(datetime.now(timezone.utc)+timedelta(minutes=40)).isoformat())
    for actor,status in [(None,401),(customer,403),(operator,403),(tokens['payment-service'],403)]:
        for action in ['reserve','release']:
            call('promotion-b',f'/internal/promotions/orders/{winner["id"]}/coupon/{action}','POST',reserve if action=='reserve' else dict(code='LAST-ONE'),token=actor,expected=status)
    call('promotion','/promotions/codes/redeem','POST',dict(code='LAST-ONE',orderId=winner['id']),expected=409)
    assert capacity('LAST-ONE')['redeemedCount']==0
    cancelled=cancel(winner['id']);assert cancelled['status']=='CANCELLED' and hold(winner['id'])['state']=='RELEASED'
    call('promotion-b',f'/internal/promotions/orders/{winner["id"]}/coupon/reserve','POST',reserve,token=tokens['order-service'],expected=409)
    cash(winner,409)
    assert capacity('LAST-ONE')['availableUses']==1
    replacement=create('LAST-ONE','order-b');assert hold(replacement['id'])['state']=='RESERVED'
    print('PASS: only Order can reserve/release; unpaid cancellation frees one use and fences a late reserve or payment',flush=True)

    code('EXPIRE-ONE');expired=create('EXPIRE-ONE');due(expired['id'])
    poll(lambda:order(expired['id']),lambda row:row['status']=='CANCELLED')
    assert hold(expired['id'])['state']=='RELEASED' and capacity('EXPIRE-ONE')['availableUses']==1
    code('UNKNOWN-PAYMENT');uncertain=create('UNKNOWN-PAYMENT');source=uncertain['id']
    sql('payment',f"INSERT INTO payments(order_id,amount,currency,payment_method,status,idempotency_key,created_at,updated_at) VALUES ({source},{uncertain['totalPrice']},'PLN','CARD','PENDING','A14-unknown-{source}',now(),now())")
    due(source)
    poll(lambda:sql('order',f"SELECT count(*) FROM orders WHERE id={source} AND coupon_next_attempt_at>now()"),lambda count:count=='1')
    assert order(source)['status']=='CONFIRMED' and hold(source)['state']=='RESERVED'
    assert capacity('UNKNOWN-PAYMENT')['availableUses']==0
    rejected,_,_=submit('UNKNOWN-PAYMENT');assert rejected==400
    sql('payment',f"UPDATE payments SET status='FAILED',provider_declined=true,result_recorded_at=now(),provider_reference=NULL WHERE order_id={source}")
    due(source);poll(lambda:order(source),lambda row:row['status']=='CANCELLED')
    assert hold(source)['state']=='RELEASED'
    print('PASS: expiry cancels unpaid orders through Payment fencing; an unknown payment keeps the coupon occupied until a definitive decline',flush=True)

    code('LOST-RESERVE');control['drop_reserve']='LOST-RESERVE'
    status,value,key=submit('LOST-RESERVE','order-b');assert status==503,(status,value)
    source=int(sql('order',f"SELECT id FROM orders WHERE idempotency_key='{key}'"))
    accepted=poll(lambda:order(source),lambda row:row['status']=='CONFIRMED')
    assert accepted['couponReservationVerified'] and capacity('LOST-RESERVE')['reservedCount']==1
    assert sql('promotion',f'SELECT count(*) FROM coupon_reservations WHERE order_id={source}')=='1'
    code('LOST-RELEASE');released=create('LOST-RELEASE');control['drop_release']='LOST-RELEASE'
    call('order',f'/orders/{released["id"]}/status','PATCH',dict(status='CANCELLED'),expected=503)
    poll(lambda:order(released['id']),lambda row:row['status']=='CANCELLED')
    assert hold(released['id'])['state']=='RELEASED' and capacity('LOST-RELEASE')['availableUses']==1
    print('PASS: lost reserve/release replies recover through the saga without double allocation or a false completed cancellation',flush=True)

    code('LEGACY-QUOTE');legacy=create('LEGACY-QUOTE');source=legacy['id']
    # Represents an existing accepted quote from before the reservation migration.
    sql('promotion',f'DELETE FROM coupon_reservations WHERE order_id={source}')
    sql('order',f'UPDATE orders SET coupon_reservation_verified=false,coupon_expires_at=NULL,coupon_next_attempt_at=NULL WHERE id={source}')
    cash(legacy,409);assert sql('payment',f'SELECT count(*) FROM payments WHERE order_id={source}')=='0'
    control['fail_lookup']=True
    try:cash(legacy,503)
    finally:control['fail_lookup']=False
    assert sql('payment',f'SELECT count(*) FROM payments WHERE order_id={source}')=='0'
    call('order',f'/orders/{source}/coupon/reconcile','POST')
    assert order(source)['couponReservationVerified']
    cash(legacy);poll(lambda:capacity('LEGACY-QUOTE'),lambda row:row['redeemedCount']==1 and row['reservedCount']==0)
    assert order(source)['paymentState']=='COLLECTION_DUE' and hold(source)['state']=='CONSUMED'
    print('PASS: legacy quotes without proof cannot start payment; operator reconciliation restores a verified reservation and one redemption',flush=True)

    code('HONOR-QUOTE');honored=create('HONOR-QUOTE')
    sql('promotion',"UPDATE promotion_codes SET active=false,valid_to=current_date-1 WHERE code='HONOR-QUOTE'")
    cash(honored);poll(lambda:capacity('HONOR-QUOTE'),lambda row:row['redeemedCount']==1)
    assert hold(honored['id'])['state']=='CONSUMED'
    assert capacity('HONOR-QUOTE')['availableUses']==0
    print('PASS: an already-reserved price survives coupon expiry/deactivation; redemption moves a held use to consumed exactly once',flush=True)

    for domain in ['order','promotion','payment']:
        assert sql(domain,f"SELECT count(*) FROM pg_stat_activity WHERE datname='{domain}_db' AND state='idle in transaction'")=='0'
    report=dict(result='PASSED',httpChecks=http.CHECKS,orderReplicas=2,promotionReplicas=2,previewRace=True,lastUseReservedOnce=True,
        releaseTombstone=True,coordinatedExpiry=True,unknownPaymentRetainsCapacity=True,lostReserveReply=True,lostReleaseReply=True,
        legacyPaymentBlocked=True,legacyLookupOutage503=True,operatorReconciliation=True,reservedPriceHonored=True,operatorReadRoles=True,openApiContract=True)
    (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS A14: coupon capacity reservations, fenced expiry and durable saga recovery',flush=True)

def main():
    spec=importlib.util.spec_from_file_location('coupon_fixture',ROOT/'scripts/qa/deferred-payments-e2e.py')
    fixture=importlib.util.module_from_spec(spec);spec.loader.exec_module(fixture);fixture.main(coupons=True)

if __name__=='__main__':main()
