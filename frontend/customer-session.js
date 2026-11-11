/** Refresh-cookie critical section shared by every customer page. Credentials stay in memory. */
const publicSessionPaths = new Set(['/customers/login', '/customers/register', '/customers/refresh', '/customers/logout']);
export const canRefreshCustomerPath = path => !publicSessionPaths.has(String(path).split('?')[0]);

export function createCustomerSession({baseUrl, guard, onSession, onFailure = () => {},
    expire = async () => {}, fetch: send = globalThis.fetch.bind(globalThis),
    locks = () => globalThis.navigator?.locks, wait = ms => new Promise(resolve => setTimeout(resolve, ms))}) {
    const base = new URL(baseUrl);
    if (!['http:', 'https:'].includes(base.protocol) || base.username || base.password || base.search || base.hash)
        throw new Error('Invalid customer API address');
    const endpoint = base.href.replace(/\/$/, '') + '/customers/refresh';
    let pending = null;
    let generation = 0;
    let controller = null;
    function invalidate() {
        generation++;
        controller?.abort();
        controller = null;
        pending = null;
    }
    function refresh() {
        if (pending) return pending;
        const version = generation;
        const abort = new AbortController();
        controller = abort;
        const current = () => version === generation && !abort.signal.aborted;
        const renew = async () => {
            if (!current()) return false;
            if (!guard?.isFresh()) {await expire(false); return false;}
            try {
                const response = await send(endpoint, {method: 'POST', credentials: 'include',
                    cache: 'no-store', redirect: 'error', headers: {Accept: 'application/json'}, signal: abort.signal});
                if (!current()) return false;
                if (!response.ok) {
                    if (response.status === 401 || response.status === 403) guard?.clear();
                    onFailure({status: response.status});
                    return false;
                }
                const session = await response.json();
                if (!current()) return false;
                if (typeof session?.accessToken !== 'string' || !session.accessToken || !session.customer?.id)
                    throw new Error('Invalid session response');
                onSession(session);
                return true;
            } catch (error) {
                if (current()) onFailure({error});
                return false;
            }
        };
        const task = Promise.resolve().then(() => {
            if (!current()) return false;
            const manager = locks();
            return manager?.request ? manager.request('minitech-refresh-session', renew) : renew();
        }).catch(error => {if (current()) onFailure({error}); return false;}).finally(() => {
            if (pending === task) {pending = null; controller = null;}
        });
        pending = task;
        return task;
    }
    async function restore({attempts = 1} = {}) {
        const version = generation;
        for (let attempt = 0; attempt < attempts && version === generation; attempt++) {
            if (await refresh()) return true;
            if (!guard?.isFresh() || version !== generation) return false;
            if (attempt < attempts - 1) await wait(250 * (attempt + 1));
        }
        return false;
    }
    return Object.freeze({refresh, restore, invalidate});
}
