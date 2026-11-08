"""Detached, bounded stdout sink. Importing this module starts nothing."""
from __future__ import annotations

from collections import deque
import json
import os
import selectors
import socket
import sys
import time

from cc_job_logs import root_current


def write_all(fd, data):
    view = memoryview(data)
    while view:
        count = os.write(fd, view)
        if not count:
            raise OSError('Short log write')
        view = view[count:]


class RollingLog:
    marker = b'\n[CC: starsza czesc logu pominieta / earlier output omitted: write limit]\n'

    def __init__(self, fd, limit, header_size):
        self.fd, self.limit = fd, limit
        self.header = os.pread(fd, min(header_size, 4096), 0)
        self.size = len(self.header)
        self.keep = min(65536, (limit - len(self.header) - len(self.marker)) // 2)
        self.tail, self.tail_size, self.body_size = deque(), 0, 0
        self.truncated, self.dropped, self.error = header_size > len(self.header), max(0, header_size - len(self.header)), None

    def append(self, data):
        if self.error:
            self.dropped += len(data)
            return
        try:
            if self.size + len(data) > self.limit:
                kept = b''.join(self.tail)
                self.dropped += self.body_size - len(kept)
                self.truncated = True
                prefix = self.header + self.marker + kept
                os.ftruncate(self.fd, 0); os.lseek(self.fd, 0, os.SEEK_SET)
                write_all(self.fd, prefix)
                self.size, self.body_size = len(prefix), len(kept)
            write_all(self.fd, data)
            self.size += len(data); self.body_size += len(data)
            self.tail.append(data); self.tail_size += len(data)
            while self.tail_size > self.keep:
                first = self.tail.popleft()
                excess = self.tail_size - self.keep
                if excess < len(first):
                    self.tail.appendleft(first[excess:]); self.tail_size -= excess
                else:
                    self.tail_size -= len(first)
        except OSError as error:
            # A full disk must not stop draining the producer's stdout pipe.
            self.error = type(error).__name__
            self.dropped += len(data)
            self.tail.clear(); self.tail_size = 0

    def snapshot(self, complete=False):
        return {'truncated': self.truncated, 'droppedBytes': self.dropped,
                'error': self.error, 'complete': complete, 'limitBytes': self.limit}


def run(handshake=False):
    data_fd, log_fd, state_fd, directory_fd, slot_fd, ipc_fd, limit, header_size = map(int, sys.argv[1:9])
    root, job_id = sys.argv[9:11]
    connection = socket.socket(fileno=ipc_fd)
    if handshake:
        connection.settimeout(3)
        try:
            connection.send(json.dumps({'ready': os.getpid()}).encode())
            if connection.recv(256) != b'start':
                return
        except OSError:
            return
    connection.setblocking(False)
    os.set_blocking(data_fd, False)
    writer = RollingLog(log_fd, limit, header_size)
    selector = selectors.DefaultSelector()
    selector.register(data_fd, selectors.EVENT_READ, 'data')
    selector.register(connection, selectors.EVENT_READ, 'control')
    data_done = parent_done = False
    last_report = 0.0

    def report(complete=False):
        try:
            if complete: connection.settimeout(.5)
            connection.send(json.dumps(writer.snapshot(complete), separators=(',', ':')).encode())
        except (OSError, ValueError):
            pass

    try:
        while not (data_done and parent_done):
            for key, _ in selector.select(timeout=.2):
                if key.data == 'data':
                    try:
                        data = os.read(data_fd, 8192)
                    except BlockingIOError:
                        continue
                    if data:
                        writer.append(data)
                    else:
                        selector.unregister(data_fd); os.close(data_fd); data_fd = -1; data_done = True
                else:
                    try:
                        message = connection.recv(2049)
                    except BlockingIOError:
                        continue
                    if not message:
                        selector.unregister(connection); parent_done = True
                    else:
                        try:
                            command = json.loads(message)
                            if len(message) <= 2048 and command.get('done') is True:
                                note = command.get('note', '')
                                if isinstance(note, str): writer.append(note.encode('utf-8')[:1024])
                                parent_done = True
                                selector.unregister(connection)
                        except (ValueError, AttributeError):
                            pass
            if time.monotonic() - last_report >= .1:
                report(); last_report = time.monotonic()
        try:
            root_current(root, directory_fd)
            value = os.stat(job_id + '.log', dir_fd=directory_fd, follow_symlinks=False)
            opened = os.fstat(log_fd)
            if value.st_nlink == 1 and (value.st_dev, value.st_ino) == (opened.st_dev, opened.st_ino):
                marker = json.dumps({'version': 1, 'id': job_id, 'closedAt': time.time()}, separators=(',', ':')).encode()
                os.lseek(state_fd, 0, os.SEEK_SET); os.ftruncate(state_fd, 0); write_all(state_fd, marker)
        except (OSError, RuntimeError) as error:
            writer.error = writer.error or type(error).__name__
        report(complete=True)
    finally:
        selector.close(); connection.close()
        for fd in (data_fd, log_fd, state_fd, directory_fd, slot_fd):
            if fd >= 0:
                os.close(fd)


def detached():
    # The sink is adopted by init, so an exec/reload of CC cannot leave a zombie
    # child that the new Python process no longer knows how to reap.
    if os.fork(): os._exit(0)
    os.setsid()
    if os.fork(): os._exit(0)
    try:
        run(handshake=True)
    finally:
        os._exit(0)
