#!/usr/bin/env python3
"""A38: actual Chat replicas, PostgreSQL, Notification and owned Mailpit; no user profiles."""
import concurrent.futures
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import secrets
import socket
import subprocess
import threading
import time
import urllib.request

ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('chat_http',ROOT/'scripts/qa/access-control-e2e.py')
http=importlib.util.module_from_spec(spec);spec.loader.exec_module(http)

def main():
    directory=ROOT/'tmp'/('audit-a38-'+secrets.token_hex(4));directory.mkdir(parents=True)
    containers=[];processes={};handles=[];urls={};report={'status':'FAILED'}
    password=secrets.token_urlsafe(24);operator=secrets.token_urlsafe(32);customer=secrets.token_urlsafe(32)
    chat_token=secrets.token_urlsafe(32);notification_token=secrets.token_urlsafe(32)
    faults={'offline':True,'drop':False,'dropped':False,'chatTransactionDuringHttp':False}
    def sql(database,query):return http.command('docker','exec',postgres,'psql','-X','-v','ON_ERROR_STOP=1','-U','audit','-d',database+'_db','-Atqc',query)
    class Fixture(BaseHTTPRequestHandler):
        def log_message(self,*args):pass
        def read(self):
            if self.headers.get('Transfer-Encoding','').lower()=='chunked':
                parts=[]
                while True:
                    length=int(self.rfile.readline().split(b';')[0],16)
                    if not length:self.rfile.readline();break
                    parts.append(self.rfile.read(length));self.rfile.read(2)
                return b''.join(parts)
            return self.rfile.read(int(self.headers.get('Content-Length','0')))
        def do_POST(self):
            body=self.read()
            if self.path=='/internal/auth/introspect':
                token=json.loads(body).get('token');actor={'role':'ANONYMOUS','service':None,'customerId':None,'email':None}
                if token==operator:actor['role']='OPERATOR'
                elif token==customer:actor.update(role='CUSTOMER',customerId=42,email='other@example.test')
                elif token in (chat_token,notification_token):actor.update(role='SERVICE',service='chat-service' if token==chat_token else 'notification-service')
                raw=json.dumps(actor).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw);return
            if faults['offline']:self.send_response(503);self.end_headers();return
            # A real network call must not retain a transaction in Chat's DB.
            if sql('chat',"SELECT count(*) FROM pg_stat_activity WHERE datname='chat_db' AND state='idle in transaction'")!='0':faults['chatTransactionDuringHttp']=True
            request=urllib.request.Request(urls['notification']+'/notifications',data=body,headers={'Content-Type':'application/json','Authorization':'Bearer '+chat_token},method='POST')
            try:
                with urllib.request.urlopen(request,timeout=12) as response:raw=response.read();status=response.status
            except urllib.error.HTTPError as error:
                raw=error.read();status=error.code
                with (directory/'proxy-errors.log').open('ab') as log:log.write(str(status).encode()+b' '+raw+b'\n')
            if faults['drop'] and not faults['dropped']:
                faults['dropped']=True;self.connection.shutdown(socket.SHUT_RDWR);self.connection.close();return
            self.send_response(status);self.send_header('Content-Type','application/json');self.end_headers();self.wfile.write(raw)
    class FixtureServer(ThreadingHTTPServer):
        request_queue_size=64
        daemon_threads=True
    fixture=FixtureServer(('127.0.0.1',0),Fixture);threading.Thread(target=fixture.serve_forever,daemon=True).start()
    fixture_url=f'http://127.0.0.1:{fixture.server_port}'
    def docker(image,port,*args):
        identifier=http.command('docker','run','-d','-p',f'127.0.0.1::{port}',*args,image);containers.append(identifier)
        return identifier,http.command('docker','port',identifier,str(port)).rsplit(':',1)[1]
    def poll(action,predicate,seconds=40):
        end=time.monotonic()+seconds;value=None
        while time.monotonic()<end:
            value=action()
            if predicate(value):return value
            time.sleep(.15)
        raise AssertionError('Timed out: '+str(value)[:200])
    def start(name,enabled=False):
        module='notification-service' if name=='notification' else 'chat-service';db='notification' if name=='notification' else 'chat'
        with socket.socket() as probe:probe.bind(('127.0.0.1',0));port=probe.getsockname()[1]
        env={k:v for k,v in os.environ.items() if k in ('PATH','JAVA_HOME','LANG','HOME','TMP','TEMP')}
        env.update(SPRING_PROFILES_ACTIVE='dev',SERVER_PORT=str(port),DB_URL=f'jdbc:postgresql://127.0.0.1:{db_port}/{db}_db',DB_USERNAME='audit',DB_PASSWORD=password,
            REDIS_URL=f'redis://127.0.0.1:{redis_port}',MAIL_HOST='127.0.0.1',MAIL_PORT=mail_port,MANAGEMENT_HEALTH_MAIL_ENABLED='false',
            MESSAGING_CONSUMER_ENABLED='false',NOTIFICATION_RETRY_ENABLED='false',PLATFORM_AUTH_URL=fixture_url,
            PLATFORM_SERVICE_TOKEN=notification_token if name=='notification' else chat_token,NOTIFICATION_SERVICE_URL=fixture_url,
            CHAT_OUTBOX_ENABLED=str(enabled).lower(),CHAT_OUTBOX_FIXED_DELAY_MS='150',CHAT_OUTBOX_MAX_ATTEMPTS='10',
            SPRING_FLYWAY_BASELINE_ON_MIGRATE='true',SPRING_FLYWAY_BASELINE_VERSION='3',SPRING_DATASOURCE_HIKARI_MAXIMUM_POOL_SIZE='4')
        log=(directory/(name+'-'+secrets.token_hex(2)+'.log')).open('w');handles.append(log)
        processes[name]=subprocess.Popen(['java','-Xms32m','-Xmx160m','-XX:TieredStopAtLevel=1','-jar',str(next((ROOT/module/'target').glob('*-SNAPSHOT.jar')))],env=env,stdout=log,stderr=subprocess.STDOUT)
        urls[name]=f'http://127.0.0.1:{port}'
        end=time.monotonic()+140
        while time.monotonic()<end:
            assert processes[name].poll() is None,name+' exited; see '+str(directory)
            try:
                with urllib.request.urlopen(urls[name]+'/actuator/health',timeout=2) as response:
                    if response.status==200:return
            except OSError:pass
            time.sleep(.3)
        raise AssertionError(name+' startup timeout')
    def kill(name):
        if name in processes and processes[name].poll() is None:processes[name].kill();processes[name].wait(timeout=10)
    def request(path,method='GET',body=None,actor=None,cap=None,node='chat-a',expected=200):
        return http.request(urls[node]+path,method,body,token=actor,headers={'X-Chat-Access-Token':cap} if cap else {},expected=expected)
    def mails():
        with urllib.request.urlopen('http://127.0.0.1:'+mail_api+'/api/v1/messages') as response:return json.load(response)['total']
    try:
        postgres,db_port=docker('postgres:16-alpine',5432,'-e','POSTGRES_USER=audit','-e','POSTGRES_PASSWORD='+password)
        broker,redis_port=docker('redis:7-alpine',6379)
        mail,mail_port=docker('axllent/mailpit:latest',1025,'-p','127.0.0.1::8025');mail_api=http.command('docker','port',mail,'8025').rsplit(':',1)[1]
        poll(lambda:subprocess.run(['docker','exec',postgres,'pg_isready','-U','audit'],capture_output=True).returncode,lambda v:v==0)
        for database in ['chat','notification']:http.command('docker','exec',postgres,'createdb','-U','audit',database+'_db')
        migrations=ROOT/'chat-service/src/main/resources/db/migration'
        for version in [1,2,3]:sql('chat',next(migrations.glob(f'V{version}__*.sql')).read_text())
        legacy=hashlib.sha256(('L'*43).encode()).hexdigest()
        sql('chat',f"INSERT INTO chat_messages(customer_name,customer_email,subject,message,status,notification_status,reply_message,reply_notification_status,created_at,updated_at,replied_at,access_token_hash) VALUES ('Legacy','legacy@example.test','Legacy','Old question','ANSWERED','SENT','Old answer','SENT',now(),now(),now(),'{legacy}'),('Legacy','legacy@example.test','Legacy','Old question','ANSWERED','FAILED','Old answer','FAILED',now(),now(),now(),'{legacy}')")
        start('notification')
        for kind,recipient in [('CHAT_MESSAGE_RECEIVED','support@minitech.local'),('CHAT_REPLY_SENT','legacy@example.test')]:
            http.request(urls['notification']+'/notifications','POST',dict(eventType=kind,sourceId=2,recipient=recipient,channel='EMAIL',subject='Legacy',message='Legacy',simulateFailure=False),token=chat_token,expected=201)
        sql('notification',"UPDATE notifications SET status='FAILED',next_attempt_at=NULL,failure_reason='UNCERTAIN_FIXTURE' WHERE event_type='CHAT_REPLY_SENT'")
        start('chat-a');start('chat-b')
        assert [e['message'] for e in request('/chat/messages/1/entries',cap='L'*43)['entries']]==['Old question','Old answer']
        assert sql('chat',"SELECT count(*) FROM chat_notification_outbox WHERE status='ACCEPTED'")=='2'
        cap=secrets.token_urlsafe(32)
        root=request('/chat/messages','POST',dict(customerName='Guest',customerEmail='guest@example.test',subject='Subject',message='Question'),cap=cap,expected=201);thread=root['id']
        request(f'/chat/messages/{thread}/entries',cap='W'*43,expected=401)
        request(f'/chat/messages/{thread}/reply','POST',{'message':'Forbidden'},cap=cap,expected=401)
        request(f'/chat/messages/{thread}/reply','POST',{'message':'Forbidden'},actor=customer,expected=403)
        def reply(text,key,node='chat-a',expected=200):return request(f'/chat/messages/{thread}/reply','POST',{'message':text,'requestId':key},actor=operator,node=node,expected=expected)
        reply('First answer','first');reply('Second answer '+('x'*3980),'second',node='chat-b')
        reply('First answer','first',node='chat-b');reply('Changed','first',expected=409)
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:list(pool.map(lambda n:reply('Concurrent','race',n),['chat-a','chat-b']))
        request(f'/chat/messages/{thread}/entries','POST',{'message':'Customer follow-up','requestId':'follow-up'},cap=cap)
        page=request(f'/chat/messages/{thread}/entries?size=2',cap=cap)
        previous=request(f'/chat/messages/{thread}/entries?size=100&before={page["nextCursor"]}',cap=cap)
        all_entries=previous['entries']+page['entries'];assert len(all_entries)==5
        assert [e['message'] for e in all_entries][0:2]==['Question','First answer']
        assert len({e['id'] for e in all_entries})==5
        # SQL immutability and atomic rollback of a message + its durable task.
        denied=subprocess.run(['docker','exec',postgres,'psql','-v','ON_ERROR_STOP=1','-U','audit','-d','chat_db','-c',"UPDATE chat_entries SET message='altered'"],capture_output=True);assert denied.returncode!=0
        sql('chat',"CREATE FUNCTION fixture_task_failure() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'fixture'; END $$; CREATE TRIGGER fixture_task_failure BEFORE INSERT ON chat_notification_outbox FOR EACH ROW EXECUTE FUNCTION fixture_task_failure()")
        reply('Must roll back','rollback',expected=500)
        assert sql('chat',f'SELECT count(*) FROM chat_entries WHERE conversation_id={thread}')=='5'
        sql('chat','DROP TRIGGER fixture_task_failure ON chat_notification_outbox; DROP FUNCTION fixture_task_failure()')
        print('PASS migration, immutable history, owner/operator access, reply races, cursor and atomic rollback',flush=True)
        kill('chat-a');start('chat-a',True)
        poll(lambda:sql('chat',"SELECT count(*) FROM chat_notification_outbox WHERE status='RETRY'"),lambda v:int(v)>0)
        kill('chat-a');faults.update(offline=False,drop=True)
        start('chat-a',True);kill('chat-b');start('chat-b',True)
        poll(lambda:faults['dropped'],bool)
        poll(lambda:sql('chat',"SELECT count(*) FROM chat_notification_outbox WHERE status<>'ACCEPTED'"),lambda v:v=='0',seconds=60)
        assert not faults['chatTransactionDuringHttp']
        expected_mails=2+int(sql('notification',"SELECT count(*) FROM notifications WHERE event_type IN ('CHAT_ENTRY_RECEIVED','CHAT_ENTRY_REPLIED') AND status='SENT'"))
        poll(mails,lambda v:v==expected_mails)
        assert sql('notification',"SELECT count(*) FROM notifications WHERE event_type='CHAT_ENTRY_REPLIED'")=='3'
        assert int(sql('notification',"SELECT max(length(message)) FROM notifications WHERE event_type='CHAT_ENTRY_REPLIED'"))>4000
        # A terminal transport failure is replayable; accepted notifications are not.
        faults['offline']=True;reply('Replay after outage','dead')
        dead=int(sql('chat',f'SELECT max(id) FROM chat_entries WHERE conversation_id={thread}'))
        poll(lambda:(sql('chat',f"UPDATE chat_notification_outbox SET attempts=9,next_attempt_at=now() WHERE entry_id={dead} AND status IN ('PENDING','RETRY') AND processing_token IS NULL"),sql('chat',f'SELECT attempts FROM chat_notification_outbox WHERE entry_id={dead}'))[1],lambda v:int(v)>=9)
        poll(lambda:sql('chat',f'SELECT status FROM chat_notification_outbox WHERE entry_id={dead}'),lambda v:v=='DEAD_LETTER')
        request(f'/chat/messages/{thread}/entries/{dead}/retry-notification','POST',actor=customer,expected=403)
        faults['offline']=False
        request(f'/chat/messages/{thread}/entries/{dead}/retry-notification','POST',actor=operator)
        poll(lambda:sql('chat',f'SELECT status FROM chat_notification_outbox WHERE entry_id={dead}'),lambda v:v=='ACCEPTED')
        request(f'/chat/messages/{thread}/entries/{dead}/retry-notification','POST',actor=operator,expected=409)
        assert sql('notification',"SELECT status FROM notifications WHERE event_type='CHAT_REPLY_SENT'")=='FAILED'
        report.update(status='PASS',httpChecks=http.CHECKS,legacyMigration=True,immutableHistory=True,atomicRollback=True,
            replicaIdempotency=True,restartAndLostReceiptRecovery=True,full4000CharacterReply=True,notificationManualFailurePreserved=True,operatorReplay=True,
            noDatabaseTransactionDuringHttp=True,externalMessagesSent=False)
        print('PASS restart, lost receipt, full message emails, manual failure preservation and fenced operator replay',flush=True)
    finally:
        for name in list(processes):kill(name)
        for handle in handles:handle.close()
        fixture.shutdown();fixture.server_close()
        if containers:subprocess.run(['docker','rm','-f','-v',*containers],capture_output=True)
        (directory/'report.json').write_text(json.dumps(report,indent=2))
        print('Report: '+str(directory/'report.json'),flush=True)

if __name__=='__main__':main()
