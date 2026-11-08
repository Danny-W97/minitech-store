/** Capability-only chat transport. The widget owns drafts, persistence and rendering. */
export function createChatClient({request}) {
    const reads = new Map();
    let generation = 0;
    const aborted = () => new DOMException('Odczyt rozmowy został anulowany.', 'AbortError');

    function identifier(value, zero = false) {
        if (!['string', 'number'].includes(typeof value) || (typeof value === 'number' && !Number.isSafeInteger(value))) {
            throw new Error('Nieprawidłowy identyfikator rozmowy lub historii.');
        }
        const text = String(value);
        if (!(zero ? /^(0|[1-9]\d*)$/ : /^[1-9]\d*$/).test(text)) throw new Error('Nieprawidłowy identyfikator rozmowy lub historii.');
        return text;
    }

    function headers(token) {
        if (typeof token !== 'string' || !/^[A-Za-z0-9_-]{43,128}$/.test(token)) throw new Error('Brak poprawnego klucza dostępu do rozmowy.');
        return new Headers({'X-Chat-Access-Token': token});
    }

    function conversationPayload(payload, id) {
        const received = identifier(payload?.id);
        if (id && received !== id) throw new Error('Odpowiedź dotyczy innej rozmowy.');
        return payload;
    }

    async function create(body, token) {
        const payload = await request('/chat/messages', {method: 'POST', anonymous: true, headers: headers(token), body: JSON.stringify(body)}, false);
        return conversationPayload(payload);
    }

    async function reply(id, body, token) {
        id = identifier(id);
        const payload = await request(`/chat/messages/${id}/entries`, {method: 'POST', anonymous: true, headers: headers(token), body: JSON.stringify(body)}, false);
        return conversationPayload(payload, id);
    }

    function read(kind, id, token, before = 0) {
        id = identifier(id);
        const accessHeaders = headers(token);
        const cursor = identifier(before, true);
        const key = JSON.stringify([kind, id, token, cursor]);
        if (reads.has(key)) return reads.get(key).promise;
        const version = generation;
        const controller = new AbortController();
        const isStale = () => version !== generation || controller.signal.aborted;
        const path = kind === 'history' ? `/chat/messages/${id}/entries?before=${cursor}&size=50` : `/chat/messages/${id}`;
        const operation = Promise.resolve().then(async () => {
            if (isStale()) throw aborted();
            try {
                const payload = await request(path, {anonymous: true, headers: accessHeaders, signal: controller.signal}, false);
                if (isStale()) throw aborted();
                if (kind === 'history') {
                    if (!Array.isArray(payload?.entries)) throw new Error('Nieprawidłowa odpowiedź historii rozmowy.');
                    return payload;
                }
                return conversationPayload(payload, id);
            } catch (error) {
                if (isStale()) throw aborted();
                throw error;
            }
        }).finally(() => {
            if (reads.get(key)?.promise === operation) reads.delete(key);
        });
        reads.set(key, {promise: operation, controller});
        return operation;
    }

    function cancelReads() {
        generation++;
        for (const {controller} of reads.values()) controller.abort();
        reads.clear();
    }

    return Object.freeze({create, reply, conversation: (id, token) => read('conversation', id, token),
        history: (id, token, before = 0) => read('history', id, token, before), cancelReads});
}
