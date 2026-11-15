#!/usr/bin/env python3
"""Executable QA gates. HTTP errors, schema failures and missed budgets fail closed."""
import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime,timezone
import json
import http.client
import socket
import math
import os
from pathlib import Path
import re
import secrets
import threading
import time
import urllib.parse

ROOT=Path(__file__).resolve().parents[2]
CONTRACT=ROOT/'scripts/qa/contracts/api-gateway-v1.json'

class GateFailure(RuntimeError):
    def __init__(self,message,details=None):super().__init__(message);self.details=details

def invalid_constant(value):raise ValueError('Non-finite JSON constant')

def require(condition,message):
    if not condition:raise GateFailure(message)

@dataclass
class Response:
    status:int
    headers:dict
    body:bytes
    milliseconds:float
    error:str=''
    def document(self):
        require('json' in self.headers.get('content-type','').lower(),'Expected JSON content type')
        try:return json.loads(self.body,parse_constant=invalid_constant)
        except (ValueError,UnicodeDecodeError) as error:raise GateFailure('Invalid JSON response') from error

class Client:
    def __init__(self,base,token='',timeout=20):
        parsed=urllib.parse.urlsplit(base)
        require(parsed.scheme in {'http','https'} and parsed.netloc and not parsed.username and not parsed.password
                and not parsed.query and not parsed.fragment,'Invalid QA base URL')
        require(not token or re.fullmatch(r'[A-Za-z0-9._~+/=-]+',token),'Invalid QA operator credential format')
        self.base=base.rstrip('/');self.token=token;self.timeout=timeout
    def get(self,path,authenticated=False,method='GET',body=None,headers=None):
        require(path.startswith('/') and not path.startswith('//'),'QA request must use a relative path')
        outgoing=dict(headers or {})
        if authenticated:
            require(bool(self.token),'QA_OPERATOR_TOKEN is required for the authenticated gate')
            outgoing['Authorization']='Bearer '+self.token
        data=None if body is None else json.dumps(body).encode()
        if data is not None:outgoing['Content-Type']='application/json'
        start=time.perf_counter();parsed=urllib.parse.urlsplit(self.base)
        connection=(http.client.HTTPSConnection if parsed.scheme=='https' else http.client.HTTPConnection)(parsed.hostname,parsed.port,timeout=self.timeout)
        expired=threading.Event();active_socket=[None]
        def abort():
            expired.set()
            if active_socket[0] is not None:
                try:active_socket[0].shutdown(socket.SHUT_RDWR)
                except OSError:pass
            connection.close()
        timer=threading.Timer(self.timeout,abort);timer.daemon=True;timer.start()
        try:
            connection.connect();active_socket[0]=connection.sock
            if expired.is_set():raise TimeoutError('QA deadline expired')
            connection.request(method,parsed.path.rstrip('/')+path,body=data,headers=outgoing)
            with connection.getresponse() as response:
                raw=response.read(8*1024*1024+1)
                if expired.is_set():raise TimeoutError('QA deadline expired')
                require(len(raw)<=8*1024*1024,'QA response exceeded 8 MiB')
                return Response(response.status,{k.lower():v for k,v in response.headers.items()},raw,(time.perf_counter()-start)*1000)
        except (OSError,http.client.HTTPException) as error:
            return Response(0,{},b'',(time.perf_counter()-start)*1000,type(error).__name__)
        finally:timer.cancel();connection.close()

def status(response,expected,label):
    require(response.status==expected,f'{label}: expected HTTP {expected}, got {response.status or "transport failure"}')
    return response

def validate(value,schema,label):
    try:from jsonschema import Draft202012Validator
    except ImportError as error:raise GateFailure('Install scripts/qa/requirements.txt before running schema gates') from error
    # Contracts are local. Do not permit automatic network resolution of a schema reference.
    def local(node):
        if isinstance(node,dict):
            require('$ref' not in node,'QA schemas must be self-contained')
            for child in node.values():local(child)
        elif isinstance(node,list):
            for child in node:local(child)
    local(schema);Draft202012Validator.check_schema(schema)
    errors=list(Draft202012Validator(schema).iter_errors(value))
    require(not errors,f'{label}: response schema mismatch at '+('/'.join(map(str,errors[0].absolute_path)) if errors else 'root'))

def normalize(path):return re.sub(r'\{[^}]+\}','{}',path)

def operation(document,verb,path):
    matches=[operations for name,operations in document.get('paths',{}).items() if normalize(name)==normalize(path)]
    require(len(matches)==1 and verb.lower() in matches[0],f'Missing OpenAPI operation: {verb} {path}')
    return matches[0][verb.lower()]

def dereference(node,document):
    seen=set()
    while '$ref' in node:
        ref=node['$ref'];require(ref.startswith('#/') and ref not in seen,'Unsupported or circular OpenAPI reference');seen.add(ref)
        target=document
        for part in ref[2:].split('/'):target=target[part.replace('~1','/').replace('~0','~')]
        node=target
    return node

def check_document(document,expectation,label):
    require(str(document.get('openapi','')).startswith('3.'),label+': expected OpenAPI 3')
    for route in expectation['operations']:
        verb,path=route.split(' ',1);op=operation(document,verb,path)
        require(bool(op.get('responses')),label+': missing response definitions '+route)
        if verb=='GET':
            response=op['responses'].get('200');require(response is not None,label+': missing HTTP 200 response '+route)
            content=response.get('content',{})
            require(any(media.get('schema') for media in content.values()),label+': missing response schema '+route)
        if route in expectation.get('requestBodies',[]):
            request=op.get('requestBody',{})
            require(request.get('required') is True and any(media.get('schema') for media in request.get('content',{}).values()),label+': missing required request schema '+route)
    for name,properties in expectation.get('schemas',{}).items():
        model=document.get('components',{}).get('schemas',{}).get(name)
        require(model is not None,label+': missing DTO '+name)
        model=dereference(model,document)
        for key,expected_type in properties.items():
            prop=model.get('properties',{}).get(key);require(prop is not None,label+': missing property '+name+'.'+key)
            prop=dereference(prop,document);actual=prop.get('type')
            require(actual==expected_type or isinstance(actual,list) and expected_type in actual,label+': wrong DTO type '+name+'.'+key)

def contract_gate(client,contract_path=CONTRACT):
    contract=json.loads(Path(contract_path).read_text(encoding='utf-8'))
    root=status(client.get('/v3/api-docs'),200,'Gateway OpenAPI').document()
    require(root.get('info',{}).get('version')==contract['version'],'Wrong Gateway API version')
    for path,methods in contract['paths'].items():
        for verb in methods:operation(root,verb,path)
    for alias,expectation in contract['services'].items():
        document=status(client.get('/v3/api-docs/'+alias+'?refresh=true'),200,alias+' OpenAPI').document()
        check_document(document,expectation,alias)
    products=status(client.get('/api/v1/products'),200,'Catalog').document()
    validate(products,contract['runtime']['catalog'],'Catalog')
    product_id=products[0]['id']
    validate(status(client.get(f'/api/v1/products/{product_id}'),200,'Product').document(),contract['runtime']['product'],'Product')
    validate(status(client.get(f'/api/v1/inventory/items/{product_id}'),200,'Inventory').document(),contract['runtime']['inventory'],'Inventory')
    validate(status(client.get('/api/v1/deliveries/methods'),200,'Delivery methods').document(),contract['runtime']['delivery'],'Delivery methods')
    validate(status(client.get(f'/api/v1/reviews/products/{product_id}'),200,'Public reviews').document(),contract['runtime']['reviews'],'Public reviews')
    status(client.get('/api/v1/products/9223372036854775807'),404,'Missing product')
    status(client.get('/api/v1/orders?size=0',authenticated=True),400,'Invalid list window')
    for route,schema in contract['runtime']['operatorLists'].items():
        response=status(client.get('/api/v1/'+route+'?size=1',authenticated=True),200,route)
        validate(response.document(),schema,route)
        require(response.headers.get('x-page-size')=='1' and response.headers.get('x-has-more') in {'true','false'}
                and re.fullmatch(r'\d+',response.headers.get('x-next-cursor','')),route+': missing pagination headers')
    return {'services':len(contract['services']),'gatewayPaths':len(contract['paths']),'runtimeContracts':len(contract['runtime']['operatorLists'])+8}

def security_gate(client):
    health=status(client.get('/actuator/health'),200,'Health').document()
    require(health.get('status')=='UP' and set(health)<={'status','groups'},'Actuator health exposed details or is not UP')
    require('groups' not in health or isinstance(health['groups'],list) and all(isinstance(group,str) for group in health['groups']),'Invalid health groups payload')
    status(client.get('/actuator/env'),404,'Actuator env')
    protected=['orders','payments','notifications','customers','invoices','returns','chat/messages','promotions/codes']
    for route in protected:
        status(client.get('/api/v1/'+route),401,'Anonymous '+route)
    status(client.get('/api/v1/customers/me',headers={'Authorization':'Bearer not-a-jwt'}),401,'Malformed JWT')
    status(client.get('/api/v1/products',method='POST',body={}),401,'Anonymous catalog write')
    status(client.get('/api/v1/orders?size=1',authenticated=True),200,'Authenticated control')
    for alias in ['','/customer','/payment','/billing']:
        document=status(client.get('/v3/api-docs'+alias),200,'OpenAPI safety').document()
        serialized=re.sub(r'[^a-z0-9]','',json.dumps(document).lower())
        require(not any(secret in serialized for secret in ['customerjwtsecret','dbpassword','refreshtokenhash']),'Secret identifier exposed in OpenAPI')
    return {'anonymousBoundaries':len(protected)+2,'authenticatedControl':True,'healthDetails':False}

def percentile(values,p):
    require(bool(values),'No latency samples');return sorted(values)[max(0,math.ceil(len(values)*p)-1)]

def gauge(client,name):
    payload=status(client.get('/actuator/metrics/'+name,authenticated=True),200,'Resource metric '+name).document()
    measurements=payload.get('measurements',[])
    require(bool(measurements),'Missing metric measurements: '+name)
    value=measurements[0].get('value')
    require(isinstance(value,(int,float)) and not isinstance(value,bool) and math.isfinite(value) and value>=0,'Invalid metric value: '+name)
    return value

def load_gate(client,metrics,requests=100,concurrency=10,p95=1500,p99=3000,max_error_rate=0,max_pending=10,warmup=5,path='/api/v1/products'):
    require(1<=requests<=10000 and 1<=concurrency<=min(requests,64),'Invalid load size/concurrency')
    require(0<p95<=p99 and math.isfinite(p99) and 0<=max_error_rate<=1 and 0<=max_pending<=1000,'Invalid load thresholds')
    require(metrics is not None and bool(metrics.token),'Resource metrics and QA_OPERATOR_TOKEN are required')
    schema=json.loads(CONTRACT.read_text(encoding='utf-8'))['runtime']['catalog']
    def check():
        response=client.get(path)
        try:status(response,200,'Load request');validate(response.document(),schema,'Load response');return response,True,''
        except GateFailure as error:return response,False,str(error)
    def sample():return check()[:2]
    for _ in range(warmup):
        _,ok,error=check()
        require(ok,'Load warmup failed: '+error)
    resources=[];resource_errors=[];stop=threading.Event()
    def resource():
        try:
            active=gauge(metrics,'hikaricp.connections.active');maximum=gauge(metrics,'hikaricp.connections.max');pending=gauge(metrics,'hikaricp.connections.pending')
            require(maximum>0,'Pool maximum must be positive');resources.append({'active':active,'max':maximum,'pending':pending})
        except (GateFailure,OSError) as error:resource_errors.append(str(error))
    resource();require(not resource_errors,'Resource baseline unavailable')
    def monitor():
        while not stop.wait(.25):resource()
    thread=threading.Thread(target=monitor,daemon=True);thread.start();start=time.perf_counter()
    try:
        with ThreadPoolExecutor(max_workers=concurrency) as workers:observed=list(workers.map(lambda _:sample(),range(requests)))
    finally:stop.set();thread.join(timeout=15)
    duration=time.perf_counter()-start;resource()
    require(not thread.is_alive() and not resource_errors,'Resource sampling failed')
    times=[response.milliseconds for response,_ in observed];failures=sum(not ok for _,ok in observed)
    summary={'requests':len(observed),'concurrency':concurrency,'durationSeconds':round(duration,3),'rps':round(len(observed)/duration,2),
        'statuses':dict(Counter(str(response.status) for response,_ in observed)),'errors':failures,'errorRate':failures/len(observed),
        'p95Ms':round(percentile(times,.95),3),'p99Ms':round(percentile(times,.99),3),'peakDbPending':max(row['pending'] for row in resources),
        'peakDbActive':max(row['active'] for row in resources),'resourceSamples':len(resources),
        'limits':{'p95Ms':p95,'p99Ms':p99,'errorRate':max_error_rate,'dbPending':max_pending}}
    violations=[]
    for key,limit in [('p95Ms',p95),('p99Ms',p99),('errorRate',max_error_rate),('peakDbPending',max_pending)]:
        if summary[key]>limit:violations.append(key)
    if violations:raise GateFailure('Load thresholds exceeded: '+','.join(violations),summary)
    return summary

CHAOS_PATHS={'payment-service':'payments?size=1','inventory-service':'inventory/items/1','product-service':'products/1',
    'order-service':'orders?size=1','customer-service':'customers?size=1','delivery-service':'deliveries/methods',
    'promotion-service':'promotions/active','notification-service':'notifications?size=1','chat-service':'chat/messages?size=1',
    'returns-service':'returns?size=1','billing-service':'invoices?size=1','wishlist-service':'wishlists/customer/1',
    'review-service':'reviews/products/1','recommendation-service':'recommendations/products/1'}

def chaos_gate(client,target,suspend,resume,max_failure_ms=18000,recovery_seconds=45):
    require(target in CHAOS_PATHS,'Unsupported isolated chaos target')
    dependent='/api/v1/'+CHAOS_PATHS[target]
    independent='/api/v1/products' if target=='inventory-service' else '/api/v1/inventory/items/1'
    contract=json.loads(CONTRACT.read_text(encoding='utf-8'))
    dependent_schema=contract['chaosSchemas'][target]
    independent_schema=contract['runtime']['catalog' if target=='inventory-service' else 'inventory']
    baseline=status(client.get(dependent,authenticated=True),200,'Chaos baseline').document()
    validate(baseline,dependent_schema,'Chaos baseline')
    require(isinstance(baseline,(list,dict)),'Chaos baseline has no business JSON')
    validate(status(client.get(independent),200,'Independent baseline').document(),independent_schema,'Independent baseline')
    suspended=False
    try:
        suspend();suspended=True
        with ThreadPoolExecutor(max_workers=1) as workers:
            failure=workers.submit(client.get,dependent,True)
            for _ in range(3):
                unaffected=status(client.get(independent),200,'Fault isolation');validate(unaffected.document(),independent_schema,'Fault isolation')
                require(unaffected.milliseconds<3000,'Independent operation exceeded 3 seconds')
                time.sleep(.1)
            response=failure.result(timeout=25)
        status(response,503,'Controlled degradation')
        require(response.milliseconds<=max_failure_ms,'Dependency failure exceeded the time budget')
        require(isinstance(response.document(),dict),'Degradation response is not JSON object')
    finally:
        if suspended:resume()
    started=time.perf_counter();end=started+recovery_seconds
    while True:
        recovered=client.get(dependent,authenticated=True)
        require(time.perf_counter()<=end,'Recovery exceeded its time budget')
        if recovered.status==200:break
        require(recovered.status==503 and time.perf_counter()<end,'Dependency did not recover to HTTP 200')
        time.sleep(.3)
    payload=recovered.document();validate(payload,dependent_schema,'Recovered business response')
    validate(status(client.get(independent),200,'Isolation after recovery').document(),independent_schema,'Isolation after recovery')
    return {'target':target,'baseline':200,'duringFault':503,'afterRecovery':200,
        'failureMs':round(response.milliseconds,3),'recoverySeconds':round(time.perf_counter()-started,3),'isolationChecks':4}

def report(gate,action,directory=None):
    directory=Path(directory or os.environ.get('QA_REPORT_DIRECTORY',ROOT/'gitIgnoreOwn/runtime/qa'));directory.mkdir(parents=True,exist_ok=True)
    document={'gate':gate,'startedAt':datetime.now(timezone.utc).isoformat(),'status':'FAILED'}
    try:
        document['result']=action();document['status']='PASSED';return document['result']
    except Exception as error:
        # Do not serialize request bodies, headers or credentials to the report.
        document['error']=str(error)
        if isinstance(error,GateFailure) and error.details is not None:document['result']=error.details
        raise
    finally:
        document['finishedAt']=datetime.now(timezone.utc).isoformat()
        path=directory/(gate+'-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%S')+'-'+secrets.token_hex(4)+'.json')
        path.write_text(json.dumps(document,indent=2),encoding='utf-8');print('QA report:',path,flush=True)

def main():
    parser=argparse.ArgumentParser();parser.add_argument('gate',choices=['contract','security','load'])
    parser.add_argument('--base-url',required=True);parser.add_argument('--metrics-url');args=parser.parse_args()
    token=os.environ.get('QA_OPERATOR_TOKEN','');client=Client(args.base_url,token)
    try:
        if args.gate=='contract':action=lambda:contract_gate(client)
        elif args.gate=='security':action=lambda:security_gate(client)
        else:
            metrics=Client(args.metrics_url,token,3) if args.metrics_url else None
            action=lambda:load_gate(client,metrics,int(os.environ.get('LOAD_REQUESTS','100')),int(os.environ.get('LOAD_CONCURRENCY','10')),
                float(os.environ.get('LOAD_P95_MS','1500')),float(os.environ.get('LOAD_P99_MS','3000')),
                float(os.environ.get('LOAD_MAX_ERROR_RATE','0')),float(os.environ.get('LOAD_MAX_DB_PENDING','10')))
        result=report(args.gate,action);print('PASS '+args.gate+':',json.dumps(result),flush=True)
    except Exception as error:print('FAIL '+args.gate+':',str(error),flush=True);return 1
    return 0

if __name__=='__main__':raise SystemExit(main())
