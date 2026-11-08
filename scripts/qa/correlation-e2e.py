#!/usr/bin/env python3
"""A42.2: real Gateway/Recommendation JVMs and owned fixtures, database and Redis."""
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import threading
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('correlation_http', ROOT / 'scripts/qa/access-control-e2e.py')
http = importlib.util.module_from_spec(spec); spec.loader.exec_module(http)


def main():
    directory = ROOT / 'tmp' / ('audit-a42-2-' + secrets.token_hex(4)); directory.mkdir(parents=True)
    containers, processes, logs, seen = [], [], [], []
    urls = {}; fail_products = threading.Event(); lock = threading.Lock()
    operator, service_token, password = (secrets.token_urlsafe(32) for _ in range(3))
    report = {'status': 'FAILED'}; checks = [0]
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def reply(self, value, status=200):
            data = json.dumps(value).encode(); self.send_response(status)
            self.send_header('Content-Type', 'application/json'); self.send_header('Content-Length', str(len(data)))
            self.end_headers(); self.wfile.write(data)
        def do_POST(self):
            if self.path != '/internal/auth/introspect': self.reply({},404); return
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    length=int(self.rfile.readline().split(b';')[0],16)
                    if not length:self.rfile.readline();break
                    parts.append(self.rfile.read(length));self.rfile.read(2)
                raw=b''.join(parts)
            else:raw=self.rfile.read(int(self.headers.get('Content-Length','0')))
            data = json.loads(raw)
            self.reply({'role': 'OPERATOR' if data.get('token') == operator else 'ANONYMOUS'})
        def do_GET(self):
            if self.path != '/products' and not self.path.startswith('/internal/orders/customer/42/purchased-products'):
                self.reply({},404); return
            with lock: seen.append((self.path, self.headers.get('X-Correlation-Id')))
            if self.path == '/products':
                if fail_products.is_set(): self.reply({'message': 'fixture outage'},503); return
                self.reply([{'id': i, 'name': 'Fixture '+str(i), 'category': 'CPU', 'manufacturer': 'Fixture',
                             'model': 'Fixture', 'technicalParameters': {}, 'price': 100, 'regularPrice': 100,
                             'discountPercent': 0, 'promotional': False, 'newProduct': False,
                             'active': True, 'imageUrl': None} for i in [1,2]])
            else: self.reply([1])
    fixture = ThreadingHTTPServer(('127.0.0.1',0), Fixture)
    threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url = 'http://127.0.0.1:'+str(fixture.server_port)
    def docker(image, port, *args):
        identifier = http.command('docker','run','-d','-p',f'127.0.0.1::{port}',*args,image)
        containers.append(identifier)
        return identifier, http.command('docker','port',identifier,str(port)).rsplit(':',1)[1]
    def start(module):
        with socket.socket() as probe: probe.bind(('127.0.0.1',0)); port=probe.getsockname()[1]
        env = {k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','LANG','HOME','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/correlation_db',
                   DB_USERNAME='audit',DB_PASSWORD=password,REDIS_URL=f'redis://127.0.0.1:{redis_port}',
                   PLATFORM_AUTH_URL=fixture_url,PLATFORM_SERVICE_TOKEN=service_token,
                   PRODUCT_SERVICE_URL=fixture_url,ORDER_SERVICE_URL=fixture_url,CUSTOMER_SERVICE_URL=fixture_url,
                   GATEWAY_RATE_LIMIT_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='2')
        if module=='api-gateway': env['RECOMMENDATION_SERVICE_URL']=urls['recommendation-service']
        log=(directory/(module+'.log')).open('w');logs.append(log)
        process=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(next((ROOT/module/'target').glob('*-SNAPSHOT.jar')))],
                                 env=env,stdout=log,stderr=subprocess.STDOUT)
        processes.append(process); urls[module]='http://127.0.0.1:'+str(port)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            assert process.poll() is None, module+' exited; see '+str(directory)
            try:
                with urllib.request.urlopen(urls[module]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError: pass
            time.sleep(.3)
        raise AssertionError(module+' startup timed out')
    def call(path, candidate=None, gateway=True, expected=200, authorized=False):
        base=urls['api-gateway']+'/api' if gateway else urls['recommendation-service']
        headers={} if candidate is None else {'X-Correlation-Id':candidate}
        if authorized:headers['Authorization']='Bearer '+operator
        request=urllib.request.Request(base+path,headers=headers)
        try:
            with urllib.request.urlopen(request,timeout=15) as response:status,body,returned=response.status,response.read(),response.headers.get('X-Correlation-Id')
        except urllib.error.HTTPError as error:status,body,returned=error.code,error.read(),error.headers.get('X-Correlation-Id')
        assert status==expected,(path,status,body[:250],seen[-4:])
        with lock:checks[0]+=1
        if expected!=401:
            assert returned and re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:-]{0,99}',returned),returned
        return returned,json.loads(body)
    try:
        postgres,db_port=docker('postgres:16-alpine',5432,'-e','POSTGRES_USER=audit','-e','POSTGRES_DB=correlation_db','-e','POSTGRES_PASSWORD='+password)
        _,redis_port=docker('redis:7-alpine',6379)
        deadline=time.monotonic()+35
        while subprocess.run(['docker','exec',postgres,'pg_isready','-U','audit'],capture_output=True).returncode:
            assert time.monotonic()<deadline,'PostgreSQL startup timed out';time.sleep(.2)
        start('recommendation-service');start('api-gateway')
        def verify(candidate, gateway=True):
            before=len(seen);returned,body=call('/recommendations/products/1',candidate,gateway=gateway)
            assert [item['productId'] for item in body]==[2],body
            assert seen[before:]==[('/products',returned)],seen[before:]
            return returned
        assert verify('direct.Recommendation-1',False)=='direct.Recommendation-1'
        assert verify('fixture-Gateway:1')=='fixture-Gateway:1'
        assert verify('id with spaces',False)!='id with spaces'
        assert verify('x'*101,False)!='x'*101
        assert verify(None)
        before=len(seen);returned,body=call('/recommendations/customer/42','fixture-history',authorized=True)
        assert returned=='fixture-history' and [item['productId'] for item in body]==[2]
        assert len(seen[before:])==2 and all(identifier==returned for _,identifier in seen[before:])
        before=len(seen)
        def parallel_request(index):
            identifier='parallel-'+str(index);returned,body=call('/recommendations/products/1',identifier)
            assert returned==identifier and [item['productId'] for item in body]==[2]
        with concurrent.futures.ThreadPoolExecutor(max_workers=4) as executor:list(executor.map(parallel_request,range(8)))
        assert sorted(identifier for _,identifier in seen[before:])==['parallel-'+str(i) for i in range(8)]
        fail_products.set();returned,_=call('/recommendations/products/1','failed-request',gateway=False,expected=503)
        assert returned=='failed-request';fail_products.clear()
        assert verify('after-failure',False)=='after-failure'
        before=len(seen);call('/recommendations/customer/42','not-a-credential',gateway=False,expected=401)
        assert len(seen)==before, 'Correlation header must never authorize a customer request'
        report.update(status='PASS',realJvmServices=2,httpChecks=checks[0],concurrentRequests=8,
                      sanitizedDirectHeaders=True,customerHistoryPropagation=True,errorRecovery=True,authorizationPreserved=True,
                      externalMessagesSent=False)
        print('PASS correlation: two JVMs, HTTP propagation, untrusted IDs, concurrent isolation, errors and authorization',flush=True)
    finally:
        for process in processes:
            if process.poll() is None:process.kill();process.wait(timeout=10)
        for log in logs:log.close()
        fixture.shutdown();fixture.server_close()
        if containers:subprocess.run(['docker','rm','-f','-v',*containers],capture_output=True)
        (directory/'report.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
        print('Report: '+str(directory/'report.json'),flush=True)


if __name__=='__main__':main()
