#!/usr/bin/env python3
"""A30: real Billing/PostgreSQL and Gateway streaming under a 96 MiB heap.

The auth endpoint and a gated 160 MiB ZIP producer are isolated fixtures.
Never uses development databases, ports, credentials or document files.
"""
import base64
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import zipfile

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('document_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec); spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('document-export-'+secrets.token_hex(4)); logs.mkdir(parents=True)
    operator, customer, service= (secrets.token_urlsafe(32) for _ in range(3))
    processes=[]; handles=[]; container=None
    allow_finish=threading.Event(); producer_finished=threading.Event()
    stream_calls=[]; heap=[]; stop_sample=threading.Event()

    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def do_POST(self):
            if self.path != '/internal/auth/introspect': self.send_error(404); return
            # Service introspection payloads may use chunked transfer.
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                chunks=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size: self.rfile.readline(); break
                    chunks.append(self.rfile.read(size)); self.rfile.read(2)
                body=json.loads(b''.join(chunks))
            else: body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            token=body.get('token'); actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if token==operator: actor['role']='OPERATOR'
            if token==customer: actor.update(role='CUSTOMER',customerId=42,email='audit@example.test')
            if token==service: actor.update(role='SERVICE',service='billing-service')
            raw=json.dumps(actor).encode(); self.send_response(200); self.send_header('Content-Type','application/json'); self.end_headers(); self.wfile.write(raw)
        def do_GET(self):
            if self.path.startswith('/invoices/export/zip'):
                stream_calls.append(self.path)
                self.send_response(200); self.send_header('Content-Type','application/zip'); self.send_header('Content-Disposition','attachment; filename=large.zip'); self.end_headers()
                class Sink:
                    def write(_, value): self.wfile.write(value); return len(value)
                    def flush(_): self.wfile.flush()
                try:
                    with zipfile.ZipFile(Sink(),'w',compression=zipfile.ZIP_STORED) as archive:
                        with archive.open('payload.bin','w',force_zip64=True) as member:
                            chunk=b'A'*(64*1024); member.write(chunk); self.wfile.flush()
                            if not allow_finish.wait(20): return
                            for _ in range(160*16-1): member.write(chunk)
                    producer_finished.set()
                except (BrokenPipeError,ConnectionResetError): pass
            else: self.send_error(404)

    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture)
    threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(query):
        try: return http.command('docker','exec',container,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d','billing_db','-Atc',query)
        except subprocess.CalledProcessError as error: raise RuntimeError(error.stderr) from error
    def launch(module,label,base_billing,heap_mb=128):
        with socket.socket() as probe: probe.bind(('127.0.0.1',0)); port=probe.getsockname()[1]
        env={k:v for k,v in os.environ.items() if not k.startswith(('PLATFORM_','CUSTOMER_','BILLING_','GATEWAY_','DB_','SPRING_','REDIS_'))}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=fixture_url,
                   PLATFORM_SERVICE_TOKEN=service,GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',
                   BILLING_CONSUMER_ENABLED='false',DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/billing_db',DB_USERNAME='audit',DB_PASSWORD=password,
                   BILLING_PDF_CACHE_MAX_BYTES='65536',BILLING_PDF_CACHE_MAX_ENTRIES='10',BILLING_PDF_CACHE_TTL='PT2S',
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='2',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
        for downstream in http.OFFSETS:
            if downstream!='api-gateway': env[downstream.upper().replace('-','_')+'_URL']=fixture_url
        env['BILLING_SERVICE_URL']=base_billing
        jar=next((ROOT/module/'target').glob('*-SNAPSHOT.jar'))
        log=(logs/(label+'.log')).open('w'); handles.append(log)
        process=subprocess.Popen(['java','-Xms32m',f'-Xmx{heap_mb}m','-XX:TieredStopAtLevel=1','-jar',str(jar)],env=env,stdout=log,stderr=subprocess.STDOUT)
        url=f'http://127.0.0.1:{port}'; processes.append((label,process,url)); return url
    def get(url,token=operator,expected=200): return http.request(url,token=token,expected=expected)
    def metric(url,name):
        value=get(url+'/actuator/metrics/'+name)
        return value['measurements'][0]['value']
    try:
        password=secrets.token_urlsafe(24)
        container=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine','-c','shared_preload_libraries=pg_stat_statements')
        db_port=http.command('docker','port',container,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',container,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0: break
            time.sleep(1)
        else: raise RuntimeError('Temporary PostgreSQL not ready')
        http.command('docker','exec',container,'createdb','-U','audit','billing_db')
        billing=launch('billing-service','billing',fixture_url)
        gateway=launch('api-gateway','gateway',billing)
        streaming=launch('api-gateway','stream-gateway',fixture_url,96)
        def ready(item):
            label,process,url=item; deadline=time.monotonic()+120
            while time.monotonic()<deadline:
                if process.poll() is not None: raise RuntimeError(label+' exited; see '+str(logs))
                try:
                    with urllib.request.urlopen(url+'/actuator/health',timeout=2) as response:
                        if response.status==200: return
                except (OSError,urllib.error.URLError): pass
                time.sleep(1)
            raise RuntimeError(label+' health timeout')
        with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool: list(pool.map(ready,processes))
        print('READY: Billing and two isolated Gateway instances',flush=True)
        sql("CREATE EXTENSION pg_stat_statements; INSERT INTO invoices(id,order_id,customer_id,customer_email,invoice_number,billing_kind,buyer_name,street,postal_code,city,country,net_amount,vat_amount,gross_amount,delivery_cost,discount_amount,currency,status,created_at) SELECT g,g,42,'audit@example.test','A30/'||g,'PERSON','Audit','Street 1','00-001','City','PL',10,2.30,12.30,0,0,'PLN','ISSUED',CASE WHEN g<=60 THEN timestamptz '2026-01-01' ELSE timestamptz '2020-01-01' END FROM generate_series(1,100000) g; INSERT INTO invoice_items(invoice_id,position_no,product_id,sku,product_name,quantity,unit_price,total_price) SELECT g,1,1,'A30','Audit item',1,12.30,12.30 FROM generate_series(1,60) g; ANALYZE invoices;")
        api=gateway+'/api/v1'; query='?from=2026-01-01T00:00:00Z&to=2026-01-01T00:00:00Z'
        for prefix in [billing,gateway+'/api/v1']:
            get(prefix+'/invoices/export/zip',None,401); get(prefix+'/invoices/export/zip',customer,403)
            get(prefix+'/invoices/export/zip?from=2026-02-01T00:00:00Z&to=2026-01-01T00:00:00Z',expected=400)
            get(prefix+'/invoices/export/zip',expected=413)
        for i in range(1,15):
            document=get(api+f'/invoices/{i}/pdf'); assert document.startswith(b'%PDF')
            assert metric(billing,'billing.pdf.cache.bytes')<=65536
        assert metric(billing,'billing.pdf.cache.entries')<=10
        time.sleep(2.1); assert metric(billing,'billing.pdf.cache.bytes')==0
        sql('SELECT pg_stat_statements_reset()')
        archive=get(api+'/invoices/export/zip'+query)
        path=logs/'invoices.zip'; path.write_bytes(archive)
        with zipfile.ZipFile(path) as zipped:
            assert len(zipped.infolist())==60 and zipped.testzip() is None
            for name in zipped.namelist(): assert zipped.read(name).startswith(b'%PDF')
        assert metric(billing,'billing.pdf.cache.bytes')==0
        statements=sql("select query,rows,calls from pg_stat_statements where query like 'select %' and query like '%from invoices %' and query not like '%pg_stat%' order by rows desc")
        (logs/'export-statements.txt').write_text(statements)
        assert 'created_at' in statements
        for statement in statements.splitlines():
            _,rows,calls=statement.rsplit('|',2); assert int(rows)/int(calls)<=25, statement
        empty=get(api+'/invoices/export/zip?from=2030-01-01T00:00:00Z'); (logs/'empty.zip').write_bytes(empty)
        with zipfile.ZipFile(logs/'empty.zip') as zipped: assert not zipped.infolist()
        print('PASS: 60 PDFs from 100000 invoices, filtered SQL windows <=25; cache <=64 KiB, TTL and no export cache pollution',flush=True)
        def sample():
            while not stop_sample.is_set():
                try: heap.append(metric(streaming,'jvm.memory.used?tag=area:heap'))
                except Exception: pass
                stop_sample.wait(.1)
        sampler=threading.Thread(target=sample,daemon=True); sampler.start()
        started=time.monotonic()
        with urllib.request.urlopen(streaming+'/api/v1/invoices/export/zip',timeout=30) as response:
            first=response.read(1024); first_seconds=time.monotonic()-started
            assert first.startswith(b'PK') and not producer_finished.is_set()
            allow_finish.set(); total=len(first)
            with (logs/'large.zip').open('wb') as output:
                output.write(first)
                while chunk:=response.read(65536): output.write(chunk); total+=len(chunk)
        stop_sample.set(); sampler.join(timeout=5)
        with zipfile.ZipFile(logs/'large.zip') as zipped:
            assert zipped.getinfo('payload.bin').file_size==160*1024*1024 and zipped.testzip() is None
        assert len(stream_calls)==1 and total>160*1024*1024
        assert all(process.poll() is None for _,process,_ in processes)
        report=dict(archiveBytes=total,gatewayMaxHeapBytes=96*1024*1024,peakMeasuredHeapBytes=max(heap,default=0),firstChunkSeconds=round(first_seconds,3),upstreamCalls=len(stream_calls))
        (logs/'stream-report.json').write_text(json.dumps(report,indent=2))
        (logs/'large.zip').unlink() # only this run's generated 160 MiB file
        print('PASS: 160 MiB ZIP through a 96 MiB Gateway heap, first bytes before completion, one upstream call:',json.dumps(report),flush=True)
    finally:
        allow_finish.set(); stop_sample.set()
        for _,process,_ in processes: process.terminate()
        for _,process,_ in processes:
            try: process.wait(timeout=15)
            except subprocess.TimeoutExpired: process.kill(); process.wait()
        for handle in handles: handle.close()
        if container: subprocess.run(['docker','rm','-f',container],capture_output=True)
        fixture.shutdown(); fixture.server_close()
        print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__': main()
