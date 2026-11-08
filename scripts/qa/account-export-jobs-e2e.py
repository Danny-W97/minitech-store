#!/usr/bin/env python3
"""A43.31: real Customer/Gateway/PG; bounded HTTP fixtures for already tested domain exports."""
import argparse,base64,hashlib,hmac,json,os,re,secrets,subprocess,threading,time,uuid
from pathlib import Path
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
from urllib.parse import urlsplit,parse_qs
import quality_gates as qa
from importlib.machinery import SourceFileLoader
helpers=SourceFileLoader('security_fixture',str(Path(__file__).with_name('security-data-export-e2e.py'))).load_module()
ROOT=helpers.ROOT;command=helpers.command;port=helpers.port
NOW='2026-10-28T12:00:00Z'

def record(service,filename,name):
    path=next((ROOT/(service+'-service/src/main/java')).rglob(filename+'.java'))
    match=re.search(r'public record '+name+r'\((.*?)\)\s*\{',path.read_text(encoding='utf-8'),re.S)
    assert match,(filename,name)
    result={}
    for field in match[1].split(','):
        kind,key=field.strip().split()[-2:]
        if kind.startswith('List<'):value=[]
        elif kind in ('boolean','Boolean'):value=False
        elif kind in ('Long','long','Integer','int'):value=1
        elif kind=='BigDecimal':value=12.34
        elif kind=='Instant':value=NOW
        else:value='fixture'
        result[key]=value
    return result

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--with-browser',action='store_true');args=parser.parse_args()
    directory=ROOT/'tmp'/('export-jobs-'+secrets.token_hex(4));directory.mkdir(parents=True)
    names=('customer-service','api-gateway');urls={n:f'http://127.0.0.1:{port()}' for n in names};credentials={n:secrets.token_urlsafe(32) for n in names}
    password,operator=secrets.token_urlsafe(32),secrets.token_urlsafe(32);signing=secrets.token_bytes(48)
    postgres=None;processes={};handles=[];checks=[];calls=[];fail_review=True;oversize=False
    def token(customer=1,email='owner@fixture.example'):
        enc=lambda x:base64.urlsafe_b64encode(json.dumps(x).encode()).rstrip(b'=').decode()
        unsigned=enc({'alg':'HS256','typ':'JWT'})+'.'+enc({'typ':'access','sub':str(customer),'email':email,'exp':int(time.time())+1200})
        return unsigned+'.'+base64.urlsafe_b64encode(hmac.new(signing,unsigned.encode(),hashlib.sha256).digest()).rstrip(b'=').decode()
    bearer=token();other=token(2,'other@fixture.example')
    configs=[
        ('wishlist','WishlistDataExportPage','wishlist-page','items','Item','/wishlists/me/data-export'),
        ('review','ReviewDataExportPage','reviews-page','items','Item','/reviews/me/data-export'),
        ('recommendation','RecommendationDataExportPage','recommendation-log-page','items','Item','/recommendations/me/data-export'),
        ('order','OrderDataExportPage','orders-page','orders','OrderData','/orders/me/data-export'),
        ('returns','ReturnDataExportPage','returns-page','returns','ReturnData','/returns/me/data-export'),
        ('order','OrderItemDataExportPage','order-items-page','items','Item','/orders/me/data-export/7/items'),
        ('inventory','InventoryDataExport','inventory-reservation',None,'Reservation','/inventory/me/data-export/orders/7'),
        ('inventory','InventoryItemDataExportPage','inventory-reservation-items-page','items','Item','/inventory/me/data-export/orders/7/items'),
        ('promotion','CouponDataExport','coupon-reservation',None,'Reservation','/promotions/me/data-export/orders/7'),
        ('promotion','RedemptionDataExportPage','coupon-redemptions-page','items','Redemption','/promotions/me/data-export/orders/7/redemptions'),
        ('payment','PaymentDataExport','payment-for-order',None,'PaymentData','/payments/me/data-export/orders/7'),
        ('delivery','ShipmentDataExport','shipment-for-order',None,'ShipmentData','/deliveries/me/data-export/orders/7'),
        ('billing','InvoiceDataExport','invoice-for-order',None,'InvoiceData','/invoices/me/data-export/orders/7'),
        ('billing','InvoiceItemDataExportPage','invoice-items-page','items','Item','/invoices/me/data-export/orders/7/items'),
        ('billing','InvoiceCorrectionDataExportPage','invoice-corrections-page','corrections','Correction','/invoices/me/data-export/orders/7/corrections'),
        ('notification','NotificationDataExportPage','order-notifications','items','Entry','/notifications/me/data-export/orders/7'),
        ('returns','ReturnItemDataExportPage','return-items-page','items','Item','/returns/me/data-export/9/items'),
        ('returns','ReturnEventDataExportPage','return-events-page','events','Event','/returns/me/data-export/9/events')]
    documents={}
    for service,file,scope,collection,rowtype,path in configs:
        doc=record(service,file,file);row=record(service,file,rowtype)
        doc.update(schemaVersion=1,scope=scope,complete=False,omissions=[])
        if 'orderId' in doc:doc['orderId']=7
        if 'returnId' in doc:doc['returnId']=9
        if 'nextCursor' in doc:doc.update(nextCursor='0',hasMore=False)
        if 'orderId' in row:row['orderId']=7
        if 'returnId' in row:row['returnId']=9
        if 'correctionId' in row:row['correctionId']=3
        if scope=='orders-page':row.update(orderId=7,deliveryAddress=None,legacyLine=None)
        if scope=='returns-page':row['returnId']=9
        if 'pdf' in row:row['pdf']={'path':'/invoices/me/data-export/orders/7/corrections/3/pdf','mediaType':'application/pdf','representation':'rendered-stored-document'}
        if collection:doc[collection]=[row]
        elif scope in ('inventory-reservation','coupon-reservation'):doc['reservation']=row
        elif scope=='payment-for-order':doc.update(payment=row,refund=None)
        elif scope=='shipment-for-order':doc['shipment']=row
        elif scope=='invoice-for-order':doc.update(invoice=row,pdf={'path':'/invoices/me/data-export/orders/7/pdf','mediaType':'application/pdf','representation':'rendered-stored-document'})
        if scope=='recommendation-log-page':doc.update(recordingEnabled=True,historyCoverage='optional-retained-log')
        if scope=='order-notifications':doc.update(authorizationMode='customer-order-owner',sourceId=7,conversationId=None);row.update(sourceId=7,eventType='ORDER_CREATED')
        documents[path]=doc
    class Sources(BaseHTTPRequestHandler):
        def do_GET(self):
            nonlocal fail_review
            path=urlsplit(self.path).path;proof=path=='/internal/orders/7/access'
            expected='Bearer '+(credentials['customer-service'] if proof else bearer)
            assert self.headers.get('Authorization')==expected,'Incorrect propagated source identity'
            assert self.headers.get('X-Request-Deadline-Ms'),'Missing bounded upstream deadline'
            calls.append(path);status=200;kind='application/json'
            if proof:data={'customerId':1,'customerEmail':'owner@fixture.example','guestAllowed':False}
            elif path.endswith('/pdf'):data=b'%PDF-fixture-stored-document';kind='application/pdf'
            elif path=='/reviews/me/data-export' and fail_review:fail_review=False;status=503;data={'privateProviderError':'must-not-persist'}
            elif path=='/payments/me/data-export/orders/7':status=404;data={}
            else:
                assert path in documents,path
                data=json.loads(json.dumps(documents[path]))
                if path=='/wishlists/me/data-export':
                    if oversize:data['unexpected']='x'*524289
                    elif parse_qs(urlsplit(self.path).query).get('cursor',['0'])[0]=='0':data.update(items=[{'productId':n+1000,'createdAt':NOW} for n in range(50)],hasMore=True,nextCursor='100')
                    else:data['items']=[{'productId':999,'createdAt':NOW}]
            raw=data if isinstance(data,bytes) else json.dumps(data).encode();self.send_response(status);self.send_header('Content-Type',kind);self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def log_message(self,*args):pass
    sources=ThreadingHTTPServer(('127.0.0.1',0),Sources);thread=threading.Thread(target=sources.serve_forever,daemon=True);thread.start()
    def sql(query):return command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','fixture','-d','customer_db','-Atqc',query)
    def start(name):
        env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','HOME','LANG','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=urls[name].rsplit(':',1)[1],DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/customer_db',DB_USERNAME='fixture',DB_PASSWORD=password,
            CUSTOMER_SERVICE_URL=urls['customer-service'],PLATFORM_AUTH_URL=urls['customer-service'],PLATFORM_OPERATOR_TOKEN=operator,PLATFORM_SERVICE_TOKEN=credentials[name],PLATFORM_SERVICE_CREDENTIALS_JSON=json.dumps(credentials),
            CUSTOMER_JWT_SECRET=base64.b64encode(signing).decode(),CUSTOMER_MFA_ENCRYPTION_KEY_BASE64=base64.b64encode(secrets.token_bytes(32)).decode(),CUSTOMER_SECURITY_RETENTION_ENABLED='false',CUSTOMER_AUDIT_RETENTION_MODE='off',CUSTOMER_EXPORT_CLEANUP_ENABLED='false',
            GATEWAY_RATE_LIMIT_ENABLED='false',MANAGEMENT_HEALTH_REDIS_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='3',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0')
        env.update({service.upper()+'_SERVICE_URL':f'http://127.0.0.1:{sources.server_port}' for service in ('order','inventory','payment','notification','delivery','promotion','wishlist','review','recommendation','returns','billing')})
        jars=list((ROOT/name/'target').glob('*-SNAPSHOT.jar'));assert len(jars)==1
        handle=(directory/(name+'-'+str(len(handles))+'.log')).open('w');handles.append(handle)
        processes[name]=subprocess.Popen(['java','-Xms48m','-Xmx192m','-XX:TieredStopAtLevel=1','-jar',str(jars[0])],env=env,cwd=ROOT,stdout=handle,stderr=subprocess.STDOUT)
        deadline=time.monotonic()+120
        while time.monotonic()<deadline:
            if processes[name].poll() is not None:raise RuntimeError(f'{name} exited; fixture logs {directory}')
            if qa.Client(urls[name],timeout=2).get('/actuator/health').status==200:return
            time.sleep(.3)
        raise RuntimeError(f'{name} startup timed out')
    def stop(name):
        p=processes[name]
        if p.poll() is None:
            p.terminate()
            try:p.wait(timeout=10)
            except subprocess.TimeoutExpired:p.kill();p.wait(timeout=10)
    try:
        postgres=command('docker','run','-d','--rm','-p','127.0.0.1::5432','-e','POSTGRES_USER=fixture','-e','POSTGRES_PASSWORD='+password,'postgres:16-alpine');db_port=command('docker','port',postgres,'5432').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-U','fixture'],capture_output=True).returncode==0:break
            time.sleep(.5)
        else:raise RuntimeError('PostgreSQL startup timed out')
        command('docker','exec',postgres,'createdb','-U','fixture','customer_db')
        for name in names:start(name)
        sql("INSERT INTO customers(id,email,password_hash,first_name,last_name,street,postal_code,city,country,enabled,created_at,updated_at) SELECT n,CASE n WHEN 1 THEN 'owner@fixture.example' ELSE 'other@fixture.example' END,'private-password','Jan','Test','Testowa 1','00-001','Warszawa','PL',true,now(),now() FROM generate_series(1,2)n")
        snapshot="SELECT json_build_array((SELECT json_agg(c ORDER BY id) FROM customers c),(SELECT json_agg(a ORDER BY id) FROM audit_events a),(SELECT json_agg(f ORDER BY id) FROM refresh_tokens f),(SELECT json_agg(p ORDER BY id) FROM password_reset_tokens p),(SELECT json_agg(t ORDER BY id) FROM customer_totp_methods t),(SELECT json_agg(r ORDER BY id) FROM customer_recovery_codes r),(SELECT json_agg(w ORDER BY export_id) FROM user_credentials w))::text"
        before=sql(snapshot);client=qa.Client(urls['api-gateway'],timeout=15);base='/api/v1/customers/me/data-export/jobs';headers={'Authorization':'Bearer '+bearer}
        def request(path='',method='GET',key=None,identity=None,status=200):
            response=qa.status(client.get(base+path,method=method,headers={**headers,**({'Idempotency-Key':str(key)} if key else {}),**({'Authorization':'Bearer '+identity} if identity else {})}),status,'export job '+method+' '+path)
            if status==200:assert 'private' in response.headers['cache-control'] and 'no-store' in response.headers['cache-control'] and response.headers['x-content-type-options']=='nosniff'
            return response.document() if status==200 else None
        key=uuid.uuid4();job=request(method='POST',key=key);jid=job['jobId'];assert request(method='POST',key=key)['jobId']==jid and job['ownerId']=='1'
        assert (qa.datetime.fromisoformat(job['expiresAt'].replace('Z','+00:00'))-qa.datetime.fromisoformat(job['createdAt'].replace('Z','+00:00'))).total_seconds()==3600
        request(method='POST',key=uuid.uuid4(),status=409)
        step=uuid.uuid4();first=request('/'+jid+'/advance','POST',step);assert first['partCount']==1
        assert request('/'+jid+'/advance','POST',step)['partCount']==1
        chunk=request('/'+jid+'/chunks')['parts'];assert len(chunk)==1 and chunk[0]['data']['profile']['id']==1
        stop('customer-service');start('customer-service');assert request('/'+jid+'/chunks')['parts']==chunk
        assert any(j['jobId']==jid for j in request()['jobs'])
        assert request('/'+jid+'/advance','POST',step)['partCount']==1
        checks.append('durable idempotent creation/advance and repeatable stored chunk survive real Customer restart')
        for _ in range(50):
            job=request('/'+jid+'/advance','POST',uuid.uuid4())
            if job['state']!='ACTIVE':break
        assert job['state']=='PARTIAL',job
        manifest=request('/'+jid+'/manifest');assert not manifest['complete'] and not manifest['automaticScopeComplete']
        assert any(row['error_code']=='SOURCE_UNAVAILABLE' for row in manifest['sources'])
        request('/'+jid+'/retry','POST')
        for _ in range(10):
            job=request('/'+jid+'/advance','POST',uuid.uuid4())
            if job['state']!='ACTIVE':break
        assert job['state']=='READY' and job['automaticScopeComplete'] and not job['complete'],job
        allparts=[];after=0
        while True:
            page=request('/'+jid+f'/chunks?after={after}&size=3');allparts+=page['parts'];after=page['nextSequence']
            if not page['hasMore']:break
        assert len(allparts)==job['partCount']==26 and len({p['sequence'] for p in allparts})==26
        assert sum(len(p['data']['items']) for p in allparts if p['scope']=='wishlist-page')==51
        assert any(p['scope']=='payment-for-order' and p['data']=={'missing':True} for p in allparts)
        assert sum('pdf-representation' in p['scope'] for p in allparts)==2
        for p in allparts:
            # Digest is for the exact stored UTF-8 JSON bytes, not a reserialization by the downloader.
            saved=sql(f"SELECT data_json FROM account_export_chunks WHERE job_id='{jid}' AND sequence_no={p['sequence']}")
            assert hashlib.sha256(saved.encode()).hexdigest()==p['sha256'] and len(saved.encode())==p['bytes']
        after=0
        while after<len(allparts):
            page=request('/'+jid+f'/chunks?after={after}&size=3&raw=true');assert page['ownerId']=='1'
            for p in page['parts']:
                saved=sql(f"SELECT data_json FROM account_export_chunks WHERE job_id='{jid}' AND sequence_no={p['sequence']}")
                assert p['dataJson']==saved and 'data' not in p and isinstance(p['parentId'],str) and isinstance(p['detailId'],str)
                assert hashlib.sha256(p['dataJson'].encode()).hexdigest()==p['sha256']
            after=page['nextSequence']
        manifest=request('/'+jid+'/manifest');assert len(manifest['serviceClassification'])==14 and len(manifest['copyBoundaries'])==17
        assert len(manifest['separateProofRequired'])==2 and manifest['manualReviewRequired'] and not manifest['complete']
        assert '/internal/orders/7/access' in calls
        checks.append('all automatic source shapes from production DTOs, nested order/return/invoice/correction/PDF fanout, 51 wishlist rows, optional absence proof, transient failure/retry, exact digests and 14-owner partial manifest')
        for suffix,method in [('', 'GET'),('/chunks','GET'),('/manifest','GET'),('/advance','POST'),('/cancel','POST'),('/retry','POST')]:
            request('/'+jid+suffix,method,uuid.uuid4(),other,404)
            for identity in (operator,credentials['customer-service'],'invalid'):request('/'+jid+suffix,method,uuid.uuid4(),identity,401)
        request('/'+jid+'/chunks?size=4',status=400);request('/'+jid+'/chunks?after=-1',status=400)
        persisted=sql("SELECT json_build_array((SELECT json_agg(j) FROM account_export_jobs j),(SELECT json_agg(t) FROM account_export_tasks t),(SELECT json_agg(c) FROM account_export_chunks c))::text")
        assert all(secret not in persisted for secret in (bearer,other,credentials['customer-service'],'must-not-persist','private-password'))
        assert sql(snapshot)==before
        checks.append('every job route protects owner and rejects operator/service; no JWT/provider errors/secrets persisted; seven business/security tables unchanged')
        request('/'+jid+'/cancel','POST');request('/'+jid+'/chunks',status=410)
        second=request(method='POST',key=uuid.uuid4());sid=second['jobId'];sql(f"UPDATE account_export_jobs SET created_at=now()-interval '2 hours',expires_at=now()-interval '1 hour' WHERE id='{sid}'")
        request('/'+sid,status=410);request('/'+sid+'/chunks',status=410);request('/'+sid+'/advance','POST',uuid.uuid4(),status=410)
        oversize=True;third=request(method='POST',key=uuid.uuid4());tid=third['jobId']
        for _ in range(40):
            job=request('/'+tid+'/advance','POST',uuid.uuid4())
            if job['state']!='ACTIVE':break
        assert job['state']=='LIMIT_REACHED' and not job['automaticScopeComplete']
        assert any(row['error_code']=='PART_LIMIT' for row in request('/'+tid+'/manifest')['sources'])
        checks.append('immediate cancellation/one-hour expiry cut off content and further writes; oversized source stops with explicit incomplete limit state')
        for prefix in ('/api','/api/v1'):
            qa.status(client.get(prefix+'/customers/me/data-export/jobs/'+tid,headers=headers),200,'Gateway job routing')
        spec=qa.Client(urls['customer-service']).get('/v3/api-docs').document();contract=json.loads((ROOT/'scripts/qa/contracts/api-gateway-v1.json').read_text(encoding='utf-8'));qa.check_document(spec,contract['services']['customer'],'Customer')
        checks.append('both Gateway prefixes and generated Customer OpenAPI contract')
        browser_report=None
        if args.with_browser:
            sql('TRUNCATE account_export_chunks,account_export_tasks,account_export_jobs')
            oversize=False;fail_review=True
            browser_directory=directory/'browser'
            node='/mnt/c/Program Files/nodejs/node.exe'
            if Path(node).is_file():
                script=command('wslpath','-w',str(ROOT/'scripts/qa/account-export-browser.cjs'))
                artifact_directory=command('wslpath','-w',str(browser_directory))
            else:node='node';script=str(ROOT/'scripts/qa/account-export-browser.cjs');artifact_directory=str(browser_directory)
            payload={'gateway':urls['api-gateway'],'accessToken':bearer,'directory':artifact_directory,
                'customer':{'id':1,'email':'owner@fixture.example','firstName':'Jan','lastName':'Test','street':'Testowa 1','postalCode':'00-001','city':'Warszawa','country':'PL','enabled':True}}
            child=subprocess.run([node,script],input=json.dumps(payload),capture_output=True,text=True,cwd=ROOT,timeout=180)
            if child.returncode:raise RuntimeError('Real export browser failed: '+child.stderr[-2500:])
            browser_report=json.loads(child.stdout.strip().splitlines()[-1]);assert browser_report['status']=='PASS' and browser_report['automaticParts']==26
            browser_directory.mkdir(exist_ok=True);(browser_directory/'report.json').write_text(json.dumps(browser_report,indent=2)+'\n',encoding='utf-8')
            checks.append('real storefront browser prepares/resumes/downloads 26 actual database parts through Gateway and cancels the result')
        sql("UPDATE customers SET enabled=false WHERE id=1")
        request('/'+tid,status=401);request('/'+tid+'/chunks',status=401);request('/'+tid+'/advance','POST',uuid.uuid4(),status=401)
        checks.append('disabling the account immediately blocks status, stored data and further collection')
        report={'stage':'A43.32' if args.with_browser else 'A43.31','status':'PASS','checks':checks,'browser':browser_report,'rawDownloadExtension':'A43.32-owner-text-and-exact-dataJson-with-SHA256','automaticParts':26,'businessTablesUnchanged':7,'sourceKind':'bounded-fixtures-derived-from-production-export-DTOs','realCustomerGatewayPostgres':True,'realUserDataAccessed':False,'deployment':False,'fullAccountExportComplete':False}
        path=directory/'report.json';path.write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8');print(json.dumps({'status':'PASS','checks':len(checks),'report':str(path)}))
    finally:
        for name in reversed(list(processes)):stop(name)
        for h in handles:h.close()
        if postgres:command('docker','rm','-f',postgres)
        sources.shutdown();sources.server_close();thread.join(timeout=5)

if __name__=='__main__':main()
