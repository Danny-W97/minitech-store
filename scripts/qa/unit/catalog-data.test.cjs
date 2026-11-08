const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const window = { setTimeout, clearTimeout };
vm.runInNewContext(fs.readFileSync(path.resolve(__dirname, '../../../frontend/catalog-data.js'), 'utf8'),
    { window, AbortController, Error, Map, Set, Promise });
const { withDeadline, createInventoryReader, mapLimit } = window.MiniTechCatalogData;
const pause = milliseconds => new Promise(resolve => setTimeout(resolve, milliseconds));
const row = (productId, availableQuantity = 10) => ({ productId, availableQuantity, reservedQuantity: 0, soldQuantity: 0 });

test('inventory batches stay within 100 IDs and two concurrent requests for a large explicit selection', async () => {
    let active = 0, maximum = 0;
    const sizes = [], updates = [];
    const reader = createInventoryReader({ signal: new AbortController().signal, onUpdate: value => updates.push(value),
        request: async ids => {
            active++; maximum = Math.max(maximum, active); sizes.push(ids.length);
            await pause(5); active--; return ids.map(id => row(id));
        } });
    const result = await reader.load(Array.from({ length: 501 }, (_, index) => index + 1));
    assert.equal(Object.keys(result).length, 501);
    assert.equal(maximum, 2); assert.equal(sizes.length, 6);
    assert.ok(sizes.every(size => size > 0 && size <= 100));
    assert.equal(updates.length, 6);
});

test('overlapping readers share requests and explicit refresh bypasses previous data', async () => {
    const calls = []; let quantity = 5;
    const reader = createInventoryReader({ signal: new AbortController().signal, onUpdate() {},
        request: async ids => { calls.push([...ids]); return ids.map(id => row(id, quantity)); } });
    await Promise.all([reader.load([1, 2, 2]), reader.load([2, 3])]);
    assert.equal(calls.length, 1); assert.equal(calls[0].length, 3);
    await reader.load([1, 2]); assert.equal(calls.length, 1);
    quantity = 0;
    const fresh = await reader.load([1, 2], { force: true });
    assert.equal(calls.length, 2); assert.equal(fresh[1].availableQuantity, 0);
});

test('missing, invalid and failed responses remain unknown rather than sold out', async () => {
    for (const response of [[], [{ ...row(1), availableQuantity: -1 }], [row(9)], [row(1), row(1)], null]) {
        const reader = createInventoryReader({ signal: new AbortController().signal, onUpdate() {}, request: async () => response });
        assert.equal((await reader.load([1]))[1], null);
    }
    const reader = createInventoryReader({ signal: new AbortController().signal, onUpdate() {}, request: async () => { throw Error('offline'); } });
    assert.equal((await reader.load([1]))[1], null);
});

test('cart refresh takes priority over queued background availability', async () => {
    const calls = [], releases = [];
    const reader = createInventoryReader({ signal: new AbortController().signal, onUpdate() {},
        request: ids => {
            calls.push([...ids]);
            return new Promise(resolve => releases.push(() => resolve(ids.map(id => row(id)))));
        } });
    const background = reader.load(Array.from({ length: 300 }, (_, index) => index + 1));
    while (calls.length < 2) await pause(1);
    const cart = reader.load([299], { force: true });
    releases[0]();
    while (calls.length < 3) await pause(1);
    assert.equal(calls[2][0], 299);
    releases[1](); releases[2]();
    assert.equal((await cart)[299].availableQuantity, 10);
    await background;
});

test('deadline cancels fetch/body work even when a dependency does not settle', async () => {
    let signal;
    await assert.rejects(withDeadline(child => { signal = child; return new Promise(() => {}); }, { timeoutMs: 15 }),
        error => error.name === 'TimeoutError');
    assert.equal(signal.aborted, true);
});

test('cancelled catalog discards in-flight metadata and releases queued readers', async () => {
    const controller = new AbortController(); let updates = 0, calls = 0;
    const reader = createInventoryReader({ signal: controller.signal, onUpdate() { updates++; },
        request: () => { calls++; return new Promise(() => {}); } });
    const pending = reader.load(Array.from({ length: 350 }, (_, index) => index + 1));
    await pause(5); controller.abort();
    const result = await pending;
    assert.equal(calls, 2); assert.equal(updates, 0);
    assert.ok(Object.values(result).every(value => value === null));
});

test('hot-shot enrichment has bounded concurrency and preserves input order', async () => {
    let active = 0, maximum = 0;
    const result = await mapLimit([1, 2, 3, 4, 5], 2, async id => {
        active++; maximum = Math.max(maximum, active); await pause(id % 2 ? 5 : 1); active--; return id;
    });
    assert.equal(JSON.stringify(result), '[1,2,3,4,5]'); assert.equal(maximum, 2);
});
