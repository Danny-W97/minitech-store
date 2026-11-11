import { getPanelJobTitle } from './panel-i18n.js?v=20261026-1';
import { state } from './panel-state.js?v=20261027-a42';

export const $ = (selector, root = document) => root.querySelector(selector);

export const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];

export const escapeHtml = (value) => String(value ?? '').replace(/[&<>'"]/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[char]));

export const formatTime = (seconds) => seconds ? new Date(seconds * 1000).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';

export const getJobTitle = job => getPanelJobTitle(job, state.language, state.actions);
