import { toast } from './panel-feedback.js?v=20261027-a42';
import { getPanelMessages } from './panel-i18n.js?v=20261026-1';
import { pollJob, showDrawer } from './panel-jobs.js?v=20261027-a42';
import { openAction } from './panel-operations.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function renderDiagnosticBanner(diag, job) {
    if (!diag) return '';
    const lang = state.language || 'pl';
    const dict = getPanelMessages(lang);
    const dDiag = dict.diagnostics || {};
    const title = diag.title?.[lang] || diag.title?.pl || dDiag.detectedProblem || (lang === 'en' ? 'Detected Problem' : 'Wykryty problem');
    const cause = diag.cause?.[lang] || diag.cause?.pl || '';
    const solution = diag.solution?.[lang] || diag.solution?.pl || '';
    const fixLabel = diag.fixButtonLabel?.[lang] || diag.fixButtonLabel?.pl || dDiag.quickFix || (lang === 'en' ? 'Quick Fix' : 'Napraw automatycznie');
    const isUnknown = !diag.matched;
    const tagLabel = isUnknown ? (dDiag.unknownIssue || (lang === 'en' ? 'Unknown Issue' : 'Nieznany błąd')) : (diag.category?.toUpperCase() || 'DIAGNOSTIC');
    const icon = isUnknown ? '❓' : '🚨';

    let fixButtonHtml = '';
    if (diag.fixAction) {
      fixButtonHtml = `<button type="button" class="btn-fix-action" data-fix-action="${escapeHtml(diag.fixAction)}"><span>🛠</span> ${escapeHtml(fixLabel)}</button>`;
    }

    const askAiLabel = dDiag.askAiBtn || (lang === 'en' ? '✦ Ask AI Assistant' : '✦ Zapytaj Asystenta AI');
    const askAiHtml = `<button type="button" class="btn-ask-ai" data-ask-ai-job="${escapeHtml(job?.id || '')}"><span>✦</span> ${escapeHtml(askAiLabel.replace(/^✦\s*/, ''))}</button>`;

    const causeHeading = dDiag.causeLabel || (lang === 'en' ? '👉 Cause:' : '👉 Przyczyna:');
    const solHeading = dDiag.solutionLabel || (lang === 'en' ? '💡 Solution:' : '💡 Rozwiązanie:');

    return `
      <div class="diagnostic-banner">
        <div class="diagnostic-header">
          <div class="diagnostic-title-group">
            <span class="diagnostic-icon">${icon}</span>
            <h4 class="diagnostic-title">${escapeHtml(title)}</h4>
          </div>
          <span class="diagnostic-tag ${isUnknown ? 'unknown' : ''}">${escapeHtml(tagLabel)}</span>
        </div>
        <div class="diagnostic-content">
          <div class="diagnostic-field">
            <strong>${causeHeading}</strong>
            <span>${escapeHtml(cause)}</span>
          </div>
          <div class="diagnostic-field">
            <strong>${solHeading}</strong>
            <span>${escapeHtml(solution)}</span>
          </div>
        </div>
        <div class="diagnostic-actions">
          ${fixButtonHtml}
          ${askAiHtml}
        </div>
      </div>
    `;
  }

export async function triggerDirectAction(actionOrId) {
    const actionId = typeof actionOrId === 'object' && actionOrId !== null ? actionOrId.id : actionOrId;
    const item = state.actions.find(entry => entry.id === actionId);
    if (!item) return toast(state.language === 'en' ? `Action not found: ${actionId}` : `Nie znaleziono akcji: ${actionId}`, true);

    if (item.danger || item.kind?.startsWith('secret-')) {
      return openAction(item.id);
    }

    const values = {};
    let hasRequiredEmptyField = false;
    if (item.fields?.length) {
      for (const field of item.fields) {
        if (field.default !== undefined && field.default !== null && field.default !== '') {
          values[field.name] = String(field.default);
        } else if (field.required) {
          hasRequiredEmptyField = true;
        }
      }
    }

    if (hasRequiredEmptyField) {
      return openAction(item.id);
    }

    const dict = getPanelMessages(state.language);
    try {
      const job = await api('/api/jobs', {method: 'POST', body: JSON.stringify({action: item.id, values, confirmed: true})});
      showDrawer(job);
      pollJob(job.id);
      toast(`${dict.toasts.jobStarted}: ${item.title}`);
    } catch (error) {
      toast(error.message, true);
    }
  }

export function playSound(type) {
    try {
      const AudioCtx = window.AudioContext || window.webkitAudioContext;
      if (!AudioCtx) return;
      const ctx = new AudioCtx();
      const now = ctx.currentTime;
      const osc = ctx.createOscillator();
      const gain = ctx.createGain();
      osc.connect(gain);
      gain.connect(ctx.destination);
      if (type === 'success') {
        osc.type = 'sine';
        osc.frequency.setValueAtTime(587.33, now);
        osc.frequency.exponentialRampToValueAtTime(880.00, now + 0.12);
        gain.gain.setValueAtTime(0.12, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.35);
        osc.start(now);
        osc.stop(now + 0.35);
      } else if (type === 'error') {
        osc.type = 'triangle';
        osc.frequency.setValueAtTime(240, now);
        osc.frequency.setValueAtTime(180, now + 0.14);
        gain.gain.setValueAtTime(0.18, now);
        gain.gain.exponentialRampToValueAtTime(0.001, now + 0.4);
        osc.start(now);
        osc.stop(now + 0.4);
      }
    } catch (_) {}
  }

export function notifyDesktop(title, body) {
    if (typeof Notification !== 'undefined') {
      if (Notification.permission === 'granted') {
        try { new Notification(title, { body }); } catch (_) {}
      } else if (Notification.permission === 'default') {
        Notification.requestPermission();
      }
    }
  }

export async function openDiagnosticReportDialog() {
    const dialog = $('#report-dialog');
    if (!dialog) return;

    const isEn = state.language === 'en';
    const eyebrow = $('#report-eyebrow');
    if (eyebrow) eyebrow.textContent = isEn ? 'ENVIRONMENT DIAGNOSTIC REPORT' : 'RAPORT DIAGNOSTYCZNY ŚRODOWISKA';
    const title = $('#report-title');
    if (title) title.textContent = isEn ? '📋 MiniTech Platform Status' : '📋 Stan Platformy MiniTech';
    const okBtn = $('#report-ok-btn');
    if (okBtn) okBtn.textContent = 'OK';
    const cancelBtn = $('#report-cancel-btn');
    if (cancelBtn) cancelBtn.textContent = isEn ? 'Cancel' : 'Anuluj';
    const copyBtn = $('#report-copy-md');
    if (copyBtn) copyBtn.textContent = isEn ? '📋 Copy to clipboard' : '📋 Kopiuj do schowka';

    $('#report-body').innerHTML = '<div class="skeleton" style="height: 220px; border-radius: 12px;"></div>';
    $('#report-chip-time').textContent = isEn ? 'Fetching…' : 'Pobieranie…';
    
    dialog.showModal();

    try {
      const data = await api('/api/system/diagnostic-report');
      runtime.currentReportMarkdown = data.markdown || '';

      const online = data.onlineCount ?? 0;
      const total = data.totalCount ?? (data.services?.length || 15);
      const isAllUp = online === total;

      $('#report-chip-profile').textContent = `${isEn ? 'Profile' : 'Profil'}: ${escapeHtml(data.profile || state.profile).toUpperCase()}`;
      const healthChip = $('#report-chip-health');
      healthChip.textContent = `${online}/${total} ONLINE ${isAllUp ? '🟢' : '🟡'}`;
      healthChip.className = `report-chip ${isAllUp ? 'online' : ''}`;
      $('#report-chip-time').textContent = data.timestamp || new Date().toLocaleString();

      const sTitle = isEn ? 'Microservices & Gateways (15 services)' : 'Mikroserwisy i bramki (15 usług)';
      const secTitle = isEn ? 'SOPS & Age Secrets Vault' : 'Skarbiec Sekretów SOPS / Age';
      const jTitle = isEn ? 'Recent Operations & Session Jobs' : 'Ostatnie Zadania i Logi Sesji';

      const servicesHtml = (data.services || []).map(s => `
        <div class="report-service-item">
          <span class="state-light ${s.online ? 'online' : ''}"></span>
          <strong>${escapeHtml(s.name)}</strong>
          <small>${s.online ? `${s.latencyMs} ms` : 'OFFLINE'}</small>
        </div>
      `).join('') || `<div class="muted">${isEn ? 'No service data' : 'Brak danych o serwisach'}</div>`;

      const profiles = data.secretProfiles || {};
      const secretsHtml = Object.keys(profiles).map(k => {
        const p = profiles[k] || {};
        const keyOk = p.key ? '🟢 OK' : '🔴 BRAK';
        const fileOk = p.file ? '🟢 OK' : '🔴 BRAK';
        return `
          <div class="report-secret-card">
            <header>Profil ${escapeHtml(k).toUpperCase()}</header>
            <div class="report-secret-row"><span>Klucz Age:</span><strong>${keyOk}</strong></div>
            <div class="report-secret-row"><span>Plik SOPS:</span><strong>${fileOk}</strong></div>
          </div>
        `;
      }).join('') || `<div class="muted">${isEn ? 'No secrets data' : 'Brak danych o sekretach'}</div>`;

      const jobs = data.recentJobs || [];
      const jobsHtml = jobs.length > 0 ? jobs.map(j => `
        <div class="report-job-row">
          <div>
            <strong>${escapeHtml(j.title)}</strong>
            <small style="color:var(--muted); margin-left: 6px;">(${escapeHtml(j.actionId)})</small>
          </div>
          <div>
            <span class="status-tag ${j.status === 'success' ? 'online' : ''}">${escapeHtml(j.status)}</span>
          </div>
        </div>
      `).join('') : `<div class="muted" style="font-size:12px; padding: 4px 0;">${isEn ? 'No jobs recorded in current session.' : 'Brak zarejestrowanych zadań w bieżącej sesji.'}</div>`;

      $('#report-body').innerHTML = `
        <section class="report-section">
          <div class="report-section-title">
            <span>🌐 ${escapeHtml(sTitle)}</span>
            <small style="color:${isAllUp ? 'var(--green)' : 'var(--accent)'}; font-weight:700;">${online}/${total} UP</small>
          </div>
          <div class="report-services-grid">${servicesHtml}</div>
        </section>

        <section class="report-section">
          <div class="report-section-title">
            <span>🔒 ${escapeHtml(secTitle)}</span>
          </div>
          <div class="report-secrets-grid">${secretsHtml}</div>
        </section>

        <section class="report-section">
          <div class="report-section-title">
            <span>🐳 ${escapeHtml(jTitle)}</span>
          </div>
          <div class="report-jobs-list">${jobsHtml}</div>
        </section>
      `;

      playSound('open');
    } catch (err) {
      $('#report-body').innerHTML = `<div class="danger-confirm">${escapeHtml(err.message)}</div>`;
      playSound('error');
    }
  }
