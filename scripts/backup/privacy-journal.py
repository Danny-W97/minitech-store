#!/usr/bin/env python3
"""Independent, encrypted, signed privacy intents. No automatic key generation or deletion.

The expected head must come from independent current custody, never the backup.
An authenticated old journal is still old; signature verification alone is insufficient.
"""
import argparse,base64,contextlib,hashlib,importlib.util,json,os,re,secrets,subprocess,tempfile,uuid
from pathlib import Path
from urllib.request import Request,build_opener,HTTPRedirectHandler
from urllib.parse import urlsplit

ROOT=Path(__file__).resolve().parents[2]
ZERO='0'*64
MAX_BYTES=16384

def canonical(value):return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=True).encode('ascii')
def digest(value):return hashlib.sha256(value).hexdigest()
def strict(raw):
    if len(raw)>MAX_BYTES:raise ValueError('Journal record too large')
    def pairs(values):
        result={}
        for name,value in values:
            if name in result:raise ValueError('Duplicate journal field')
            result[name]=value
        return result
    return json.loads(raw,object_pairs_hook=pairs)
def identifier(value):
    if not isinstance(value,str) or str(uuid.UUID(value))!=value:raise ValueError('Noncanonical journal identifier')
    return value
def capsule_hash(c):
    if not isinstance(c,dict) or set(c)!={'schemaVersion','customerId','requestId','confirmedAt','accountPolicyHash','commercePolicyHash','financePolicyHash'} or type(c['schemaVersion']) is not int or c['schemaVersion']!=1:raise ValueError('Invalid privacy capsule')
    customer=c['customerId']
    if not isinstance(customer,str) or not re.fullmatch('[1-9][0-9]{0,18}',customer) or int(customer)>2**63-1:raise ValueError('Invalid privacy subject')
    identifier(c['requestId']);
    from datetime import datetime
    if not isinstance(c['confirmedAt'],str) or len(c['confirmedAt'])>35 or not c['confirmedAt'].endswith('Z') or datetime.fromisoformat(c['confirmedAt'].replace('Z','+00:00')).utcoffset().total_seconds()!=0:raise ValueError('Invalid confirmation time')
    policies=[]
    for name in ('accountPolicyHash','commercePolicyHash','financePolicyHash'):
        value=c[name]
        if value is not None and (not isinstance(value,str) or not re.fullmatch('[0-9a-f]{64}',value)):raise ValueError('Invalid privacy policy')
        policies.append(value or '-')
    return digest(('A43.37/account/1\n'+customer+'\n'+c['requestId']+'\n'+c['confirmedAt']+'\n'+'\n'.join(policies)+'\n').encode('ascii'))
def entry_frame(entry):return ('A43.37/entry/1\n'+identifier(entry['ledgerId'])+'\n'+str(entry['sequence'])+'\n'+entry['previousHash']+'\n'+capsule_hash(entry['capsule'])+'\n').encode('ascii')
def head_frame(head):return ('A43.37/head/1\n'+identifier(head['ledgerId'])+'\n'+str(head['sequence'])+'\n'+head['entryHash']+'\n').encode('ascii')
def command(*args,input=None):
    r=subprocess.run(args,input=input,capture_output=True,timeout=60)
    if r.returncode:raise RuntimeError('Journal command failed: '+Path(args[0]).name)
    return r.stdout
def path_safe(path):
    path=Path(path).absolute()
    if any(part.is_symlink() for part in [path,*path.parents]):raise ValueError('Journal symlink path forbidden')
    return path
def outside(path,parent):
    if path.resolve().is_relative_to(parent.resolve()) or parent.resolve().is_relative_to(path.resolve()):raise ValueError('Journal, keys and backup storage must be separate')
def sign(frame,key):
    with tempfile.TemporaryDirectory(prefix='privacy-signature-') as temporary:
        directory=Path(temporary);directory.chmod(0o700);message=directory/'message';message.write_bytes(frame);message.chmod(0o600)
        return base64.b64encode(command('openssl','pkeyutl','-sign','-rawin','-inkey',str(path_safe(key)),'-in',str(message))).decode('ascii')
def verify(frame,signature,key):
    try:raw=base64.b64decode(signature,validate=True)
    except Exception:raise ValueError('Invalid journal signature')
    if len(raw)!=64:raise ValueError('Invalid journal signature length')
    # OpenSSL Ed25519 one-shot APIs need a regular, seekable message file.
    with tempfile.TemporaryDirectory(prefix='privacy-signature-') as temporary:
        directory=Path(temporary);directory.chmod(0o700)
        message=directory/'message';sig=directory/'signature';message.write_bytes(frame);sig.write_bytes(raw)
        message.chmod(0o600);sig.chmod(0o600)
        command('openssl','pkeyutl','-verify','-rawin','-pubin','-inkey',str(path_safe(key)),'-sigfile',str(sig),'-in',str(message))
def receipt(entry):return base64.urlsafe_b64encode(canonical(entry)).rstrip(b'=').decode('ascii')

class Journal:
    def __init__(self,directory,identity,public_key,backup_root,signing_key=None,recipient=None):
        self.directory=path_safe(directory);self.identity=path_safe(identity);self.public_key=path_safe(public_key);self.backup_root=path_safe(backup_root)
        self.signing_key=path_safe(signing_key) if signing_key else None;self.recipient=recipient
        outside(self.directory,self.backup_root)
        for key in [self.identity,self.public_key,*([self.signing_key] if self.signing_key else [])]:outside(key,self.directory);outside(key,self.backup_root)
    @contextlib.contextmanager
    def locked(self):
        if not self.directory.is_dir():raise ValueError('Journal must be explicitly initialized')
        # Linux/WSL production CLI; the lock spans append, fsync and publication of the signed head.
        import fcntl
        lock=path_safe(self.directory/'.lock')
        with lock.open('a+b') as handle:
            lock.chmod(0o600);fcntl.flock(handle,fcntl.LOCK_EX)
            try:yield
            finally:fcntl.flock(handle,fcntl.LOCK_UN)
    def write(self,name,value):
        if not self.recipient:raise ValueError('Journal encryption recipient required')
        data=canonical(value)
        if len(data)>MAX_BYTES:raise ValueError('Journal record too large')
        encrypted=command('age','-r',self.recipient,input=data)
        temporary=self.directory/('.pending-'+secrets.token_hex(8))
        try:
            with temporary.open('xb') as output:temporary.chmod(0o600);output.write(encrypted);output.flush();os.fsync(output.fileno())
            os.replace(temporary,path_safe(self.directory/name))
            fd=os.open(self.directory,os.O_RDONLY)
            try:os.fsync(fd)
            finally:os.close(fd)
        finally:temporary.unlink(missing_ok=True)
    def read(self,name):
        path=path_safe(self.directory/name)
        if not path.is_file() or path.stat().st_size>MAX_BYTES+4096:raise ValueError('Missing or invalid encrypted journal record')
        return strict(command('age','-d','-i',str(self.identity),str(path)))
    def initialize(self,ledger_id):
        identifier(ledger_id)
        if not self.signing_key:raise ValueError('Journal signing key required')
        self.directory.mkdir(parents=True,mode=0o700,exist_ok=True);self.directory.chmod(0o700)
        with self.locked():
            if set(p.name for p in self.directory.iterdir())!={'.lock'}:raise ValueError('Journal initialization never overwrites existing files')
            head={'schemaVersion':1,'ledgerId':ledger_id,'sequence':0,'entryHash':ZERO};head['signature']=sign(head_frame(head),self.signing_key);verify(head_frame(head),head['signature'],self.public_key)
            self.write('head.json.age',head)
        return self.pin(head)
    @staticmethod
    def pin(head):return head['ledgerId']+':'+str(head['sequence'])+':'+head['entryHash']
    def load(self,expected_head=None):
        head=self.read('head.json.age')
        if set(head)!={'schemaVersion','ledgerId','sequence','entryHash','signature'} or type(head['schemaVersion']) is not int or head['schemaVersion']!=1 or type(head['sequence']) is not int or not 0<=head['sequence']<=100000 or not re.fullmatch('[0-9a-f]{64}',head['entryHash']):raise ValueError('Invalid journal head')
        verify(head_frame(head),head['signature'],self.public_key)
        if expected_head is not None and self.pin(head)!=expected_head:raise ValueError('Journal is not the independently pinned current head')
        expected={'.lock','head.json.age'}|{f'{n:020d}.json.age' for n in range(1,head['sequence']+1)}
        if {p.name for p in self.directory.iterdir()}!=expected:raise ValueError('Journal has missing, partial or unexpected records')
        previous=ZERO;entries=[];subjects={};requests={}
        for n in range(1,head['sequence']+1):
            entry=self.read(f'{n:020d}.json.age')
            if set(entry)!={'schemaVersion','ledgerId','sequence','previousHash','capsule','entryHash','signature'} or type(entry['schemaVersion']) is not int or entry['schemaVersion']!=1 or entry['ledgerId']!=head['ledgerId'] or type(entry['sequence']) is not int or entry['sequence']!=n or entry['previousHash']!=previous:raise ValueError('Broken journal chain')
            frame=entry_frame(entry)
            if digest(frame)!=entry['entryHash']:raise ValueError('Journal entry hash mismatch')
            verify(frame,entry['signature'],self.public_key);previous=entry['entryHash'];c=entry['capsule'];h=capsule_hash(c)
            if c['customerId'] in subjects and subjects[c['customerId']]!=h or c['requestId'] in requests and requests[c['requestId']]!=h:raise ValueError('Conflicting privacy identity or policy')
            subjects[c['customerId']]=h;requests[c['requestId']]=h;entries.append(entry)
        if previous!=head['entryHash']:raise ValueError('Journal head does not match its chain')
        return head,entries
    def append(self,capsule):
        with self.locked():
            head,entries=self.load()
            entry,updated=self._append_locked(capsule,head,self.index(entries))
            return entry,self.pin(updated)
    @staticmethod
    def index(entries):return {key:entry for entry in entries for key in [('customer',entry['capsule']['customerId']),('request',entry['capsule']['requestId'])]}
    def _append_locked(self,capsule,head,index):
        """Caller owns the lock and has verified this complete chain once for this run."""
        capsule_hash(capsule)
        if not self.signing_key:raise ValueError('Journal signing key required')
        keys=[('customer',capsule['customerId']),('request',capsule['requestId'])]
        for key in keys:
            if key in index:
                entry=index[key]
                if entry['capsule']!=capsule:raise ValueError('Journal identity cannot be rebound')
                return entry,head
        if head['sequence']>=100000:raise ValueError('Journal capacity requires an explicit migration; no records are removed')
        entry={'schemaVersion':1,'ledgerId':head['ledgerId'],'sequence':head['sequence']+1,'previousHash':head['entryHash'],'capsule':capsule}
        frame=entry_frame(entry);entry['entryHash']=digest(frame);entry['signature']=sign(frame,self.signing_key);verify(frame,entry['signature'],self.public_key)
        self.write(f"{entry['sequence']:020d}.json.age",entry)
        updated={'schemaVersion':1,'ledgerId':head['ledgerId'],'sequence':entry['sequence'],'entryHash':entry['entryHash']};updated['signature']=sign(head_frame(updated),self.signing_key)
        self.write('head.json.age',updated)
        for key in keys:index[key]=entry
        return entry,updated

def sync(journal,origin,token):
    uri=urlsplit(origin)
    if uri.username or uri.password or uri.query or uri.fragment or uri.path not in ('','/') or uri.scheme not in ('http','https') or uri.scheme=='http' and uri.hostname not in ('127.0.0.1','localhost','::1'):raise ValueError('Use a fixed HTTPS or loopback journal API origin')
    origin=origin.rstrip('/')
    class NoRedirect(HTTPRedirectHandler):
        def redirect_request(self,*args,**kwargs):return None
    opener=build_opener(NoRedirect())
    def http(path,body=None):
        request=Request(origin+path,data=canonical(body) if body is not None else None,headers={'Authorization':'Bearer '+token,'Content-Type':'application/json'},method='POST' if body is not None else 'GET')
        with opener.open(request,timeout=10) as response:
            raw=response.read(131073)
            if len(raw)>131072:raise ValueError('Journal API response too large')
            return json.loads(raw)
    cursor=0;count=0
    with journal.locked():
        head,entries=journal.load();index=journal.index(entries)
        while True:
            page=http('/customers/privacy-journal/requests?after='+str(cursor)+'&size=50')
            if page.get('schemaVersion')!=1 or not isinstance(page.get('requests'),list) or len(page['requests'])>50:raise ValueError('Invalid journal page')
            for row in page['requests']:
                entry,head=journal._append_locked(row['capsule'],head,index)
                response=http('/customers/privacy-journal/requests/'+entry['capsule']['requestId']+'/ack',{'receipt':receipt(entry)})
                if response.get('acknowledged') is not True:raise ValueError('Journal receipt not acknowledged')
                count+=1
            after=int(page['nextCustomerId'])
            if page.get('end') is True:break
            if after<=cursor:raise ValueError('Journal API cursor did not advance')
            cursor=after
        pin=journal.pin(head)
    return {'status':'PASS','acknowledgedRequests':count,'currentHead':pin,'externalHeadCustodyRequired':True}

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('command',choices=['init','verify','sync']);p.add_argument('--directory',type=Path,required=True);p.add_argument('--identity',type=Path,required=True);p.add_argument('--public-key',type=Path,required=True);p.add_argument('--backup-root',type=Path,required=True);p.add_argument('--signing-key',type=Path);p.add_argument('--recipient');p.add_argument('--ledger-id');p.add_argument('--expected-head');p.add_argument('--origin');args=p.parse_args()
    journal=Journal(args.directory,args.identity,args.public_key,args.backup_root,args.signing_key,args.recipient)
    if args.command=='init':result={'status':'PASS','currentHead':journal.initialize(args.ledger_id)}
    elif args.command=='verify':
        if not args.expected_head:raise ValueError('An independently supplied current head is required')
        head,entries=journal.load(args.expected_head);result={'status':'PASS','currentHead':journal.pin(head),'requests':len(entries)}
    else:
        token=os.environ.get('PLATFORM_OPERATOR_TOKEN','')
        if not token:raise ValueError('Operator credential required in environment')
        result=sync(journal,args.origin,token)
    print(json.dumps(result))

if __name__=='__main__':
    try:main()
    except Exception as error:raise SystemExit('Privacy journal failed: '+type(error).__name__)
