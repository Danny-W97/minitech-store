/** Resumable account export. Only metadata lives in this module; downloaded data never enters browser storage. */
const BASE = '/customers/me/data-export/jobs';
const MAX_BYTES = 16777216, MAX_PART = 524288, MAX_TASKS = 4096, MAX_FILE = 25165824;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const HASH = /^[0-9a-f]{64}$/;
const STATES = new Set(['ACTIVE', 'PARTIAL', 'READY', 'LIMIT_REACHED', 'CANCELLED']);
const integer = (value, max) => Number.isSafeInteger(value) && value >= 0 && value <= max;
const ownerId = value => (typeof value === 'string' || Number.isSafeInteger(value)) && /^[1-9]\d*$/.test(String(value)) ? String(value) : null;
const failure = code => Object.assign(new Error(code), {code});
const require = condition => { if (!condition) throw failure('INVALID_ACCOUNT_JOB'); };

export function createAccountExportJobs({request, getOwner, document,
    crypto = globalThis.crypto, now = Date.now,
    createObjectURL = blob => globalThis.URL.createObjectURL(blob),
    revokeObjectURL = url => globalThis.URL.revokeObjectURL(url),
    setTimeout: schedule = globalThis.setTimeout.bind(globalThis),
    clearTimeout: unschedule = globalThis.clearTimeout.bind(globalThis)}) {
    const listeners = new Set(), urls = new Map();
    let state = Object.freeze({phase: 'idle', job: null, jobs: []}), pending = null, generation = 0;
    let expiryTimer = null, createKey = null, stepKey = null;
    function publish(next) {
        state = Object.freeze({...state, ...next});
        for (const listener of listeners) listener(state);
    }
    function release(url) {
        if (!urls.has(url)) return;
        unschedule(urls.get(url)); urls.delete(url); revokeObjectURL(url);
    }
    function interrupt() {
        generation++;
        pending?.controller.abort(); pending = null;
        for (const url of [...urls.keys()]) release(url);
    }
    function expire() {
        interrupt(); stepKey = null; createKey = null;
        publish({phase: 'expired', operation: null, code: null, job: null,
            jobs: state.jobs.filter(job => Date.parse(job.expiresAt) > now())});
    }
    function arm(job) {
        if (expiryTimer !== null) unschedule(expiryTimer);
        expiryTimer = null;
        if (!job || job.state === 'CANCELLED') return;
        const owner = ownerId(getOwner());
        expiryTimer = schedule(() => {
            expiryTimer = null;
            if (state.job?.jobId !== job.jobId || ownerId(getOwner()) !== owner) return;
            if (now() < Date.parse(job.expiresAt)) arm(job); else expire();
        }, Math.max(0, Date.parse(job.expiresAt) - now()));
    }
    function validateJob(job, owner, id) {
        require(job?.schemaVersion === 1 && UUID.test(job.jobId) && (!id || job.jobId === id)
            && job.ownerId === owner && STATES.has(job.state) && HASH.test(job.policyHash)
            && job.complete === false && job.automaticScopeComplete === (job.state === 'READY')
            && integer(job.partCount, MAX_TASKS) && integer(job.recordedBytes, MAX_BYTES)
            && job.maxBytes === MAX_BYTES && job.maxPartBytes === MAX_PART && job.maxTasks === MAX_TASKS
            && ['done', 'pending', 'failed'].every(key => integer(job.progress?.[key], MAX_TASKS))
            && Date.parse(job.expiresAt) - Date.parse(job.createdAt) === 3600000);
        if (Date.parse(job.expiresAt) <= now()) throw failure('EXPORT_EXPIRED');
        return job;
    }
    function adopt(job) {
        const jobs = [job, ...state.jobs.filter(item => item.jobId !== job.jobId)].slice(0, 3);
        publish({job, jobs}); arm(job);
    }
    function operate(operation, action) {
        const owner = ownerId(getOwner());
        if (!owner) return Promise.reject(failure('SESSION_EXPIRED'));
        if (pending?.owner === owner) return pending.promise;
        if (pending) cancelAll();
        const entry = {owner, version: generation, controller: new AbortController()};
        const check = () => {
            if (entry.controller.signal.aborted || generation !== entry.version || ownerId(getOwner()) !== owner)
                throw new DOMException('Export stopped', 'AbortError');
            if (!['discover', 'prepare'].includes(operation) && state.job && Date.parse(state.job.expiresAt) <= now()) throw failure('EXPORT_EXPIRED');
        };
        const call = async (suffix = '', method = 'GET', key, large = false) => {
            check();
            const result = await request(BASE + suffix, {method, signal: entry.controller.signal,
                timeoutMs: 12000, maxResponseBytes: large ? 4194304 : 262144, cache: 'no-store',
                headers: {Accept: 'application/json', ...(key ? {'Idempotency-Key': key} : {})}});
            check(); return result;
        };
        const context = {owner, check, call,
            job: async id => validateJob(await call('/' + id), owner, id),
            validate: (job, id) => validateJob(job, owner, id)};
        entry.promise = Promise.resolve().then(async () => {
            try {
                check();
                await action(context);
                check(); publish({phase: operation === 'download' || operation === 'manifest' ? 'downloaded' : 'idle', operation: null});
            } catch (error) {
                if (entry.controller.signal.aborted || generation !== entry.version || ownerId(getOwner()) !== owner) {
                    if (entry.reason === 'session-expired') throw failure('SESSION_EXPIRED');
                    throw new DOMException('Export stopped', 'AbortError');
                }
                if (error.status === 410 || error.code === 'EXPORT_EXPIRED') expire();
                else publish({phase: 'error', operation: null, code: error.code, status: error.status});
                throw error;
            }
        }).finally(() => {if (pending === entry) pending = null;});
        pending = entry; publish({phase: 'busy', operation, code: null, status: null, downloadedParts: 0});
        return entry.promise;
    }
    async function discover(ctx) {
        const data = await ctx.call();
        require(data?.schemaVersion === 1 && data.complete === false && Array.isArray(data.jobs) && data.jobs.length <= 3);
        const jobs = data.jobs.map(job => ctx.validate(job));
        require(new Set(jobs.map(job => job.jobId)).size === jobs.length);
        publish({jobs});
        const selected = jobs.find(job => job.jobId === state.job?.jobId)
            || jobs.find(job => job.state === 'ACTIVE' || job.state === 'PARTIAL') || jobs[0] || null;
        if (state.job?.jobId !== selected?.jobId) stepKey = null;
        publish({job: selected}); arm(selected);
        return selected;
    }
    async function collect(ctx, job) {
        adopt(job);
        let steps = 0;
        while (job.state === 'ACTIVE') {
            ctx.check(); require(++steps <= MAX_TASKS + 8);
            stepKey ||= crypto.randomUUID();
            job = ctx.validate(await ctx.call('/' + job.jobId + '/advance', 'POST', stepKey), job.jobId);
            stepKey = null; adopt(job);
        }
    }
    function refresh() {return operate('discover', discover);}
    function start() {
        return operate('prepare', async ctx => {
            // Recover an unfinished job before creating another, including after reload or a lost POST response.
            const current = await discover(ctx);
            if (current && ['ACTIVE', 'PARTIAL'].includes(current.state)) throw failure('EXPORT_ALREADY_ACTIVE');
            createKey ||= crypto.randomUUID();
            let job;
            try {job = ctx.validate(await ctx.call('', 'POST', createKey));}
            catch (error) {
                if (error.status === 409) {await discover(ctx); throw failure('EXPORT_ALREADY_ACTIVE');}
                throw error;
            }
            createKey = null; stepKey = null; await collect(ctx, job);
        });
    }
    function resume() {
        return operate('collect', async ctx => {
            require(state.job && ['ACTIVE', 'PARTIAL'].includes(state.job.state));
            let job = await ctx.job(state.job.jobId);
            if (job.state === 'PARTIAL') job = ctx.validate(await ctx.call('/' + job.jobId + '/retry', 'POST'), job.jobId);
            await collect(ctx, job);
        });
    }
    function pause() {interrupt(); publish({phase: 'paused', operation: null}); arm(state.job);}
    function cancelAll(reason = 'cancelled') {
        if (pending) pending.reason = reason;
        interrupt(); if (expiryTimer !== null) unschedule(expiryTimer); expiryTimer = null;
        createKey = null; stepKey = null; state = Object.freeze({phase: 'idle', job: null, jobs: []});
        for (const listener of listeners) listener(state);
    }
    function cancel() {
        const job = state.job; if (!job) return Promise.resolve();
        pause();
        return operate('cancel', async ctx => {
            const result = ctx.validate(await ctx.call('/' + job.jobId + '/cancel', 'POST'), job.jobId);
            require(result.state === 'CANCELLED'); stepKey = null; adopt(result);
        });
    }
    function select(id) {
        if (pending) return Promise.reject(failure('EXPORT_BUSY'));
        require(UUID.test(id) && state.jobs.some(job => job.jobId === id)); stepKey = null;
        return operate('discover', async ctx => {adopt(await ctx.job(id));});
    }
    async function manifest(ctx, job) {
        const data = await ctx.call('/' + job.jobId + '/manifest');
        ctx.validate(data, job.jobId);
        require(data.scope === 'account-export-manifest' && data.policyHash === job.policyHash
            && Array.isArray(data.sourceDefinitions) && data.sourceDefinitions.length === 25
            && Object.keys(data.serviceClassification || {}).length === 14
            && Array.isArray(data.separateProofRequired) && data.separateProofRequired.length > 0
            && Array.isArray(data.manualReviewRequired) && data.manualReviewRequired.length > 0
            && Array.isArray(data.copyBoundaries) && Array.isArray(data.limitations));
        return data;
    }
    function save(blob, filename, ctx) {
        ctx.check(); require(blob.size <= MAX_FILE);
        const url = createObjectURL(blob); urls.set(url, null);
        let link;
        try {
            ctx.check(); link = document.createElement('a'); link.href = url; link.download = filename;
            document.body.append(link); link.click();
        } catch (error) {release(url); throw error;}
        finally {link?.remove();}
        try {urls.set(url, schedule(() => release(url), 1000));} catch {release(url);}
    }
    function download(manifestOnly = false) {
        return operate(manifestOnly ? 'manifest' : 'download', async ctx => {
            require(state.job && state.job.state !== 'CANCELLED');
            const job = await ctx.job(state.job.jobId); adopt(job);
            const data = await manifest(ctx, job);
            if (manifestOnly) {
                await ctx.job(job.jobId); ctx.check();
                save(new Blob([JSON.stringify(data)], {type: 'application/json'}), 'account-export-manifest.json', ctx); return;
            }
            require(data.partCount > 0);
            const pieces = ['{"schemaVersion":1,"scope":"account-data-export","complete":false,"automaticScopeComplete":'
                + JSON.stringify(data.automaticScopeComplete) + ',"manifest":' + JSON.stringify(data) + ',"parts":['];
            let after = 0, bytes = 0;
            const scopes = new Set(data.sourceDefinitions.map(source => source.scope));
            try {
                while (after < data.partCount) {
                    const page = await ctx.call('/' + job.jobId + '/chunks?raw=true&size=3&after=' + after, 'GET', undefined, true);
                    require(page?.schemaVersion === 1 && page.jobId === job.jobId && page.ownerId === ctx.owner
                        && page.complete === false && page.expiresAt === job.expiresAt
                        && Array.isArray(page.parts) && page.parts.length > 0 && page.parts.length <= 3
                        && page.nextSequence === page.parts.at(-1).sequence && typeof page.hasMore === 'boolean');
                    for (const part of page.parts) {
                        if (after === data.partCount) break;
                        require(part.sequence === after + 1 && scopes.has(part.scope)
                            && typeof part.dataJson === 'string' && integer(part.bytes, MAX_PART) && part.bytes > 0
                            && HASH.test(part.sha256) && ['parentId', 'detailId', 'cursor'].every(key => typeof part[key] === 'string' && /^(0|[1-9]\d*)$/.test(part[key])));
                        const encoded = new TextEncoder().encode(part.dataJson);
                        require(encoded.length === part.bytes && (bytes += encoded.length) <= MAX_BYTES);
                        const digest = [...new Uint8Array(await crypto.subtle.digest('SHA-256', encoded))].map(b => b.toString(16).padStart(2, '0')).join('');
                        ctx.check(); require(digest === part.sha256);
                        let parsed; try {parsed = JSON.parse(part.dataJson);} catch {throw failure('INVALID_ACCOUNT_JOB');}
                        require(parsed && typeof parsed === 'object' && !Array.isArray(parsed));
                        if (part.scope === 'customer-profile-and-security-summary') require(ownerId(parsed.profile?.id) === ctx.owner);
                        const meta = {sequence: part.sequence, scope: part.scope, parentId: part.parentId, detailId: part.detailId,
                            cursor: part.cursor, bytes: part.bytes, sha256: part.sha256,
                            readStartedAt: part.readStartedAt, readFinishedAt: part.readFinishedAt};
                        // Embed original JSON bytes, never stringify parsed money values or large identifiers.
                        pieces.push((after ? ',' : '') + JSON.stringify(meta).slice(0, -1) + ',"data":', part.dataJson, '}');
                        after++; publish({downloadedParts: after});
                    }
                }
                require(bytes === data.recordedBytes);
                const final = await ctx.job(job.jobId); require(final.state !== 'CANCELLED' && final.policyHash === job.policyHash);
                pieces.push(']}'); ctx.check();
                save(new Blob(pieces, {type: 'application/json'}), data.automaticScopeComplete ? 'account-data-export.json' : 'account-data-export-partial.json', ctx);
            } finally {pieces.length = 0;}
        });
    }
    function subscribe(listener) {listeners.add(listener); listener(state); return () => listeners.delete(listener);}
    return Object.freeze({refresh, start, resume, pause, cancel, select, download: () => download(false),
        downloadManifest: () => download(true), cancelAll, subscribe});
}

/** Safe text-only controls; domain data and upstream error text are never inserted into the page. */
export function bindAccountExportJobsUi({jobs, root, translate: t, events, language = () => 'pl', onSignInRequired = () => {}}) {
    if (!root) return () => {};
    const nodes = Object.fromEntries([...root.querySelectorAll('[data-export-action]')].map(node => [node.dataset.exportAction, node]));
    const status = root.querySelector('[data-export-status]'), progress = root.querySelector('[data-export-progress]');
    const selector = root.querySelector('select'), expires = root.querySelector('[data-export-expires]');
    let current;
    function render() {
        const busy = current.phase === 'busy', job = current.job, active = job?.state === 'ACTIVE' || job?.state === 'PARTIAL';
        for (const node of root.querySelectorAll('[data-export-text]')) node.textContent = t(node.dataset.exportText);
        for (const [action, node] of Object.entries(nodes)) {
            node.textContent = t(action === 'resume' && job?.state === 'PARTIAL' ? 'accountJobsRetry' : 'accountJobs' + action[0].toUpperCase() + action.slice(1));
            node.disabled = busy;
        }
        nodes.start.disabled = busy || active;
        nodes.resume.hidden = !active; nodes.pause.hidden = !busy || ['discover', 'cancel'].includes(current.operation);
        nodes.pause.disabled = false;
        nodes.cancel.hidden = !job || job.state === 'CANCELLED'; nodes.cancel.disabled = busy && current.operation === 'cancel';
        nodes.download.hidden = !job || !job.partCount || job.state === 'CANCELLED';
        nodes.manifest.hidden = !job || job.state === 'CANCELLED';
        selector.disabled = busy || !current.jobs.length;
        selector.replaceChildren(...current.jobs.map(item => {
            const option = root.ownerDocument.createElement('option'); option.value = item.jobId;
            option.textContent = t('accountJobsOption' + item.state) + ' — ' + new Date(item.createdAt).toLocaleString(language(), {dateStyle: 'short', timeStyle: 'short'});
            option.selected = item.jobId === job?.jobId; return option;
        }));
        let key = current.phase === 'expired' ? 'accountJobsExpired'
            : current.phase === 'paused' ? 'accountJobsPaused'
            : current.phase === 'downloaded' ? 'accountJobsDownloaded'
            : current.phase === 'busy' ? current.operation === 'download' ? 'accountJobsDownloading' : 'accountJobsPreparing'
            : job ? 'accountJobsState' + job.state : 'accountJobsIdle';
        if (current.phase === 'error') key = [401, 403].includes(current.status) || current.code === 'SESSION_EXPIRED' ? 'accountJobsSignIn'
            : current.status === 429 ? 'accountJobsQuota'
            : current.status === 409 || current.code === 'EXPORT_ALREADY_ACTIVE' ? 'accountJobsConflict'
            : ['INVALID_ACCOUNT_JOB', 'RESPONSE_LIMIT'].includes(current.code) ? 'accountJobsInvalid' : 'accountJobsFailed';
        status.textContent = t(key);
        progress.textContent = job ? t('accountJobsProgress').replace('{done}', job.progress.done).replace('{pending}', job.progress.pending).replace('{failed}', job.progress.failed)
            + (current.operation === 'download' ? ' ' + t('accountJobsDownloadProgress').replace('{done}', current.downloadedParts).replace('{total}', job.partCount) : '') : '';
        expires.textContent = job ? t('accountJobsExpires').replace('{time}', new Date(job.expiresAt).toLocaleString(language())) : '';
        for (const action of ['start', 'resume', 'download']) nodes[action].setAttribute('aria-busy', String(busy && !nodes[action].hidden));
    }
    const unsubscribe = jobs.subscribe(value => {current = value; render();});
    const run = action => {
        const result = action === 'manifest' ? jobs.downloadManifest() : jobs[action]();
        Promise.resolve(result).catch(error => {if (error.code === 'SESSION_EXPIRED') onSignInRequired();});
    };
    const click = event => {const action = event.target.closest('[data-export-action]')?.dataset.exportAction; if (action && nodes[action] && !nodes[action].disabled) run(action);};
    const change = () => {jobs.select(selector.value).catch(() => {});};
    const opened = () => {if (!current.job && current.phase !== 'busy') run('refresh');};
    root.addEventListener('click', click); selector.addEventListener('change', change);
    events.addEventListener('minitech:language-changed', render); events.addEventListener('minitech:profile-opened', opened);
    return () => {unsubscribe(); root.removeEventListener('click', click); selector.removeEventListener('change', change);
        events.removeEventListener('minitech:language-changed', render); events.removeEventListener('minitech:profile-opened', opened);};
}
