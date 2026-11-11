/* Bounded public metadata reads. This module never reads or changes images. */
(function catalogData(global) {
    'use strict';

    function abortError() {
        const error = new Error('Odczyt został anulowany.'); error.name = 'AbortError'; return error;
    }

    async function withDeadline(operation, { signal, timeoutMs = 3500 } = {}) {
        if (signal?.aborted) throw signal.reason || abortError();
        const controller = new AbortController();
        const abort = () => controller.abort(signal.reason || abortError());
        signal?.addEventListener('abort', abort, { once: true });
        const timeout = global.setTimeout(() => {
            const error = new Error('Przekroczono czas oczekiwania na dane.'); error.name = 'TimeoutError';
            controller.abort(error);
        }, timeoutMs);
        let rejectAbort;
        const aborted = new Promise((_, reject) => { rejectAbort = reject; });
        const reject = () => rejectAbort(controller.signal.reason || abortError());
        controller.signal.addEventListener('abort', reject, { once: true });
        try { return await Promise.race([operation(controller.signal), aborted]); }
        finally {
            global.clearTimeout(timeout);
            signal?.removeEventListener('abort', abort);
            controller.signal.removeEventListener('abort', reject);
        }
    }

    async function mapLimit(items, limit, worker) {
        let next = 0;
        const result = new Array(items.length);
        await Promise.all(Array.from({ length: Math.min(limit, items.length) }, async () => {
            while (next < items.length) { const index = next++; result[index] = await worker(items[index], index); }
        }));
        return result;
    }

    function createInventoryReader({ request, onUpdate, signal, batchSize = 100, concurrency = 2, timeoutMs = 3500 }) {
        if (!Number.isInteger(batchSize) || batchSize < 1 || batchSize > 100
                || !Number.isInteger(concurrency) || concurrency < 1 || concurrency > 4) {
            throw new RangeError('Invalid inventory batching limits');
        }
        const values = new Map(), pending = new Map(), queue = new Map(), priority = new Map();
        let active = 0, scheduled = null;

        function schedule() {
            if (scheduled === null && !signal.aborted) scheduled = global.setTimeout(() => { scheduled = null; pump(); }, 0);
        }

        function pump() {
            while (!signal.aborted && active < concurrency && (queue.size || priority.size)) {
                const ids = [...priority.keys()].slice(0, batchSize);
                for (const id of queue.keys()) { if (ids.length >= batchSize) break; ids.push(id); }
                const entries = ids.map(id => pending.get(id));
                ids.forEach(id => { queue.delete(id); priority.delete(id); });
                active++;
                withDeadline(child => request(ids, child), { signal, timeoutMs }).then(rows => {
                    if (!Array.isArray(rows)) throw new Error('Nieprawidłowa odpowiedź magazynu.');
                    const found = new Map(), requested = new Set(ids);
                    for (const row of rows) {
                        if (!row || !requested.has(row.productId) || found.has(row.productId)
                                || !['availableQuantity', 'reservedQuantity', 'soldQuantity']
                                    .every(field => Number.isInteger(row[field]) && row[field] >= 0)) {
                            throw new Error('Nieprawidłowa odpowiedź magazynu.');
                        }
                        found.set(row.productId, row);
                    }
                    return found;
                }).catch(() => new Map()).then(found => {
                    const update = {};
                    ids.forEach((id, index) => {
                        const value = found.get(id) || null;
                        if (!signal.aborted) { values.set(id, value); update[id] = value; }
                        pending.delete(id); entries[index].resolve(value);
                    });
                    if (!signal.aborted) onUpdate(update);
                }).finally(() => { active--; pump(); });
            }
        }

        function get(id, force) {
            if (signal.aborted) return Promise.resolve(null);
            if (pending.has(id)) {
                const entry = pending.get(id);
                if (force && queue.has(id)) { queue.delete(id); priority.set(id, entry); }
                if (!force || priority.has(id)) return entry.promise;
                return entry.promise.then(() => get(id, true));
            }
            if (!force && values.has(id)) return Promise.resolve(values.get(id));
            let resolve;
            const promise = new Promise(done => { resolve = done; });
            const entry = { promise, resolve };
            pending.set(id, entry); (force ? priority : queue).set(id, entry); schedule();
            return promise;
        }

        signal.addEventListener('abort', () => {
            if (scheduled !== null) global.clearTimeout(scheduled);
            queue.forEach((entry, id) => { entry.resolve(null); pending.delete(id); }); queue.clear();
            priority.forEach((entry, id) => { entry.resolve(null); pending.delete(id); }); priority.clear();
        }, { once: true });

        return Object.freeze({
            async load(ids, { force = false } = {}) {
                const unique = [...new Set(ids.map(Number).filter(id => Number.isSafeInteger(id) && id > 0))];
                const result = await Promise.all(unique.map(id => get(id, force)));
                return Object.fromEntries(unique.map((id, index) => [id, result[index]]));
            }
        });
    }

    global.MiniTechCatalogData = Object.freeze({ withDeadline, mapLimit, createInventoryReader });
}(window));
