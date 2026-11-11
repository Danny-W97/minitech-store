import { renderDiagnosticBanner } from './panel-diagnostics.js?v=20261027-a42';
import { openAction, generateUuid } from './panel-operations.js?v=20261027-a42';
import { state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function extractReturnsData(text) {
  if (!text || typeof text !== 'string') return null;
  // 1. Check for embedded machine comment
  const commentMatch = text.match(/<!--\s*CC_RETURNS_JSON:\s*(\[[\s\S]*?\])\s*-->/);
  if (commentMatch) {
    try {
      const arr = JSON.parse(commentMatch[1]);
      if (Array.isArray(arr)) return arr;
    } catch (e) {}
  }
  // 2. Check for JSON array in text (e.g. from historical job logs)
  const start = text.indexOf('[');
  const end = text.lastIndexOf(']');
  if (start !== -1 && end > start) {
    try {
      const candidate = text.slice(start, end + 1);
      const arr = JSON.parse(candidate);
      if (Array.isArray(arr) && arr.length > 0 && (arr[0].requestType !== undefined || arr[0].effectsState !== undefined)) {
        return arr;
      }
    } catch (e) {}
  }
  return null;
}

function formatDate(isoStr) {
  if (!isoStr) return '-';
  try {
    const d = new Date(isoStr);
    if (isNaN(d.getTime())) return isoStr.slice(0, 19).replace('T', ' ');
    return d.toLocaleString('pl-PL', {
      year: 'numeric', month: '2-digit', day: '2-digit',
      hour: '2-digit', minute: '2-digit', second: '2-digit'
    });
  } catch (e) {
    return isoStr.slice(0, 19).replace('T', ' ');
  }
}

function getStatusBadge(status) {
  const st = String(status || '').toUpperCase();
  let badgeClass = 'status-requested';
  if (st === 'APPROVED') badgeClass = 'status-approved';
  else if (st === 'REJECTED') badgeClass = 'status-rejected';
  else if (st === 'UNDER_REVIEW') badgeClass = 'status-under-review';
  else if (st === 'COMPLETED') badgeClass = 'status-completed';
  return `<span class="badge-status ${badgeClass}">${escapeHtml(st)}</span>`;
}

function getTypeBadge(reqType, lang) {
  const t = String(reqType || '').toUpperCase();
  const isEn = lang === 'en';
  if (t === 'COMPLAINT') {
    return `<span class="badge-status type-complaint">${isEn ? 'COMPLAINT' : 'REKLAMACJA'}</span>`;
  }
  return `<span class="badge-status type-return">${isEn ? 'RETURN' : 'ZWROT'}</span>`;
}

export function renderJobCustomView(container, job) {
  if (!container) return;
  if (!job) {
    container.replaceChildren();
    const out = $('#job-output');
    if (out) out.style.display = '';
    return;
  }

  // 1. If job failed / has diagnosis banner, prioritize diagnosis
  if (job.diagnosis) {
    container.innerHTML = renderDiagnosticBanner(job.diagnosis, job);
    const out = $('#job-output');
    if (out) out.style.display = '';
    return;
  }

  // 2. Check if job is returns-list or contains returns data
  const isReturnsJob = job.action === 'returns-list' || job.command?.includes('returns-list');
  const returnsData = extractReturnsData(job.output);

  if (!isReturnsJob && (!returnsData || !returnsData.length)) {
    container.replaceChildren();
    const out = $('#job-output');
    if (out) out.style.display = '';
    return;
  }

  const lang = state.language || 'pl';
  const isEn = lang === 'en';

  if (!returnsData || !returnsData.length) {
    container.innerHTML = `
      <div class="returns-job-panel">
        <div class="returns-job-header">
          <div class="returns-job-title-group">
            <span class="returns-job-icon">🔄</span>
            <h3 class="returns-job-title">${isEn ? 'Returns & Complaints' : 'Zgłoszenia zwrotów i reklamacji'}</h3>
          </div>
        </div>
        <div class="table-empty">${isEn ? 'No return or complaint tickets found.' : 'Brak zgłoszeń zwrotów lub reklamacji.'}</div>
      </div>
    `;
    const out = $('#job-output');
    if (out) out.style.display = '';
    return;
  }

  // Build the returns table HTML
  const rowsHtml = returnsData.map((r, idx) => {
    const id = r.id ?? '?';
    const ordId = r.orderId ?? '-';
    const email = r.customerEmail || '-';
    const custId = r.customerId ? `<small style="color:var(--muted); margin-left:4px;">(#${r.customerId})</small>` : '';
    const dateFormatted = formatDate(r.createdAt);
    const typeBadge = getTypeBadge(r.requestType, lang);
    const statusBadge = getStatusBadge(r.status);
    const effects = r.effectsState || '-';
    
    // Items
    let itemsHtml = '<span style="color:var(--muted);">-</span>';
    if (r.items && r.items.length) {
      itemsHtml = r.items.map(it => {
        const name = escapeHtml(it.productName || (isEn ? 'Product' : 'Produkt'));
        const qty = it.quantity ?? 1;
        return `<div><strong>${name}</strong> <span class="badge-status" style="background:rgba(255,255,255,0.06); padding:1px 6px;">x${qty}</span></div>`;
      }).join('');
    }

    const reason = escapeHtml(r.reason || '-');
    const desc = escapeHtml(r.description || (isEn ? 'No description provided.' : 'Brak dodatkowego opisu klienta.'));
    const ver = r.version ?? 0;
    const reqRec = r.requiresReconciliation ? (isEn ? 'Yes (reconciliation required)' : 'TAK (wymaga uzgodnienia)') : (isEn ? 'No' : 'NIE');
    const modNote = r.moderationNote ? `<div style="margin-top:4px;"><strong style="color:#fbbf24;">${isEn ? 'Moderator note' : 'Notatka moderatora'}:</strong> <span>${escapeHtml(r.moderationNote)}</span></div>` : '';

    return `
      <tr class="return-main-row" data-ret-id="${id}">
        <td><strong style="color:#38bdf8; font-size:13px;">#${id}</strong></td>
        <td><strong style="color:#f8fafc;">#${ordId}</strong></td>
        <td>${typeBadge}</td>
        <td>${statusBadge}</td>
        <td><code style="font-size:11px; background:rgba(255,255,255,0.05); padding:2px 6px; border-radius:4px; color:#cbd5e1;">${escapeHtml(effects)}</code></td>
        <td style="white-space:nowrap; font-size:12px; color:#cbd5e1;">${dateFormatted}</td>
        <td style="font-size:12px;">${escapeHtml(email)}${custId}</td>
        <td style="font-size:12px;">${itemsHtml}</td>
        <td style="white-space:nowrap;">
          <div style="display:flex; gap:6px; align-items:center;">
            <button type="button" class="btn-mini btn-toggle-ret-detail" data-target="ret-exp-${id}" title="${isEn ? 'Toggle details' : 'Pokaż/ukryj szczegóły'}">
              <span class="chevron-icon">▾</span> ${isEn ? 'Details' : 'Szczegóły'}
            </button>
            <button type="button" class="btn-mini btn-action-ret-status" data-id="${id}" data-version="${ver}" style="color:#38bdf8; border-color:rgba(56,189,248,0.4);" title="${isEn ? 'Change status' : 'Zmień status zgłoszenia'}">
              ⚙️ ${isEn ? 'Status' : 'Status'}
            </button>
          </div>
        </td>
      </tr>
      <tr class="return-expand-row" id="ret-exp-${id}" style="display:none;">
        <td colspan="9" style="padding:0;">
          <div class="return-expand-box">
            <div style="display:flex; gap:8px;">
              <span class="field-label">${isEn ? 'Reason:' : 'Powód:'}</span>
              <span style="font-weight:600; color:#f1f5f9;">${reason}</span>
            </div>
            <div style="display:flex; gap:8px; align-items:flex-start;">
              <span class="field-label">${isEn ? 'Customer description:' : 'Opis klienta:'}</span>
              <span style="color:#e2e8f0; line-height:1.45; background:rgba(0,0,0,0.2); padding:8px 12px; border-radius:6px; flex:1;">${desc}</span>
            </div>
            <div style="display:flex; flex-wrap:wrap; gap:18px; color:var(--muted); font-size:11px; margin-top:4px; padding-top:6px; border-top:1px solid rgba(255,255,255,0.05);">
              <span>${isEn ? 'Version' : 'Wersja'}: <strong>${ver}</strong></span>
              <span>${isEn ? 'Reconciliation required' : 'Wymaga uzgodnienia'}: <strong>${reqRec}</strong></span>
              <span>${isEn ? 'Updated' : 'Aktualizacja'}: <strong>${formatDate(r.updatedAt)}</strong></span>
              ${modNote}
            </div>
          </div>
        </td>
      </tr>
    `;
  }).join('');

  container.innerHTML = `
    <div class="returns-job-panel">
      <div class="returns-job-header">
        <div class="returns-job-title-group">
          <span class="returns-job-icon">🔄</span>
          <h3 class="returns-job-title">${isEn ? 'Returns & Complaints' : 'Tabela zwrotów i reklamacji'}</h3>
          <span class="badge-status completed" style="font-size:11px;">${isEn ? `Total: ${returnsData.length}` : `Łącznie: ${returnsData.length}`}</span>
        </div>
        <div style="display:flex; align-items:center; gap:8px;">
          <button type="button" class="btn-mini btn-toggle-raw-log" id="btn-toggle-raw-log">
            <span>📋</span> <span id="raw-log-toggle-text">${isEn ? 'Show Console Log' : 'Pokaż log konsoli'}</span>
          </button>
        </div>
      </div>
      <div class="table-container" style="max-height: 520px; overflow-y: auto;">
        <table class="data-table">
          <thead>
            <tr>
              <th>ID</th>
              <th>${isEn ? 'Order' : 'Zamówienie'}</th>
              <th>${isEn ? 'Type' : 'Typ'}</th>
              <th>Status</th>
              <th>${isEn ? 'Effects' : 'Skutki domeny'}</th>
              <th>${isEn ? 'Date' : 'Data zgłoszenia'}</th>
              <th>${isEn ? 'Customer' : 'Klient'}</th>
              <th>${isEn ? 'Items' : 'Pozycje'}</th>
              <th>${isEn ? 'Actions' : 'Akcje'}</th>
            </tr>
          </thead>
          <tbody>
            ${rowsHtml}
          </tbody>
        </table>
      </div>
    </div>
  `;

  // By default when table is rendered, hide the raw <pre id="job-output">
  const out = $('#job-output');
  if (out) out.style.display = 'none';

  // Attach event handlers
  const toggleBtn = container.querySelector('#btn-toggle-raw-log');
  if (toggleBtn && out) {
    toggleBtn.onclick = () => {
      const isHidden = out.style.display === 'none';
      out.style.display = isHidden ? 'block' : 'none';
      const label = container.querySelector('#raw-log-toggle-text');
      if (label) {
        label.textContent = isHidden
          ? (isEn ? 'Hide Console Log' : 'Ukryj log konsoli')
          : (isEn ? 'Show Console Log' : 'Pokaż log konsoli');
      }
    };
  }

  // Row expand toggles
  container.querySelectorAll('.btn-toggle-ret-detail').forEach(btn => {
    btn.onclick = () => {
      const targetId = btn.dataset.target;
      const targetRow = container.querySelector(`#${targetId}`);
      if (targetRow) {
        const isCollapsed = targetRow.style.display === 'none';
        targetRow.style.display = isCollapsed ? 'table-row' : 'none';
        btn.querySelector('.chevron-icon').textContent = isCollapsed ? '▴' : '▾';
      }
    };
  });

  // Action status button
  container.querySelectorAll('.btn-action-ret-status').forEach(btn => {
    btn.onclick = () => {
      const retId = btn.dataset.id;
      const ver = btn.dataset.version || '0';
      openAction('returns-status', { id: retId, version: ver, operation: generateUuid() });
    };
  });
}
