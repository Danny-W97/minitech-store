import { toast } from './panel-feedback.js?v=20261027-a42';
import { api } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$, escapeHtml } from './panel-utils.js?v=20261027-a42';

export function setupEventsViewOnce() {
    if (runtime.eventsViewSetupDone) return;
    runtime.eventsViewSetupDone = true;

    $$('.sub-tab-btn[data-eventtab]').forEach(btn => {
      btn.onclick = () => {
        runtime.eventSubTab = btn.dataset.eventtab;
        $$('.sub-tab-btn[data-eventtab]').forEach(b => b.classList.toggle('active', b === btn));
        $$('#view-events .subpane').forEach(p => p.classList.toggle('active', p.id === `events-pane-${runtime.eventSubTab}`));
        loadActiveEventTab();
      };
    });

    $$('.stream-pill[data-stream]').forEach(pill => {
      pill.onclick = () => {
        runtime.activeRedisStream = pill.dataset.stream;
        $$('.stream-pill[data-stream]').forEach(p => p.classList.toggle('active', p === pill));
        const badge = $('#active-stream-badge');
        if (badge) badge.textContent = runtime.activeRedisStream;
        loadStream(true);
      };
    });

    const refBtn = $('#events-refresh-btn');
    if (refBtn) refBtn.onclick = () => loadActiveEventTab(true);

    const traceBtn = $('#trace-search-btn');
    if (traceBtn) traceBtn.onclick = () => runTrace();
    const traceInput = $('#trace-cid-input');
    if (traceInput) traceInput.onkeydown = (e) => { if (e.key === 'Enter') runTrace(); };
  }

export function loadActiveEventTab(force = false) {
    if (runtime.eventSubTab === 'stream') loadStream(force);
    else if (runtime.eventSubTab === 'outbox') loadOutbox(force);
    else if (runtime.eventSubTab === 'dlq') loadDlq(force);
  }

export async function loadEventsView() {
    setupEventsViewOnce();
    loadActiveEventTab();
  }

export async function loadStream(force = false) {
    const tbody = $('#stream-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedStream.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? `Reading Redis stream ${runtime.activeRedisStream}...` : `Odczytywanie strumienia Redis ${runtime.activeRedisStream}...`}</td></tr>`;
      try {
        const res = await api(`/api/events/stream?stream=${encodeURIComponent(runtime.activeRedisStream)}`);
        runtime.cachedStream = res.events || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="6" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderStreamTable();
  }

export function renderStreamTable() {
    const tbody = $('#stream-tbody');
    if (!tbody) return;
    if (!runtime.cachedStream.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? `No events in ${runtime.activeRedisStream} stream yet.` : `Brak zdarzeń w strumieniu ${runtime.activeRedisStream}. Złóż zamówienie w sklepie, aby wygenerować zdarzenia.`}</td></tr>`;
      return;
    }
    tbody.innerHTML = runtime.cachedStream.map(entry => {
      const id = entry.id;
      const ts = entry.timestamp || '—';
      const d = entry.data || {};
      const evType = d.eventType || d.type || (d._bootstrap ? 'BOOTSTRAP' : 'Event');
      const orderId = d.orderId || '—';
      let amt = '—';
      if (d.amount) {
        amt = `${d.amount} PLN`;
      } else if (d.grossAmount) {
        amt = `${d.grossAmount} PLN`;
      } else if (d.correctionGross) {
        const delta = Number(d.correctionGross) || 0;
        const sign = delta > 0 ? '+' : '';
        const finalStr = d.finalGross ? ` (po: ${d.finalGross} PLN)` : '';
        amt = `${sign}${d.correctionGross} PLN${finalStr}`;
      }
      const payloadStr = Object.entries(d).map(([k, v]) => `${k}=${v}`).join(', ');
      let badgeCls = 'published';
      if (d.eventType === 'PAYMENT_SUCCEEDED') badgeCls = 'paid';
      else if (d.eventType === 'INVOICE_ISSUED') badgeCls = 'completed';
      else if (d.eventType === 'INVOICE_CORRECTED') badgeCls = 'warning';
      else if (d._bootstrap) badgeCls = 'pending';
      return `
        <tr>
          <td><code style="font-size:11px;color:var(--primary);">${escapeHtml(id)}</code></td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(ts)}</td>
          <td><span class="badge-status ${badgeCls}">${escapeHtml(evType)}</span></td>
          <td>${orderId !== '—' ? `<span class="badge" style="font-weight:700;">#${escapeHtml(orderId)}</span>` : '<span style="color:var(--muted);">—</span>'}</td>
          <td style="font-variant-numeric:tabular-nums;"><strong>${escapeHtml(amt)}</strong></td>
          <td style="font-size:11px; font-family:ui-monospace, monospace; color:var(--muted); max-width:360px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(payloadStr)}">${escapeHtml(payloadStr)}</td>
        </tr>
      `;
    }).join('');
  }

export async function loadOutbox(force = false) {
    const tbody = $('#outbox-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedOutbox.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Loading outbox events...' : 'Odpytywanie payment_outbox_events...'}</td></tr>`;
      try {
        const res = await api('/api/events/outbox');
        runtime.cachedOutbox = res.outbox || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderOutboxTable();
  }

export function renderOutboxTable() {
    const tbody = $('#outbox-tbody');
    if (!tbody) return;
    if (!runtime.cachedOutbox.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Outbox table is empty.' : 'Tabela Outbox jest pusta.'}</td></tr>`;
      return;
    }
    tbody.innerHTML = runtime.cachedOutbox.map(o => {
      const id = o.id;
      const evType = o.event_type || o.eventType || '—';
      const orderId = o.order_id || o.orderId || '—';
      const amt = o.amount ? `${o.amount} PLN` : '—';
      const st = (o.status || 'PENDING').toUpperCase();
      const stClass = st.toLowerCase();
      const attempts = o.attempts != null ? o.attempts : 0;
      const cid = o.correlation_id || o.correlationId || '';
      const err = o.last_error || o.lastError || '—';
      const dt = o.created_at ? new Date(o.created_at).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      const cidBtn = cid ? `<button class="btn-mini jump-trace-btn" data-cid="${escapeHtml(cid)}" title="${state.language === 'en' ? 'Trace this correlation ID' : 'Śledź ten identyfikator korelacji'}">🔍 ${escapeHtml(cid.slice(0, 8))}…</button>` : '—';
      return `
        <tr>
          <td><strong>#${id}</strong></td>
          <td><strong>${escapeHtml(evType)}</strong></td>
          <td>#${escapeHtml(orderId)}</td>
          <td>${escapeHtml(amt)}</td>
          <td><span class="badge-status ${stClass}">${escapeHtml(st)}</span></td>
          <td>${attempts}</td>
          <td>${cidBtn}</td>
          <td style="font-size:11px; color:${err !== '—' ? 'var(--danger)' : 'var(--muted)'}; max-width:200px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(err)}">${escapeHtml(err)}</td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
        </tr>
      `;
    }).join('');

    $$('.jump-trace-btn', tbody).forEach(b => {
      b.onclick = () => {
        const cid = b.dataset.cid;
        const traceTabBtn = $('#events-tab-trace-btn');
        if (traceTabBtn) traceTabBtn.click();
        const inp = $('#trace-cid-input');
        if (inp) inp.value = cid;
        runTrace();
      };
    });
  }

export async function loadDlq(force = false) {
    const tbody = $('#dlq-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedDlq.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'Loading Dead Letter Queue...' : 'Ładowanie kolejki martwych powiadomień (DLQ)...'}</td></tr>`;
      try {
        const res = await api('/api/events/dlq');
        runtime.cachedDlq = res.dlq || [];
      } catch (err) {
        tbody.innerHTML = `<tr><td colspan="9" class="table-empty error">${escapeHtml(err.message)}</td></tr>`;
        return;
      }
    }
    renderDlqTable();
  }

export function renderDlqTable() {
    const tbody = $('#dlq-tbody');
    if (!tbody) return;
    if (!runtime.cachedDlq.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty" style="color:var(--green);">🎉 ${state.language === 'en' ? 'Dead Letter Queue is empty. All notifications delivered successfully!' : 'Dead Letter Queue jest pusta. Wszystkie powiadomienia dostarczono pomyślnie!'}</td></tr>`;
      return;
    }
    tbody.innerHTML = runtime.cachedDlq.map(d => {
      const id = d.id;
      const evType = d.event_type || d.eventType || '—';
      const recip = d.recipient || '—';
      const chan = d.channel || 'EMAIL';
      const subj = d.subject || '—';
      const reason = d.failure_reason || d.failureReason || '—';
      const st = (d.status || 'FAILED').toUpperCase();
      const attempts = d.attempt_count != null ? d.attempt_count : (d.attempts ?? 1);
      const isReplayed = st === 'RESOLVED' || st === 'REPLAYED';
      const replayBtn = isReplayed 
        ? `<span style="color:var(--green); font-size:11px;">✓ Ponowiono</span>`
        : `<button class="btn-mini replay-dlq-btn" data-dlq-id="${id}" style="border-color:#38bdf8; color:#38bdf8;" title="Ponów wysyłkę z DLQ">⚡ Ponów</button>`;
      return `
        <tr>
          <td><strong>#${id}</strong></td>
          <td><strong>${escapeHtml(evType)}</strong></td>
          <td><code>${escapeHtml(recip)}</code></td>
          <td><span style="font-size:11px; color:var(--muted);">${escapeHtml(chan)}</span></td>
          <td style="max-width:180px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(subj)}">${escapeHtml(subj)}</td>
          <td style="color:var(--danger); font-size:11px; max-width:220px; overflow:hidden; text-overflow:ellipsis; white-space:nowrap;" title="${escapeHtml(reason)}">${escapeHtml(reason)}</td>
          <td><span class="badge-status ${st.toLowerCase()}">${escapeHtml(st)}</span></td>
          <td>${attempts}</td>
          <td>${replayBtn}</td>
        </tr>
      `;
    }).join('');

    $$('.replay-dlq-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const dlqId = btn.dataset.dlqId;
        btn.disabled = true;
        try {
          await api('/api/events/dlq/replay', { method: 'POST', body: JSON.stringify({ id: dlqId }) });
          toast(state.language === 'en' ? `Replayed DLQ #${dlqId}` : `Ponowiono wpis DLQ #${dlqId}`);
          await loadDlq(true);
        } catch (err) {
          toast(err.message, true);
        } finally {
          btn.disabled = false;
        }
      };
    });
  }

export async function runTrace() {
    const input = $('#trace-cid-input');
    const container = $('#trace-timeline-container');
    if (!input || !container) return;
    const cid = input.value.trim();
    if (!cid) {
      toast(state.language === 'en' ? 'Please enter a correlation ID.' : 'Wprowadź identyfikator X-Correlation-Id.', true);
      return;
    }
    container.innerHTML = `<div class="timeline-empty-state"><span class="timeline-empty-icon">⏳</span><h3>${state.language === 'en' ? 'Searching logs across services...' : 'Przeszukiwanie logów mikroserwisów...'}</h3><p>Identyfikator: <code>${escapeHtml(cid)}</code></p></div>`;
    try {
      const res = await api(`/api/trace?correlationId=${encodeURIComponent(cid)}`);
      const events = res.timeline || [];
      if (!events.length) {
        container.innerHTML = `
          <div class="timeline-empty-state">
            <span class="timeline-empty-icon">🔍</span>
            <h3>${state.language === 'en' ? 'No log entries found for this Correlation ID.' : 'Nie znaleziono wpisów w logach dla podanego Correlation ID.'}</h3>
            <p>${state.language === 'en' ? 'Ensure services are running or place an order via the store or App Verify to generate logs.' : 'Upewnij się, że serwisy działają lub złóż zamówienie w sklepie, aby wygenerować logi.'}</p>
          </div>
        `;
        return;
      }
      container.innerHTML = `
        <div style="margin-bottom: 20px; display: flex; justify-content: space-between; align-items: center;">
          <div>
            <strong>X-Correlation-Id:</strong> <code>${escapeHtml(cid)}</code>
          </div>
          <span class="toolbar-badge">${events.length} ${state.language === 'en' ? 'events detected' : 'zarejestrowanych zdarzeń'}</span>
        </div>
        <div class="timeline-flow">
          ${events.map(ev => {
            const srv = ev.service || 'UNKNOWN';
            const lvl = (ev.level || 'INFO').toLowerCase();
            const ts = ev.timestamp || '—';
            const msg = ev.message || '';
            return `
              <div class="timeline-item ${lvl}">
                <div class="timeline-item-meta">
                  <span class="timeline-srv-name">${escapeHtml(srv)} <span class="level-pill ${lvl}">${lvl.toUpperCase()}</span></span>
                  <span class="timeline-time">${escapeHtml(ts)}</span>
                </div>
                <div class="timeline-msg">${escapeHtml(msg)}</div>
              </div>
            `;
          }).join('')}
        </div>
      `;
    } catch (err) {
      container.innerHTML = `<div class="timeline-empty-state error"><span class="timeline-empty-icon">⚠️</span><h3>Błąd wyszukiwania śladu</h3><p>${escapeHtml(err.message)}</p></div>`;
    }
  }
