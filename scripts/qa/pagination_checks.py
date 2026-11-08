"""A29 acceptance scenario, called only with access-control-e2e.py --pagination.

Uses exclusively that scenario's disposable databases and real packaged services.
"""
import json
import time
import urllib.request
import urllib.error
from decimal import Decimal, ROUND_HALF_UP


def run_pagination_checks(api, urls, operator, credentials, one, two, product_id, sql, logdir):
    checks = 0
    reports = {}

    def get(path, token=operator, expected=200, origin=None):
        nonlocal checks
        headers = {'Authorization': 'Bearer ' + token} if token else {}
        if origin: headers['Origin'] = origin
        request = urllib.request.Request((path if path.startswith('http') else api + path), headers=headers)
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                status, body, headers = response.status, response.read(), response.headers
        except urllib.error.HTTPError as error:
            status, body, headers = error.code, error.read(), error.headers
        assert status == expected, (path, status, body[:300])
        checks += 1
        return json.loads(body), headers, len(body)

    def clone(database, table, changes):
        overrides = ",".join(f"'{key}',{expression}" for key, expression in {'id': 'g', **changes}.items())
        sql(database, f"WITH seed AS MATERIALIZED (SELECT to_jsonb(t) AS value FROM {table} t ORDER BY id LIMIT 1), expanded AS MATERIALIZED (SELECT jsonb_populate_record(null::{table}, seed.value || jsonb_build_object({overrides})) AS row FROM seed CROSS JOIN generate_series(1000000,1099999) g) INSERT INTO {table} SELECT (row).* FROM expanded; ANALYZE {table};")
        assert int(sql(database, f'SELECT count(*) FROM {table} WHERE id BETWEEN 1000000 AND 1099999')) == 100000
        print('SEEDED', database, table, '100000', flush=True)

    clone('order_db', 'orders', {'customer_id': str(one[0]), 'customer_email': "'page@audit.example'", 'status': "'PAID'", 'idempotency_key': "'page-'||g"})
    clone('order_db', 'order_items', {'order_id': 'g', 'product_id': str(product_id)})
    clone('payment_db', 'payments', {'order_id': 'g', 'idempotency_key': "'page-'||g", 'status': "'SUCCEEDED'"})
    clone('customer_db', 'customers', {'email': "'page-'||g||'@audit.example'", 'verification_token_hash': 'null'})
    clone('notification_db', 'notifications', {'source_id': 'g', 'status': "'SENT'"})
    clone('chat_db', 'chat_messages', {'status': "'NEW'", 'subject': "'A29 page '||g"})
    clone('returns_db', 'return_requests', {'order_id': 'g', 'customer_id': str(one[0])})
    clone('review_db', 'reviews', {'order_id': 'g', 'customer_id': str(one[0]), 'product_id': str(product_id), 'status': "'PUBLISHED'", 'rating': '(g%5)+1'})
    clone('billing_db', 'invoices', {'order_id': 'g', 'customer_id': str(one[0]), 'invoice_number': "'A29/'||g"})
    clone('billing_db', 'invoice_corrections', {'correction_number': "'A29-K/'||g"})
    # Dead letters need no SMTP requests; replay remains an explicit operator action.
    sql('notification_db', "INSERT INTO notification_dead_letters(id,notification_id,event_type,source_id,recipient,channel,subject,message,status,failure_reason,attempt_count,created_at,updated_at) SELECT g,g,'PAYMENT_SUCCEEDED',g,'page@audit.example','EMAIL','A29','A29','OPEN','A29',3,now(),now() FROM generate_series(1000000,1099999) g; ANALYZE notification_dead_letters;")

    endpoints = ['/orders', f'/orders/customer/{one[0]}', '/payments', '/customers', '/notifications', '/chat/messages', '/returns', f'/returns/customer/{one[0]}', f'/reviews/products/{product_id}', f'/reviews/customer/{one[0]}', '/invoices', f'/invoices/customer/{one[0]}', '/invoices/corrections']
    _, exposed, _ = get('/orders?size=1', origin='http://127.0.0.1:18070')
    assert 'x-next-cursor' in exposed.get('Access-Control-Expose-Headers', '').lower()
    for path in endpoints:
        started = time.monotonic()
        first, headers, length = get(path + '?size=50')
        assert len(first) == 50 and headers['X-Has-More'] == 'true', (path, headers, len(first))
        assert int(headers['X-Next-Cursor']) == first[-1]['id']
        second, more, _ = get(path + '?size=50&cursor=' + headers['X-Next-Cursor'])
        assert len(second) == 50 and not {r['id'] for r in first}.intersection(r['id'] for r in second)
        assert all(r['id'] < first[-1]['id'] for r in second)
        bounded, _, _ = get(path + '?size=200')
        assert len(bounded) == 200
        for bad in ['size=0', 'size=201', 'size=-1', 'cursor=-1', 'cursor=9223372036854775808']:
            get(path + '?' + bad, expected=400)
        empty, end, _ = get(path + '?cursor=1')
        assert not empty and end['X-Has-More'] == 'false' and end['X-Next-Cursor'] == '0'
        reports[path] = {'rowsInFixture': 100000, 'pageRows': len(first), 'pageBytes': length, 'twoPagesSeconds': round(time.monotonic()-started, 3)}
    # Ascending queues: stable FIFO tie-breaker even when all timestamps are equal.
    for path in ['/notifications/dead-letter', '/reviews/moderation/pending']:
        if 'reviews' in path:
            sql('review_db', 'UPDATE reviews SET status=\'PENDING\' WHERE id BETWEEN 1000000 AND 1000100')
        rows, headers, _ = get(path + '?size=50')
        following, _, _ = get(path + '?size=50&cursor=' + headers['X-Next-Cursor'])
        assert len(rows) == len(following) == 50
        assert all(row['id'] > rows[-1]['id'] for row in following)

    # An insert above the current cursor does not duplicate or displace the next page.
    first, headers, _ = get('/orders?size=17')
    clone_query = "INSERT INTO orders SELECT (jsonb_populate_record(null::orders,to_jsonb(o)||jsonb_build_object('id',1100000,'idempotency_key','A29-new'))).* FROM orders o WHERE id=1099999"
    sql('order_db', clone_query)
    # The newly inserted page boundary record must remain a valid order with its item.
    sql('order_db', "INSERT INTO order_items SELECT (jsonb_populate_record(null::order_items,to_jsonb(i)||jsonb_build_object('id',1100000,'order_id',1100000))).* FROM order_items i WHERE order_id=1099999 LIMIT 1")
    assert sql('order_db', 'SELECT count(*) FROM order_items WHERE order_id=1100000') == '1'
    following, _, _ = get('/orders?size=17&cursor=' + headers['X-Next-Cursor'])
    assert [row['id'] for row in following] == list(range(first[-1]['id']-1, first[-1]['id']-18, -1))
    # Traverse a finite slice including a short final page, without OFFSET or gaps.
    ids, cursor = [], '1000111'
    for _ in range(10):
        rows, headers, _ = get('/orders?size=23&cursor=' + cursor)
        ids.extend(r['id'] for r in rows if r['id'] >= 1000000)
        if not headers['X-Has-More'] == 'true': break
        cursor = headers['X-Next-Cursor']
    assert ids == list(range(1000110, 999999, -1))
    # SQL filters, customer ownership, and service-only aggregates.
    rows, _, _ = get(f'/orders/customer/{one[0]}?status=PAID&productId={product_id}&size=20', one[2])
    assert len(rows) == 20 and all(r['customerId'] == one[0] and r['status'] == 'PAID' for r in rows)
    get(f'/orders/customer/{one[0]}?cursor=1099900', two[2], 403)
    get(f'/invoices/customer/{one[0]}?cursor=1099900', two[2], 403)
    get(f'/returns/customer/{one[0]}?cursor=1099900', two[2], 403)
    get(f'/reviews/customer/{one[0]}?cursor=1099900', two[2], 403)
    rows, _, _ = get('/chat/messages?search=1099999')
    assert len(rows) == 1 and rows[0]['id'] == 1099999
    get('/chat/messages?search=' + 'x'*201, expected=400)
    url = urls['order-service'] + f'/internal/orders/customer/{one[0]}/purchased-products?productIds={product_id},99999999'
    products, _, _ = get(url, credentials['recommendation-service'])
    assert products == [product_id]
    for token in [None, operator, one[2], credentials['payment-service']]: get(url, token, 403 if token else 401)
    get(url.replace(f'{product_id},99999999', ','.join(map(str,range(1,102)))), credentials['recommendation-service'], 400)
    sql('review_db', "UPDATE reviews SET status='PUBLISHED' WHERE id BETWEEN 1000000 AND 1099999")
    count, avg = sql('review_db', f"select count(*),avg(rating) from reviews where product_id={product_id} and status='PUBLISHED'").split('|')
    summary, _, _ = get(f'/reviews/products/{product_id}/summary', None)
    assert summary['publishedCount'] == int(count)
    assert Decimal(str(summary['averageRating'])) == Decimal(avg).quantize(Decimal('.01'), rounding=ROUND_HALF_UP)
    public, _, _ = get(f'/reviews/products/{product_id}?size=20', None)
    assert all(not {'customerId','orderId','moderationNote'}.intersection(row) for row in public)
    invoice_id, count, delta = sql('billing_db', 'select invoice_id,count(*),sum(correction_gross) from invoice_corrections group by invoice_id order by count(*) desc limit 1').split('|')
    totals, _, _ = get(f'/invoices/corrections/summary?invoiceIds={invoice_id}')
    assert len(totals) == 1 and totals[0]['correctionCount'] == int(count)
    assert Decimal(str(totals[0]['grossDelta'])) == Decimal(delta)
    get(f'/invoices/corrections/summary?invoiceIds={invoice_id}', one[2], 403)
    # Measure returned DB rows independently of HTTP response trimming.
    for database, path, table in [('order_db', '/orders?size=50', 'orders'), ('review_db', f'/reviews/products/{product_id}?size=50', 'reviews')]:
        sql(database, 'CREATE EXTENSION IF NOT EXISTS pg_stat_statements; SELECT pg_stat_statements_reset()')
        get(path)
        statements = sql(database, f"select query,rows,calls from pg_stat_statements where query like 'select %' and query like '%{table}%' and query not like '%pg_stat%' order by rows desc")
        (logdir / (database + '-page-statements.txt')).write_text(statements)
        assert statements
        for statement in statements.splitlines():
            _, rows, calls = statement.rsplit('|',2)
            assert int(rows) / int(calls) <= 51, statement
    for database, query in [('order_db', f'select id from orders where customer_id={one[0]} and id<1050000 order by id desc limit 51'), ('review_db', f"select id from reviews where product_id={product_id} and status='PUBLISHED' and id<1050000 order by id desc limit 51")]:
        plan = sql(database, 'EXPLAIN (ANALYZE, BUFFERS) ' + query)
        assert 'Index' in plan and 'Seq Scan' not in plan, plan
        (logdir / (database + '-plan.txt')).write_text(plan)
    (logdir / 'pagination-report.json').write_text(json.dumps(reports, indent=2))
    print(f'PASS A29: {checks} pagination HTTP checks; 100,000 rows in 11 tables, bounded SQL rows and index plans.', flush=True)
