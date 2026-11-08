import copy
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

ROOT=Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'scripts/qa'))
import quality_gates as gates

CONTRACT=json.loads(gates.CONTRACT.read_text())

def sample(schema):
    if 'const' in schema:return schema['const']
    if 'enum' in schema:return schema['enum'][0]
    kind=schema.get('type');kind=kind[0] if isinstance(kind,list) else kind
    if kind=='object':return {key:sample(value) for key,value in schema['properties'].items()}
    if kind=='array':return [sample(schema['items']) for _ in range(max(1,schema.get('minItems',0)))]
    if kind=='integer':return max(1,schema.get('minimum',1))
    if kind=='number':return 10.0
    if kind=='boolean':return True
    if kind=='string':return '2026-01-01' if schema.get('minLength',0)>=10 else 'Fixture'
    return None

def spec(alias):
    expected=CONTRACT['services'][alias];paths={}
    for route in expected['operations']:
        verb,path=route.split(' ',1)
        operation={'responses':{'200':{'description':'OK','content':{'application/json':{'schema':{'type':'object'}}}}}}
        if route in expected['requestBodies']:operation['requestBody']={'required':True,'content':{'application/json':{'schema':{'type':'object'}}}}
        paths.setdefault(path,{})[verb.lower()]=operation
    return {'openapi':'3.1.0','paths':paths,'components':{'schemas':{name:{'type':'object','properties':{key:{'type':kind} for key,kind in properties.items()}} for name,properties in expected['schemas'].items()}}}

class GateTests(unittest.TestCase):
    def setUp(self):
        self.state={'fault':False,'faultStatus':503,'recoverStatus':200,'resumed':0,'bypass':False,'delay':0,'pending':0,'badPrice':False,'healthLeak':False,'docMutation':None,'trickle':False,'recoverDelay':0,'breakIsolation':False}
        state=self.state
        class Handler(BaseHTTPRequestHandler):
            def log_message(self,*args):pass
            def do_POST(self):self.reply(401,{})
            def reply(self,status,body):
                if status==0:self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
                raw=json.dumps(body).encode();self.send_response(status);self.send_header('Content-Type','application/json')
                if self.path.startswith('/api/v1/'):
                    self.send_header('X-Page-Size','1');self.send_header('X-Has-More','false');self.send_header('X-Next-Cursor','0')
                self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
            def do_GET(self):
                path=self.path.split('?')[0]
                if path=='/v3/api-docs':return self.reply(200,{'openapi':'3.1.0','info':{'version':'v1'},'paths':{name:{method.lower():{} for method in methods} for name,methods in CONTRACT['paths'].items()}})
                if path.startswith('/v3/api-docs/'):
                    alias=path.rsplit('/',1)[1];document=spec(alias)
                    if state['docMutation']:state['docMutation'](alias,document)
                    return self.reply(200,document)
                if path=='/actuator/env':return self.reply(404,{})
                if path=='/actuator/health':return self.reply(200,{'status':'UP',**({'components':{}} if state['healthLeak'] else {})})
                if path.startswith('/actuator/metrics/'):
                    name=path.rsplit('/',1)[1];value={'hikaricp.connections.active':1,'hikaricp.connections.max':3,'hikaricp.connections.pending':state['pending']}[name]
                    return self.reply(200,{'measurements':[{'value':value}]})
                if path=='/api/v1/products/9223372036854775807':return self.reply(404,{})
                if path.startswith('/api/v1/products'):
                    if state['trickle']:
                        self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers()
                        try:
                            for _ in range(100):self.wfile.write(b' ');self.wfile.flush();time.sleep(.02)
                        except OSError:pass
                        return
                    time.sleep(state['delay']);product=sample(CONTRACT['runtime']['product'])
                    if state['badPrice']:product['price']='free'
                    return self.reply(200,[product] if path.endswith('/products') else product)
                if path.startswith('/api/v1/inventory/items/'):return self.reply(200,{} if state['fault'] and state['breakIsolation'] else sample(CONTRACT['runtime']['inventory']))
                if path=='/api/v1/deliveries/methods':return self.reply(200,sample(CONTRACT['runtime']['delivery']))
                if path.startswith('/api/v1/reviews/products/'):return self.reply(200,sample(CONTRACT['runtime']['reviews']))
                if self.headers.get('Authorization')!='Bearer private-operator' and not state['bypass']:return self.reply(401,{})
                if path=='/api/v1/orders' and 'size=0' in self.path:return self.reply(400,{})
                if path=='/api/v1/payments':
                    if not state['fault'] and state['resumed']:time.sleep(state['recoverDelay'])
                    code=state['faultStatus'] if state['fault'] else state['recoverStatus']
                    if code!=200:return self.reply(code,{'message':'Controlled outage'})
                route=path.removeprefix('/api/v1/')
                if route in CONTRACT['runtime']['operatorLists']:return self.reply(200,sample(CONTRACT['runtime']['operatorLists'][route]))
                return self.reply(404,{})
        self.server=ThreadingHTTPServer(('127.0.0.1',0),Handler)
        self.thread=threading.Thread(target=self.server.serve_forever,daemon=True);self.thread.start()
        self.base='http://127.0.0.1:'+str(self.server.server_port);self.client=gates.Client(self.base,'private-operator',2)
    def tearDown(self):self.server.shutdown();self.server.server_close();self.thread.join()
    def test_good_runtime_contract_security_and_load(self):
        self.assertEqual(gates.contract_gate(self.client)['services'],14)
        self.assertTrue(gates.security_gate(self.client)['authenticatedControl'])
        self.assertEqual(gates.load_gate(self.client,self.client,requests=10,concurrency=2)['errors'],0)
    def test_changing_price_type_in_a_real_response_fails(self):
        self.state['badPrice']=True
        with self.assertRaises(gates.GateFailure):gates.contract_gate(self.client)
        with self.assertRaises(gates.GateFailure):gates.load_gate(self.client,self.client,requests=5,concurrency=1)
    def test_invalid_http_200_payload_counts_as_a_load_error(self):
        self.state['badPrice']=True
        with self.assertRaisesRegex(gates.GateFailure,'errorRate') as raised:gates.load_gate(self.client,self.client,requests=5,concurrency=1,warmup=0)
        self.assertEqual(raised.exception.details['errors'],5)
    def test_nonfinite_json_is_rejected(self):
        with self.assertRaises(gates.GateFailure):gates.Response(200,{'content-type':'application/json'},b'{"price":NaN}',1).document()
    def test_changing_documented_dto_type_fails(self):
        def mutate(alias,document):
            if alias=='product':document['components']['schemas']['ProductResponse']['properties']['price']['type']='string'
        self.state['docMutation']=mutate
        with self.assertRaises(gates.GateFailure):gates.contract_gate(self.client)
    def test_missing_success_status_is_not_a_valid_contract(self):
        def mutate(alias,document):
            if alias=='order':document['paths']['/orders/{id}']['get']['responses']={'500':{'description':'Error'}}
        self.state['docMutation']=mutate
        with self.assertRaises(gates.GateFailure):gates.contract_gate(self.client)
    def test_authentication_bypass_is_red(self):
        self.state['bypass']=True
        with self.assertRaises(gates.GateFailure):gates.security_gate(self.client)
    def test_camel_case_secret_identifiers_are_red(self):
        def mutate(alias,document):document['components']['schemas']['Leaked']={'properties':{'refreshTokenHash':{'type':'string'}}}
        self.state['docMutation']=mutate
        with self.assertRaises(gates.GateFailure):gates.security_gate(self.client)
    def test_health_details_are_red(self):
        self.state['healthLeak']=True
        with self.assertRaises(gates.GateFailure):gates.security_gate(self.client)
    def test_latency_threshold_is_enforced(self):
        self.state['delay']=.035
        with self.assertRaisesRegex(gates.GateFailure,'p95Ms'):gates.load_gate(self.client,self.client,requests=10,concurrency=2,p95=10,p99=20,warmup=0)
    def test_saturated_db_pool_is_red_even_with_http_200(self):
        self.state['pending']=11
        with self.assertRaisesRegex(gates.GateFailure,'peakDbPending'):gates.load_gate(self.client,self.client,requests=4,concurrency=1,warmup=0)
    def test_no_resource_access_is_not_a_success(self):
        with self.assertRaises(gates.GateFailure):gates.load_gate(self.client,None,requests=4,concurrency=1)
    def test_invalid_or_nonfinite_budgets_are_not_a_success(self):
        for kwargs in [{'requests':0},{'concurrency':65},{'p95':float('nan')},{'p99':float('inf')}]:
            with self.subTest(kwargs=kwargs),self.assertRaises(gates.GateFailure):gates.load_gate(self.client,self.client,**kwargs)
    def suspend(self):self.state['fault']=True
    def resume(self):self.state['fault']=False;self.state['resumed']+=1
    def test_actual_degradation_and_recovery_pass(self):
        result=gates.chaos_gate(self.client,'payment-service',self.suspend,self.resume)
        self.assertEqual(result['duringFault'],503);self.assertEqual(self.state['resumed'],1)
    def test_400_500_no_connection_or_200_during_an_outage_are_red(self):
        for code in [400,500,0,200]:
            with self.subTest(code=code):
                self.state['faultStatus']=code;before=self.state['resumed']
                with self.assertRaises(gates.GateFailure):gates.chaos_gate(self.client,'payment-service',self.suspend,self.resume)
                self.assertEqual(self.state['resumed'],before+1)
    def test_failure_to_recover_is_red(self):
        def resume():self.resume();self.state['recoverStatus']=503
        with self.assertRaises(gates.GateFailure):gates.chaos_gate(self.client,'payment-service',self.suspend,resume,recovery_seconds=0)
        self.assertEqual(self.state['resumed'],1)
    def test_a_trickling_response_is_bounded_by_the_whole_request_deadline(self):
        self.state['trickle']=True;started=time.monotonic()
        response=gates.Client(self.base,'',.15).get('/api/v1/products')
        self.assertEqual(response.status,0);self.assertLess(time.monotonic()-started,1)
    def test_success_after_the_recovery_deadline_is_red(self):
        self.state['recoverDelay']=.04
        with self.assertRaisesRegex(gates.GateFailure,'Recovery exceeded'):gates.chaos_gate(self.client,'payment-service',self.suspend,self.resume,recovery_seconds=.01)
    def test_a_broken_business_response_in_an_unaffected_service_is_red(self):
        self.state['breakIsolation']=True
        with self.assertRaises(gates.GateFailure):gates.chaos_gate(self.client,'payment-service',self.suspend,self.resume)
        self.assertEqual(self.state['resumed'],1)
    def test_external_schema_references_are_not_fetched(self):
        with self.assertRaises(gates.GateFailure):gates.validate({}, {'$ref':'https://example.invalid/schema'},'External')
    def test_failed_gate_writes_a_failed_report(self):
        with tempfile.TemporaryDirectory() as directory:
            with self.assertRaises(gates.GateFailure):gates.report('mutant',lambda:gates.require(False,'Deliberate failure'),directory)
            report=json.loads(next(Path(directory).glob('*.json')).read_text());self.assertEqual(report['status'],'FAILED')
            self.assertNotIn('private-operator',json.dumps(report))
    @unittest.skipUnless(os.name=='posix','Bash entry points are tested in WSL/CI')
    def test_the_three_bash_entry_points_execute_real_gates(self):
        with tempfile.TemporaryDirectory() as directory:
            env=dict(os.environ,QA_OPERATOR_TOKEN='private-operator',QA_REPORT_DIRECTORY=directory,
                CONTRACT_API_BASE_URL=self.base,SECURITY_API_BASE_URL=self.base,LOAD_API_BASE_URL=self.base+'/api/v1',LOAD_METRICS_URL=self.base,
                LOAD_REQUESTS='4',LOAD_CONCURRENCY='2')
            for script in ['contract-test.sh','security-test.sh','load-test.sh']:
                result=subprocess.run(['bash',str(ROOT/'scripts/qa'/script)],env=env,text=True,capture_output=True)
                self.assertEqual(result.returncode,0,result.stdout+result.stderr)
            reports=list(Path(directory).glob('*.json'));self.assertEqual(len(reports),3)
            self.assertTrue(all(json.loads(path.read_text())['status']=='PASSED' for path in reports))
    def test_cli_exit_code_is_nonzero_for_a_broken_response(self):
        self.state['badPrice']=True
        with tempfile.TemporaryDirectory() as directory:
            env=dict(os.environ,QA_OPERATOR_TOKEN='private-operator',QA_REPORT_DIRECTORY=directory)
            result=subprocess.run([sys.executable,str(ROOT/'scripts/qa/quality_gates.py'),'contract','--base-url',self.base],env=env,capture_output=True,text=True)
            self.assertNotEqual(result.returncode,0);self.assertEqual(json.loads(next(Path(directory).glob('*.json')).read_text())['status'],'FAILED')

if __name__=='__main__':unittest.main()
