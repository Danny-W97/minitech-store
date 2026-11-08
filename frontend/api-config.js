(function configureApi(global) {
    'use strict';

    const location = global.location;
    const localHost = ['localhost', '127.0.0.1', '[::1]'].includes(location.hostname);
    const localGateway = (localHost && location.port === '18070') || location.protocol === 'file:';
    const protocol = location.protocol === 'https:' ? 'https:' : 'http:';
    const gatewayOrigin = `${protocol}//${location.hostname || '127.0.0.1'}:18080`;
    const defaultBase = localGateway ? `${gatewayOrigin}/api` : `${location.origin}/api`;

    // Configuration comes from trusted deployment JavaScript, never URL parameters.
    // Cross-origin deployments must explicitly allow their backend's origin here.
    let baseUrl = defaultBase;
    if (global.MINITECH_API_BASE) {
        const configured = new URL(global.MINITECH_API_BASE, defaultBase);
        const allowedOrigins = new Set([location.origin]);
        if (localGateway) allowedOrigins.add(gatewayOrigin);
        if (Array.isArray(global.MINITECH_ALLOWED_API_ORIGINS)) {
            global.MINITECH_ALLOWED_API_ORIGINS.forEach(origin => allowedOrigins.add(origin));
        }
        if (!['http:', 'https:'].includes(configured.protocol)
                || !allowedOrigins.has(configured.origin)
                || (location.protocol === 'https:' && configured.protocol !== 'https:')
                || configured.username || configured.password || configured.search || configured.hash) {
            throw new Error('Invalid or untrusted MiniTech API configuration');
        }
        baseUrl = configured.href.replace(/\/$/, '');
    }

    function newAccessToken() {
        return btoa(String.fromCharCode(...global.crypto.getRandomValues(new Uint8Array(32))))
            .replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
    }
    function saveOrderAccess(id, token) {
        global.sessionStorage.setItem(`minitech.order.access.${baseUrl}.${id}`, token);
    }
    function orderAccess(id) {
        try { return global.sessionStorage.getItem(`minitech.order.access.${baseUrl}.${id}`); }
        catch { return null; }
    }
    global.MiniTechApi = Object.freeze({ baseUrl, newAccessToken, saveOrderAccess, orderAccess });
}(window));
