"""Bounded metadata-only history. The legacy personal file is never rewritten."""
import contextlib,json,os,re,secrets,stat,threading
from pathlib import Path
import cc_job_logs

MAX_BYTES=2*1024*1024
LIMIT=200
_LOCK=threading.Lock()
FIELDS={'id','timestamp','documentType','documentId','orderId','status'}

class HistoryUnavailable(RuntimeError):pass

def safe(path):
    path=Path(path).absolute()
    if any(p.is_symlink() or (hasattr(p,'is_junction') and p.is_junction()) for p in (path,*path.parents)):raise HistoryUnavailable('Unsafe history path')
    return path

def metadata(entry):
    if not isinstance(entry,dict):raise HistoryUnavailable('Invalid history entry')
    value={k:entry.get(k) for k in FIELDS}
    if not re.fullmatch('[0-9a-f-]{8,36}',str(value['id'])) or value['documentType'] not in ('INVOICE','CORRECTION') or value['status']!='SENT':raise HistoryUnavailable('Invalid history metadata')
    if not isinstance(value['timestamp'],str) or not re.fullmatch(r'\d{4}-\d\d-\d\d \d\d:\d\d:\d\d',value['timestamp']):raise HistoryUnavailable('Invalid history timestamp')
    for name in ('documentId','orderId'):
        if type(value[name]) is not int or not 0<value[name]<=2**63-1:raise HistoryUnavailable('Invalid history identifier')
    return value

def read(path,legacy=False,root_fd=None):
    path=safe(path)
    try:fd=os.open(path.name if root_fd is not None else path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0),dir_fd=root_fd)
    except FileNotFoundError:return []
    with os.fdopen(fd,'rb') as source:
        info=os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or info.st_nlink!=1 or info.st_size>MAX_BYTES:raise HistoryUnavailable('History requires manual review')
        data=source.read(MAX_BYTES+1)
    if len(data)>MAX_BYTES:raise HistoryUnavailable('History budget exceeded')
    try:entries=json.loads(data)
    except Exception:raise HistoryUnavailable('Invalid history document') from None
    if not isinstance(entries,list) or len(entries)>LIMIT:raise HistoryUnavailable('History requires manual review')
    result=[]
    for entry in entries:
        if legacy and isinstance(entry,dict):
            entry=dict(entry)
            for key in ('documentId','orderId'):
                if isinstance(entry.get(key),str) and re.fullmatch('[1-9][0-9]{0,18}',entry[key]):entry[key]=int(entry[key])
        value=metadata(entry)
        if not legacy and set(entry)!=FIELDS:raise HistoryUnavailable('Unexpected personal history fields')
        result.append({**value,'legacyReviewRequired':True} if legacy else value)
    return result

@contextlib.contextmanager
def locked(directory):
    directory=safe(directory);directory.mkdir(parents=True,exist_ok=True)
    lock=safe(directory/'.dispatch-history-v2.lock')
    root_fd=cc_job_logs.open_root(directory) if cc_job_logs.supported() else None
    try:
      lock_fd=os.open(lock.name if root_fd is not None else lock,os.O_RDWR|os.O_CREAT|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_NONBLOCK',0),0o600,dir_fd=root_fd)
      with _LOCK,os.fdopen(lock_fd,'r+b') as handle:
        lock_info=os.fstat(handle.fileno())
        if lock_info.st_nlink!=1 or not stat.S_ISREG(lock_info.st_mode):raise HistoryUnavailable('Unsafe lock')
        if os.name=='posix':
            import fcntl
            fcntl.flock(handle,fcntl.LOCK_EX)
        else:
            import msvcrt
            if os.fstat(handle.fileno()).st_size==0:handle.write(b'0');handle.flush()
            handle.seek(0);msvcrt.locking(handle.fileno(),msvcrt.LK_LOCK,1)
        try:
            if root_fd is not None:cc_job_logs.root_current(directory,root_fd)
            yield root_fd
        finally:
            if os.name=='posix':fcntl.flock(handle,fcntl.LOCK_UN)
            else:handle.seek(0);msvcrt.locking(handle.fileno(),msvcrt.LK_UNLCK,1)
    finally:
        if root_fd is not None:os.close(root_fd)

def load(directory,legacy_path):
    if Path(legacy_path).absolute().parent!=Path(directory).absolute():raise HistoryUnavailable('Legacy history must belong to runtime root')
    with locked(directory) as fd:return read(Path(directory)/'dispatch-history-v2.json',root_fd=fd)+read(legacy_path,legacy=True,root_fd=fd)

def append(directory,entry):
    value=metadata(entry);directory=safe(directory)
    with locked(directory) as fd:
        target=safe(directory/'dispatch-history-v2.json');entries=read(target,root_fd=fd)
        entries=[value]+[old for old in entries if old['id']!=value['id']]
        temporary=directory/('.dispatch-'+secrets.token_hex(12)+'.tmp')
        try:
            raw=os.open(temporary.name if fd is not None else temporary,os.O_WRONLY|os.O_CREAT|os.O_EXCL|getattr(os,'O_NOFOLLOW',0),0o600,dir_fd=fd)
            with os.fdopen(raw,'wb') as output:
                output.write((json.dumps(entries[:LIMIT],ensure_ascii=False)+'\n').encode());output.flush();os.fsync(output.fileno())
            safe(directory);safe(target)
            if fd is not None:cc_job_logs.root_current(directory,fd)
            os.replace(temporary.name if fd is not None else temporary,target.name if fd is not None else target,src_dir_fd=fd,dst_dir_fd=fd)
            if os.name=='posix':
                os.fsync(fd)
        finally:
            try:os.unlink(temporary.name if fd is not None else temporary,dir_fd=fd)
            except FileNotFoundError:pass
    return value
