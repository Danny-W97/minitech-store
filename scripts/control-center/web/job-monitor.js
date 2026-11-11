// Owns only job reads. Selecting another job never starts or repeats a command.
export function createJobMonitor({request, onSelect = () => {}, onData = () => {},
  onComplete = () => {}, onError = () => {}, onPending = () => {},
  setTimer = setTimeout, clearTimer = clearTimeout, interval = 1000}) {
  let current = null;
  let disposed = false;
  const statuses = new Set(['queued', 'running', 'success', 'failed']);

  function stop() {
    const previous = current;
    current = null;
    if (previous) {
      if (previous.timer !== null) clearTimer(previous.timer);
      previous.controller?.abort();
    }
    onPending(false);
  }

  function read(entry) {
    if (disposed || current !== entry) return Promise.resolve(false);
    if (entry.pending) return entry.pending;
    entry.controller = new AbortController();
    const isCurrent = () => !disposed && current === entry;
    onPending(true);
    entry.pending = Promise.resolve().then(async () => {
      try {
        if (!isCurrent()) return false;
        const job = await request(entry.id, {signal: entry.controller.signal});
        if (!isCurrent()) return false;
        if (job?.id !== entry.id || !statuses.has(job.status))
          throw new Error('Nieprawidłowa odpowiedź zadania / Invalid job response.');
        const initial = !entry.received;
        entry.received = true;
        const active = job.status === 'queued' || job.status === 'running';
        entry.wasActive ||= active;
        onData(job, {initial, isCurrent});
        if (!isCurrent()) return false;
        if (active) {
          entry.timer = setTimer(() => {
            entry.timer = null;
            read(entry);
          }, interval);
        } else if (!entry.notified && (entry.notifyCompletion || entry.wasActive)) {
          entry.notified = true;
          await onComplete(job, {isCurrent});
        }
        return true;
      } catch (error) {
        if (isCurrent()) onError(error);
        return false;
      } finally {
        entry.pending = null;
        if (isCurrent()) onPending(false);
      }
    });
    return entry.pending;
  }

  function watch(id, {notifyCompletion = false} = {}) {
    if (disposed || typeof id !== 'string' || !id.trim()) return Promise.resolve(false);
    if (current?.id === id) {
      current.notifyCompletion ||= notifyCompletion;
      // Repeated clicks share an in-flight read or the already scheduled poll.
      if (current.pending) return current.pending;
      if (current.timer !== null) return Promise.resolve(false);
      return read(current); // Explicit selection can recover a failed read.
    }
    stop();
    const entry = {id, timer: null, controller: null, pending: null,
      received: false, wasActive: false, notified: false, notifyCompletion};
    current = entry;
    onSelect(id);
    return read(entry);
  }

  return Object.freeze({watch, stop, dispose() {disposed = true; stop();},
    get selectedId() {return current?.id ?? null;},
    get busy() {return !!current?.pending;}, get disposed() {return disposed;}});
}
