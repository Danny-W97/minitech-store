#!/usr/bin/env python3
"""A43.25: owner-only invoice/item/correction and existing PDF export with isolated Customer/Order/Delivery/Gateway."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import time

import quality_gates as qa

ROOT = Path(__file__).resolve().parents[2]


def command(*args):
    result = subprocess.run(args, cwd=ROOT, capture_output=True, text=True, timeout=60)
    if result.returncode:
        raise RuntimeError(f'Fixture command failed: {args[0]} {args[1] if len(args)>1 else ""}')
    return result.stdout.strip()


def port():
    with socket.socket() as probe:
        probe.bind(('127.0.0.1', 0))
        return probe.getsockname()[1]


def main():
    directory = ROOT / 'tmp' / ('invoice-export-' + secrets.token_hex(4))
    directory.mkdir(parents=True)
    postgres = None
    processes, handles, checks = {}, [], []
    password, operator = (secrets.token_urlsafe(32) for _ in range(2))
    credentials = {name: secrets.token_urlsafe(32) for name in ('order-service', 'billing-service')}
    key = secrets.token_bytes(48)
    names = ('customer-service', 'order-service', 'billing-service', 'api-gateway')
    urls = {name: f'http://127.0.0.1:{port()}' for name in names}

    def sql(database, query):
        return command('docker', 'exec', postgres, 'psql', '-X', '-v', 'ON_ERROR_STOP=1',
                       '-U', 'fixture', '-d', database, '-Atqc', query)

    def token(customer=1, email='owner@fixture.example', expired=False):
        encode = lambda item: base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned = encode({'alg': 'HS256', 'typ': 'JWT'}) + '.' + encode(
            {'typ': 'access', 'sub': str(customer), 'email': email, 'exp': int(time.time()) + (-600 if expired else 600)})
        return unsigned + '.' + base64.urlsafe_b64encode(hmac.new(key, unsigned.encode(), hashlib.sha256).digest()).rstrip(b'=').decode()

    try:
        postgres = command('docker', 'run', '-d', '--rm', '-p', '127.0.0.1::5432',
                           '-e', 'POSTGRES_USER=fixture', '-e', 'POSTGRES_PASSWORD=' + password, 'postgres:16-alpine')
        db_port = command('docker', 'port', postgres, '5432').rsplit(':', 1)[1]
        for _ in range(60):
            if subprocess.run(['docker', 'exec', postgres, 'pg_isready', '-U', 'fixture'], capture_output=True).returncode == 0:
                break
            time.sleep(.5)
        else:
            raise RuntimeError('Fixture PostgreSQL startup timed out')
        for database in ('customer_db', 'order_db', 'billing_db'):
            command('docker', 'exec', postgres, 'createdb', '-U', 'fixture', database)
        for name in names:
            database = {'order-service': 'order_db', 'billing-service': 'billing_db'}.get(name, 'customer_db')
            env = {k: v for k, v in os.environ.items() if k in ('PATH', 'JAVA_HOME', 'HOME', 'LANG', 'TMP', 'TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev', SERVER_PORT=urls[name].rsplit(':', 1)[1],
                       DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}', DB_USERNAME='fixture', DB_PASSWORD=password,
                       CUSTOMER_SERVICE_URL=urls['customer-service'], PLATFORM_AUTH_URL=urls['customer-service'],
                       ORDER_SERVICE_URL=urls['order-service'], PLATFORM_ORDER_URL=urls['order-service'],
                       BILLING_SERVICE_URL=urls['billing-service'], PLATFORM_OPERATOR_TOKEN=operator,
                       PLATFORM_SERVICE_TOKEN=credentials.get(name, secrets.token_urlsafe(32)),
                       PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                       CUSTOMER_JWT_SECRET=base64.b64encode(key).decode(),
                       CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                       CUSTOMER_SECURITY_RETENTION_ENABLED='false', CUSTOMER_AUDIT_RETENTION_MODE='off',
                       ORDER_SAGA_RECOVERY_ENABLED='false', COUPON_EXPIRY_ENABLED='false', BILLING_CONSUMER_ENABLED='false', OUTBOX_PUBLISHER_ENABLED='false',
                       BILLING_INVOICE_STREAM_RETENTION_MODE='off', BILLING_OUTBOX_RETENTION_MODE='off', BILLING_REJECTED_INBOX_RETENTION_MODE='off',
                       GATEWAY_RATE_LIMIT_ENABLED='false', MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                       SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3', SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars = list((ROOT / name / 'target').glob('*-SNAPSHOT.jar'))
            assert len(jars) == 1, f'Package {name} first'
            handle = (directory / (name + '.log')).open('w'); handles.append(handle)
            process = subprocess.Popen(['java', '-Xms48m', '-Xmx192m', '-XX:TieredStopAtLevel=1', '-jar', str(jars[0])],
                                       env=env, cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
            processes[name] = process
            deadline = time.monotonic() + 120
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError(f'{name} exited; inspect fixture logs in {directory}')
                if qa.Client(urls[name], timeout=2).get('/actuator/health').status == 200:
                    break
                time.sleep(.3)
            else:
                raise RuntimeError(f'{name} fixture readiness timed out')


        sql('customer_db', """
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-hash','Jan','Testowy','Testowa 1','00-001','Warszawa','Polska',true,now(),now()),
                   ('other@fixture.example','fixture-hash','Anna','Inna','Testowa 2','00-002','Kraków','Polska',true,now(),now()),
                   ('disabled@fixture.example','fixture-hash','Nie','Aktywna','Testowa 3','00-003','Kraków','Polska',false,now(),now())
            """)
        sql('order_db', """
            INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              fulfillment_type,idempotency_key,guest_access_token_hash,created_at,updated_at)
            SELECT n,CASE WHEN n=2 THEN 2 WHEN n=3 THEN NULL ELSE 1 END,'owner@fixture.example',123.45,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,'HOME_DELIVERY',
              'fixture-'||n,repeat('a',64),now(),now() FROM generate_series(1,7) n;
            SELECT setval('orders_id_seq',7)
            """)
        guest_capability='G'*43
        sql('order_db',"UPDATE orders SET guest_access_token_hash='"+hashlib.sha256(guest_capability.encode()).hexdigest()+"' WHERE id=3")
        sql('billing_db', """
            INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,company_name,tax_id,
              street,postal_code,city,country,net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at)
            SELECT n,n,CASE WHEN n IN (5,6) THEN NULL WHEN n=1 THEN 2 ELSE 1 END,'owner@fixture.example','FV/FIXTURE/'||n,
              CASE WHEN n=6 THEN 'COMPANY' ELSE 'PERSON' END,'Jan Żółć',CASE WHEN n=6 THEN 'Firma Żółć' ELSE NULL END,
              CASE WHEN n=6 THEN '1234567890' ELSE NULL END,'Testowa 1','00-001','Warszawa','PL',100.37,23.08,123.45,12.34,2.50,
              'PLN',CASE WHEN n=6 THEN 'CANCELLED' ELSE 'ISSUED' END,now() FROM generate_series(1,7) n WHERE n<>4;
            SELECT setval('invoices_id_seq',7);
            INSERT INTO invoice_items(invoice_id,position_no,product_id,sku,product_name,quantity,unit_price,total_price)
            SELECT 1,1000-n,n,'SKU-'||n,'Produkt Żółć '||n,2,12.34,24.68 FROM generate_series(1,105) n;
            INSERT INTO invoice_items(invoice_id,position_no,product_id,product_name,quantity,unit_price,total_price)
            VALUES(2,1,2001,'Other',1,100,100),(3,1,3001,'Guest',1,100,100);
            INSERT INTO invoice_corrections(id,invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,
              correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at,idempotency_key,ledger_version)
            SELECT n,1,1,'KOR/FIXTURE/'||n,'Powód Żółć',100.37-(n-1)*0.01,23.08,123.45-(n-1)*0.01,-0.01,0,-0.01,
              100.37-n*0.01,23.08,123.45-n*0.01,now(),CASE WHEN n=1 THEN NULL ELSE 'fixture-private-key-'||n END,
              CASE WHEN n=1 THEN NULL ELSE n END FROM generate_series(1,105) n;
            INSERT INTO invoice_corrections(id,invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,
              correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at)
            VALUES(201,2,2,'KOR/OTHER/1','Other',100.37,23.08,123.45,-1,-0.23,-1.23,99.37,22.85,122.22,now()),
                  (301,3,3,'KOR/GUEST/1','Guest',100.37,23.08,123.45,-1,-0.23,-1.23,99.37,22.85,122.22,now()),
                  (701,7,2,'KOR/BAD/1','Bad relation',100.37,23.08,123.45,-1,-0.23,-1.23,99.37,22.85,122.22,now());
            SELECT setval('invoice_corrections_id_seq',701)
            """)
        snapshot="SELECT json_build_array((SELECT json_agg(i ORDER BY id) FROM invoices i),(SELECT json_agg(l ORDER BY id) FROM invoice_items l),(SELECT json_agg(c ORDER BY id) FROM invoice_corrections c),(SELECT json_agg(s ORDER BY series) FROM invoice_sequences s),(SELECT json_agg(o ORDER BY id) FROM invoice_outbox_events o),(SELECT json_agg(e ORDER BY event_id) FROM event_inbox e))::text"
        order_snapshot='SELECT json_agg(o ORDER BY id)::text FROM orders o'
        before=sql('billing_db',snapshot),sql('order_db',order_snapshot)
        invoice_fields={'invoiceId','orderId','customerEmail','invoiceNumber','billingKind','buyerName','companyName','taxId','street','postalCode','city','country','netAmount','vatAmount','grossAmount','deliveryCost','discountAmount','currency','status','createdAt'}
        item_fields={'itemId','positionNo','productId','sku','productName','quantity','unitPrice','totalPrice'}
        correction_fields={'correctionId','invoiceId','orderId','correctionNumber','reason','originalNet','originalVat','originalGross','correctionNet','correctionVat','correctionGross','finalNet','finalVat','finalGross','createdAt','balanceModel','pdf'}
        owner={'Authorization':'Bearer '+token(),'X-Customer-Id':'2','X-Order-Access-Token':guest_capability}
        other={'Authorization':'Bearer '+token(2,'other@fixture.example')}
        operator_headers={'Authorization':'Bearer '+operator}
        billing_client=qa.Client(urls['billing-service'],timeout=20)
        # Existing read-only renderer/cache is deliberately warmed, including foreign and inconsistent documents.
        legacy_pdf=qa.status(billing_client.get('/invoices/order/1/pdf',headers=owner),200,'existing invoice PDF').body
        legacy_correction=qa.status(billing_client.get('/invoices/corrections/1/pdf',headers=owner),200,'existing correction PDF').body
        qa.status(billing_client.get('/invoices/order/2/pdf',headers=other),200,'warm foreign PDF cache')
        qa.status(billing_client.get('/invoices/corrections/201/pdf',headers=other),200,'warm foreign correction cache')
        qa.status(billing_client.get('/invoices/corrections/701/pdf',headers=operator_headers),200,'warm inconsistent PDF cache')
        targets=[('billing-service',''),('api-gateway','/api'),('api-gateway','/api/v1')]
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=20);base=prefix+'/invoices/me/data-export/orders/'
            header=qa.status(client.get(base+'1?customerId=2',headers=owner),200,'owned header despite wrong local customer ID').document()
            assert set(header)=={'schemaVersion','scope','complete','generatedAt','invoice','pdf','omissions'}
            assert header['scope']=='invoice-for-order' and header['complete'] is False and set(header['invoice'])==invoice_fields
            assert header['invoice']['invoiceId']==1 and header['invoice']['buyerName']=='Jan Żółć' and header['invoice']['grossAmount']==123.45
            assert header['pdf']=={'path':'/invoices/me/data-export/orders/1/pdf','mediaType':'application/pdf','representation':'rendered-stored-document'}
            legacy=qa.status(client.get(base+'5',headers=owner),200,'null local customer ID').document()['invoice']
            assert legacy['invoiceId']==5 and legacy['companyName'] is None and legacy['taxId'] is None
            company=qa.status(client.get(base+'6',headers=owner),200,'stored company and cancelled invoice').document()['invoice']
            assert company['billingKind']=='COMPANY' and company['companyName']=='Firma Żółć' and company['taxId']=='1234567890' and company['status']=='CANCELLED'
            for suffix,collection,fields,scope,filename,expected,cursor_field in (
                ('items','items',item_fields,'invoice-items-page','invoice-items-data-page.json',list(range(999,894,-1)),'positionNo'),
                ('corrections','corrections',correction_fields,'invoice-corrections-page','invoice-corrections-data-page.json',list(range(105,0,-1)),'correctionId')):
                cursor,collected,pages='0',[],0
                while True:
                    response=qa.status(client.get(base+f'1/{suffix}?size=50&cursor={cursor}',headers=owner),200,'owned '+collection)
                    doc=response.document();pages+=1
                    assert set(doc)=={'schemaVersion','scope','complete','generatedAt','orderId',collection,'hasMore','nextCursor','omissions'}
                    assert doc['scope']==scope and doc['complete'] is False and doc['orderId']==1
                    rows=doc[collection];assert len(rows)<=50 and all(set(row)==fields for row in rows)
                    if collection=='items':assert all(row['quantity']==2 and row['unitPrice']==12.34 and row['productName'].startswith('Produkt Żółć') for row in rows)
                    else:
                        assert all(row['reason']=='Powód Żółć' and row['correctionGross']==-0.01 for row in rows)
                        for row in rows:
                            assert row['balanceModel']==('LEGACY_BASE_DELTA' if row['correctionId']==1 else 'CUMULATIVE_DELTA')
                            assert row['pdf']['path']==f"/invoices/me/data-export/orders/1/corrections/{row['correctionId']}/pdf"
                    assert b'fixture-private' not in response.body and b'idempotencyKey' not in response.body and b'ledgerVersion' not in response.body
                    assert response.headers['content-disposition']==f'attachment; filename="{filename}"'
                    assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                    collected.extend(row[cursor_field] for row in rows)
                    if not doc['hasMore']:assert doc['nextCursor']=='0';break
                    cursor=doc['nextCursor'];assert cursor.isdigit() and pages<10
                assert pages==3 and collected==expected
                assert qa.status(client.get(base+'5/'+suffix,headers=owner),200,'owned empty invoice').document()[collection]==[]
                assert qa.status(client.get(base+'1/'+suffix+'?cursor=1',headers=owner),200,'exhausted page').document()[collection]==[]
                for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):qa.status(client.get(base+'1/'+suffix+query,headers=owner),400,'invalid window')
            for suffix,expected_pdf in (('/pdf',legacy_pdf),('/corrections/1/pdf',legacy_correction)):
                response=qa.status(client.get(base+'1'+suffix,headers=owner),200,'authorized existing PDF rendering')
                assert response.body==expected_pdf and response.body.startswith(b'%PDF-')
                assert response.headers['content-type'].startswith('application/pdf') and 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control']
                assert response.headers['x-content-type-options']=='nosniff'
            for order_id in (2,3,4,99999,0):
                for suffix in ('','/items','/corrections','/pdf'):qa.status(client.get(base+str(order_id)+suffix,headers=owner),404,'foreign/guest/missing order or invoice')
            qa.status(client.get(base+'2/corrections/201/pdf',headers=owner),404,'foreign warmed PDF')
            qa.status(client.get(base+'3/corrections/301/pdf',headers=owner),404,'guest PDF with capability')
            for correction in (201,99999):qa.status(client.get(base+f'1/corrections/{correction}/pdf',headers=owner),404,'correction outside own invoice')
            qa.status(client.get(base+'7/corrections',headers=owner),503,'inconsistent correction page')
            qa.status(client.get(base+'7/corrections/701/pdf',headers=owner),503,'inconsistent warmed PDF')
            assert qa.status(client.get(base+'2',headers=other),200,'second owner despite stale local ID').document()['invoice']['invoiceId']==2
            qa.status(client.get(base+'1/pdf',headers=other),404,'second owner cannot read warmed PDF')
            for suffix in ('','/items','/corrections','/pdf','/corrections/1/pdf'):
                for credential,expected_status in ((None,401),(operator,403),(credentials['billing-service'],403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                    headers={} if credential is None else {'Authorization':'Bearer '+credential}
                    qa.status(client.get(base+'1'+suffix,headers=headers),expected_status,'forbidden actor')
                qa.status(client.get(base+'1'+suffix,method='POST',headers=owner),403,'wrong method')
            qa.status(client.get(prefix+'/invoices/export/zip',headers=owner),403,'operator bulk export remains private')
            checks.append(name+prefix+': own header, 105 items/corrections, stale/null customer IDs, two owners/guest, private cached PDFs, wrong links, six denied actors and invalid windows')
        assert (sql('billing_db',snapshot),sql('order_db',order_snapshot))==before
        checks.append('all six Billing tables including document sequences and Order rows unchanged')
        spec=qa.Client(urls['billing-service']).get('/v3/api-docs').document()
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        qa.check_document(spec,contract['services']['billing'],'Billing')
        for schema,fields in [('InvoiceExportData',invoice_fields),('InvoiceExportLine',item_fields),('InvoiceExportCorrection',correction_fields)]:
            assert set(spec['components']['schemas'][schema]['properties'])==fields
        assert spec['components']['securitySchemes']['bearerAuth']['scheme']=='bearer'
        checks.append('OpenAPI contract and exact invoice/item/correction field allowlists')
        processes['order-service'].terminate();processes['order-service'].wait(timeout=15)
        for name,prefix in targets:
            client=qa.Client(urls[name],timeout=10);base=prefix+'/invoices/me/data-export/orders/1'
            for suffix in ('','/items','/corrections','/pdf','/corrections/1/pdf'):
                response=qa.status(client.get(base+suffix,headers=owner),503,'Order down must reject JSON and warmed PDF')
                assert not response.body.startswith(b'%PDF-') and b'Jan' not in response.body
        assert sql('billing_db',snapshot)==before[0]
        checks.append('stopped Order: all five endpoint types fail closed through all three routes, including warmed PDFs')
        report={'stage':'A43.25','status':'PASS','checks':checks,'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
        (directory/'report.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
        print(json.dumps({'status':'PASS','checks':len(checks),'report':str(directory/'report.json')}))
    finally:
        for process in reversed(list(processes.values())):
            if process.poll() is None:
                process.terminate()
                try:process.wait(timeout=10)
                except subprocess.TimeoutExpired:process.kill();process.wait(timeout=10)
        for handle in handles:handle.close()
        if postgres:command('docker','rm','-f',postgres)


if __name__=='__main__':
    main()
