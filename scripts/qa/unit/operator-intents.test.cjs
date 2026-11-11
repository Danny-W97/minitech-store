const {test, before} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
let createOperatorIntents;
before(async () => ({createOperatorIntents} = await import('data:text/javascript;base64,' +
  Buffer.from(fs.readFileSync('scripts/control-center/web/operator-intents.js', 'utf8')).toString('base64'))));
function fixture(data = new Map(), queue = new Map()) {
  let profile = 'dev', next = 0;
  const storage = {getItem: key => data.has(key) ? data.get(key) : null,
    setItem: (key, value) => data.set(key, value), removeItem: key => data.delete(key)};
  const locks = {request(key, work) {
    const task = (queue.get(key) || Promise.resolve()).then(work);
    queue.set(key, task.catch(() => {})); return task;
  }};
  const dependencies = {getProfile: () => profile, storage: () => storage, locks: () => locks,
    randomUUID: () => `00000000-0000-4000-8000-${String(++next).padStart(12, '0')}`};
  return {data, storage, locks, dependencies, setProfile: value => {profile = value;},
    store: createOperatorIntents(dependencies)};
}
test('two tabs serialize a receipt and reuse its ID across reload', async () => {
  const a = fixture(), b = fixture(a.data);
  b.locks.request = a.locks.request.bind(a.locks);
  const [x, y] = await Promise.all([a.store.stockIntent('7', 10), b.store.stockIntent('7', 10)]);
  assert.equal(x.operationId, y.operationId);
  assert.equal((await fixture(a.data).store.stockIntent('7', 10)).operationId, x.operationId);
  await assert.rejects(a.store.stockIntent('7', 50), /Najpierw rozstrzygnij/);
});
test('receipt and correction namespaces, resources and profiles remain independent', async () => {
  const f = fixture();
  await f.store.stockIntent('7', 10); await f.store.stockIntent('8', 50);
  await f.store.correctionIntent('7', 'Rabat', '-1.00', 0, '100.00');
  f.setProfile('test'); assert.equal(f.store.readPendingStock('7'), null);
  await f.store.stockIntent('7', 50);
  assert.equal(f.data.size, 4);
  f.setProfile('dev'); assert.equal(f.store.readPendingStock('7').quantity, 10);
});
test('a profile switch while waiting for the lock cannot redirect a command or cleanup', async () => {
  const f = fixture();
  const waiting = f.store.stockIntent('7', 10); f.setProfile('test');
  const old = await waiting; const current = await f.store.stockIntent('7', 50);
  await f.store.finishStockIntent(old);
  assert.equal(f.store.readPendingStock('7').operationId, current.operationId);
  f.setProfile('dev'); assert.equal(f.store.readPendingStock('7'), null);
});
test('late cleanup cannot remove a newer receipt or correction', async () => {
  const f = fixture();
  for (const [begin, finish, args] of [[f.store.stockIntent, f.store.finishStockIntent, ['7', 10]],
    [f.store.correctionIntent, f.store.finishCorrectionIntent, ['7', 'Rabat', '-1.00', 0, '100.00']]]) {
    const old = await begin(...args); await finish(old);
    const current = await begin(...args); await finish(old);
    assert.equal(f.data.get(current.storageKey), JSON.stringify(Object.fromEntries(
      Object.entries(current).filter(([key]) => key !== 'storageKey'))));
  }
});
test('corrupt stored commands block replacement and remain available for diagnosis', async () => {
  for (const raw of ['{', 'null', '{}', '{"operationId":"x","quantity":10}']) {
    const f = fixture(); const key = 'minitech-stock-receipt-v1:dev:7'; f.data.set(key, raw);
    await assert.rejects(f.store.stockIntent('7', 10), /Nie można odczytać/);
    assert.equal(f.data.get(key), raw);
  }
  const f = fixture(); f.data.set('minitech-invoice-correction-v1:dev:7', '{}');
  await assert.rejects(f.store.correctionIntent('7', 'Rabat', '-1.00', 0, '100'), /Nie można odczytać/);
});
test('missing locks, UUID or writable storage prevents starting an operation', async () => {
  for (const override of [{locks: () => undefined}, {randomUUID: () => {throw Error('missing');}},
    {storage: () => ({getItem: () => null, setItem: () => {throw Error('storage blocked');}})}]) {
    const f = fixture(); const store = createOperatorIntents({...f.dependencies, ...override});
    await assert.rejects(store.stockIntent('7', 10)); assert.equal(f.data.size, 0);
  }
});
test('invalid receipt quantities and correction payloads never create records', async () => {
  const f = fixture();
  for (const quantity of [0, -1, 1.1, '10', NaN, Number.MAX_SAFE_INTEGER + 1])
    await assert.rejects(f.store.stockIntent('7', quantity));
  for (const args of [['', '-1.00', 0], ['bad\nreason', '-1.00', 0], ['Rabat', '-1.001', 0], ['Rabat', '-1.00', -1]])
    await assert.rejects(f.store.correctionIntent('7', ...args, '100'));
  assert.equal(f.data.size, 0);
});
test('returned commands cannot mutate persisted payloads', async () => {
  const f = fixture(); const first = await f.store.stockIntent('7', 10); first.quantity = 999;
  assert.equal(f.store.readPendingStock('7').quantity, 10);
  assert.equal((await f.store.stockIntent('7', 10)).quantity, 10);
});
