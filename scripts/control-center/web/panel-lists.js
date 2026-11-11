/** Cursor-based CC reads. Each list owns its cursors, cancellation and debounce. */
export function createPanelLists({
    request, document, getLanguage = () => 'pl', onError = () => {},
    setTimeout: schedule = globalThis.setTimeout.bind(globalThis),
    clearTimeout: cancel = globalThis.clearTimeout.bind(globalThis)
}) {
    const pages = new Map();
    let disposed = false;

    function pageFor(kind) {
        if (!pages.has(kind)) pages.set(kind, { cursors: ['0'], next: '0', more: false, generation: 0, controller: null, timer: null });
        return pages.get(kind);
    }

    function invalidate(page) {
        page.generation++;
        page.controller?.abort();
        page.controller = null;
    }

    async function fetchPage(kind, url, filters = {}) {
        if (disposed) return { stale: true };
        const page = pageFor(kind);
        invalidate(page);
        const generation = page.generation;
        const controller = page.controller = new AbortController();
        const cursors = [...page.cursors];
        const params = new URLSearchParams({ ...filters, cursor: cursors.at(-1), size: '50' });
        updateControls(kind);
        try {
            const result = await request(`${url}?${params}`, { signal: controller.signal });
            if (generation !== page.generation) return { stale: true };
            if (!result || typeof result !== 'object' || Array.isArray(result)) throw new Error('Nieprawidłowa odpowiedź listy.');
            page.next = String(result.nextCursor || '0');
            page.more = result.hasMore === true;
            page.shown = [...cursors];
            return result;
        } catch (error) {
            if (generation !== page.generation) return { stale: true };
            if (page.shown) page.cursors = [...page.shown];
            throw error;
        } finally {
            if (page.controller === controller) {
                page.controller = null;
                updateControls(kind);
            }
        }
    }

    function updateControls(kind) {
        const page = pageFor(kind);
        const { anchor, reload } = page;
        if (!anchor) return;
        let controls = document.getElementById(`list-page-${kind}`);
        if (!controls) {
            controls = document.createElement('div');
            controls.id = `list-page-${kind}`;
            controls.className = 'list-pagination';
            (kind.startsWith('correction-') ? anchor : (anchor.closest('table')?.parentElement || anchor)).after(controls);
        }
        const en = getLanguage() === 'en';
        const busy = disposed || page.controller !== null || page.timer !== null;
        controls.setAttribute('aria-busy', String(busy));
        controls.innerHTML = `<span>${en ? 'Page' : 'Strona'} ${page.cursors.length} · ${en ? 'up to 50 rows; sorting and totals apply to this page' : 'do 50 wierszy; sortowanie i sumy dotyczą tej strony'}</span>
            <button type="button" class="ghost-button" data-first ${busy || page.cursors.length === 1 ? 'disabled' : ''}>${en ? 'First' : 'Początek'}</button>
            <button type="button" class="ghost-button" data-prev ${busy || page.cursors.length === 1 ? 'disabled' : ''}>${en ? 'Previous' : 'Poprzednia'}</button>
            <button type="button" class="ghost-button" data-next ${busy || !page.more ? 'disabled' : ''}>${en ? 'Next' : 'Następna'}</button>`;
        for (const [selector, action] of [
            ['[data-first]', () => { page.cursors = ['0']; }],
            ['[data-prev]', () => page.cursors.pop()],
            ['[data-next]', () => page.cursors.push(page.next)]
        ]) {
            controls.querySelector(selector).onclick = async event => {
                if (disposed || page.controller || page.timer !== null || event.currentTarget.disabled) return;
                const previous = [...page.cursors];
                action();
                let generation = page.generation;
                try {
                    const operation = reload(true);
                    generation = page.generation;
                    await operation;
                } catch (error) {
                    if (generation === page.generation) {
                        page.cursors = previous;
                        onError(error);
                    }
                } finally { updateControls(kind); }
            };
        }
    }

    function render(kind, anchor, reload) {
        if (!anchor) return;
        Object.assign(pageFor(kind), { anchor, reload });
        updateControls(kind);
    }

    function filter(kind, reload) {
        if (disposed) return;
        const page = pageFor(kind);
        if (page.timer !== null) cancel(page.timer);
        invalidate(page);
        page.cursors = ['0'];
        // A failed filter must not restore a cursor belonging to the old query.
        page.shown = null;
        page.next = '0';
        page.more = false;
        page.timer = schedule(async () => {
            page.timer = null;
            let generation = page.generation;
            try {
                const operation = reload(true);
                generation = page.generation;
                await operation;
            } catch (error) {
                if (generation === page.generation) onError(error);
            } finally { updateControls(kind); }
        }, 300);
        updateControls(kind);
    }

    function dispose() {
        disposed = true;
        for (const [kind, page] of pages) {
            if (page.timer !== null) cancel(page.timer);
            page.timer = null;
            invalidate(page);
            updateControls(kind);
        }
    }

    function refresh() {
        for (const kind of pages.keys()) updateControls(kind);
    }

    return Object.freeze({ fetch: fetchPage, render, filter, refresh, dispose });
}
