const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let createAssistantConversation;
before(async () => ({createAssistantConversation} = await import('data:text/javascript;base64,' +
  Buffer.from(fs.readFileSync('scripts/control-center/web/assistant-conversation.js', 'utf8')).toString('base64'))));
function deferred() {let resolve, reject; const promise = new Promise((a, b) => {resolve = a; reject = b;}); return {promise, resolve, reject};}
function fixture(request) {
  const starts = [], replies = [], errors = [], pending = [];
  const controller = createAssistantConversation({request, onStart: text => {starts.push(text); return text;},
    onReply: (reply, view) => replies.push([reply, view]), onError: error => errors.push(error.message),
    onPending: (busy, stopped) => pending.push([busy, stopped])});
  return {controller, starts, replies, errors, pending};
}

test('review cancellation never sends or adds a cancelled prompt to history', async () => {
  const bodies = []; let allow = false, cancelled = 0;
  const controller = createAssistantConversation({
    review: async body => allow ? {...body, reviewToken: 'reviewed'} : null,
    request: async body => {bodies.push(body); return {reply: 'accepted'};},
    onCancel: () => cancelled++,
  });
  assert.equal(await controller.send('cancelled'), false);
  assert.equal(cancelled, 1); assert.equal(bodies.length, 0);
  allow = true; assert.equal(await controller.send('accepted'), true);
  assert.deepEqual(bodies, [{message: 'accepted', history: [], reviewToken: 'reviewed'}]);
});

test('reset or expiry during a held preview blocks a late confirmation', async () => {
  for (const stop of [false, true]) {
    const held = deferred(); let calls = 0, signal;
    const controller = createAssistantConversation({
      review: (body, options) => {signal = options.signal; return held.promise;},
      request: async () => {calls++; return {reply: 'unexpected'};},
    });
    const sent = controller.send('private'); controller.reset({stop});
    assert.equal(signal.aborted, true); held.resolve({message: 'private', history: [], reviewToken: 'old'});
    assert.equal(await sent, false); assert.equal(calls, 0);
  }
});
test('one pending turn blocks duplicate sends and passes the current prompt separately from history', async () => {
  const held = deferred(); const calls = [];
  const f = fixture((body, options) => {calls.push({body, options}); return held.promise;});
  const first = f.controller.send('  First  ');
  assert.equal(f.controller.busy, true); assert.equal(await f.controller.send('Second'), false);
  assert.equal(calls.length, 1); assert.deepEqual(calls[0].body, {message: 'First', history: []});
  assert.ok(calls[0].options.signal instanceof AbortSignal);
  held.resolve({reply: 'Answer'}); assert.equal(await first, true);
  assert.deepEqual(f.replies, [['Answer', 'First']]); assert.equal(f.controller.busy, false);
});
test('only ten completed history messages are kept, preserving complete pairs', async () => {
  const calls = []; const f = fixture(async body => {calls.push(body); return {reply: 'A' + body.message};});
  for (let i = 0; i < 8; i++) await f.controller.send(String(i));
  assert.equal(calls[7].history.length, 10);
  assert.deepEqual(calls[7].history[0], {role: 'user', content: '2'});
  assert.deepEqual(calls[7].history.at(-1), {role: 'assistant', content: 'A6'});
  assert.equal(calls[7].history.filter(item => item.content === '7').length, 0);
});
test('clearing aborts transport and discards a late success even if cancellation is ignored', async () => {
  const old = deferred(); let signal; const calls = [];
  const f = fixture((body, options) => {calls.push(body); signal = options.signal; return calls.length === 1 ? old.promise : Promise.resolve({reply: 'New'});});
  const first = f.controller.send('Old'); f.controller.reset();
  assert.equal(signal.aborted, true); assert.equal(f.controller.busy, false);
  await f.controller.send('Fresh'); old.resolve({reply: 'Stale'}); assert.equal(await first, false);
  assert.deepEqual(calls[1].history, []); assert.deepEqual(f.replies, [['New', 'Fresh']]);
});
test('a late failure cannot show an error or unlock a newer pending request', async () => {
  const old = deferred(), current = deferred(); let count = 0;
  const f = fixture(() => ++count === 1 ? old.promise : current.promise);
  const first = f.controller.send('Old'); f.controller.reset(); const second = f.controller.send('Current');
  const before = f.pending.length; old.reject(Error('Late failure')); await first;
  assert.equal(f.controller.busy, true); assert.equal(f.pending.length, before); assert.deepEqual(f.errors, []);
  current.resolve({reply: 'Current answer'}); await second;
  assert.equal(f.controller.busy, false);
});
test('expiry aborts the request and reset cannot reactivate an expired conversation', async () => {
  const old = deferred(); let calls = 0, signal;
  const f = fixture((body, options) => {calls++; signal = options.signal; return old.promise;});
  const first = f.controller.send('Old'); f.controller.stop(); f.controller.reset();
  assert.equal(signal.aborted, true); assert.equal(f.controller.stopped, true);
  assert.equal(await f.controller.send('Rejected'), false); assert.equal(calls, 1);
  old.resolve({reply: 'Stale'}); await first;
  assert.deepEqual(f.replies, []); assert.deepEqual(f.pending.at(-1), [false, true]);
});
test('a failed turn displays one error and is excluded from subsequent context', async () => {
  let calls = 0; const bodies = [];
  const f = fixture(async body => {bodies.push(body); if (++calls === 1) throw Error('Offline'); return {reply: 'Recovered'};});
  assert.equal(await f.controller.send('Failed'), false); assert.deepEqual(f.errors, ['Offline']);
  assert.equal(await f.controller.send('Retry'), true); assert.deepEqual(bodies[1].history, []);
  assert.equal(f.controller.busy, false);
});
test('malformed and empty replies do not poison history and allow recovery', async () => {
  for (const response of [null, {}, {reply: 12}, {reply: ''}, {reply: '  '}]) {
    let count = 0; const f = fixture(async body => {assert.deepEqual(body.history, []); return ++count === 1 ? response : {reply: 'Valid'};});
    assert.equal(await f.controller.send('Invalid'), false); assert.equal(f.errors.length, 1);
    assert.equal(await f.controller.send('Valid'), true);
  }
});
test('request consumers cannot mutate the private history through an earlier payload', async () => {
  let count = 0;
  const f = fixture(async body => {
    if (++count === 2) {body.history[0].content = 'tampered'; body.history.push({role: 'user', content: 'injected'});}
    if (count === 3) assert.deepEqual(body.history.map(item => item.content), ['First', 'Answer', 'Second', 'Answer']);
    return {reply: 'Answer'};
  });
  await f.controller.send('First'); await f.controller.send('Second'); await f.controller.send('Third');
  assert.deepEqual(f.errors, []);
});
test('empty input has no side effects', async () => {
  const f = fixture(() => {throw Error('must not send');});
  for (const value of ['', '  ', null, undefined]) assert.equal(await f.controller.send(value), false);
  assert.deepEqual(f.starts, []); assert.deepEqual(f.pending, []);
});
