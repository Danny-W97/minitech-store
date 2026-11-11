#!/usr/bin/env python3
"""A11 safety boundary: actual services, disposable PostgreSQL and two Payment replicas.
Refunds are deliberately unavailable: no successful cancellation may imply one.
"""
import concurrent.futures
import importlib.util
import json
from pathlib import Path
import signal
import urllib.error

ROOT=Path(__file__).resolve().parents[2]


def run(logs,call,create,accept,sql,processes,start,ready,poll,tokens,customer,operator):
    def cancel(order,expected=200):
        return call('order',f'/orders/{order["id"]}/status','PATCH',dict(status='CANCELLED'),expected=expected)
    def current(order):return call('order',f'/orders/{order["id"]}')
    def reservation(order):return sql('inventory',f"SELECT status FROM inventory_reservations WHERE order_id={order['id']}")
    def no_effects(order):
        source=order['id']
        for database,table in [('billing','invoices'),('delivery','shipments'),('promotion','promotion_redemptions')]:
            assert sql(database,f'SELECT count(*) FROM {table} WHERE order_id={source}')=='0'
    unpaid=create();cancel(unpaid);assert current(unpaid)['status']=='CANCELLED' and reservation(unpaid)=='RELEASED'
    cancel(unpaid);accept(unpaid,expected=409);no_effects(unpaid)
    path=f'/internal/payments/orders/{unpaid["id"]}/cancel'
    for actor,expected in [(None,401),(operator,403),(customer,403),(tokens['delivery-service'],403)]:
        call('payment-b',path,'POST',token=actor,expected=expected)
    assert call('payment-b',path,'POST',token=tokens['order-service'])==dict(orderId=unpaid['id'],cancelled=True)
    print('PASS: unpaid cancellation fences both replicas, releases once and restricts the internal API',flush=True)

    lost=create();sql('order',f"UPDATE orders SET inventory_reservation_id=NULL WHERE id={lost['id']}")
    cancel(lost);assert current(lost)['status']=='CANCELLED' and reservation(lost)=='RELEASED';no_effects(lost)
    print('PASS: a reservation committed before its lost response is resolved by order ID',flush=True)

    # Old FAILED did not reliably mean a provider decline. None of these rows
    # proves that funds/fulfillment are safe to reverse.
    for state,reference in [('PENDING',None),('PENDING','AUTH-UNKNOWN'),('FAILED',None),('SUCCEEDED','AUTH-PAID')]:
        order=create();source=order['id'];provider='NULL' if reference is None else "'"+reference+"'"
        sql('payment',f"INSERT INTO payments(order_id,idempotency_key,amount,currency,payment_method,status,provider_reference,created_at,updated_at,processing_until,result_recorded_at) VALUES({source},'legacy-{source}',{order['totalPrice']},'PLN','CARD','{state}',{provider},now(),now(),now()-interval '1 minute',NULL)")
        cancel(order,409);assert current(order)['status']=='CONFIRMED' and reservation(order)=='RESERVED';no_effects(order)
        assert sql('order',f'SELECT state FROM order_sagas WHERE order_id={source}')=='WAITING_FOR_PAYMENT'
    print('PASS: pending/expired/authorized/successful and ambiguous legacy FAILED payments cannot be cancelled',flush=True)

    paid=create();call('order',f'/orders/{paid["id"]}/status','PATCH',dict(status='PAID'));cancel(paid,409)
    assert current(paid)['status']=='PAID' and reservation(paid)=='RESERVED'
    cash=create();payment=accept(cash);cancel(cash,409)
    assert current(cash)['paymentState']=='COLLECTION_DUE' and reservation(cash)=='CONFIRMED'
    assert call('payment-b',f'/payments/{payment["id"]}',token=customer)['status']=='PENDING'
    print('PASS: paid and accepted cash orders retain their financial and fulfillment state',flush=True)

    # Race actual first inserts on both services. Cancellation may win, or a
    # payment may claim first. A rejected acceptance is safe to retry explicitly.
    for _ in range(3):
        order=create()
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            def attempt(action):
                try:return action(),200
                except AssertionError as failure:
                    if '409' not in str(failure):raise
                    return None,409
            first=pool.submit(attempt,lambda:cancel(order))
            second=pool.submit(attempt,lambda:accept(order))
            cancelled,cancel_code=first.result(30);accepted,pay_code=second.result(30)
        if cancel_code==200:
            assert pay_code==409 and current(order)['status']=='CANCELLED' and reservation(order)=='RELEASED'
            assert sql('payment',f"SELECT count(*) FROM payments WHERE order_id={order['id']}")=='0'
        else:
            if pay_code==409:accepted=accept(order)
            assert current(order)['paymentState']=='COLLECTION_DUE' and reservation(order)=='CONFIRMED'
            cancel(order,409)
    print('PASS: concurrent cancellation/first payment cannot both succeed',flush=True)

    unavailable=create();processes['payment-a'].send_signal(signal.SIGSTOP)
    try:
        cancel(unavailable,503)
        assert current(unavailable)['status']=='CONFIRMED' and reservation(unavailable)=='RESERVED'
        assert sql('order',f"SELECT state FROM order_sagas WHERE order_id={unavailable['id']}")=='COMPENSATION_PENDING'
    finally:processes['payment-a'].send_signal(signal.SIGCONT)
    poll(lambda:current(unavailable),lambda row:row['status']=='CANCELLED')
    assert reservation(unavailable)=='RELEASED';no_effects(unavailable)
    print('PASS: dependency outage leaves a durable pending cancellation and recovers automatically',flush=True)

    crash=create();source=crash['id']
    sql('order',f"CREATE FUNCTION pause_cancel() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id={source} AND NEW.status='CANCELLED' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_cancel BEFORE UPDATE ON orders FOR EACH ROW EXECUTE FUNCTION pause_cancel()")
    with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
        pending=pool.submit(cancel,crash)
        poll(lambda:sql('order',"SELECT count(*) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'"),lambda count:count!='0')
        assert reservation(crash)=='RELEASED'
        processes['order'].kill();processes['order'].wait(10)
        sql('order',"SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='order_db' AND wait_event='PgSleep'")
        sql('order','DROP TRIGGER pause_cancel ON orders; DROP FUNCTION pause_cancel()')
        sql('order',f"UPDATE order_sagas SET processing_until=now()-interval '1 second',next_attempt_at=now() WHERE order_id={source}")
        try:pending.result(10)
        except (OSError,urllib.error.URLError):pass
    start('order');ready('order');poll(lambda:current(crash),lambda row:row['status']=='CANCELLED')
    assert reservation(crash)=='RELEASED';cancel(crash);no_effects(crash)
    assert sql('inventory','SELECT reserved_quantity FROM inventory_items WHERE product_id=1')=='5'
    report=dict(result='PASSED',paymentReplicas=2,firstPaymentRaces=3,crashAfterRelease=True,automaticRecovery=True,refundProvider='unavailable; unsafe cancellation blocked')
    (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print('PASS A11: cancellation fences, conservative money boundary, replica race and crash recovery',flush=True)


if __name__=='__main__':
    spec=importlib.util.spec_from_file_location('deferred_setup',ROOT/'scripts/qa/deferred-payments-e2e.py')
    setup=importlib.util.module_from_spec(spec);spec.loader.exec_module(setup);setup.main(cancellation=True)
