const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let createJobMonitor;
before(async () => ({createJobMonitor} = await import('data:text/javascript;base64,' +
  Buffer.from(fs.readFileSync('scripts/control-center/web/job-monitor.js', 'utf8')).toString('base64'))));
function deferred() {let resolve, reject; const promise = new Promise((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject};}
function fixture(request) {
  const timers = new Map(), calls = [], selections = [], data = [], completed = [], errors = [], pending = [];
  let nextTimer = 0;
  const monitor = createJobMonitor({request: (id, options) => {calls.push({id, signal: options.signal}); return request(id, options);},
    onSelect: id => selections.push(id), onData: job => data.push(job), onComplete: job => completed.push(job.id),
    onError: error => errors.push(error.message), onPending: value => pending.push(value),
    setTimer: (callback, delay) => {const key = ++nextTimer; timers.set(key, {callback, delay}); return key;},
    clearTimer: key => timers.delete(key)});
  async function tick() {
    assert.equal(timers.size, 1); const [key, timer] = timers.entries().next().value;
    timers.delete(key); timer.callback();
    await Promise.resolve(); await Promise.resolve(); await Promise.resolve();
  }
  return {monitor, timers, calls, selections, data, completed, errors, pending, tick};
}
test('queued and running jobs poll in sequence and notify completion once', async () => {
  const statuses = ['queued', 'running', 'success'];
  const f = fixture(async id => ({id, status: statuses.shift() || 'success'}));
  await f.monitor.watch('a'); assert.equal(f.timers.values().next().value.delay, 1000);
  await f.tick(); await f.tick();
  assert.deepEqual(f.data.map(job => job.status), ['queued', 'running', 'success']);
  assert.deepEqual(f.completed, ['a']); assert.equal(f.timers.size, 0);
  await f.monitor.watch('a'); assert.deepEqual(f.completed, ['a']);
});
test('repeated clicks share an in-flight read and do not create another timer', async () => {
  const held = deferred(); const f = fixture(() => held.promise);
  const first = f.monitor.watch('a'), second = f.monitor.watch('a');
  assert.equal(first, second); await Promise.resolve(); assert.equal(f.calls.length, 1);
  held.resolve({id: 'a', status: 'running'}); await first;
  await f.monitor.watch('a'); assert.equal(f.calls.length, 1); assert.equal(f.timers.size, 1);
});
test('switching jobs aborts the old fetch and rejects a non-cooperative late success', async () => {
  const old = deferred(); const f = fixture(id => id === 'a' ? old.promise : Promise.resolve({id, status: 'running'}));
  const first = f.monitor.watch('a'); await Promise.resolve();
  await f.monitor.watch('b'); assert.equal(f.calls[0].signal.aborted, true);
  old.resolve({id: 'a', status: 'success'}); assert.equal(await first, false);
  assert.deepEqual(f.data.map(job => job.id), ['b']); assert.deepEqual(f.completed, []);
  assert.equal(f.timers.size, 1); assert.equal(f.monitor.selectedId, 'b');
});
test('a late failure cannot report an error or clear the newer read pending state', async () => {
  const old = deferred(), current = deferred();
  const f = fixture(id => id === 'a' ? old.promise : current.promise);
  const first = f.monitor.watch('a'); await Promise.resolve(); const second = f.monitor.watch('b');
  await Promise.resolve(); const before = f.pending.length;
  old.reject(Error('old failure')); await first;
  assert.equal(f.monitor.busy, true); assert.equal(f.pending.length, before); assert.deepEqual(f.errors, []);
  current.resolve({id: 'b', status: 'success'}); await second;
});
test('switching removes the old scheduled poll and even an already queued callback stays inert', async () => {
  const f = fixture(async id => ({id, status: id === 'a' ? 'running' : 'success'}));
  await f.monitor.watch('a'); const oldCallback = [...f.timers.values()][0].callback;
  await f.monitor.watch('b'); assert.equal(f.timers.size, 0);
  oldCallback(); await Promise.resolve(); assert.deepEqual(f.calls.map(call => call.id), ['a', 'b']);
});
test('historical completed jobs do not notify but a newly created completed job does', async () => {
  const f = fixture(async id => ({id, status: 'success'}));
  await f.monitor.watch('history'); assert.deepEqual(f.completed, []);
  await f.monitor.watch('new', {notifyCompletion: true}); assert.deepEqual(f.completed, ['new']);
  await f.monitor.watch('new', {notifyCompletion: true}); assert.deepEqual(f.completed, ['new']);
});
test('failed jobs produce one completion and no further polls', async () => {
  const f = fixture(async id => ({id, status: 'failed'}));
  await f.monitor.watch('a', {notifyCompletion: true});
  assert.deepEqual(f.completed, ['a']); assert.equal(f.timers.size, 0); assert.deepEqual(f.errors, []);
});
test('read errors stop automatic polling and explicit selection retries only the read', async () => {
  let count = 0; const f = fixture(id => {if (++count === 1) throw Error('offline'); return {id, status: 'running'};});
  await f.monitor.watch('a'); assert.deepEqual(f.errors, ['offline']); assert.equal(f.timers.size, 0);
  assert.equal(f.monitor.busy, false); await f.monitor.watch('a');
  assert.equal(f.calls.length, 2); assert.equal(f.timers.size, 1);
});
test('invalid response IDs and statuses stop without updating the selected job', async () => {
  for (const job of [null, {}, {id: 'other', status: 'success'}, {id: 'a', status: 'unknown'}]) {
    const f = fixture(async () => job); await f.monitor.watch('a');
    assert.equal(f.data.length, 0); assert.equal(f.completed.length, 0);
    assert.equal(f.errors.length, 1); assert.equal(f.timers.size, 0);
  }
});
test('expiry/disposal aborts the current read and permanently blocks polling restart', async () => {
  const held = deferred(); const f = fixture(() => held.promise);
  const first = f.monitor.watch('a'); await Promise.resolve(); f.monitor.dispose();
  assert.equal(f.calls[0].signal.aborted, true); assert.equal(f.monitor.disposed, true);
  held.resolve({id: 'a', status: 'running'}); await first;
  assert.equal(await f.monitor.watch('b'), false); assert.equal(f.calls.length, 1);
  assert.equal(f.timers.size, 0); assert.equal(f.data.length, 0);
});
test('stop can be resumed and stale async completion callbacks can check ownership', async () => {
  let context; const held = deferred();
  const monitor = createJobMonitor({request: async id => ({id, status: 'success'}),
    onComplete: (job, value) => {context = value; return held.promise;}});
  const first = monitor.watch('a', {notifyCompletion: true}); await Promise.resolve(); await Promise.resolve();
  assert.equal(context.isCurrent(), true); monitor.stop(); assert.equal(context.isCurrent(), false);
  await monitor.watch('b'); held.resolve(); await first; assert.equal(monitor.selectedId, 'b');
});
test('invalid selections and a superseded selection before dispatch do not send requests', async () => {
  const f = fixture(async id => ({id, status: 'success'}));
  for (const id of ['', ' ', null, 1]) assert.equal(await f.monitor.watch(id), false);
  const old = f.monitor.watch('a'); await f.monitor.watch('b'); await old;
  assert.deepEqual(f.calls.map(call => call.id), ['b']);
});
