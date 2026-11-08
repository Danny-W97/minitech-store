#!/usr/bin/env python3
"""A13: two Billing JVMs, a V7-to-V8 upgrade and disposable PostgreSQL. No external services."""
import concurrent.futures
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import socket
import subprocess
import threading
import time
import urllib.request
import uuid
import urllib.error

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('correction_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)

def main():
    logs=ROOT/'tmp'/('invoice-corrections-'+secrets.token_hex(4));logs.mkdir(parents=True)
    processes={};handles=[];container=None;report={'result':'FAILED'}
    operator,customer,service,password=(secrets.token_urlsafe(32) for _ in range(4))
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_POST(self):
            if self.path!='/internal/auth/introspect':self.send_error(404);return
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                chunks=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:self.rfile.readline();break
                    chunks.append(self.rfile.read(size));self.rfile.read(2)
                data=json.loads(b''.join(chunks))
            else:data=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            token=data.get('token');actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if token==operator:actor['role']='OPERATOR'
            elif token==customer:actor.update(role='CUSTOMER',customerId=42,email='audit@example.test')
            elif token==service:actor.update(role='SERVICE',service='billing-service')
            raw=json.dumps(actor).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(query):return http.command('docker','exec',container,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d','billing_db','-Atqc',query)
    def launch(name,legacy=False):
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        env={key:value for key,value in os.environ.items() if not key.startswith(('PLATFORM_','BILLING_','DB_','SPRING_','REDIS_','OUTBOX_'))}
        env.update(SPRING_PROFILES_ACTIVE='test',SERVER_PORT=str(port),PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=fixture_url,
            PLATFORM_SERVICE_TOKEN=service,DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/billing_db',DB_USERNAME='audit',DB_PASSWORD=password,
            BILLING_CONSUMER_ENABLED='false',OUTBOX_PUBLISHER_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',
            SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='6',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
        args=['java','-Xms32m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(logs/'billing-service.jar')]
        if legacy:args+=['--spring.flyway.target=7','--spring.jpa.hibernate.ddl-auto=none']
        handle=(logs/(name+'.log')).open('w',encoding='utf-8');handles.append(handle)
        process=subprocess.Popen(args,env=env,stdout=handle,stderr=subprocess.STDOUT);processes[name]=process
        url=f'http://127.0.0.1:{port}';deadline=time.monotonic()+150
        while time.monotonic()<deadline:
            if process.poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(url+'/actuator/health',timeout=2) as response:
                    if response.status==200:return url
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError(name+' readiness timeout')
    def stop(name):
        process=processes[name];process.terminate()
        try:process.wait(10)
        except subprocess.TimeoutExpired:process.kill();process.wait()
    def command(delta,key=None,version=0):return dict(reason='Rabat',correctionGrossAmount=delta,idempotencyKey=key or uuid.uuid4().hex,expectedVersion=version)
    try:
        shutil.copyfile(next((ROOT/'billing-service/target').glob('*-SNAPSHOT.jar')),logs/'billing-service.jar')
        container=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',container,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL not ready')
        http.command('docker','exec',container,'createdb','-U','audit','billing_db')
        launch('v7',True)
        sql("""INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at)
            SELECT g,g,42,'audit@example.test','A13/'||g,'PERSON','Audit','Street 1','00-001','City','PL',81.30,18.70,100,0,0,'PLN','ISSUED',now() FROM generate_series(1,4) g;
            UPDATE invoices SET net_amount=0.02,vat_amount=0.01,gross_amount=0.03 WHERE id=3;
            INSERT INTO invoice_corrections(invoice_id,order_id,correction_number,reason,original_net,original_vat,original_gross,correction_net,correction_vat,correction_gross,final_net,final_vat,final_gross,created_at)
            VALUES (2,2,'OLD/1','Historyczna',81.30,18.70,100,-8.13,-1.87,-10,73.17,16.83,90,now()),
                   (2,2,'OLD/2','Historyczna',81.30,18.70,100,-16.26,-3.74,-20,65.04,14.96,80,now());""")
        old=sql('SELECT row_to_json(c) FROM invoice_corrections c ORDER BY id');stop('v7')
        a=launch('billing-a');b=launch('billing-b')
        assert sql('SELECT count(*) FROM flyway_schema_history WHERE version=\'8\' AND success')=='1'
        assert sql('SELECT count(*) FROM invoice_corrections WHERE idempotency_key IS NULL AND ledger_version IS NULL')=='2'
        # The upgrade adds fields, preserving every amount and every original document number.
        after=sql("SELECT row_to_json(c)::jsonb-'idempotency_key'-'ledger_version' FROM invoice_corrections c ORDER BY id")
        assert [json.loads(row) for row in old.splitlines()]==[json.loads(row) for row in after.splitlines()]
        print('PASS: V7 history survives the V8 migration unchanged; two Billing replicas validate the schema',flush=True)
        def get(base,path,token=operator,expected=200):return http.request(base+path,token=token,expected=expected)
        def issue(base,invoice,body,token=operator,expected=201):return http.request(base+f'/invoices/{invoice}/corrections','POST',body,token=token,expected=expected)
        from quality_gates import check_document
        expectation=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text())['services']['billing']
        check_document(get(a,'/v3/api-docs'),expectation,'Billing A13')
        for base in [a,b]:
            for token,status in [(None,401),(customer,403),(service,403)]:
                issue(base,1,command('-10.00'),token,status)
                get(base,'/invoices/1/correction-balance',token,status)
        assert get(a,'/invoices/1/correction-balance')['version']==0
        first=command('-10.00');second=command('-20.00',version=1)
        ca=issue(a,1,first);cb=issue(b,1,second)
        assert ca['finalGross']==90 and cb['originalGross']==90 and cb['finalGross']==70
        assert issue(b,1,first)['id']==ca['id'];assert issue(a,1,second)['id']==cb['id']
        assert get(b,'/invoices/1/correction-balance')['grossAmount']==70
        assert get(b,'/invoices/corrections/summary?invoiceIds=1')[0]['grossDelta']==-30
        pdf=get(a,f'/invoices/corrections/{cb["id"]}/pdf');assert pdf.startswith(b'%PDF');(logs/'cumulative-correction.pdf').write_bytes(pdf)
        for body in [dict(first,correctionGrossAmount='-11.00'),dict(first,reason='Inny powód'),command('-1.00',version=0)]:issue(a,1,body,expected=409)
        for delta in ['0','0.001','-70.01','100000000000000000.00']:issue(b,1,command(delta,version=2),expected=400)
        issue(b,1,dict(command('-1.00',version=2),reason='x'*256),expected=400)
        issue(b,1,dict(command('-1.00',version=2),idempotencyKey=None),expected=400)
        assert sql('SELECT count(*) FROM invoice_corrections WHERE invoice_id=1')=='2'
        assert sql("SELECT count(*) FROM invoice_outbox_events WHERE event_id LIKE 'invoice-corrected-%'")=='2'
        print('PASS: 100 - 10 - 20 = 70 across replicas; repeats, invalid commands and stale versions never append or publish',flush=True)
        body=command('-5.00',version=2)
        # Pretend the first client lost the reply: replay on the other instance.
        committed=issue(a,2,body);replayed=issue(b,2,body)
        assert committed['id']==replayed['id'] and committed['originalGross']==70 and committed['finalGross']==65
        legacy=get(a,'/invoices/2/corrections');assert any(row['balanceModel']=='LEGACY_BASE_DELTA' and row['finalGross']==80 for row in legacy)
        assert get(b,'/invoices/2/correction-balance')['legacyCorrections']==2
        for version in range(3):issue(a if version%2 else b,3,command('-0.01',version=version))
        balance=get(a,'/invoices/3/correction-balance');assert balance['grossAmount']==balance['netAmount']==balance['vatAmount']==0
        print('PASS: legacy deltas are cumulative without rewriting snapshots; cent-sized reductions end at zero without VAT drift',flush=True)
        body=command('-10.00');start=threading.Barrier(6)
        def duplicate(index):start.wait(10);return issue(a if index%2 else b,4,body)['id']
        with concurrent.futures.ThreadPoolExecutor(max_workers=6) as pool:ids=list(pool.map(duplicate,range(6)))
        assert len(set(ids))==1
        start=threading.Barrier(2)
        def distinct(index):
            start.wait(10)
            request=urllib.request.Request((a if index else b)+'/invoices/4/corrections',data=json.dumps(command('-10.00',version=1)).encode(),
                headers={'Authorization':'Bearer '+operator,'Content-Type':'application/json'},method='POST')
            try:
                with urllib.request.urlopen(request,timeout=20) as response:return response.status
            except urllib.error.HTTPError as error:return error.code
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:statuses=list(pool.map(distinct,range(2)))
        assert sorted(statuses)==[201,409],statuses
        assert sql('SELECT count(*) FROM invoice_corrections WHERE invoice_id=4')=='2'
        assert get(b,'/invoices/4/correction-balance')['grossAmount']==80
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='billing_db' AND state='idle in transaction'")=='0'
        print('PASS: six simultaneous retries create one document; distinct requests for one version have exactly one winner',flush=True)
        report.update(result='PASSED',httpChecks=http.CHECKS+2,billingReplicas=2,migrationFromV7=True,legacySnapshotsPreserved=True,
            cumulativeBalance=True,idempotentReplay=True,concurrentRetries=6,staleVersionProtected=True,centRounding=True,contractVerified=True)
        print('PASS A13: cumulative correction ledger, migration, replay and replica concurrency',flush=True)
    finally:
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        for name in processes:stop(name)
        for handle in handles:handle.close()
        (logs/'billing-service.jar').unlink(missing_ok=True)
        if container:subprocess.run(['docker','rm','-f',container],capture_output=True)
        fixture.shutdown();fixture.server_close();print('Isolated resources removed; logs:',logs,flush=True)

if __name__=='__main__':main()
