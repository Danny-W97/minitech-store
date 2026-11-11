"""Read a bounded sample of job output; cache diagnoses, never raw log text."""
from __future__ import annotations

import copy
import os
from pathlib import Path
import stat
import threading

from cc_job_logs import LogSafetyError, open_root, supported


def read_limit_from_env(values):
    limit = int(values.get('CC_JOB_LOG_READ_MAX_BYTES', '120000'))
    if not 4096 <= limit <= 1048576:
        raise ValueError('CC_JOB_LOG_READ_MAX_BYTES must be between 4096 and 1048576')
    return limit


def fingerprint(value):
    return value.st_dev, value.st_ino, value.st_size, value.st_mtime_ns, value.st_ctime_ns


def file_version(path):
    try:
        return fingerprint(Path(path).stat())
    except OSError:
        return None


def open_log(path):
    path = Path(path)
    flags = os.O_RDONLY | getattr(os, 'O_NONBLOCK', 0) | getattr(os, 'O_BINARY', 0)
    if supported():
        directory = open_root(path.parent)
        try:
            return os.open(path.name, flags | os.O_NOFOLLOW, dir_fd=directory)
        finally:
            os.close(directory)
    # Native Windows has no directory-handle API. Reject reparse points in all
    # existing components and compare the final file identity after opening.
    for item in (path, *path.parents):
        value = item.lstat()
        if stat.S_ISLNK(value.st_mode) or getattr(value, 'st_file_attributes', 0) & 0x400:
            raise LogSafetyError('Unsafe log path')
    before = path.lstat()
    fd = os.open(path, flags)
    after = os.fstat(fd)
    if (before.st_dev, before.st_ino) != (after.st_dev, after.st_ino):
        os.close(fd)
        raise LogSafetyError('Log changed while opening')
    return fd


def read_at(fd, offset, size):
    os.lseek(fd, offset, os.SEEK_SET)
    chunks = []
    remaining = size
    while remaining:
        data = os.read(fd, min(8192, remaining))
        if not data:
            break
        chunks.append(data)
        remaining -= len(data)
    return b''.join(chunks)


class JobOutputReader:
    def __init__(self):
        self._lock = threading.Lock()
        self._cache_key = self._cache_diagnosis = None

    @staticmethod
    def empty(limit, include_output):
        value = {'diagnosis': None, 'diagnosisSampled': None, 'outputReadLimitBytes': limit,
                 'outputReadLimited': None, 'outputReadChanged': None, 'outputReadError': None}
        if include_output:
            value['output'] = ''
        return value

    @staticmethod
    def notice(lang):
        return ('\n\n[CC: pominięto środkową część logu; pokazano ograniczony początek i koniec.]\n\n'
                if lang == 'pl' else '\n\n[CC: middle of log omitted; showing a bounded head and tail.]\n\n')

    def inspect(self, path, *, limit, failed, include_output, diagnose, rules_version, lang,
                clean=lambda text: text, expected_identity=None):
        if not 4096 <= limit <= 1048576:
            raise ValueError('Invalid log read limit')
        value = self.empty(limit, include_output)
        with self._lock:
            fd = None
            try:
                fd = open_log(path)
                before = os.fstat(fd)
                if not stat.S_ISREG(before.st_mode) or before.st_nlink != 1:
                    raise LogSafetyError('Unsafe log file')
                if expected_identity is not None and (before.st_dev, before.st_ino) != expected_identity:
                    raise LogSafetyError('Log identity changed')
                version = fingerprint(before)
                key = version, limit, rules_version
                limited = before.st_size > limit
                value.update(outputReadLimited=limited, outputReadChanged=False)
                if failed:
                    value['diagnosisSampled'] = limited
                if failed and not include_output and self._cache_key == key:
                    value['diagnosis'] = self._diagnosis(self._cache_diagnosis, limited)
                    return value
                if limited:
                    head_size = min(16384, limit // 4)
                    head = read_at(fd, 0, head_size)
                    tail = read_at(fd, before.st_size - (limit - head_size), limit - head_size)
                    text = head.decode('utf-8', errors='replace') + self.notice(lang) + tail.decode('utf-8', errors='replace')
                else:
                    text = read_at(fd, 0, min(before.st_size, limit)).decode('utf-8', errors='replace')
                changed = fingerprint(os.fstat(fd)) != version
                text = clean(text)
                value['outputReadChanged'] = changed
                if include_output:
                    value['output'] = text
                if failed:
                    diagnosis = (self._cache_diagnosis if not changed and self._cache_key == key
                                 else diagnose(text) if text else None)
                    value['diagnosisSampled'] = limited or changed
                    value['diagnosis'] = self._diagnosis(diagnosis, limited or changed)
                    if not changed:
                        self._cache_key, self._cache_diagnosis = key, copy.deepcopy(diagnosis)
            except FileNotFoundError:
                value['outputReadError'] = 'missing'
                self._cache_key = self._cache_diagnosis = None
            except (OSError, LogSafetyError):
                value['outputReadError'] = 'unavailable'
                self._cache_key = self._cache_diagnosis = None
            finally:
                if fd is not None:
                    os.close(fd)
        return value

    @staticmethod
    def _diagnosis(diagnosis, sampled):
        value = copy.deepcopy(diagnosis)
        if value is not None:
            value['sampled'] = sampled
            if sampled:
                cause = value.get('cause') if isinstance(value.get('cause'), dict) else {}
                value['cause'] = {
                    **cause,
                    'pl': cause.get('pl', '') + '\nDiagnoza opiera się na ograniczonej próbce logu.',
                    'en': cause.get('en', '') + '\nDiagnosis is based on a bounded log sample.',
                }
        return value
