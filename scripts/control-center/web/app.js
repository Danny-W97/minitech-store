import { createAssistantConversation } from './assistant-conversation.js?v=20261027-1';
import { createAiDeliveryReview } from './assistant-delivery-review.js?v=20261030-1';
import { renderAssistantMarkdown } from './assistant-markdown.js?v=20261027-1';
import { createJobMonitor } from './job-monitor.js?v=20261027-1';
import { loadAiConfig, renderAiBubble } from './panel-assistant.js?v=20261027-a42';
import { bind } from './panel-bindings.js?v=20261027-a42';
import { loadActiveDataTab } from './panel-data.js?v=20261027-a42';
import { notifyDesktop, playSound, renderDiagnosticBanner } from './panel-diagnostics.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { loadHealth, startDynamicPolling } from './panel-health.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { loadJobs, showDrawer } from './panel-jobs.js?v=20261027-a42';
import { loadSecrets, renderOperations } from './panel-operations.js?v=20261027-a42';
import { renderJobCustomView } from './panel-returns.js?v=20261103-1';
import { applyLanguage, setView, setupCommandPalette } from './panel-routing.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$, escapeHtml, getJobTitle } from './panel-utils.js?v=20261027-a42';

async function init() {
    bind();
    $('#local-address').textContent = location.host;
    try {
      const data = await api('/api/bootstrap');
      state.profile = data.profile;
      state.language = data.language || 'pl';
      state.actions = data.actions;
      $('#profile-value').textContent = data.profile;
      if (data.links?.store) $('#store-link').href = data.links.store;
      if (data.links?.mailpit) $('#mailpit-link').href = data.links.mailpit;
      applyLanguage(state.language);
      renderOperations();
      setupCommandPalette();
      const hash = location.hash.slice(1); if (['dashboard','services','operations','data','events','chaos','chat','assistant','tests','jobs','terminal','about'].includes(hash)) setView(hash);
      await Promise.all([loadHealth(), loadJobs(), loadSecrets(), loadAiConfig()]);
      startDynamicPolling();
    } catch (error) { toast(state.language === 'en' ? `Dashboard failed to load configuration: ${error.message}` : `Panel nie może pobrać konfiguracji: ${error.message}`, true); }
  }

runtime.jobMonitor = createJobMonitor({
    request: (id, {signal}) => api(`/api/jobs/${encodeURIComponent(id)}`, {signal}),
    onSelect(id) {
      state.selectedJob = id;
      state.selectedJobData = null;
      const job = state.jobs.find(entry => entry.id === id);
      if (job) showDrawer(job);
      $$('.job-row').forEach(row => row.classList.toggle('active', row.dataset.job === id));
      $('#job-output').textContent = getPanelMessages(state.language).jobs.waitingOutput;
      $('#job-output').style.display = '';
      $('#job-diagnostic-slot').replaceChildren();
    },
    onPending(pending) { $('#job-output').setAttribute('aria-busy', String(pending)); },
    onData(job, {initial}) {
      state.selectedJobData = job;
      showDrawer(job);
      const out = $('#job-output');
      const wasNearBottom = (out.scrollHeight - out.scrollTop - out.clientHeight) < 80;
      const dict = getPanelMessages(state.language);
      out.textContent = job.output || (job.status === 'running' || job.status === 'queued'
        ? dict.jobs.waitingOutput : dict.jobs.noLogContent);
      const diagSlot = $('#job-diagnostic-slot');
      renderJobCustomView(diagSlot, job);
      if (initial || wasNearBottom) out.scrollTop = out.scrollHeight;
    },
    async onComplete(job, {isCurrent}) {
      const dict = getPanelMessages(state.language);
      const jobTitle = getJobTitle(job);
      playSound(job.status === 'success' ? 'success' : 'error');
      notifyDesktop(job.status === 'success' ? `✅ ${jobTitle}` : `❌ ${jobTitle}`, job.status === 'success' ? (state.language === 'en' ? 'Job completed successfully.' : 'Zadanie zakończone powodzeniem.') : (state.language === 'en' ? 'Job failed.' : 'Zadanie zakończyło się błędem.'));
      toast(job.status === 'success' ? `${dict.toasts.jobFinished}: ${jobTitle}` : `${dict.toasts.jobError}: ${jobTitle}`, job.status !== 'success');
      if (job.action === 'language-set' || job.command?.includes('language-set')) {
        const bootstrapData = await api('/api/bootstrap');
        if (!isCurrent()) return;
        state.actions = bootstrapData.actions;
        applyLanguage(bootstrapData.language);
        renderOperations();
        loadSecrets();
      }
      if (isCurrent()) { loadJobs(); loadHealth(); }
    },
    onError: error => toast(error.message, true),
  });

runtime.assistantConversation = createAssistantConversation({
    review: createAiDeliveryReview({api, dialog: document.querySelector('#ai-delivery-review'), language: () => state.language}),
    onCancel(bubble) {if (bubble) bubble.querySelector('.bubble-content').textContent = state.language === 'en' ? 'Delivery cancelled.' : 'Wysyłka anulowana.';},
    request: (body, {signal}) => api('/api/ai/chat', {method: 'POST', body: JSON.stringify(body), signal}),
    onPending(pending, stopped) {
      const button = $('#ai-send-btn');
      if (button) button.disabled = pending || stopped;
      $$('.ai-chip').forEach(chip => { chip.disabled = pending || stopped; });
      $('#ai-messages')?.setAttribute('aria-busy', String(pending));
      const input = $('#ai-input');
      if (input) input.disabled = stopped;
    },
    onStart(text) {
      renderAiBubble('user', `<p>${escapeHtml(text)}</p>`);
      const dict = getPanelMessages(state.language);
      return renderAiBubble('assistant', `<p class="muted"><em>${escapeHtml(dict.assistant.thinking)}</em></p>`);
    },
    onReply(reply, bubble) {
      if (bubble) bubble.querySelector('.bubble-content').innerHTML = renderAssistantMarkdown(reply);
      const messages = $('#ai-messages');
      if (messages) messages.scrollTop = messages.scrollHeight;
    },
    onError(error, bubble) {
      if (bubble) bubble.querySelector('.bubble-content').innerHTML = `<p style="color:var(--danger)">⚠️ ${escapeHtml(error.message)}</p>`;
    },
  });

window.minitechReloadDataTab = () => loadActiveDataTab(true);

init();
