/** Same-origin CC transport. The session credential lives only inside this instance. */
export function createControlClient({
    baseUrl = globalThis.location.href,
    fetch: send = globalThis.fetch.bind(globalThis),
    getLanguage = () => 'pl',
    onSessionExpired = () => {}
} = {}) {
    const base = new URL(baseUrl);
    if (!['http:', 'https:'].includes(base.protocol)||base.username||base.password) throw new Error('Niedozwolony adres API panelu.');
    let token = '';
    let invalidation = 0;
    const active = new Set();
    function clear() {
        token = ''; invalidation++;
        for (const controller of active) controller.abort();
        active.clear();
    }

    function expiredError() {
        const error = new Error(getLanguage() === 'en'
            ? 'Panel session expired. Reload this page.'
            : 'Sesja panelu wygasła. Odśwież stronę.');
        error.code = 'CC_SESSION_EXPIRED';
        return error;
    }

    async function perform(path, options = {}) {
        const url = new URL(path, base);
        if (url.origin !== base.origin || !url.pathname.startsWith('/api/') || url.username || url.password) {
            throw new Error('Niedozwolony adres API panelu.');
        }
        if (!token && !['/api/bootstrap', '/api/ping'].includes(url.pathname)) throw expiredError();
        const usedToken = token;
        const version = invalidation;
        const headers = new Headers(options.headers || {});
        if (!headers.has('Accept')) headers.set('Accept', 'application/json');
        if (!headers.has('Accept-Language')) headers.set('Accept-Language', getLanguage());
        if (options.body) headers.set('Content-Type', 'application/json');
        // A caller cannot replace the credential, including with differently cased headers.
        headers.delete('X-Control-Center-Token');
        if (usedToken) headers.set('X-Control-Center-Token', usedToken);
        const response = await send(url.href, {
            // URL validation above enforces same origin. cors + omitted cookies keeps
            // Firefox/WebKit from serializing a same-origin POST's Origin as null.
            ...options, credentials: 'omit', mode: 'cors', redirect: 'error', headers
        });
        if (!response.ok) {
            const payload = await response.json().catch(() => ({}));
            if (response.status === 401 && payload?.code === 'CC_SESSION_EXPIRED'
                    && usedToken && usedToken === token && version === invalidation) {
                clear();
                onSessionExpired(expiredError());
            }
            const error = new Error(payload?.message || payload?.error || `HTTP ${response.status}`);
            error.definitive = payload?.definitive === true;
            error.status = response.status;
            error.code = payload?.code;
            throw error;
        }
        // Do not deliver a late success to controls already stopped after session expiry.
        if (usedToken && version !== invalidation) throw expiredError();
        return { response, url, version };
    }

    async function json(path, options = {}) {
        const controller = new AbortController();active.add(controller);
        try {
        const signal=options.signal?AbortSignal.any([options.signal,controller.signal]):controller.signal;
        const { response, url, version } = await perform(path, {...options,signal});
        const payload = await response.json().catch(() => ({}));
        if (version !== invalidation) throw expiredError();
        if (url.pathname === '/api/bootstrap') {
            if (typeof payload?.token !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(payload.token)) {
                throw new Error('Nieprawidłowa odpowiedź inicjalizacji panelu.');
            }
            token = payload.token;
            const { token: credential, ...configuration } = payload;
            return configuration;
        }
        return payload;
        } finally {active.delete(controller);}
    }

    async function blob(path, options = {}) {
        const controller = new AbortController();active.add(controller);
        try {
        const signal=options.signal?AbortSignal.any([options.signal,controller.signal]):controller.signal;
        const { response, version } = await perform(path, {...options,signal});
        const payload = await response.blob();
        if (version !== invalidation) throw expiredError();
        return payload;
        } finally {active.delete(controller);}
    }

    async function logout() {
        const credential=token;
        clear();onSessionExpired(expiredError());
        if(!credential)return {serverRevoked:false};
        try {
            const response=await send(new URL('/api/session/logout',base).href,{method:'POST',headers:{'X-Control-Center-Token':credential},credentials:'omit',mode:'cors',redirect:'error',cache:'no-store',signal:AbortSignal.timeout(5000)});
            const body=await response.json();
            return {serverRevoked:response.ok&&body.sessionRevoked===true};
        } catch {return {serverRevoked:false};}
    }

    return Object.freeze({ json, blob, logout });
}
