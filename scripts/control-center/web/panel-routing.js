import { createCommandPalette } from './command-palette.js?v=20261027-1';
import { loadAiConfig, updateAiModeBadge } from './panel-assistant.js?v=20261027-a42';
import { loadChaosView } from './panel-chaos.js?v=20261027-a42';
import { loadChat } from './panel-chat.js?v=20261027-a42';
import { renderCustomersTable } from './panel-customers.js?v=20261027-a42';
import { renderOrdersTable } from './panel-orders.js?v=20261027-a42';
import { renderInvoicesTable, renderDispatchTable } from './panel-invoices.js?v=20261027-a42';
import { renderInventoryTable } from './panel-inventory.js?v=20261027-a42';
import { loadDataView } from './panel-data.js?v=20261027-a42';
import { openDiagnosticReportDialog, renderDiagnosticBanner } from './panel-diagnostics.js?v=20261027-a42';
import { loadEventsView } from './panel-events.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { renderHealth } from './panel-health.js?v=20261027-a42';
import { getPanelMessages, normalizePanelLanguage, renderPanelLanguage } from './panel-i18n.js?v=20261026-1';
import { loadJobs, renderJobsList } from './panel-jobs.js?v=20261027-a42';
import { loadSecrets, openAction, renderOperations } from './panel-operations.js?v=20261027-a42';
import { renderJobCustomView } from './panel-returns.js?v=20261103-1';
import { api, listPager } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$ } from './panel-utils.js?v=20261027-a42';

export function applyLanguage(lang) {
    state.language = normalizePanelLanguage(lang);
    const dict = getPanelMessages(state.language);
    renderPanelLanguage({ language: state.language, profile: state.profile, document,
      currentView: location.hash.slice(1) || 'dashboard' });
    listPager.refresh();
    runtime.commandPalette?.refresh();
    updateAiModeBadge();
    renderCustomersTable();
    renderOrdersTable();
    renderInvoicesTable();
    renderDispatchTable();
    renderInventoryTable();
    loadChaosView();
    renderJobsList();
    const diagSlot = $('#job-diagnostic-slot');
    if (diagSlot) {
      renderJobCustomView(diagSlot, state.selectedJobData);
    }
    const jobOutput = $('#job-output');
    if (jobOutput && !state.selectedJobData) jobOutput.textContent = dict.jobs.selectJobPrompt;
  }

export function setView(name) {
    $$('.view').forEach(node => node.classList.toggle('active', node.id === `view-${name}`));
    $$('.nav-item').forEach(node => node.classList.toggle('active', node.dataset.view === name));
    const dict = getPanelMessages(state.language);
    const viewTitle = $('#view-title');
    if (viewTitle) viewTitle.textContent = dict.viewTitles[name] || 'MiniTech Control';
    location.hash = name;
    if (name === 'chat') loadChat();
    if (name === 'assistant') loadAiConfig();
    if (name === 'jobs') loadJobs();
    if (name === 'data') loadDataView();
    if (name === 'events') loadEventsView();
    if (name === 'chaos') loadChaosView();
  }

export async function toggleLanguage() {
    const nextLang = state.language === 'en' ? 'pl' : 'en';
    try {
      const data = await api('/api/language', {method: 'POST', body: JSON.stringify({language: nextLang})});
      state.language = data.language || nextLang;
      state.actions = data.actions || state.actions;
      applyLanguage(state.language);
      renderOperations();
      renderHealth();
      renderJobsList();
      await Promise.all([loadJobs(), loadSecrets()]);
      toast(state.language === 'en' ? 'Language switched to English.' : 'Język zmieniony na polski.');
    } catch (error) {
      toast(error.message, true);
    }
  }

export function setupCommandPalette() {
    runtime.commandPalette?.dispose();
    runtime.commandPalette = createCommandPalette({
      document, eventTarget: window, getLanguage: () => state.language,
      getActions: () => state.actions, getViewTitles: () => getPanelMessages(state.language).viewTitles,
      onView: setView, onAction: openAction,
      onExternal: url => window.open(url, '_blank', 'noopener,noreferrer'),
      onReport: openDiagnosticReportDialog, onError: error => toast(error.message, true)
    });
  }
