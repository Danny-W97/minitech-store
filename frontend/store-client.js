/** Store JSON/binary transport. Session state and refresh-cookie handling belong to the page. */
export function createStoreClient({
    baseUrl, getAccessToken = () => null, getHeaders = () => ({}),
    refreshSession = async () => false, canRefresh = () => false, retry,
    fetch: send = globalThis.fetch.bind(globalThis)
}) {
    const base = new URL(baseUrl);
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash) {
        throw new Error('Invalid MiniTech API address');
    }
    const prefix = base.href.replace(/\/$/, '');
    const pathPrefix = base.pathname.replace(/\/$/, '') + '/';
    let refreshPromise = null;
    let generation = 0;
    const active = new Set();
    function invalidate() {
        generation++;
        refreshPromise = null;
        for (const controller of active) controller.abort();
        active.clear();
    }

    function checkSignal(signal) {
        if (signal?.aborted) throw signal.reason || new DOMException('Request aborted', 'AbortError');
    }

    function renew() {
        if (!refreshPromise) {
            const task = Promise.resolve().then(refreshSession).finally(() => { if(refreshPromise===task)refreshPromise=null; });
            refreshPromise = task;
        }
        return refreshPromise;
    }

    async function readText(response, limit, signal) {
        if (!limit) return response.text();
        const reader = response.body?.getReader();
        if (!reader) {
            const text = await response.text();
            if (new TextEncoder().encode(text).length > limit) throw Object.assign(new Error('Response too large'), {code: 'RESPONSE_LIMIT'});
            return text;
        }
        const decoder = new TextDecoder('utf-8', {fatal: true}), pieces = [];
        let bytes = 0;
        try {
            while (true) {
                checkSignal(signal);
                const {done, value} = await reader.read();
                if (done) break;
                bytes += value.byteLength;
                if (bytes > limit) throw Object.assign(new Error('Response too large'), {code: 'RESPONSE_LIMIT'});
                pieces.push(decoder.decode(value, {stream: true}));
            }
            pieces.push(decoder.decode());
            return pieces.join('');
        } catch (error) {
            try { await reader.cancel(); } catch { /* Release the connection even on abort. */ }
            throw error;
        } finally { reader.releaseLock(); pieces.length = 0; }
    }

    async function perform(format, path, options = {}, retryAfterRefresh = true, signal = options.signal) {
        const version = generation, controller = new AbortController();
        const combined = signal ? AbortSignal.any([signal,controller.signal]) : controller.signal;
        active.add(controller);
        const current = () => { checkSignal(combined); if (version !== generation) throw new DOMException('Session changed', 'AbortError'); };
        try { return await execute(format,path,options,retryAfterRefresh,combined,current); }
        finally { active.delete(controller); }
    }
    async function execute(format, path, options, retryAfterRefresh, signal, current) {
        if (typeof path !== 'string' || !path.startsWith('/') || path.startsWith('//')) throw new Error('Invalid MiniTech API path');
        const url = new URL(prefix + path);
        if (url.origin !== base.origin || !url.pathname.startsWith(pathPrefix) || url.username || url.password || url.hash) {
            throw new Error('Invalid MiniTech API path');
        }
        checkSignal(signal);
        const { anonymous = false, paged = false, timeoutMs, maxResponseBytes, ...fetchOptions } = options;
        if (maxResponseBytes !== undefined && (!Number.isSafeInteger(maxResponseBytes) || maxResponseBytes < 1 || maxResponseBytes > 8388608)) throw new Error('Invalid response limit');
        const usedToken = anonymous ? null : getAccessToken();
        const headers = new Headers(options.headers || {});
        if (options.body != null && !headers.has('Content-Type')) headers.set('Content-Type', 'application/json');
        new Headers(getHeaders()).forEach((value, name) => headers.set(name, value));
        headers.delete('Authorization');
        if (usedToken) headers.set('Authorization', `Bearer ${usedToken}`);
        const response = await send(url.href, {
            ...fetchOptions, signal, headers, credentials: anonymous ? 'omit' : 'include',
            cache: options.cache || 'no-store', mode: 'cors', redirect: 'error'
        });
        current();
        if (format === 'blob' && response.ok) {
            const data = await response.blob();
            checkSignal(signal);
            current();
            if(usedToken&&getAccessToken()!==usedToken)throw new DOMException('Session changed','AbortError');
            return data;
        }
        const raw = await readText(response, maxResponseBytes, signal);
        checkSignal(signal);
        current();
        let data = raw;
        try { data = raw ? JSON.parse(raw) : null; } catch { /* Preserve plain downstream error messages. */ }
        const code = data?.details?.code || data?.code;
        const rejectedMfaProof = typeof code === 'string' && code.startsWith('MFA_');
        if (response.status === 401 && !anonymous && !rejectedMfaProof && retryAfterRefresh && canRefresh(path)) {
            const currentToken = getAccessToken();
            const renewed=(currentToken && currentToken !== usedToken) || await renew();
            current();
            if (renewed) {
                checkSignal(signal);
                current();
                return format === 'json' && retry ? retry(path, options, signal) : perform(format, path, options, false, signal);
            }
        }
        if (!response.ok) {
            const message = data && typeof data === 'object'
                ? (data.message || data.error || `HTTP ${response.status}`) : raw || `HTTP ${response.status}`;
            const error = new Error(message);
            error.code = code;
            error.orderId = data?.details?.orderId;
            error.status = response.status;
            error.path = path;
            throw error;
        }
        if(usedToken&&getAccessToken()!==usedToken)throw new DOMException('Session changed','AbortError');
        return paged ? {
            items: data, hasMore: response.headers.get('X-Has-More') === 'true',
            nextCursor: response.headers.get('X-Next-Cursor') || '0'
        } : data;
    }

    return Object.freeze({
        invalidate,
        request: (...args) => perform('json', ...args),
        blob: (...args) => perform('blob', ...args)
    });
}
