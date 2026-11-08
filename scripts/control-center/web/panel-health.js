import { loadChat } from './panel-chat.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function renderHealth() {
    const dict = getPanelMessages(state.language);
    const online = state.health.filter(item => item.online).length;
    const total = state.health.length || 15;
    $('#metric-online').textContent = `${online}/${total}`;
    updateFaviconAndTitle(online, total);
    $('#service-preview').innerHTML = state.health.map(item => `<div class="service-preview-row"><span class="state-light ${item.online ? 'online' : ''}"></span><strong>${escapeHtml(item.name)}</strong><small>${item.online ? `${item.latencyMs} ms` : 'OFFLINE'}</small></div>`).join('') || `<div class="empty-state">${escapeHtml(dict.dashboard.emptyData)}</div>`;
    const isEn = state.language === 'en';
    const s = dict.services;
    $('#service-grid').innerHTML = state.health.map(item => `
      <article class="service-card">
        <div>
          <header>
            <h3>${escapeHtml(item.name)} Service</h3>
            <span class="status-tag ${item.online ? 'online' : ''}">${item.online ? 'UP' : 'OFFLINE'}</span>
          </header>
          <p>127.0.0.1:${item.port} · ${item.online ? `${item.latencyMs} ms` : (isEn ? 'no response' : 'brak odpowiedzi')}</p>
          ${item.memory ? `<div class="service-stats-pill" title="${item.cpu ? `CPU: ${escapeHtml(item.cpu)}` : ''}">🧠 ${escapeHtml(item.memory.split('/')[0].trim())}${item.cpu ? ` • ${escapeHtml(item.cpu)}` : ''}</div>` : ''}
        </div>
        <div class="service-card-actions">
          <button class="service-action-btn run-btn" data-action="app-run" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Start service' : 'Uruchom serwis'}">▶ ${escapeHtml(s.btnStart || 'Start')}</button>
          <button class="service-action-btn stop-btn" data-action="app-stop" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Stop service' : 'Zatrzymaj serwis'}">⏹ ${escapeHtml(s.btnStop || 'Stop')}</button>
          <button class="service-action-btn log-btn" data-action="app-logs" data-service="${escapeHtml(item.alias || item.name.toLowerCase())}" title="${isEn ? 'Show logs' : 'Pokaż logi'}">📄 ${escapeHtml(s.btnLogs || 'Logi')}</button>
        </div>
      </article>
    `).join('');
  }

export async function loadHealth(silent = false) {
    if (runtime.isHealthLoading) return;
    runtime.isHealthLoading = true;
    const btn = $('#health-refresh');
    if (!silent && btn) btn.disabled = true;
    try {
      const data = await api('/api/health');
      state.health = data.services;
      renderHealth();
    } catch (error) {
      if (!silent) toast(error.message, true);
    } finally {
      runtime.isHealthLoading = false;
      if (!silent && btn) btn.disabled = false;
    }
  }

export function startDynamicPolling() {
    if (runtime.dynamicPollTimer) clearInterval(runtime.dynamicPollTimer);
    runtime.dynamicPollTimer = setInterval(() => {
      if (!document.hidden) {
        loadHealth(true);
        if ($('#view-chat')?.classList.contains('active')) {
          loadChat();
        }
      }
    }, 4000);
  }

export function updateFaviconAndTitle(online, total) {
    if (total > 0 && online === total) {
      document.title = `🟢 (${online}/${total}) MiniTech Control`;
    } else if (total > 0 && online < total) {
      document.title = `🔴 (${online}/${total}) MiniTech Control`;
    } else {
      document.title = `MiniTech Control`;
    }
  }
