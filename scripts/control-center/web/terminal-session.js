/** Single terminal read loop. Writes are never retried automatically. */
export function createTerminalSession({request, onSession = () => {}, onOutput = () => {},
  onAccepted = () => {}, onState = () => {}, onError = () => {}, setTimer = setTimeout, clearTimer = clearTimeout}) {
  let current = null, generation = 0, disposed = false, starting = false;
  let timer = null, reader = null, reading = null, write = null;
  const matches = session => !disposed && current === session;
  const publish = () => onState({starting, writing: !!write, alive: !!current?.alive && !disposed, disposed});
  function stopRead() {
    if (timer !== null) clearTimer(timer);
    timer = null; reader?.abort(); reader = null; reading = null;
  }
  function refresh() {
    if (disposed || starting || !current?.alive) return Promise.resolve(false);
    if (reading) return reading;
    if (timer !== null) clearTimer(timer);
    timer = null;
    const session = current, abort = new AbortController(); reader = abort;
    const task = Promise.resolve().then(async () => {
      try {
        if (!matches(session)) return false;
        const data = await request(`/api/terminal/${encodeURIComponent(session.id)}`, {signal: abort.signal});
        if (!matches(session)) return false;
        if (data?.id !== session.id || typeof data.alive !== 'boolean') throw Error('Invalid terminal response');
        session.alive = data.alive;
        onOutput(data.output || ''); publish();
        if (session.alive) timer = setTimer(() => {timer = null; refresh();}, 500);
        return true;
      } catch (error) {if (matches(session)) onError(error); return false;}
      finally {if (reading === task) {reading = null; reader = null;}}
    });
    reading = task; return task;
  }
  async function start() {
    if (disposed || starting) return false;
    const previous = current;
    const version = ++generation; starting = true; current = null; write = null; stopRead(); publish();
    try {
      const data = await request('/api/terminal', {method: 'POST', body: '{}'});
      if (disposed || generation !== version) return false;
      if (typeof data?.id !== 'string' || !data.id) throw Error('Invalid terminal response');
      current = {id: data.id, alive: true}; onSession(current.id);
    } catch (error) {
      if (!disposed && generation === version) {current = previous; onError(error); queueMicrotask(refresh);}
      return false;
    }
    finally {if (generation === version) {starting = false; publish();}}
    return refresh();
  }
  async function send(data) {
    if (disposed || starting || write || !current?.alive || !data) return false;
    const session = current, operation = {}; write = operation; publish();
    try {
      await request(`/api/terminal/${encodeURIComponent(session.id)}/input`, {method: 'POST', body: JSON.stringify({data})});
      if (!matches(session)) return false;
      onAccepted(data); refresh(); return true;
    } catch (error) {if (matches(session)) onError(error); return false;}
    finally {if (write === operation) {write = null; publish();}}
  }
  function dispose() {disposed = true; generation++; starting = false; current = null; write = null; stopRead(); publish();}
  return Object.freeze({start, refresh, send, dispose});
}
