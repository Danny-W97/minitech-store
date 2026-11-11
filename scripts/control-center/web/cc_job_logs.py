"""Bounded retention of certified, unreferenced CC job logs (POSIX/WSL)."""
from __future__ import annotations

from dataclasses import dataclass
import errno
import json
import math
import os
from pathlib import Path
import re
import stat
import threading
import time


class LogSafetyError(RuntimeError):
    pass


def supported():
    return os.name == 'posix' and hasattr(os, 'O_NOFOLLOW') and os.open in os.supports_dir_fd


def open_root(path, create=False):
    """Walk from / using directory handles; no ancestor may be a symlink."""
    absolute = Path(os.path.abspath(path))
    if '..' in Path(path).parts or absolute == Path('/'):
        raise LogSafetyError('Invalid job log root')
    fd = os.open('/', os.O_RDONLY | os.O_DIRECTORY)
    try:
        for part in absolute.parts[1:]:
            try:
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            except FileNotFoundError:
                if not create:
                    raise
                try:
                    os.mkdir(part, mode=0o700, dir_fd=fd)
                except FileExistsError:
                    pass
                child = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=fd)
            os.close(fd)
            fd = child
        return fd
    except BaseException:
        os.close(fd)
        raise


def same_file(a, b):
    return (a.st_dev, a.st_ino, a.st_mode, a.st_nlink, a.st_size, a.st_mtime_ns, a.st_ctime_ns) == (
        b.st_dev, b.st_ino, b.st_mode, b.st_nlink, b.st_size, b.st_mtime_ns, b.st_ctime_ns)


def root_current(root, fd):
    current = open_root(root)
    try:
        a, b = os.fstat(current), os.fstat(fd)
        if (a.st_dev, a.st_ino) != (b.st_dev, b.st_ino):
            raise LogSafetyError('Job log directory changed')
    finally:
        os.close(current)


@dataclass(frozen=True)
class LogRetentionConfig:
    mode: str = 'observe'
    days: int = 14
    batch_size: int = 100
    scan_limit: int = 500
    interval_seconds: int = 600

    def __post_init__(self):
        if (self.mode not in {'off', 'observe', 'purge'} or not 1 <= self.days <= 3650
                or not 1 <= self.batch_size <= 1000 or not 1 <= self.scan_limit <= 10000
                or not 1 <= self.interval_seconds <= 86400):
            raise ValueError('Invalid CC job log retention settings')

    @classmethod
    def from_env(cls, values):
        return cls(values.get('CC_JOB_LOG_RETENTION_MODE', 'observe'),
                   int(values.get('CC_JOB_LOG_RETENTION_DAYS', '14')),
                   int(values.get('CC_JOB_LOG_RETENTION_BATCH_SIZE', '100')),
                   int(values.get('CC_JOB_LOG_RETENTION_SCAN_LIMIT', '500')),
                   int(values.get('CC_JOB_LOG_RETENTION_INTERVAL_SECONDS', '600')))


class JobLease:
    """Protect retained history; a separate log lock follows inherited stdout."""
    def __init__(self, root, job_id):
        import fcntl
        if not re.fullmatch(r'[0-9a-f]{12}', job_id):
            raise LogSafetyError('Unexpected job log identity')
        self.root, self.job_id = Path(root), job_id
        self.directory_fd = self.fd = None
        self.history, self.finished = True, False
        self.log_identity = None
        self.lock = threading.Lock()
        try:
            self.directory_fd = open_root(root, create=True)
            # A UUID collision must not overwrite either an old log or a live lease.
            try:
                os.stat(job_id + '.log', dir_fd=self.directory_fd, follow_symlinks=False)
            except FileNotFoundError:
                pass
            else:
                raise LogSafetyError('Job log already exists')
            self.fd = os.open(job_id + '.state', os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                              0o600, dir_fd=self.directory_fd)
            fcntl.flock(self.fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            self._write(None)
        except BaseException:
            self.close()
            raise

    def _write(self, closed_at):
        data = json.dumps({'version': 1, 'id': self.job_id, 'closedAt': closed_at}, separators=(',', ':')).encode()
        os.lseek(self.fd, 0, os.SEEK_SET)
        os.ftruncate(self.fd, 0)
        view = memoryview(data)
        while view:
            count = os.write(self.fd, view)
            view = view[count:]

    def open_log(self):
        import fcntl
        root_current(self.root, self.directory_fd)
        fd = os.open(self.job_id + '.log', os.O_RDWR | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW,
                     0o600, dir_fd=self.directory_fd)
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            value = os.fstat(fd)
            self.log_identity = (value.st_dev, value.st_ino)
            return os.fdopen(fd, 'w', encoding='utf-8')
        except BaseException:
            os.close(fd)
            raise

    def mark_closed(self, finished_at):
        with self.lock:
            try:
                if self.log_identity is not None:
                    value = os.stat(self.job_id + '.log', dir_fd=self.directory_fd, follow_symlinks=False)
                    if stat.S_ISREG(value.st_mode) and value.st_nlink == 1 and (value.st_dev, value.st_ino) == self.log_identity:
                        self._write(finished_at)
            finally:
                self.finished = True
                if not self.history:
                    self.close()

    def retire(self):
        with self.lock:
            self.history = False
            if self.finished:
                self.close()

    def delegated(self):
        """The sink certifies closure; this process retains only history."""
        with self.lock:
            self.finished = True
            if not self.history:
                self.close()

    def close(self):
        # Locks disappear when the last description closes; no forced unlock.
        for name in ('fd', 'directory_fd'):
            fd = getattr(self, name, None)
            if fd is not None:
                os.close(fd)
                setattr(self, name, None)

    def __del__(self):
        self.close()


class JobLogRetention:
    names = re.compile(r'[0-9a-f]{12}\.state\Z')
    ids = re.compile(r'[0-9a-f]{12}\Z')

    def __init__(self, root, config):
        self.root, self.config = Path(os.path.abspath(root)), config
        self.supported = supported()
        if config.mode == 'purge' and not self.supported:
            raise ValueError('CC log purge requires POSIX/WSL file locks and directory handles')
        self._stop = threading.Event()
        self._cycle_lock, self._state_lock = threading.Lock(), threading.Lock()
        self._iterator = self._directory_fd = self._thread = None
        self._state = {'initialized': True, 'mode': config.mode, 'supported': self.supported,
                       'retentionDays': config.days, 'batchSize': config.batch_size,
                       'scanLimit': config.scan_limit, 'intervalSeconds': config.interval_seconds,
                       'lastSuccessAt': None, 'lastFailureAt': None, 'lastError': None,
                       'runs': 0, 'failures': 0, 'deletedTotal': 0, 'deletedBytesTotal': 0,
                       'removedMarkersTotal': 0, 'lastRun': None}

    def lease(self, job_id, log_path):
        if not self.supported or self.config.mode == 'off':
            return None
        if not self.ids.fullmatch(job_id) or Path(os.path.abspath(log_path)) != self.root / (job_id + '.log'):
            raise LogSafetyError('Unexpected job log identity or path')
        try:
            return JobLease(self.root, job_id)
        except OSError as error:
            raise LogSafetyError('Cannot reserve job log (' + type(error).__name__ + ')') from None

    def snapshot(self):
        with self._state_lock:
            return {**self._state, 'lastRun': dict(self._state['lastRun']) if self._state['lastRun'] else None}

    def _close_scan(self):
        if self._iterator is not None:
            self._iterator.close()
            self._iterator = None
        if self._directory_fd is not None:
            os.close(self._directory_fd)
            self._directory_fd = None

    def _candidate(self, name, cutoff, counts):
        import fcntl
        directory = self._directory_fd
        before = os.stat(name, dir_fd=directory, follow_symlinks=False)
        if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1 or before.st_size > 512:
            counts['skippedUnknown'] += 1
            return
        fd = os.open(name, os.O_RDWR | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        log_fd = None
        try:
            state = os.fstat(fd)
            if not stat.S_ISREG(state.st_mode) or state.st_nlink != 1 or state.st_size > 512:
                counts['skippedUnknown'] += 1
                return
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                counts['skippedActive'] += 1
                return
            data = json.loads(os.read(fd, 513))
            job_id, closed = name[:-6], data.get('closedAt')
            if (set(data) != {'version', 'id', 'closedAt'} or type(data['version']) is not int or data['version'] != 1
                    or data['id'] != job_id or type(closed) not in (int, float) or not math.isfinite(closed) or closed <= 0):
                counts['skippedUnknown'] += 1
                return
            if closed >= cutoff or state.st_mtime >= cutoff:
                return
            log_name = job_id + '.log'
            try:
                log_fd = os.open(log_name, os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
                log = os.fstat(log_fd)
            except FileNotFoundError:
                log = None
            if log is not None:
                if not stat.S_ISREG(log.st_mode) or log.st_nlink != 1:
                    counts['skippedUnknown'] += 1
                    return
                try:
                    fcntl.flock(log_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                except BlockingIOError:
                    counts['skippedActive'] += 1
                    return
                if log.st_mtime >= cutoff:
                    return
                counts['eligibleFiles'] += 1
                counts['eligibleBytes'] += log.st_size
            else:
                counts['orphanMarkers'] += 1
            if self.config.mode != 'purge':
                return
            root_current(self.root, directory)
            if not same_file(state, os.stat(name, dir_fd=directory, follow_symlinks=False)):
                counts['skippedChanged'] += 1
                return
            if log is not None:
                if not same_file(log, os.stat(log_name, dir_fd=directory, follow_symlinks=False)):
                    counts['skippedChanged'] += 1
                    return
                os.unlink(log_name, dir_fd=directory)
                counts['deletedFiles'] += 1
                counts['deletedBytes'] += log.st_size
            counts['changedRecords'] += 1
            os.unlink(name, dir_fd=directory)
            counts['removedMarkers'] += 1
        except (ValueError, TypeError, AttributeError, OverflowError):
            counts['skippedUnknown'] += 1
        finally:
            if log_fd is not None:
                os.close(log_fd)
            os.close(fd)

    def run_once(self):
        if self.config.mode == 'off' or not self.supported:
            return
        with self._cycle_lock:
            now, deadline = time.time(), time.monotonic() + 2
            counts = {key: 0 for key in ('scannedEntries', 'eligibleFiles', 'eligibleBytes', 'deletedFiles', 'deletedBytes',
                                        'removedMarkers', 'orphanMarkers', 'skippedActive', 'skippedUnknown', 'skippedChanged', 'changedRecords')}
            counts['scanComplete'] = False
            error = None
            try:
                if self._iterator is None:
                    try:
                        self._directory_fd = open_root(self.root)
                    except FileNotFoundError:
                        counts['scanComplete'] = True
                    else:
                        self._iterator = os.scandir(self._directory_fd)
                if self._iterator is not None:
                    root_current(self.root, self._directory_fd)
                    while (counts['scannedEntries'] < self.config.scan_limit and counts['changedRecords'] < self.config.batch_size
                           and time.monotonic() < deadline and not self._stop.is_set()):
                        entry = next(self._iterator, None)
                        if entry is None:
                            counts['scanComplete'] = True
                            self._close_scan()
                            break
                        counts['scannedEntries'] += 1
                        if not self.names.fullmatch(entry.name):
                            continue
                        try:
                            self._candidate(entry.name, now - self.config.days * 86400, counts)
                        except FileNotFoundError:
                            counts['skippedChanged'] += 1
                        except OSError as candidate_error:
                            # Symlinks/non-files are protected, but permission/I/O failures must be visible.
                            if candidate_error.errno in (errno.ELOOP, errno.ENXIO):
                                counts['skippedUnknown'] += 1
                            else:
                                raise
            except (OSError, LogSafetyError) as failure:
                error = type(failure).__name__
                self._close_scan()
            finally:
                with self._state_lock:
                    self._state['runs'] += 1
                    self._state['deletedTotal'] += counts['deletedFiles']
                    self._state['deletedBytesTotal'] += counts['deletedBytes']
                    self._state['removedMarkersTotal'] += counts['removedMarkers']
                    if error:
                        self._state['failures'] += 1
                        self._state['lastFailureAt'] = now
                        self._state['lastError'] = error
                    else:
                        self._state['lastSuccessAt'] = now
                        self._state['lastError'] = None
                        self._state['lastRun'] = counts

    def start(self):
        if self.config.mode == 'off' or not self.supported or self._thread is not None:
            return
        def worker():
            try:
                while not self._stop.is_set():
                    self.run_once()
                    self._stop.wait(self.config.interval_seconds)
            finally:
                with self._cycle_lock:
                    self._close_scan()
        self._thread = threading.Thread(target=worker, name='cc-job-log-retention', daemon=True)
        self._thread.start()

    def stop(self):
        self._stop.set()
        if self._thread is not None:
            self._thread.join(timeout=3)
            if self._thread.is_alive():
                return
        with self._cycle_lock:
            self._close_scan()
