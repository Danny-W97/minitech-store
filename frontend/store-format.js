import { i18n } from './i18n.js?v=20261028-a43-32';


export function roundMoney(value) {
    return Math.round((Number(value || 0) + Number.EPSILON) * 100) / 100;
}

export function formatDeliveryDate(value) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!value) return isEn ? 'no date' : 'brak terminu';
    const date = new Date(`${value}T12:00:00`);
    return Number.isNaN(date.getTime())
        ? value
        : date.toLocaleDateString(isEn ? 'en-US' : 'pl-PL', { weekday: 'long', day: '2-digit', month: '2-digit', year: 'numeric' });
}

export function formatOrderDate(value) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    if (!value) return isEn ? 'Date unavailable' : 'Data niedostępna';
    const date = new Date(value);
    return Number.isNaN(date.getTime())
        ? String(value)
        : date.toLocaleString(isEn ? 'en-US' : 'pl-PL', { dateStyle: 'medium', timeStyle: 'short' });
}

export function finitePrice(primary, fallback = 0) {
    const first = Number(primary);
    if (Number.isFinite(first)) return first;
    const second = Number(fallback);
    return Number.isFinite(second) ? second : 0;
}

export function formatMoney(value) {
    if (i18n) return i18n.formatMoney(value);
    return `${Number(value || 0).toLocaleString('pl-PL', { minimumFractionDigits: 2, maximumFractionDigits: 2 })} zł`;
}

export function escapeHtml(value) {
    return String(value ?? '').replace(/[&<>'"]/g, character => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[character]));
}
