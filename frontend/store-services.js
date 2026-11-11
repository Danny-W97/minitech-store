import { createCustomerSession, canRefreshCustomerPath } from './customer-session.js?v=20261027-1';
import { createInvoiceDownloads } from './invoice-downloads.js?v=20261026-1';
import { createAccountExportDownloads } from './account-export.js?v=20261028-a43-32';
import { createAccountExportJobs } from './account-export-jobs.js?v=20261028-a43-32';
import { clearCustomerSession, expireBrowserSession, setSession } from './store-account.js?v=20261028-a43-32';
import { createStoreClient } from './store-client.js?v=20261026-2';
import { sessionGuard, state } from './store-state.js?v=20261028-a43-32';

export function getApiBase() {
    return window.MiniTechApi.baseUrl;
}

export async function api(path, options = {}, retryAfterRefresh = true) {
    const perform = signal => storeClient.request(path, options, retryAfterRefresh, signal);
    return options.timeoutMs ? window.MiniTechCatalogData.withDeadline(perform, { signal: options.signal, timeoutMs: options.timeoutMs }) : perform(options.signal);
}

export function canRefresh(path) {
    return canRefreshCustomerPath(path);
}

export function refreshSession() { return customerSession.refresh(); }

export function restoreSession() { return customerSession.restore({attempts: 3}); }

export const API_BASE = getApiBase();

export const customerSession = createCustomerSession({baseUrl: API_BASE, guard: sessionGuard,
    onSession: setSession, expire: expireBrowserSession,
    onFailure: ({status}) => {if (status === 401 || status === 403) clearCustomerSession('session-expired');}});

export const storeClient = createStoreClient({
    baseUrl: API_BASE, getAccessToken: () => state.accessToken, refreshSession, canRefresh,
    retry: (path, options, signal) => api(path, { ...options, signal }, false)
});

export const invoiceDownloads = createInvoiceDownloads({
    requestBlob: storeClient.blob, document,
    getOwner: () => state.customer?.id ?? null,
    getOrderAccess: id => window.MiniTechApi.orderAccess(id)
});

export const accountExportDownloads = createAccountExportDownloads({
    requestBlob: storeClient.blob, document, getOwner: () => state.customer?.id ?? null
});

export const accountExportJobs = createAccountExportJobs({request: api, document, getOwner: () => state.customer?.id ?? null});
