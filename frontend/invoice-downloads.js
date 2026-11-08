/** Invoice download lifecycle. HTTP authorization remains in the injected store client. */
export function createInvoiceDownloads({
    requestBlob, getOwner = () => null, getOrderAccess = () => null, document,
    createObjectURL = blob => globalThis.URL.createObjectURL(blob),
    revokeObjectURL = url => globalThis.URL.revokeObjectURL(url),
    setTimeout: schedule = globalThis.setTimeout.bind(globalThis)
}) {
    const pending = new Map();
    let generation = 0;
    const aborted = () => new DOMException('Pobieranie faktury zostało anulowane.', 'AbortError');

    function download(id) {
        if (!['string', 'number'].includes(typeof id) || (typeof id === 'number' && !Number.isSafeInteger(id))) {
            return Promise.reject(new Error('Nieprawidłowy identyfikator zamówienia.'));
        }
        const orderId = String(id);
        if (!/^[1-9]\d*$/.test(orderId)) return Promise.reject(new Error('Nieprawidłowy identyfikator zamówienia.'));
        const owner = getOwner();
        const capability = getOrderAccess(orderId);
        const key = JSON.stringify([orderId, owner, capability]);
        if (pending.has(key)) return pending.get(key).promise;
        const version = generation;
        const controller = new AbortController();
        const isStale = () => controller.signal.aborted || version !== generation
            || getOwner() !== owner || getOrderAccess(orderId) !== capability;
        const operation = Promise.resolve().then(async () => {
            if (isStale()) throw aborted();
            const headers = new Headers({Accept: 'application/pdf'});
            if (capability) headers.set('X-Order-Access-Token', capability);
            try {
                const blob = await requestBlob(`/invoices/order/${orderId}/pdf`, {headers, signal: controller.signal, anonymous: owner === null});
                if (isStale()) throw aborted();
                const url = createObjectURL(blob);
                try {
                    const link = document.createElement('a');
                    link.href = url;
                    link.download = `invoice-${orderId}.pdf`;
                    link.click();
                } finally {
                    try { schedule(() => revokeObjectURL(url), 1000); }
                    catch { revokeObjectURL(url); }
                }
            } catch (error) {
                if (isStale()) throw aborted();
                throw error;
            }
        }).finally(() => {
            // An older cancelled operation must not delete a newer download of this order.
            if (pending.get(key)?.promise === operation) pending.delete(key);
        });
        pending.set(key, {promise: operation, controller});
        return operation;
    }

    function cancelAll() {
        generation++;
        for (const {controller} of pending.values()) controller.abort();
        pending.clear();
    }

    return Object.freeze({download, cancelAll});
}
