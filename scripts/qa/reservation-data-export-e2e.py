#!/usr/bin/env python3
"""A43.28: owned PostgreSQL/JAR fixtures; read-only reservation and coupon exports."""
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import quality_gates as qa

ROOT=Path(__file__).resolve().parents[2]

def command(*args):
    deadline = time.time() + 60
    while True:
        try:
            return subprocess.run(args, cwd=ROOT, check=True, capture_output=True, text=True).stdout.strip()
        except subprocess.CalledProcessError as e:
            if len(args) > 3 and args[3] == 'createdb' and time.time() < deadline:
                time.sleep(0.5)
                continue
            raise

def port():
    with socket.socket() as listener:listener.bind(('127.0.0.1',0));return listener.getsockname()[1]

def main():
    directory=ROOT/'tmp'/('reservation-export-'+secrets.token_hex(4));directory.mkdir(parents=True)
    names=('customer-service','order-service','inventory-service','promotion-service','api-gateway')
    urls={name:f'http://127.0.0.1:{port()}' for name in names};credentials={name:secrets.token_urlsafe(32) for name in names}
    password,operator=secrets.token_urlsafe(32),secrets.token_urlsafe(32);signing_key=secrets.token_bytes(48)
    postgres=None;processes={};handles=[];checks=[];calls=[]
    class Probe(BaseHTTPRequestHandler):
        def do_GET(self):calls.append(self.command);self.send_response(503);self.end_headers()
        do_POST=do_GET
        do_PATCH=do_GET
        def log_message(self,*args):pass
    probe=ThreadingHTTPServer(('127.0.0.1',0),Probe);thread=threading.Thread(target=probe.serve_forever,daemon=True);thread.start()
    def sql(database,query):return command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d',database,'-Atqc',query)
    def token(customer=1,email='owner@fixture.example',expired=False):
        encode=lambda item:base64.urlsafe_b64encode(json.dumps(item).encode()).rstrip(b'=').decode()
        unsigned=encode({'alg':'HS256','typ':'JWT'})+'.'+encode({'typ':'access','sub':str(customer),'email':email,'exp':int(time.time())+(-600 if expired else 600)})
        return unsigned+'.'+base64.urlsafe_b64encode(hmac.new(signing_key,unsigned.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('Fixture PostgreSQL startup timed out')
        for database in ('customer_db','order_db','inventory_db','promotion_db'):command('docker','exec',postgres,'createdb','-U','fixture',database)
        for name in names:
            database={'order-service':'order_db','inventory-service':'inventory_db','promotion-service':'promotion_db'}.get(name,'customer_db')
            env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
            env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],
                DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}',DB_USERNAME='fixture',DB_PASSWORD=password,
                CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],
                INVENTORY_SERVICE_URL=urls['inventory-service'],PROMOTION_SERVICE_URL=urls['promotion-service'],
                ORDER_SERVICE_URL=urls['order-service'],PLATFORM_ORDER_URL=urls['order-service'],
                PRODUCT_SERVICE_URL=f'http://127.0.0.1:{probe.server_port}',PAYMENT_SERVICE_URL=f'http://127.0.0.1:{probe.server_port}',NOTIFICATION_SERVICE_URL=f'http://127.0.0.1:{probe.server_port}',
                PLATFORM_OPERATOR_TOKEN=operator,PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
                CUSTOMER_JWT_SECRET=base64.b64encode(signing_key).decode(),CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),
                CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',
                ORDER_SAGA_RECOVERY_ENABLED='false',COUPON_EXPIRY_ENABLED='false',OUTBOX_PUBLISHER_ENABLED='false',
                INVENTORY_RESERVATION_CLEANUP_FIXED_DELAY_MS='3600000',
                GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',MANAGEMENT_HEALTH_MAIL_ENABLED='false',
                SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
            jars=list((ROOT/name/'target').glob('*-SNAPSHOT.jar'));assert len(jars)==1,f'Package {name} first'
            handle=(directory/(name+'.log')).open('w');handles.append(handle)
            processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jars[0])],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
            deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if processes[name].poll() is not None:raise RuntimeError(f'{name} exited; fixture logs: {directory}')
                if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:break
                time.sleep(.3)
            else:raise RuntimeError(f'{name} fixture startup timed out')
        sql('customer_db',"""
            INSERT INTO customers(email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at)
            VALUES ('owner@fixture.example','fixture-hash','Jan','Test','Testowa 1','00-001','Warszawa','PL',true,now(),now()),
                   ('other@fixture.example','fixture-hash','Anna','Test','Testowa 2','00-002','Kraków','PL',true,now(),now()),
                   ('disabled@fixture.example','fixture-hash','Nie','Aktywny','Testowa 3','00-003','Kraków','PL',false,now(),now())
            """)
        guest='G'*43
        sql('order_db',f"""
            INSERT INTO orders(id,customer_id,customer_email,total_price,status,delivery_first_name,delivery_last_name,
              delivery_street,delivery_postal_code,delivery_city,delivery_country,delivery_method,delivery_cost,
              fulfillment_type,idempotency_key,guest_access_token_hash,created_at,updated_at)
            SELECT n,CASE WHEN n=2 THEN 2 WHEN n=3 THEN NULL ELSE 1 END,'same@fixture.example',123.45,'PAID',
              'Jan','Żółć','Testowa 1','00-001','Warszawa','PL','STANDARD_COURIER',12.34,'HOME_DELIVERY',
              'fixture-private-order-'||n,'{hashlib.sha256(guest.encode()).hexdigest()}',now(),now() FROM generate_series(1,8) n
            """)
        sql('inventory_db',"""
            INSERT INTO inventory_reservations(id,order_id,status,expires_at,created_at,updated_at)
            SELECT 70+n,n,CASE WHEN n=5 THEN 'CONFIRMED' WHEN n=6 THEN 'RELEASED' ELSE 'RESERVED' END,
              now()-interval '1 day',now()-interval '2 days',now()-interval '2 days' FROM generate_series(1,8) n WHERE n NOT IN (4,7);
            INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) SELECT 71,n,2 FROM generate_series(1,105) n;
            INSERT INTO inventory_reservation_items(reservation_id,product_id,quantity) VALUES (72,1,7);
            INSERT INTO inventory_stock_operations(operation_id,product_id,kind,quantity,available_before,available_quantity,reserved_quantity,sold_quantity,before_version,version,created_at)
            SELECT 'fixture-private-operation',product_id,'ADJUSTMENT',0,available_quantity,available_quantity,reserved_quantity,sold_quantity,version,version,now() FROM inventory_items WHERE product_id=1
            """)
        sql('promotion_db',"""
            INSERT INTO coupon_reservations(order_id,version,code,subtotal,discount_amount,state,accepted_at,expires_at,created_at,updated_at)
            SELECT n,987,'SAME',123.45,12.34,CASE WHEN n IN (2,5) THEN 'CONSUMED' WHEN n=6 THEN 'RELEASED' ELSE 'RESERVED' END,
              null,now()-interval '1 day',now()-interval '2 days',now()-interval '2 days' FROM generate_series(1,8) n WHERE n NOT IN (4,7);
            INSERT INTO promotion_redemptions(order_id,code,redeemed_at) SELECT 1,concat('SAME',n),now() FROM generate_series(1,105) n;
            INSERT INTO promotion_redemptions(order_id,code,redeemed_at) VALUES (2,'SAME1',now()),(7,'LEGACY',now());
            INSERT INTO promotion_codes(code,description,discount_percent,max_redemptions,redeemed_count,active,valid_from,valid_to,created_at,updated_at)
            VALUES ('fixture-private-code','fixture-private-definition',10,97,23,true,current_date-1,current_date+1,now(),now())
            """)
        snapshots={
          'inventory_db':"SELECT json_build_array((SELECT json_agg(r ORDER BY id) FROM inventory_reservations r),(SELECT json_agg(i ORDER BY id) FROM inventory_reservation_items i),(SELECT json_agg(s ORDER BY id) FROM inventory_items s),(SELECT json_agg(o ORDER BY id) FROM inventory_stock_operations o))::text",
          'promotion_db':"SELECT json_build_array((SELECT json_agg(r ORDER BY order_id) FROM coupon_reservations r),(SELECT json_agg(d ORDER BY id) FROM promotion_redemptions d),(SELECT json_agg(c ORDER BY id) FROM promotion_codes c),(SELECT json_agg(p ORDER BY id) FROM promotion_campaigns p),(SELECT json_agg(h ORDER BY id) FROM promotion_price_history h))::text",
          'order_db':"SELECT json_agg(o ORDER BY id)::text FROM orders o"}
        before={db:sql(db,query) for db,query in snapshots.items()};owner={'Authorization':'Bearer '+token()}
        configs=[('inventory-service','inventory','items','inventory-reservation','inventory-reservation-data.json','inventory-reservation-items-page.json',
            {'reservationId','orderId','status','expiresAt','createdAt','updatedAt'},{'reservationItemId','reservationId','productId','quantity'},'reservationItemId'),
            ('promotion-service','promotions','redemptions','coupon-reservation','coupon-reservation-data.json','coupon-redemptions-page.json',
            {'orderId','code','subtotal','discountAmount','state','acceptedAt','expiresAt','createdAt','updatedAt'},{'redemptionId','orderId','code','redeemedAt'},'redemptionId')]
        for service,root_path,child,scope,header_file,page_file,res_fields,item_fields,item_id in configs:
            for name,prefix in ((service,''),('api-gateway','/api'),('api-gateway','/api/v1')):
                client=qa.Client(urls[name],timeout=10);base=prefix+'/'+root_path+'/me/data-export/orders/'
                response=qa.status(client.get(base+'1?customerId=2',headers={**owner,'X-Customer-Id':'2'}),200,'owned reservation header')
                doc=response.document();assert set(doc)=={'schemaVersion','scope','complete','generatedAt','orderId','reservation','omissions'}
                assert doc['scope']==scope and doc['complete'] is False and doc['orderId']==1 and set(doc['reservation'])==res_fields
                assert doc['reservation']['status' if service=='inventory-service' else 'state']=='RESERVED'
                if service=='inventory-service':assert doc['reservation']['reservationId']==71
                else:assert doc['reservation']['subtotal']==123.45 and doc['reservation']['discountAmount']==12.34 and doc['reservation']['acceptedAt'] is None
                assert response.headers['content-disposition']=='attachment; filename="'+header_file+'"'
                cursor='0';ids=[]
                while True:
                    response=qa.status(client.get(base+f'1/{child}?size=50&cursor={cursor}',headers=owner),200,'bounded transaction page')
                    doc=response.document();assert set(doc)=={'schemaVersion','scope','complete','generatedAt','orderId','items','hasMore','nextCursor','omissions'}
                    assert doc['orderId']==1 and doc['complete'] is False and len(doc['items'])<=50 and all(set(row)==item_fields for row in doc['items'])
                    if service=='inventory-service':assert all(row['reservationId']==71 and row['quantity']==2 for row in doc['items'])
                    else:assert all(row['orderId']==1 for row in doc['items'])
                    assert 'no-store' in response.headers['cache-control'] and 'private' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
                    assert response.headers['content-disposition']=='attachment; filename="'+page_file+'"' and b'fixture-private' not in response.body
                    ids.extend(row[item_id] for row in doc['items'])
                    if not doc['hasMore']:assert doc['nextCursor']=='0';break
                    cursor=doc['nextCursor'];assert len(ids)<150
                assert ids==list(range(105,0,-1))
                for suffix in ('','/'+child):
                    route=base+'1'+suffix
                    for credential,expected in ((None,401),(operator,403),(credentials[service],403),('invalid',401),(token(expired=True),401),(token(3,'disabled@fixture.example'),401)):
                        qa.status(client.get(route,headers={} if credential is None else {'Authorization':'Bearer '+credential}),expected,'strict customer role')
                    for order_id in (2,3,71,99999):qa.status(client.get(base+str(order_id)+suffix,headers={**owner,'X-Order-Access-Token':guest}),404,'other guest absent or mismatched source id')
                    qa.status(client.get(base+'3'+suffix,headers={'X-Order-Access-Token':guest}),401,'guest capability does not grant account export')
                    qa.status(client.get(route,method='POST',headers=owner),403,'no mutation permission')
                other=qa.status(client.get(base+'2/'+child,headers={'Authorization':'Bearer '+token(2,'other@fixture.example')}),200,'same source email separate owner').document()
                assert len(other['items'])==1
                assert qa.status(client.get(base+'4',headers=owner),200,'no reservation').document()['reservation'] is None
                assert qa.status(client.get(base+'4/'+child,headers=owner),200,'no rows').document()['items']==[]
                assert qa.status(client.get(base+'8/'+child,headers=owner),200,'stored empty reservation').document()['items']==[]
                assert qa.status(client.get(base+'1/'+child+'?cursor=1',headers=owner),200,'exhausted source').document()['items']==[]
                for query in ('?size=0','?size=51','?cursor=-1','?cursor=9223372036854775808'):
                    qa.status(client.get(base+'1/'+child+query,headers=owner),400,'invalid window')
                for order_id,expected in ((5,'CONFIRMED' if service=='inventory-service' else 'CONSUMED'),(6,'RELEASED')):
                    actual=qa.status(client.get(base+str(order_id),headers=owner),200,'stored closed state').document()['reservation']
                    assert actual['status' if service=='inventory-service' else 'state']==expected
                if service=='promotion-service':
                    assert qa.status(client.get(base+'7',headers=owner),200,'legacy redemption without reservation').document()['reservation'] is None
                    assert len(qa.status(client.get(base+'7/redemptions',headers=owner),200,'legacy redemption preserved').document()['items'])==1
                for path in ('/inventory/reservations/71','/inventory/operations') if service=='inventory-service' else ('/promotions/codes/reservations','/promotions/codes/SAME/capacity'):
                    qa.status(client.get(prefix+path,headers=owner),403,'operational API not opened')
                checks.append(name+prefix+': '+scope+', 105 bounded rows, role/source/email isolation, empty/legacy/closed states and private projections')
        contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'))
        for service,root_path,_,_,_,_,res_fields,item_fields,_ in configs:
            spec=qa.Client(urls[service]).get('/v3/api-docs').document();qa.check_document(spec,contract['services']['inventory' if service=='inventory-service' else 'promotion'],service)
            models=('InventoryExportReservation','InventoryExportReservationItem') if service=='inventory-service' else ('CouponExportReservation','CouponExportRedemption')
            assert set(spec['components']['schemas'][models[0]]['properties'])==res_fields and set(spec['components']['schemas'][models[1]]['properties'])==item_fields
            checks.append(service+': OpenAPI contract and exact projection allowlists')
        for db,query in snapshots.items():assert sql(db,query)==before[db]
        assert calls==[];checks.append('ten Inventory/Promotion/Order tables unchanged, including stock, TTL, coupon counters/tombstones; zero product/payment/notification calls')
        processes['order-service'].terminate();processes['order-service'].wait(timeout=15)
        for service,root_path,child,*_ in configs:
            for name,prefix in ((service,''),('api-gateway','/api'),('api-gateway','/api/v1')):
                client=qa.Client(urls[name],timeout=15)
                for suffix in ('','/'+child):qa.status(client.get(prefix+'/'+root_path+'/me/data-export/orders/1'+suffix,headers=owner),503,'Order unavailable fails closed')
        for db,query in snapshots.items():assert sql(db,query)==before[db]
        assert calls==[];checks.append('Order down returns 503 for headers/pages without cached authority, mutations or side-effect calls')
        report={'stage':'A43.28','status':'PASS','checks':checks,'sideEffectRequests':len(calls),'snapshottedTables':10,'realUserDataAccessed':False,'fullAccountExportComplete':False,'deployment':False}
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
        probe.shutdown();probe.server_close();thread.join(timeout=5)

if __name__=='__main__':main()
