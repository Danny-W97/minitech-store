#!/usr/bin/env python3
"""A32: two real replicas per service, disposable PostgreSQL/Redis/Mailpit.
Faults use this run's DB triggers and Redis ACLs; no production test switches.
"""
import concurrent.futures
from http.server import BaseHTTPRequestHandler,ThreadingHTTPServer
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
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('events_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)


def main():
    logs=ROOT/'tmp'/('event-reliability-'+secrets.token_hex(4));logs.mkdir(parents=True)
    containers=[];processes={};handles=[];urls={};report={};password=secrets.token_urlsafe(24)
    operator=secrets.token_urlsafe(32);tokens={module:secrets.token_urlsafe(32) for module in ['payment-service','billing-service','notification-service']}
    item=dict(productId=1,sku='SKU',productName='Product',quantity=1,unitPrice=10,totalPrice=10)
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args): pass
        def reply(self,body):
            raw=json.dumps(body).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(raw)));self.end_headers();self.wfile.write(raw)
        def do_POST(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    length=int(self.rfile.readline().split(b';')[0],16)
                    if not length:self.rfile.readline();break
                    parts.append(self.rfile.read(length));self.rfile.read(2)
                body=json.loads(b''.join(parts))
            else:body=json.loads(self.rfile.read(int(self.headers.get('Content-Length','0'))))
            actor=dict(role='ANONYMOUS',service=None,customerId=None,email=None)
            if body.get('token')==operator:actor['role']='OPERATOR'
            for module,token in tokens.items():
                if body.get('token')==token:actor.update(role='SERVICE',service=module)
            self.reply(actor)
        def do_GET(self):
            self.reply(dict(id=int(self.path.rsplit('/',1)[1]),customerId=None,customerEmail='owner@example.test',status='PAID',totalPrice=10,deliveryCost=0,discountAmount=0,items=[item],paymentState='PAID',lifecycleReviewRequired=False))
    fixture=ThreadingHTTPServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def docker(image,port,*extra,command_args=(),stable=False):
        host_port=''
        if stable:
            with socket.socket() as probe:probe.bind(('127.0.0.1',0));host_port=str(probe.getsockname()[1])
        container=http.command('docker','run','-d','--rm','-p',f'127.0.0.1:{host_port}:{port}',*extra,image,*command_args);containers.append(container)
        return container,http.command('docker','port',container,str(port)).rsplit(':',1)[1]
    def sql(database,query):
        return http.command('docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d',database+'_db','-Atqc',query)
    def redis(*args):
        raw=http.command('docker','exec',broker,'redis-cli','--json',*map(str,args));return json.loads(raw) if raw else None
    def poll(action,predicate,seconds=20):
        end=time.monotonic()+seconds;value=None
        while time.monotonic()<end:
            value=action()
            if predicate(value):return value
            time.sleep(.15)
        raise AssertionError('Timed out; last state: '+repr(value))
    def start(name):
        module=name.rsplit('-',1)[0]+'-service';database=module.removesuffix('-service')
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        env={k:v for k,v in os.environ.items() if not k.startswith(('PLATFORM_','SPRING_','DB_','REDIS_','MAIL_','PAYMENT_','BILLING_','NOTIFICATION_','EVENT_','OUTBOX_'))}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{database}_db?ApplicationName={name}',DB_USERNAME='audit',DB_PASSWORD=password,
            REDIS_URL=f'redis://127.0.0.1:{redis_port}',MAIL_HOST='127.0.0.1',MAIL_PORT=mail_port,
            PLATFORM_SERVICE_TOKEN=tokens[module],PLATFORM_AUTH_URL=fixture_url,PLATFORM_ORDER_URL=fixture_url,ORDER_SERVICE_URL=fixture_url,
            MANAGEMENT_HEALTH_MAIL_ENABLED='false',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4',SPRING_DATASOURCE_HIKARI_MINIMUM_IDLE='0',
            OUTBOX_PUBLISHER_FIXED_DELAY_MS='150',EVENT_CONSUMER_CLAIM_IDLE_MS='1000',NOTIFICATION_CONSUMER_FIXED_DELAY_MS='150',BILLING_CONSUMER_FIXED_DELAY_MS='150',NOTIFICATION_RETRY_FIXED_DELAY_MS='150',
            NOTIFICATION_CONSUMER_NAME=name,BILLING_CONSUMER_NAME=name,PAYMENT_STREAM_RETENTION_AGE_MS='604800000')
        path=logs/(name+'-'+secrets.token_hex(2)+'.log');handle=path.open('w');handles.append(handle)
        jar=next((ROOT/module/'target').glob('*-SNAPSHOT.jar'))
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(jar)],env=env,stdout=handle,stderr=subprocess.STDOUT)
        urls[name]=f'http://127.0.0.1:{port}'
    def ready(name):
        end=time.monotonic()+160
        while time.monotonic()<end:
            if processes[name].poll() is not None:raise RuntimeError(name+' exited; see '+str(logs))
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.5)
        raise RuntimeError(name+' health timeout')
    def launch(names):
        for name in names:start(name)
        with concurrent.futures.ThreadPoolExecutor(max_workers=len(names)) as workers:list(workers.map(ready,names))
    def kill(names):
        for name in names:
            if processes[name].poll() is None:processes[name].kill()
        for name in names:processes[name].wait(timeout=10)
    def records():return redis('XRANGE','payment-events','-','+') or []
    def as_map(value):return value if isinstance(value,dict) else dict(zip(value[::2],value[1::2]))
    def event_fields(row):return as_map(row[1])
    def add(source,event_id):
        return redis('XADD','payment-events','*','eventId',event_id,'eventType','PAYMENT_PENDING','orderId',source,'amount','10.00','recipient','owner@example.test')
    def mails():
        with urllib.request.urlopen('http://127.0.0.1:'+mail_api+'/api/v1/messages',timeout=3) as response:return json.load(response)['total']
    def sent(source):return sql('notification',f"SELECT count(*) FROM notifications WHERE source_id={source} AND status='SENT'")=='1'
    def drained():
        rows=records()
        if not rows:return False
        groups=redis('XINFO','GROUPS','payment-events') or []
        return len(groups)==2 and all((as_map(row)['pending']==0 and as_map(row)['last-delivered-id']==rows[-1][0]) for row in groups)
    def seed(source):
        sql('payment',f"INSERT INTO payment_outbox_events(event_key,event_type,order_id,amount,recipient,status,attempts,next_attempt_at,created_at,updated_at) VALUES('PAYMENT_PENDING:{source}:owner@example.test','PAYMENT_PENDING',{source},10,'owner@example.test','PENDING',0,now(),now(),now())")
    def sleep_trigger(database,table,name,condition):
        sql(database,f"CREATE FUNCTION {name}() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF {condition} THEN PERFORM pg_sleep(60); END IF; RETURN NEW; END $$; CREATE TRIGGER {name} BEFORE INSERT OR UPDATE ON {table} FOR EACH ROW EXECUTE FUNCTION {name}()")
    def drop_trigger(database,table,name):sql(database,f'DROP TRIGGER {name} ON {table}; DROP FUNCTION {name}()')
    def sleeping(database):return sql(database,"SELECT application_name FROM pg_stat_activity WHERE wait_event='PgSleep' AND application_name<>'' LIMIT 1")
    try:
        postgres,db_port=docker('postgres:16-alpine',5432,'-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password)
        broker,redis_port=docker('redis:7-alpine',6379,stable=True,command_args=('redis-server','--appendonly','yes','--appendfsync','always'))
        mail,mail_port=docker('axllent/mailpit:latest',1025,'-p','127.0.0.1::8025')
        mail_api=http.command('docker','port',mail,'8025').rsplit(':',1)[1]
        for _ in range(60):
            if subprocess.run(['docker','exec',postgres,'pg_isready','-h','127.0.0.1','-U','audit'],capture_output=True).returncode==0:break
            time.sleep(1)
        else:raise RuntimeError('Temporary PostgreSQL not ready')
        for database in ['payment','billing','notification']:http.command('docker','exec',postgres,'createdb','-U','audit',database+'_db')
        nodes=['payment-a','payment-b','billing-a','billing-b','notification-a','notification-b'];notifications=nodes[-2:];billings=nodes[2:4]
        launch(nodes);print('READY: two real replicas each of Payment, Billing and Notification',flush=True)
        for source in range(1,21):seed(source)
        poll(lambda:sql('payment',"SELECT count(*) FROM payment_outbox_events WHERE status='PUBLISHED'"),lambda value:value=='20')
        poll(lambda:sql('notification',"SELECT count(*) FROM notifications WHERE status='SENT'"),lambda value:value=='20')
        poll(drained,bool)
        assert len(records())==20 and mails()==20
        for group in ['notification-service','billing-service']:
            consumers=redis('XINFO','CONSUMERS','payment-events',group);assert len(consumers)>=2
        print('PASS: two publishers/consumers, 20 durable events, one send per event',flush=True)
        original=event_fields(records()[0]);args=[part for pair in original.items() for part in pair]
        for _ in range(20):redis('XADD','payment-events','*',*args)
        poll(drained,bool);assert mails()==20 and sql('notification','SELECT count(*) FROM event_inbox')=='20'
        print('PASS: duplicate stream records are idempotent',flush=True)
        before_restart=len(records());http.command('docker','restart',broker)
        assert http.command('docker','port',broker,'6379').rsplit(':',1)[1]==redis_port
        poll(lambda:redis('PING'),lambda value:value=='PONG');poll(drained,bool)
        assert len(records())==before_restart and mails()==20
        print('PASS: Redis restart preserves stream/group state with AOF fsync always',flush=True)
        sleep_trigger('payment','payment_outbox_events','pause_publish',"NEW.status='PUBLISHED' AND NEW.order_id=901")
        seed(901);owner=poll(lambda:sleeping('payment'),bool)
        poll(lambda:sent(901),bool);kill([owner]);drop_trigger('payment','payment_outbox_events','pause_publish')
        sql('payment',"UPDATE payment_outbox_events SET processing_until=now()-interval '1 second' WHERE order_id=901")
        poll(lambda:sql('payment',"SELECT status FROM payment_outbox_events WHERE order_id=901"),lambda value:value=='PUBLISHED')
        poll(drained,bool);duplicates=[event_fields(row) for row in records() if event_fields(row).get('orderId')=='901']
        assert len(duplicates)==2 and len({row['eventId'] for row in duplicates})==1 and mails()==21
        launch([owner]);print('PASS: crash after XADD/before SQL completion recovers with stable event ID',flush=True)
        redis('ACL','SETUSER','default','-xadd');seed(902)
        poll(lambda:sql('payment',"SELECT status FROM payment_outbox_events WHERE order_id=902"),lambda value:value=='FAILED')
        redis('ACL','SETUSER','default','+xadd');sql('payment',"UPDATE payment_outbox_events SET next_attempt_at=now() WHERE order_id=902")
        poll(lambda:sent(902),bool);assert mails()==22
        print('PASS: broker outage leaves a durable retry',flush=True)
        sleep_trigger('notification','event_inbox','pause_inbox',"NEW.event_id='crash-before-inbox'")
        add(903,'crash-before-inbox');poll(lambda:sleeping('notification'),bool);kill(notifications)
        drop_trigger('notification','event_inbox','pause_inbox')
        assert sql('notification',"SELECT count(*) FROM event_inbox WHERE event_id='crash-before-inbox'")=='0'
        assert sql('notification','SELECT count(*) FROM notifications WHERE source_id=903')=='0'
        launch(notifications);poll(lambda:sent(903),bool);assert mails()==23
        print('PASS: crash before inbox commit rolls back the job; new consumer reclaims pending',flush=True)
        redis('ACL','SETUSER','default','-xack');add(904,'crash-after-inbox')
        poll(lambda:sent(904),bool);assert mails()==24
        poll(lambda:sql('billing',"SELECT count(*) FROM event_inbox WHERE event_id='crash-after-inbox'"),lambda value:value=='1')
        assert redis('XPENDING','payment-events','notification-service')[0]>0
        kill(notifications+billings);redis('ACL','SETUSER','default','+xack');launch(notifications+billings)
        poll(drained,bool);assert mails()==24
        assert sql('notification',"SELECT count(*) FROM event_inbox WHERE event_id='crash-after-inbox'")=='1'
        print('PASS: crash after SQL commit/before ACK is idempotent across consumer names',flush=True)
        redis('XADD','payment-events','*','eventType','invalid')
        altered=dict(original);altered['amount']='11.00';redis('XADD','payment-events','*',*[part for pair in altered.items() for part in pair])
        poll(drained,bool)
        for database in ['notification','billing']:assert sql(database,"SELECT count(*) FROM event_inbox WHERE status='REJECTED'")=='2'
        assert mails()==24;print('PASS: malformed events and reused IDs are durably quarantined before ACK',flush=True)
        sleep_trigger('notification','notifications','pause_smtp_result',"NEW.status='SENT' AND NEW.source_id=906")
        add(906,'uncertain-smtp');poll(lambda:sleeping('notification'),bool);assert mails()==25;kill(notifications)
        drop_trigger('notification','notifications','pause_smtp_result')
        sql('notification',"UPDATE notifications SET processing_until=now()-interval '1 second' WHERE source_id=906")
        launch(notifications)
        poll(lambda:sql('notification',"SELECT status FROM notifications WHERE source_id=906"),lambda value:value=='FAILED')
        assert sql('notification',"SELECT count(*) FROM notification_dead_letters WHERE source_id=906")=='1'
        add(906,'uncertain-smtp');poll(drained,bool);assert mails()==25
        print('PASS: SMTP accepted before process death is quarantined without an automatic second send',flush=True)
        redis('ACL','SETUSER','default','-xadd')
        request=dict(orderId=1001,customerId=None,customerEmail='owner@example.test',grossAmount=10,currency='PLN',deliveryCost=0,discountAmount=0,items=[item],billingData=dict(company=False,buyerName='Buyer',street='Street',postalCode='00-001',city='City',country='PL'))
        invoice=http.request(urls['billing-a']+'/invoices','POST',request,token=operator,expected=201)
        poll(lambda:sql('billing',"SELECT count(*) FROM invoice_outbox_events WHERE status='FAILED'"),lambda value:value=='1')
        redis('ACL','SETUSER','default','+xadd');sql('billing','UPDATE invoice_outbox_events SET next_attempt_at=now()')
        poll(lambda:sql('billing',"SELECT count(*) FROM invoice_outbox_events WHERE status='PUBLISHED'"),lambda value:value=='1')
        http.request(urls['billing-a']+f"/invoices/{invoice['id']}/corrections",'POST',dict(reason='Audit',correctionGrossAmount=-1,idempotencyKey=secrets.token_hex(16),expectedVersion=0),token=operator,expected=201)
        poll(lambda:sql('billing',"SELECT count(*) FROM invoice_outbox_events WHERE status='PUBLISHED'"),lambda value:value=='2')
        assert len(redis('XRANGE','invoice-events','-','+'))==2
        print('PASS: invoice/correction events survive a broker outage via transactional outbox',flush=True)
        # Exercise the exact retention Lua shipped by the application against isolated old entries.
        source=(ROOT/'platform-security/src/main/java/org/demo/platformevents/RedisEventStreams.java').read_text()
        trim=re.search(r'TRIM=new DefaultRedisScript<>\("""(.*?)""",Long.class\)',source,re.S).group(1)
        key='retention-fixture'
        def resp(parts):return '*'+str(len(parts))+'\r\n'+''.join('$'+str(len(part))+'\r\n'+part+'\r\n' for part in parts)
        commands=''.join(resp(['XADD',key,f'1000-{i}','value','old']) for i in range(3000))
        http.command('docker','exec','-i',broker,'redis-cli','--pipe',input=commands)
        redis('XGROUP','CREATE',key,'first','0-0')
        def trim_once():return redis('EVAL',trim,1,key,f'{int(time.time()*1000)-1000}-0','first','second')
        assert trim_once()==0
        redis('XGROUP','CREATE',key,'second','0-0');assert trim_once()==0
        redis('XREADGROUP','GROUP','first','dead','COUNT',1,'STREAMS',key,'>')
        for group in ['first','second']:redis('XGROUP','SETID',key,group,'1000-2999')
        assert trim_once()==0
        redis('XACK',key,'first','1000-0');redis('XGROUP','CREATE',key,'extra','0-0');assert trim_once()==0
        redis('XGROUP','SETID',key,'extra','1000-2999');assert trim_once()>0
        assert redis('XLEN',key)<3000
        report.update(paymentPublished=sql('payment',"SELECT count(*) FROM payment_outbox_events WHERE status='PUBLISHED'"),mailCount=mails(),retentionRemaining=redis('XLEN',key),notificationInbox=sql('notification','SELECT count(*) FROM event_inbox'),billingInbox=sql('billing','SELECT count(*) FROM event_inbox'))
        (logs/'report.json').write_text(json.dumps(report,indent=2));print('PASS: retention protects missing groups, unread and pending records, including additional groups',flush=True)
        print('PASS A32:',json.dumps(report),flush=True)
    finally:
        for process in processes.values():
            if process.poll() is None:process.terminate()
        for process in processes.values():
            try:process.wait(timeout=15)
            except subprocess.TimeoutExpired:process.kill();process.wait()
        for handle in handles:handle.close()
        for container in containers:subprocess.run(['docker','rm','-f',container],capture_output=True)
        fixture.shutdown();fixture.server_close();print('Isolated resources removed; logs:',logs,flush=True)


if __name__=='__main__':main()
