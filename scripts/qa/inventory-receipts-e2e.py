#!/usr/bin/env python3
"""A21: two real Inventory JVMs sharing disposable PostgreSQL; no developer resources."""
import concurrent.futures
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import http.client
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import signal
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('stock_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('inventory-receipts-'+secrets.token_hex(4));logs.mkdir(parents=True)
    processes={};handles=[];urls={};ports={};postgres=None
    password=secrets.token_urlsafe(24);operator=secrets.token_urlsafe(32);customer=secrets.token_urlsafe(32)
    tokens={name:secrets.token_urlsafe(32) for name in ['order-service','payment-service','inventory-service']}
    committed_reply=threading.Event();drop_reply=threading.Event();forwarded={}
    for name in ['a','b']:
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));ports[name]=probe.getsockname()[1]
        urls[name]=f'http://127.0.0.1:{ports[name]}'
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def do_POST(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    size=int(self.rfile.readline().split(b';')[0],16)
                    if not size:self.rfile.readline();break
                    parts.append(self.rfile.read(size));self.rfile.read(2)
                data=json.loads(b''.join(parts))
            else:data=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            if self.path=='/drop-reply':
                forwarded['result']=http.request(urls['a']+f'/inventory/items/{data["productId"]}/receipts','POST',
                        dict(quantity=data['quantity'],operationId=data['operationId']),token=operator,expected=201)
                committed_reply.set();drop_reply.wait(20);self.close_connection=True;return
            actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if data.get('token')==operator:actor['role']='OPERATOR'
            if data.get('token')==customer:actor.update(role='CUSTOMER',customerId=17,email='owner@example.test')
            for service,token in tokens.items():
                if data.get('token')==token:actor.update(role='SERVICE',service=service)
            raw=json.dumps(actor).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
    class FixtureServer(ThreadingHTTPServer):
        # The native test deliberately fans out requests across both JVMs. Keep
        # introspection capacity above that burst so 503s reflect app behavior,
        # not the test fixture's default listen backlog of five connections.
        request_queue_size=128
        daemon_threads=True
    fixture=FixtureServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def sql(query):return http.command('docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d','inventory_db','-Atqc',query)
    def poll(action,predicate,seconds=30):
        deadline=time.monotonic()+seconds;value=None
        while time.monotonic()<deadline:
            value=action()
            if predicate(value):return value
            time.sleep(.05)
        raise AssertionError('Timeout; last value: '+repr(value))
    def start(name):
        env={key:value for key,value in os.environ.items() if not key.startswith(('SPRING_','PLATFORM_','DB_','INVENTORY_','RESILIENCE_','REDIS_'))}
        env.update(SPRING_PROFILES_ACTIVE='prod',SERVER_PORT=str(ports[name]),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/inventory_db?ApplicationName=inventory-{name}',
                   DB_USERNAME='audit',DB_PASSWORD=password,PLATFORM_SERVICE_TOKEN=tokens['inventory-service'],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=fixture_url,
                   SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',INVENTORY_RESERVATION_TTL_SECONDS='900',
                   INVENTORY_RESERVATION_CLEANUP_FIXED_DELAY_MS='600000')
        handle=(logs/(name+'-'+secrets.token_hex(2)+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(jar)],env=env,stdout=handle,stderr=subprocess.STDOUT)
    def ready(name):
        deadline=time.monotonic()+180
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError('Readiness timeout '+name)
    def call(name,path,method='GET',body=None,token=operator,expected=200):
        return http.request(urls[name]+path,method,body,token=token,expected=expected)
    def key():return str(uuid.uuid4())
    def receive(name,product,quantity,operation,expected=201):
        return call(name,f'/inventory/items/{product}/receipts','POST',dict(quantity=quantity,operationId=operation),expected=expected)
    def stock(product):return call('b',f'/inventory/items/{product}',token=None)
    def reserve(name,order,product,quantity):
        return call(name,'/inventory/reservations','POST',dict(orderId=order,items=[dict(productId=product,quantity=quantity)]),token=tokens['order-service'],expected=201)
    def code(action):
        try:action();return 201
        except AssertionError as error:
            if 'got 409;' not in str(error):raise
            return 409
    try:
        jar=logs/'inventory.jar';shutil.copyfile(next((ROOT/'inventory-service/target').glob('*-SNAPSHOT.jar')),jar)
        postgres=http.command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine')
        db_port=http.command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('PostgreSQL not ready')
        http.command('docker','exec',postgres,'createdb','-U','audit','inventory_db')
        start('a');start('b')
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(ready,['a','b']))
        products=list(range(950001,950007))
        for product in products:call('a','/inventory/items','POST',dict(productId=product,availableQuantity=100),expected=201)
        print('READY: two Inventory JVMs and disposable PostgreSQL',flush=True)

        product=products[0];operation=key()
        for actor,expected in [(None,401),(customer,403),(tokens['order-service'],403),(tokens['payment-service'],403)]:
            call('a',f'/inventory/items/{product}/receipts','POST',dict(quantity=10,operationId=operation),token=actor,expected=expected)
            call('a','/inventory/operations',token=actor,expected=expected)
        committed=receive('a',product,10,operation);reserve('b',710001,product,6)
        assert receive('b',product,10,operation)==committed
        assert call('b','/inventory/operations/'+operation)==committed
        assert stock(product)['availableQuantity']==104 and stock(product)['reservedQuantity']==6
        receive('a',product,11,operation,409);receive('b',products[1],10,operation,409)
        call('a',f'/inventory/items/{product}','PATCH',dict(availableQuantity=200),expected=400)
        for quantity in [0,-1,1.5,2147483648]:receive('a',product,quantity,key(),400)
        receive('a',product,2147483647,key(),409)
        print('PASS: authorized delta receipt, immutable result replay, rejected legacy PATCH and overflow',flush=True)

        shared=key()
        with concurrent.futures.ThreadPoolExecutor(max_workers=12) as pool:
            replies=list(pool.map(lambda i:receive('a' if i%2 else 'b',product,3,shared),range(12)))
        assert all(row==replies[0] for row in replies) and stock(product)['availableQuantity']==107
        race_product=products[1]
        for i in range(8):
            with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
                receipt=pool.submit(receive,'a',race_product,5,key());reservation=pool.submit(reserve,'b',720000+i,race_product,2)
                receipt.result(20);reservation.result(20)
        actual=stock(race_product);assert actual['availableQuantity']==124 and actual['reservedQuantity']==16 and actual['version']==16
        print('PASS: 12 same-key calls and 8 receipt/reservation races preserve both effects exactly once',flush=True)

        collision=key()
        sql(f"CREATE FUNCTION pause_collision() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_id='{collision}' THEN PERFORM pg_sleep(1); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_collision BEFORE INSERT ON inventory_stock_operations FOR EACH ROW EXECUTE FUNCTION pause_collision()")
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:
            first=pool.submit(code,lambda:receive('a',products[2],4,collision));second=pool.submit(code,lambda:receive('b',products[3],4,collision))
            assert sorted([first.result(20),second.result(20)])==[201,409]
        sql('DROP TRIGGER pause_collision ON inventory_stock_operations; DROP FUNCTION pause_collision()')
        assert stock(products[2])['availableQuantity']+stock(products[3])['availableQuantity']==204
        print('PASS: cross-product UUID conflict rolls the losing stock change back',flush=True)

        old=stock(product);reserve('a',730001,product,1);correction=key()
        body=dict(availableQuantity=90,expectedVersion=old['version'],operationId=correction)
        call('b',f'/inventory/items/{product}','PATCH',body,expected=409)
        body['expectedVersion']=stock(product)['version'];corrected=call('b',f'/inventory/items/{product}','PATCH',body)
        receive('a',product,2,key());assert call('a',f'/inventory/items/{product}','PATCH',body)==corrected
        assert stock(product)['availableQuantity']==92
        page=call('b',f'/inventory/operations?productId={product}&size=2&cursor=0');assert len(page)==2
        next_page=call('a',f'/inventory/operations?productId={product}&size=2&cursor={page[-1]["id"]}')
        assert not {row['id'] for row in page}.intersection(row['id'] for row in next_page)
        print('PASS: stale stocktake is rejected; committed correction replays after later receipts; ledger pages are bounded',flush=True)

        rollback=key();target=products[4]
        sql(f"CREATE FUNCTION pause_receipt() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.operation_id='{rollback}' THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER pause_receipt BEFORE INSERT ON inventory_stock_operations FOR EACH ROW EXECUTE FUNCTION pause_receipt()")
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(receive,'a',target,7,rollback)
            poll(lambda:sql("SELECT count(*) FROM pg_stat_activity WHERE datname='inventory_db' AND application_name='inventory-a' AND wait_event='PgSleep'"),lambda count:count=='1')
            assert stock(target)['availableQuantity']==100
            processes['a'].kill();processes['a'].wait(10)
            sql("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname='inventory_db' AND application_name='inventory-a' AND wait_event='PgSleep'")
            sql('DROP TRIGGER pause_receipt ON inventory_stock_operations; DROP FUNCTION pause_receipt()')
            try:pending.result(10)
            except (OSError,urllib.error.URLError):pass
        assert stock(target)['availableQuantity']==100
        receive('b',target,7,rollback);assert stock(target)['availableQuantity']==107
        start('a');ready('a')
        print('PASS: process death between stock UPDATE and ledger INSERT rolls both back; another replica retries once',flush=True)

        lost=key();target=products[5]
        with concurrent.futures.ThreadPoolExecutor(max_workers=1) as pool:
            pending=pool.submit(http.request,fixture_url+'/drop-reply','POST',dict(productId=target,quantity=9,operationId=lost))
            assert committed_reply.wait(15)
            processes['a'].kill();processes['a'].wait(10);drop_reply.set()
            try:pending.result(10)
            except (OSError,urllib.error.URLError):pass
        assert call('b','/inventory/operations/'+lost)==forwarded['result']
        assert receive('b',target,9,lost)==forwarded['result'] and stock(target)['availableQuantity']==109
        assert sql("SELECT count(*) FROM pg_stat_activity WHERE datname='inventory_db' AND state='idle in transaction'")=='0'
        report=dict(result='PASSED',inventoryReplicas=2,sameKeyCalls=12,reservationRaces=8,rollbackBeforeLedger=True,lostReplyAfterCommit=True,httpChecks=http.CHECKS)
        (logs/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
        print('PASS A21: atomic receipts, immutable ledger, versioned corrections and replica crash/reply-loss recovery',flush=True)
    finally:
        drop_reply.set()
        for process in processes.values():
            if process.poll() is None:process.terminate()
        for process in processes.values():
            try:process.wait(timeout=12)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for handle in handles:handle.close()
        if postgres:subprocess.run(['docker','rm','-f',postgres],capture_output=True)
        fixture.shutdown();fixture.server_close()
        print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__':main()
