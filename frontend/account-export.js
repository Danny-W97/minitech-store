/** Owner-scoped profile download. Transport/session policy stays in the store client. */
export function createAccountExportDownloads({
    requestBlob, getOwner, document,
    createObjectURL = blob => globalThis.URL.createObjectURL(blob),
    revokeObjectURL = url => globalThis.URL.revokeObjectURL(url),
    setTimeout: schedule = globalThis.setTimeout.bind(globalThis)
}) {
    const listeners = new Set();
    let pending = null, generation = 0, state = {phase: 'idle'};
    const aborted = () => new DOMException('Account export cancelled', 'AbortError');
    const failure = code => Object.assign(new Error(code), {code});
    const ownerId = value => {
        if (typeof value === 'number' && !Number.isSafeInteger(value)) return null;
        return ['number', 'string'].includes(typeof value) && /^[1-9]\d*$/.test(String(value)) ? String(value) : null;
    };
    function publish(next) {
        state = Object.freeze(next);
        for (const listener of listeners) listener(state);
    }
    function download() {
        const identity = getOwner();
        const owner = ownerId(identity);
        if (!owner) {
            const error = failure(identity == null ? 'SESSION_EXPIRED' : 'INVALID_ACCOUNT_OWNER');
            if (identity != null) publish({phase: 'error', code: error.code});
            return Promise.reject(error);
        }
        if (pending?.owner === owner) return pending.promise;
        if (pending) cancelAll();
        const entry = {owner, controller: new AbortController(), version: generation, reason: null};
        const stale = () => entry.controller.signal.aborted || generation !== entry.version || ownerId(getOwner()) !== owner;
        entry.promise = Promise.resolve().then(async () => {
            if (stale()) throw aborted();
            try {
                const blob = await requestBlob('/customers/me/data-export', {
                    headers: {Accept: 'application/json'}, signal: entry.controller.signal, cache: 'no-store'
                });
                if (stale()) throw aborted();
                // The current profile contract is small. Reject error pages, huge bodies and wrong-account responses.
                if (!(blob instanceof Blob) || blob.type.split(';')[0].toLowerCase() !== 'application/json'
                        || blob.size > 65536) throw failure('INVALID_ACCOUNT_EXPORT');
                let data;
                try { data = JSON.parse(await blob.text()); } catch { throw failure('INVALID_ACCOUNT_EXPORT'); }
                if (stale()) throw aborted();
                if (data?.schemaVersion !== 1 || data.scope !== 'customer-profile-and-security-summary'
                        || data.complete !== false || ownerId(data.profile?.id) !== owner || !Array.isArray(data.omissions)) {
                    throw failure('INVALID_ACCOUNT_EXPORT');
                }
                const url = createObjectURL(blob);
                try {
                    const link = document.createElement('a');
                    link.href = url;
                    link.download = 'customer-profile-export.json';
                    link.click();
                } finally {
                    try { schedule(() => revokeObjectURL(url), 1000); }
                    catch { revokeObjectURL(url); }
                }
                if (!stale()) publish({phase: 'done'});
            } catch (error) {
                if (stale()) {
                    if (entry.reason === 'session-expired') throw failure('SESSION_EXPIRED');
                    throw aborted();
                }
                publish({phase: 'error', code: error.code, status: error.status});
                throw error;
            }
        }).finally(() => {
            if (pending === entry) pending = null;
        });
        pending = entry;
        publish({phase: 'busy'});
        return entry.promise;
    }
    function cancelAll(reason = 'cancelled') {
        generation++;
        if (pending) { pending.reason = reason; pending.controller.abort(); }
        pending = null;
        publish({phase: 'idle'});
    }
    function subscribe(listener) {
        listeners.add(listener);
        listener(state);
        return () => listeners.delete(listener);
    }
    return Object.freeze({download, cancelAll, subscribe});
}

/** UI never displays server-provided markup or stores the downloaded profile. */
export function bindAccountExportUi({downloads, button, status, hint, translate, events, getOwner, onSignInRequired}) {
    if (!button || !status || !hint) return () => {};
    let current;
    function render() {
        const busy = current?.phase === 'busy';
        button.disabled = busy;
        button.textContent = translate(busy ? 'accountExportBusy' : 'accountExportDownload');
        button.setAttribute('aria-busy', String(busy));
        hint.textContent = translate('accountExportHint');
        const key = current?.phase === 'busy' ? 'accountExportBusy'
            : current?.phase === 'done' ? 'accountExportStarted'
            : current?.phase === 'error' ? current.code === 'INVALID_ACCOUNT_EXPORT' ? 'accountExportInvalid'
                : [401, 403].includes(current.status) || current.code === 'SESSION_EXPIRED' ? 'accountExportSignIn' : 'accountExportFailed'
            : null;
        status.textContent = key ? translate(key) : '';
    }
    const unsubscribe = downloads.subscribe(value => { current = value; render(); });
    const click = () => downloads.download().catch(error => {
        if (error.code === 'SESSION_EXPIRED' && !getOwner()) onSignInRequired();
    });
    button.addEventListener('click', click);
    events.addEventListener('minitech:language-changed', render);
    return () => {
        unsubscribe();
        button.removeEventListener('click', click);
        events.removeEventListener('minitech:language-changed', render);
    };
}
