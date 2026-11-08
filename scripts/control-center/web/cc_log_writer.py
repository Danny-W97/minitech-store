"""Bounded job stdout capture with detached sinks and cross-reload slots."""
from __future__ import annotations

from dataclasses import dataclass
import json
import os
from pathlib import Path
import socket
import select
import signal
import stat
import subprocess
import sys
import threading
import time

from cc_job_logs import LogSafetyError, open_root, supported


class CaptureBusy(RuntimeError):
    pass


@dataclass(frozen=True)
class WriteConfig:
    mode: str = 'auto'
    max_bytes: int = 1048576

    def __post_init__(self):
        if self.mode not in {'auto', 'bounded', 'direct'} or not 65536 <= self.max_bytes <= 16777216:
            raise ValueError('Invalid CC job log write settings')

    @classmethod
    def from_env(cls, values):
        return cls(values.get('CC_JOB_LOG_WRITE_MODE', 'auto'),
                   int(values.get('CC_JOB_LOG_WRITE_MAX_BYTES', '1048576')))


def capture_supported():
    if not (sys.platform.startswith('linux') and supported() and hasattr(socket, 'SOCK_SEQPACKET')
            and hasattr(os, 'pidfd_open') and hasattr(signal, 'pidfd_send_signal')):
        return False
    try:
        fd = os.pidfd_open(os.getpid()); os.close(fd)
        return True
    except OSError:
        return False


class SinkHandle:
    def __init__(self, pid):
        self.lock = threading.RLock()
        self.pid, self.fd, self.returncode = pid, None, None
        self.fd = os.pidfd_open(pid)

    def poll(self):
        with self.lock:
            if self.fd is not None and select.select([self.fd], [], [], 0)[0]:
                self.close(); self.returncode = 0
            return self.returncode

    def wait(self, timeout):
        with self.lock:
            if self.fd is not None and not select.select([self.fd], [], [], timeout)[0]:
                raise subprocess.TimeoutExpired('cc-log-sink', timeout)
            self.poll()
            return self.returncode

    def terminate(self):
        with self.lock:
            if self.fd is not None:
                try: signal.pidfd_send_signal(self.fd, signal.SIGTERM)
                except ProcessLookupError: pass

    def close(self):
        with self.lock:
            if self.fd is not None:
                os.close(self.fd); self.fd = None

    def __del__(self):
        self.close()


class Reservation:
    def __init__(self, directory, fd):
        self.directory, self.fd = directory, fd

    def close(self):
        for name in ('fd', 'directory'):
            fd = getattr(self, name)
            if fd is not None:
                os.close(fd); setattr(self, name, None)

    def __del__(self):
        self.close()


class Capture:
    def __init__(self, process, connection, write_fd, limit):
        self.process, self.connection, self.write_fd = process, connection, write_fd
        self.lock = threading.Lock()
        self.parent_done = False
        self.state = {'truncated': False, 'droppedBytes': 0, 'error': None, 'complete': False, 'limitBytes': limit}

    def _receive(self):
        if self.connection is not None:
            for _ in range(256):
                try:
                    data = self.connection.recv(2049)
                except (BlockingIOError, OSError):
                    break
                if not data: break
                try:
                    value = json.loads(data)
                    if (len(data) <= 2048 and set(value) == set(self.state)
                            and type(value['truncated']) is bool and type(value['complete']) is bool
                            and type(value['droppedBytes']) is int and value['droppedBytes'] >= 0
                            and value['limitBytes'] == self.state['limitBytes']
                            and (value['error'] is None or isinstance(value['error'], str))):
                        self.state = value
                except (ValueError, TypeError):
                    pass

    def refresh(self):
        with self.lock:
            self._receive()
            ended = self.process.poll()
            if ended is not None:
                self._receive()
                if not self.state['complete']:
                    self.state['error'] = 'SinkExited'
                if self.connection is not None:
                    self.connection.close(); self.connection = None
            return ended

    def snapshot(self):
        self.refresh()
        with self.lock: return dict(self.state)

    def spawned(self):
        if self.write_fd is not None:
            os.close(self.write_fd); self.write_fd = None

    def finish(self, note=''):
        with self.lock:
            if self.parent_done: return
            self.parent_done = True
            if self.connection is not None:
                message = json.dumps({'done': True, 'note': note[:256]}, separators=(',', ':')).encode()
                try: self.connection.send(message)
                except OSError: self.state['error'] = self.state['error'] or 'ControlUnavailable'
        self.spawned()
        # Foreground logs are normally closed immediately; descendants may keep stdout.
        deadline = time.monotonic() + .5
        while self.process.poll() is None and time.monotonic() < deadline:
            self.refresh(); time.sleep(.01)
        self.refresh()

    def detach(self):
        with self.lock:
            if self.connection is not None:
                self.connection.close(); self.connection = None


class CaptureManager:
    slot_count = 8

    def __init__(self, root, config):
        self.root, self.config = Path(root), config
        available = capture_supported()
        if config.mode == 'bounded' and not available:
            raise ValueError('Bounded job logs require Linux/WSL')
        self.enabled = available and config.mode != 'direct'
        self._lock, self._stop = threading.Lock(), threading.Event()
        self._captures, self._thread = [], None

    def reserve(self):
        if not self.enabled: return None
        import fcntl
        directory = open_root(self.root, create=True)
        try:
            for index in range(self.slot_count):
                fd = os.open('.capture-slot-' + str(index), os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW,
                             0o600, dir_fd=directory)
                try:
                    value = os.fstat(fd)
                    if not stat.S_ISREG(value.st_mode) or value.st_nlink != 1:
                        raise LogSafetyError('Unsafe capture slot')
                    try: fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    except BlockingIOError:
                        os.close(fd); continue
                    return Reservation(directory, fd)
                except BaseException:
                    os.close(fd); raise
            raise CaptureBusy('OsiÄ…gniÄ™to limit 8 aktywnych odbiornikĂłw stdout. Poczekaj na zakoĹ„czenie pisarzy.')
        except BaseException:
            os.close(directory); raise

    def status(self):
        return {'configuredMode': self.config.mode, 'effectiveMode': 'bounded' if self.enabled else 'direct',
                'limitBytes': self.config.max_bytes if self.enabled else None,
                'slotCapacity': self.slot_count if self.enabled else None}

    def launch(self, lease, reservation, header):
        stream = lease.open_log()
        read_fd = write_fd = None
        parent = child = None
        try:
            encoded = header.encode('utf-8')
            prefix = encoded[:4096]
            stream.buffer.write(prefix); stream.buffer.flush()
            read_fd, write_fd = os.pipe()
            parent, child = socket.socketpair(socket.AF_UNIX, socket.SOCK_SEQPACKET)
            for endpoint in (parent, child):
                endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 8192)
                endpoint.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 8192)
            parent.settimeout(3)
            descriptors = (read_fd, stream.fileno(), lease.fd, lease.directory_fd, reservation.fd, child.fileno())
            process = subprocess.Popen(
                [sys.executable, '-c', 'from cc_log_sink import detached; detached()', *map(str, descriptors),
                 str(self.config.max_bytes), str(len(encoded)), str(self.root), lease.job_id],
                cwd=Path(__file__).resolve().parent, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL, pass_fds=descriptors, start_new_session=True,
                env={'PYTHONUTF8': '1'})
            try:
                process.wait(timeout=3)
                ready = json.loads(parent.recv(256))
                if set(ready) != {'ready'} or type(ready['ready']) is not int:
                    raise LogSafetyError('Invalid sink handshake')
                handle = SinkHandle(ready['ready'])
                parent.send(b'start')
                parent.setblocking(False)
            except BaseException:
                if process.poll() is None: process.terminate(); process.wait(timeout=3)
                raise
            capture = Capture(handle, parent, write_fd, self.config.max_bytes)
            parent = None; write_fd = None
            with self._lock: self._captures.append(capture)
            return capture
        finally:
            stream.close(); reservation.close()
            for fd in (read_fd, write_fd):
                if fd is not None: os.close(fd)
            if parent is not None: parent.close()
            if child is not None: child.close()

    def start(self):
        if not self.enabled or self._thread is not None: return
        def reap():
            while not self._stop.wait(.1):
                with self._lock:
                    self._captures = [item for item in self._captures if item.refresh() is None]
        self._thread = threading.Thread(target=reap, name='cc-log-sink-reaper', daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread is not None: self._thread.join(timeout=1)
        with self._lock:
            for item in self._captures:
                item.refresh(); item.detach()
            self._captures.clear()
