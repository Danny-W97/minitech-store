"""Control Center feature; runtime dependencies are supplied by the composition root."""
from __future__ import annotations
import os
import signal
import subprocess
import threading
import time
import uuid
from cc_job_logs import LogSafetyError
from cc_log_writer import CaptureBusy

def register_job(services, job: Job) -> None:
    with services.STATE_LOCK:
        if services.SHUTTING_DOWN.is_set(): raise services.ResourceLimitError('Panel jest zatrzymywany. Odśwież stronę po ponownym uruchomieniu.')
        running = sum(item.status in {'queued', 'running'} for item in services.JOBS.values())
        if running >= services.MAX_RUNNING_JOBS: raise services.ResourceLimitError('Osiągnięto limit równoległych zadań.')
        completed = sorted((item for item in services.JOBS.values() if item.status not in {'queued', 'running'}), key=lambda item: item.created_at)
        if job.id in services.JOBS or (len(services.JOBS) >= services.MAX_JOB_HISTORY and not completed):
            raise services.ResourceLimitError('Osiągnięto limit historii aktywnych zadań lub ID jest już użyte.')
        retention = getattr(services, 'LOG_RETENTION', None)
        writer = getattr(services, 'LOG_WRITER', None)
        try:
            if writer is not None:
                job.capture_reservation = writer.reserve()
            if retention is not None:
                job.retention_lease = retention.lease(job.id, job.log_path)
            if job.capture_reservation is not None and job.retention_lease is None:
                job.retention_lease = services.cc_job_logs.JobLease(services.JOB_ROOT, job.id)
        except (CaptureBusy, OSError, LogSafetyError) as error:
            if job.capture_reservation is not None:
                job.capture_reservation.close(); job.capture_reservation = None
            if isinstance(error, CaptureBusy): raise services.ResourceLimitError(str(error)) from None
            raise LogSafetyError('Cannot reserve job output') from None
        while len(services.JOBS) >= services.MAX_JOB_HISTORY and completed:
            previous = services.JOBS.pop(completed.pop(0).id, None)
            if previous is not None and previous.retention_lease is not None:
                previous.retention_lease.retire()
        if len(services.JOBS) >= services.MAX_JOB_HISTORY: raise services.ResourceLimitError('Osiągnięto limit historii aktywnych zadań.')
        services.JOBS[job.id] = job

def stop_terminal(services, session: TerminalSession) -> None:
    session.alive = False
    try: os.killpg(session.pid, signal.SIGTERM)
    except ProcessLookupError: pass

def terminate_job(services, process: subprocess.Popen) -> None:
    if os.name == 'posix':
        try: os.killpg(process.pid, signal.SIGTERM)
        except ProcessLookupError: pass
    else:
        process.terminate()
    try: process.wait(timeout=2)
    except subprocess.TimeoutExpired:
        if os.name == 'posix':
            try: os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError: pass
        else: process.kill()
        process.wait()
    if os.name == 'posix':
        # The shell may exit before a child that ignores TERM. Its owned group
        # must not outlive a timed-out task or a panel shutdown.
        try: os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError: pass

def run_job(services, job: Job, stdin_text: str) -> None:
    try:
        _run_job(services, job, stdin_text)
    except (OSError, LogSafetyError) as error:
        job.exit_code, job.status, job.finished_at = -1, 'failed', time.time()
        print('CC job log failed (' + type(error).__name__ + ')', flush=True)
    finally:
        lease = job.retention_lease
        if lease is not None and job.finished_at is not None:
            try:
                if job.log_capture is not None:
                    lease.delegated()
                else:
                    lease.mark_closed(job.finished_at)
            except OSError as error:
                print('CC job closure marker failed (' + type(error).__name__ + ')', flush=True)


def _run_job(context, job: Job, stdin_text: str) -> None:
    services = context
    writer = getattr(services, 'LOG_WRITER', None)
    if writer is not None and writer.enabled:
        return _run_bounded_job(services, job, stdin_text, writer)
    job.started_at = time.time()
    job.status = "running"
    lease = job.retention_lease
    if lease is None:
        services.JOB_ROOT.mkdir(parents=True, exist_ok=True)
    env = os.environ.copy()
    env["TERM"] = "dumb"
    with (lease.open_log() if lease is not None else job.log_path.open("w", encoding="utf-8")) as output:
        output.write(f"MiniTech Control · {job.title}\n$ {' '.join(job.command)}\n\n")
        output.flush()
        try:
            with services.STATE_LOCK:
                if services.SHUTTING_DOWN.is_set():
                    raise RuntimeError('Panel jest zatrzymywany; zadanie nie zostało uruchomione.')
                process = subprocess.Popen(
                    job.command,
                    cwd=services.PROJECT_ROOT,
                    stdin=subprocess.PIPE,
                    stdout=output,
                    stderr=subprocess.STDOUT,
                    text=True,
                    env=env,
                    start_new_session=os.name == 'posix',
                )
                job.process = process
            try:
                process.communicate(stdin_text if stdin_text else None, timeout=services.JOB_TIMEOUT_SECONDS)
            except subprocess.TimeoutExpired:
                services.terminate_job(process)
                output.write('\nPrzekroczono limit czasu zadania; zakończono jego procesy.\n')
                job.exit_code = -1
                job.status = 'failed'
                return
            job.exit_code = process.returncode
            job.status = "success" if process.returncode == 0 else "failed"
        except Exception as error:
            output.write(f"\nBłąd uruchomienia: {error}\n")
            job.exit_code = -1
            job.status = "failed"
        finally:
            job.finished_at = time.time()
            with services.STATE_LOCK:
                job.process = None

def _run_bounded_job(context, job, stdin_text, writer):
    job.started_at, job.status = time.time(), 'running'
    capture, note = None, ''
    try:
        with context.STATE_LOCK:
            if context.SHUTTING_DOWN.is_set():
                raise RuntimeError('Panel jest zatrzymywany; zadanie nie zostało uruchomione.')
            capture = writer.launch(job.retention_lease, job.capture_reservation,
                                    f"MiniTech Control · {job.title}\n$ {' '.join(job.command)}\n\n")
            job.capture_reservation = None; job.log_capture = capture
            env = os.environ.copy(); env['TERM'] = 'dumb'
            process = subprocess.Popen(job.command, cwd=context.PROJECT_ROOT, stdin=subprocess.PIPE,
                stdout=capture.write_fd, stderr=subprocess.STDOUT, text=True, env=env, start_new_session=True)
            job.process = process
            capture.spawned()
        try:
            process.communicate(stdin_text if stdin_text else None, timeout=context.JOB_TIMEOUT_SECONDS)
            job.exit_code = process.returncode
            job.status = 'success' if process.returncode == 0 else 'failed'
        except subprocess.TimeoutExpired:
            context.terminate_job(process)
            note = '\nPrzekroczono limit czasu zadania; zakończono jego procesy.\n'
            job.exit_code, job.status = -1, 'failed'
    except Exception as error:
        job.exit_code, job.status = -1, 'failed'
        note = '\nBłąd uruchomienia: ' + type(error).__name__ + '\n'
    finally:
        job.finished_at = time.time()
        with context.STATE_LOCK: job.process = None
        if capture is not None:
            capture.finish(note)
        elif job.capture_reservation is not None:
            job.capture_reservation.close(); job.capture_reservation = None


def start_terminal(services) -> TerminalSession:
    if services.pty is None or services.select is None:
        raise RuntimeError("Terminal PTY jest dostępny wyłącznie po uruchomieniu panelu w WSL/Linux.")
    with services.STATE_LOCK:
        if services.SHUTTING_DOWN.is_set(): raise services.ResourceLimitError('Panel jest zatrzymywany.')
        if sum(item.alive for item in services.TERMINALS.values()) >= services.MAX_TERMINALS:
            raise services.ResourceLimitError('Osiągnięto limit otwartych terminali.')
        completed = sorted((item for item in services.TERMINALS.values() if not item.alive), key=lambda item: item.created_at)
        while len(services.TERMINALS) >= services.MAX_TERMINALS * 4 and completed:
            services.TERMINALS.pop(completed.pop(0).id, None)
        pid, fd = services.pty.fork()
        if pid == 0:
            os.chdir(services.PROJECT_ROOT)
            os.execvp("bash", ["bash", str(services.CONTROL_SCRIPT)])
        session = services.TerminalSession(uuid.uuid4().hex[:12], pid, fd)
        services.TERMINALS[session.id] = session

    def reader() -> None:
        while session.alive:
            try:
                now = time.monotonic()
                if now - session.last_activity >= services.TERMINAL_IDLE_SECONDS or now - session.created_at >= services.TERMINAL_LIFETIME_SECONDS:
                    services.stop_terminal(session)
                    break
                ready, _, _ = services.select.select([fd], [], [], 0.5)
                if not ready:
                    try:
                        ended, _ = os.waitpid(pid, os.WNOHANG)
                        if ended:
                            session.alive = False
                    except ChildProcessError:
                        session.alive = False
                    continue
                chunk = os.read(fd, 8192)
                if not chunk:
                    session.alive = False
                    break
                with session.lock:
                    session.last_activity = time.monotonic()
                    session.output.extend(chunk)
                    if len(session.output) > 500_000:
                        del session.output[:-400_000]
            except OSError:
                session.alive = False
                break
        session.alive = False
        try: os.close(fd)
        except OSError: pass
        # PTY children own their process group. Reap them and bound shutdown time.
        try:
            os.killpg(pid, signal.SIGTERM)
        except ProcessLookupError:
            pass
        deadline = time.monotonic() + 2
        while True:
            try:
                ended, _ = os.waitpid(pid, os.WNOHANG)
                if ended: break
                if time.monotonic() >= deadline:
                    try: os.killpg(pid, signal.SIGKILL)
                    except ProcessLookupError: pass
                    os.waitpid(pid, 0)
                    break
                time.sleep(.05)
            except ChildProcessError: break

    threading.Thread(target=reader, daemon=True).start()
    return session

def shutdown_jobs(services) -> None:
    with services.STATE_LOCK:
        services.SHUTTING_DOWN.set()
        processes = [job.process for job in services.JOBS.values() if job.process is not None]
        jobs = list(services.JOBS.values())
    for process in processes:
        services.terminate_job(process)
    for job in jobs:
        if job.retention_lease is not None:
            job.retention_lease.retire()
        if job.log_capture is None and job.capture_reservation is not None:
            job.capture_reservation.close(); job.capture_reservation = None

def shutdown_terminals(services) -> None:
    with services.STATE_LOCK:
        sessions = [session for session in services.TERMINALS.values() if session.alive]
    for session in sessions:
        services.stop_terminal(session)
    # Reload uses exec(), so reader threads cannot finish cleanup afterwards.
    # Give them time to reap PTYs, then kill any remaining owned process groups.
    deadline = time.monotonic() + 2
    pending = list(sessions)
    while pending and time.monotonic() < deadline:
        for session in list(pending):
            try: os.killpg(session.pid, 0)
            except ProcessLookupError: pending.remove(session)
        if pending: time.sleep(.05)
    for session in pending:
        try: os.killpg(session.pid, signal.SIGKILL)
        except ProcessLookupError: pass
