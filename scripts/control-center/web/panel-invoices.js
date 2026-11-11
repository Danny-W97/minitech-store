import { updateTableSortHeaders } from './panel-data.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { expandedInvoiceCorrections, readPendingCorrection } from './panel-operations.js?v=20261027-a42';
import { api, downloadControlFile, fetchListPage, pageControls } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export async function loadCorrectionHistory(invoiceId) {
    const anchor = document.getElementById(`correction-history-${invoiceId}`);
    if (!anchor) return;
    const kind = `correction-${invoiceId}`;
    try {
      const page = await fetchListPage(kind, `/api/data/invoices/${encodeURIComponent(invoiceId)}/corrections`);
      if (page.stale || !anchor.isConnected) return;
      anchor.innerHTML = (page.items || []).map(c => `<p><strong>${escapeHtml(c.correctionNumber)}</strong> · ${escapeHtml(c.reason)} · ${escapeHtml(c.correctionGross)} PLN
        · ${c.balanceModel === 'CUMULATIVE_DELTA' ? 'Po tej korekcie / After this correction' : 'Historyczny zapis / Historical snapshot'}: ${escapeHtml(c.finalGross)} PLN
        <button type="button" class="ghost-button" data-corr-pdf="${c.id}">PDF</button>
        <button type="button" class="ghost-button" data-corr-send="${c.id}">Wyślij / Send</button></p>`).join('') || '<p>Brak korekt / No corrections</p>';
      anchor.querySelectorAll('[data-corr-pdf]').forEach(button => { button.onclick = () => downloadControlFile(`/api/data/invoices/corrections/${button.dataset.corrPdf}/pdf`, 'korekta.pdf').catch(error => toast(error.message, true)); });
      anchor.querySelectorAll('[data-corr-send]').forEach(button => { button.onclick = () => {
        const c = page.items.find(row => String(row.id) === button.dataset.corrSend);
        const inv = runtime.cachedInvoices.find(row => String(row.id) === String(invoiceId));
        openDispatchModal({ type: 'CORRECTION', id: c.id, orderId: c.orderId, docNumber: c.correctionNumber, buyerName: inv?.buyerName, email: inv?.customerEmail, amount: c.correctionGross });
      }; });
      pageControls(kind, anchor, () => loadCorrectionHistory(invoiceId));
    } catch (error) { toast(error.message, true); }
  }

export async function loadInvoices(force = false) {
    const tbody = $('#invoices-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedInvoices.length) {
      try {
        const res = await fetchListPage('invoices', '/api/data/invoices', { search: $('#invoices-search-input')?.value.trim() || '' });
        if (res.stale) return;
        pageControls('invoices', tbody, loadInvoices);
        runtime.cachedInvoices = res.invoices || [];
      } catch (err) {
        toast(err.message, true);
        return;
      }
    }
    renderInvoicesTable();
  }

export function renderInvoicesTable() {
    const tbody = $('#invoices-tbody');
    if (!tbody) return;
    const query = ($('#invoices-search-input')?.value || '').toLowerCase().trim();
    let list = runtime.cachedInvoices;

    // Update KPI metrics
    let count = list.length;
    let net = 0;
    let vat = 0;
    let gross = 0;
    for (const inv of list) {
      net += Number(inv.netAmount || 0);
      vat += Number(inv.vatAmount || 0);
      gross += Number(inv.grossAmount || 0);
    }
    const loc = state.language === 'en' ? 'en-US' : 'pl-PL';
    const kpiCount = $('#invoices-kpi-count');
    if (kpiCount) kpiCount.textContent = count;
    const kpiNet = $('#invoices-kpi-net');
    if (kpiNet) kpiNet.textContent = `${net.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;
    const kpiVat = $('#invoices-kpi-vat');
    if (kpiVat) kpiVat.textContent = `${vat.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;
    const kpiGross = $('#invoices-kpi-gross');
    if (kpiGross) kpiGross.textContent = `${gross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} PLN`;

    const badge = $('#invoices-count-badge');
    if (badge) badge.textContent = `${count} ${state.language === 'en' ? (count === 1 ? 'invoice' : 'invoices') : (count === 1 ? 'faktura' : (count > 1 && count < 5 ? 'faktury' : 'faktur'))}`;

    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="10" class="table-empty">${state.language === 'en' ? 'No invoices match criteria.' : 'Brak faktur spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Sort
    list = [...list].sort((a, b) => {
      if (runtime.invoicesSortField === 'number') {
        const nrA = String(a.invoiceNumber || '');
        const nrB = String(b.invoiceNumber || '');
        return runtime.invoicesSortDir === 'asc' ? nrA.localeCompare(nrB) : nrB.localeCompare(nrA);
      }
      if (runtime.invoicesSortField === 'order') {
        const oA = Number(a.orderId || 0);
        const oB = Number(b.orderId || 0);
        return runtime.invoicesSortDir === 'asc' ? oA - oB : oB - oA;
      }
      if (runtime.invoicesSortField === 'buyer') {
        const bA = String(a.companyName || a.buyerName || '').toLowerCase();
        const bB = String(b.companyName || b.buyerName || '').toLowerCase();
        return runtime.invoicesSortDir === 'asc' ? bA.localeCompare(bB) : bB.localeCompare(bA);
      }
      if (runtime.invoicesSortField === 'amount') {
        const gA = Number(a.grossAmount || 0);
        const gB = Number(b.grossAmount || 0);
        return runtime.invoicesSortDir === 'asc' ? gA - gB : gB - gA;
      }
      if (runtime.invoicesSortField === 'date') {
        const dtA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
        const dtB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
        return runtime.invoicesSortDir === 'asc' ? dtA - dtB : dtB - dtA;
      }
      if (runtime.invoicesSortField === 'id') {
        const idA = Number(a.id || 0);
        const idB = Number(b.id || 0);
        return runtime.invoicesSortDir === 'asc' ? idA - idB : idB - idA;
      }
      return 0;
    });

    updateTableSortHeaders('#invoices-table', runtime.invoicesSortField, runtime.invoicesSortDir);

    tbody.innerHTML = list.map(inv => {
      const dt = inv.createdAt ? new Date(inv.createdAt).toLocaleString(loc) : '—';
      const netStr = Number(inv.netAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const vatStr = Number(inv.vatAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const grossStr = Number(inv.grossAmount || 0).toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const buyerDisplay = inv.companyName ? `<strong>${escapeHtml(inv.companyName)}</strong><br><small style="color:var(--muted);">${escapeHtml(inv.buyerName)}</small>` : escapeHtml(inv.buyerName || '—');
      const taxDisplay = inv.taxId ? `<code style="font-size:11px;background:rgba(255,255,255,0.06);padding:2px 6px;border-radius:4px;">${escapeHtml(inv.taxId)}</code>` : '<span style="color:var(--muted);">—</span>';
      const pdfUrl = `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}/pdf`;
      const jsonUrl = `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}`;
      const hasCorrs = Number(inv.correctionCount) > 0;
      const totalDelta = Number(inv.correctionGrossDelta || 0);
      const finalGross = (Number(inv.grossAmount) || 0) + totalDelta;
      const finalGrossStr = finalGross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 });
      const totalDeltaStr = `${totalDelta > 0 ? '+' : ''}${totalDelta.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
      const isExpanded = expandedInvoiceCorrections.has(Number(inv.id));

      return `
        <tr>
          <td>
            <div style="display:flex;align-items:center;">
              <strong style="font-family:monospace;color:var(--primary);">${escapeHtml(inv.invoiceNumber || '—')}</strong>
              ${hasCorrs ? `
                <button type="button" class="btn-corr-toggle ${isExpanded ? 'expanded' : ''}" data-invoice-id="${inv.id}" title="${state.language === 'en' ? 'Click to show/hide corrections history' : 'Kliknij, aby rozwinąć/zwinąć historię korekt'}">
                  <span class="corr-icon">📜</span>
                  <span>${inv.correctionCount} ${state.language === 'en' ? (inv.correctionCount === 1 ? 'corr.' : 'corrs.') : (inv.correctionCount === 1 ? 'korekta' : (inv.correctionCount < 5 ? 'korekty' : 'korekt'))}</span>
                  <span class="corr-chevron">${isExpanded ? '▲' : '▼'}</span>
                </button>
              ` : ''}
            </div>
          </td>
          <td><span class="badge" style="font-weight:700;">#${inv.orderId}</span></td>
          <td>${buyerDisplay}</td>
          <td>${taxDisplay}</td>
          <td style="font-size:12px;">${escapeHtml(inv.customerEmail || '—')}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);">${netStr} ${inv.currency || 'PLN'}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;color:var(--muted);">${vatStr} ${inv.currency || 'PLN'}</td>
          <td style="text-align:right;font-variant-numeric:tabular-nums;">
            <strong>${grossStr} ${inv.currency || 'PLN'}</strong>
            ${hasCorrs ? `<div style="font-size:11px;color:#fbbf24;font-weight:600;margin-top:2px;" title="${state.language === 'en' ? 'Final amount after corrections' : 'Kwota końcowa po uwzględnieniu korekt'}">${state.language === 'en' ? 'After corr.:' : 'Po kor.:'} ${finalGrossStr} ${inv.currency || 'PLN'}</div>` : ''}
          </td>
          <td style="font-size:11px;color:var(--muted);">${dt}</td>
          <td style="text-align:right;white-space:nowrap;">
            <a href="${jsonUrl}" target="_blank" class="btn-mini" style="margin-right:4px;" title="${state.language === 'en' ? 'View invoice JSON' : 'Podgląd JSON faktury'}">📄 JSON ↗</a>
            <a href="${pdfUrl}" target="_blank" download class="btn-mini" style="background:rgba(34,197,94,0.12);color:#4ade80;border-color:rgba(34,197,94,0.3);font-weight:700;margin-right:4px;" title="${state.language === 'en' ? 'Download PDF invoice' : 'Pobierz plik PDF faktury'}">📥 PDF ↗</a>
            <button type="button" class="btn-mini btn-correct-invoice" data-invoice-id="${inv.id}" data-invoice-number="${escapeHtml(inv.invoiceNumber || '')}" data-gross="${inv.grossAmount}" style="background:rgba(245,158,11,0.12);color:#fbbf24;border-color:rgba(245,158,11,0.35);margin-right:4px;" title="${state.language === 'en' ? 'Issue correction invoice (credit note)' : 'Wystaw fakturę korygującą (KOR)'}">📝 Korekta</button>
            <button type="button" class="btn-mini btn-send-doc" data-type="INVOICE" data-id="${inv.id}" data-order-id="${inv.orderId}" data-doc-num="${escapeHtml(inv.invoiceNumber || '')}" data-buyer="${escapeHtml(inv.companyName || inv.buyerName || '')}" data-email="${escapeHtml(inv.customerEmail || '')}" data-amount="${inv.grossAmount}" style="background:rgba(14,165,233,0.12);color:#38bdf8;border-color:rgba(14,165,233,0.35);" title="${state.language === 'en' ? 'Send invoice via email' : 'Wyślij fakturę na e-mail'}">✉️</button>
          </td>
        </tr>
        ${hasCorrs ? `
          <tr id="sub-corrs-${inv.id}" class="correction-subrow" style="display:${isExpanded ? 'table-row' : 'none'};">
            <td colspan="10">
              <div class="correction-subrow-wrap">
                <div class="correction-subrow-header">
                  <div class="correction-subrow-title">
                    <span>↳</span>
                    <span>${state.language === 'en' ? `Corrections history for ${escapeHtml(inv.invoiceNumber || '')}` : `Historia korekt do faktury ${escapeHtml(inv.invoiceNumber || '')}`}</span>
                    <span style="font-size:11px;color:var(--muted);font-weight:normal;">(${state.language === 'en' ? 'Total delta:' : 'Łączna zmiana:'} <strong class="${totalDelta < 0 ? 'correction-delta-neg' : 'correction-delta-pos'}">${totalDeltaStr} ${inv.currency || 'PLN'}</strong>)</span>
                  </div>
                  <div class="correction-subrow-meta">
                    ${inv.correctionCount} ${state.language === 'en' ? (inv.correctionCount === 1 ? 'document' : 'documents') : (inv.correctionCount === 1 ? 'dokument korygujący' : (inv.correctionCount < 5 ? 'dokumenty korygujące' : 'dokumentów'))}
                  </div>
                </div>
                <div id="correction-history-${inv.id}"></div>

              </div>
            </td>
          </tr>
        ` : ''}
      `;
    }).join('');

    tbody.querySelectorAll('.btn-corr-toggle').forEach(btn => {
      btn.onclick = async (e) => {
        e.stopPropagation();
        const invId = Number(btn.dataset.invoiceId);
        const subrow = $(`#sub-corrs-${invId}`);
        if (!subrow) return;
        const isHidden = subrow.style.display === 'none';
        subrow.style.display = isHidden ? 'table-row' : 'none';
        btn.classList.toggle('expanded', isHidden);
        const chevron = btn.querySelector('.corr-chevron');
        if (chevron) {
          chevron.textContent = isHidden ? '▲' : '▼';
        }
        if (isHidden) {
          expandedInvoiceCorrections.add(invId);
          await loadCorrectionHistory(invId);
        } else {
          expandedInvoiceCorrections.delete(invId);
        }
      };
    });

    expandedInvoiceCorrections.forEach(id => { if (document.getElementById(`correction-history-${id}`)) loadCorrectionHistory(id); });
    tbody.querySelectorAll('.btn-correct-invoice').forEach(btn => {
      btn.onclick = () => {
        const invId = btn.dataset.invoiceId;
        const invNum = btn.dataset.invoiceNumber;
        const gross = parseFloat(btn.dataset.gross) || 0;
        openCorrectionModal(invId, invNum, gross);
      };
    });

    tbody.querySelectorAll('.btn-send-doc').forEach(btn => {
      btn.onclick = () => {
        openDispatchModal({
          type: btn.dataset.type,
          id: btn.dataset.id,
          orderId: btn.dataset.orderId,
          docNumber: btn.dataset.docNum,
          buyerName: btn.dataset.buyer,
          email: btn.dataset.email,
          amount: btn.dataset.amount
        });
      };
    });
  }

export async function openCorrectionModal(invId, invNum, gross) {
    const dialog = $('#correction-dialog');
    if (!dialog) return;
    const invIdInput = $('#correction-invoice-id');
    const origGrossInput = $('#correction-orig-gross');
    if (invIdInput) invIdInput.value = invId;
    if (origGrossInput) origGrossInput.value = '';
    dialog.dataset.balanceVersion = '';
    $('#correction-submit-btn').disabled = true;
    const title = $('#correction-dialog-title');
    if (title) title.textContent = state.language === 'en' ? `Issue Correction for ${invNum}` : `Wystawienie korekty faktury ${invNum}`;
    const subtitle = $('#correction-dialog-subtitle');
    if (subtitle) subtitle.textContent = state.language === 'en' ? 'Loading current balance…' : 'Odczyt bieżącego salda…';
    const reasonInput = $('#correction-reason');
    if (reasonInput) reasonInput.value = '';
    const deltaInput = $('#correction-delta');
    if (deltaInput) deltaInput.value = '';
    const errEl = $('#correction-error');
    if (errEl) { errEl.textContent = ''; errEl.hidden = true; }
    const origEl = $('#calc-orig-text');
    if (origEl) origEl.textContent = `${Number(gross).toFixed(2)} PLN`;
    const deltaEl = $('#calc-delta-text');
    if (deltaEl) { deltaEl.textContent = '0.00 PLN'; deltaEl.style.color = '#f87171'; }
    const finalEl = $('#calc-final-text');
    if (finalEl) { finalEl.textContent = `${Number(gross).toFixed(2)} PLN`; finalEl.style.color = '#4ade80'; }
    reasonInput.readOnly = false; deltaInput.readOnly = false;
    dialog.showModal();
    try {
      const pending = readPendingCorrection(invId);
      const balance = pending ? {version: pending.expectedVersion, grossAmount: pending.beforeGross}
        : await api(`/api/data/invoices/${invId}/correction-balance`);
      if (invIdInput.value !== String(invId) || !dialog.open) return;
      if (!Number.isSafeInteger(balance.version) || balance.version < 0 || !Number.isFinite(Number(balance.grossAmount))) throw new Error('Nieprawidłowe saldo faktury.');
      dialog.dataset.balanceVersion = String(balance.version);
      origGrossInput.value = balance.grossAmount;
      origEl.textContent = `${Number(balance.grossAmount).toFixed(2)} PLN`;
      finalEl.textContent = origEl.textContent;
      subtitle.textContent = pending ? 'Ponowienie poprzedniej próby korekty / Retry previous correction'
        : `${state.language === 'en' ? 'Current balance' : 'Bieżące saldo'}: ${Number(balance.grossAmount).toFixed(2)} PLN`;
      if (pending) {
        reasonInput.value = pending.reason; deltaInput.value = pending.correctionGross;
        reasonInput.readOnly = true; deltaInput.readOnly = true;
        deltaInput.dispatchEvent(new Event('input'));
      }
      $('#correction-submit-btn').disabled = false;
    } catch (error) { errEl.textContent = error.message; errEl.hidden = false; }
  }

export function openDispatchModal(doc) {
    const dialog = $('#dispatch-dialog');
    if (!dialog) return;

    const docTypeInp = $('#dispatch-doc-type');
    const docIdInp = $('#dispatch-doc-id');
    const orderIdInp = $('#dispatch-order-id');
    const docNumberInp = $('#dispatch-doc-number');
    const buyerNameInp = $('#dispatch-buyer-name');

    if (docTypeInp) docTypeInp.value = doc.type || 'INVOICE';
    if (docIdInp) docIdInp.value = doc.id;
    if (orderIdInp) orderIdInp.value = doc.orderId || '';
    if (docNumberInp) docNumberInp.value = doc.docNumber || '';
    if (buyerNameInp) buyerNameInp.value = doc.buyerName || '';

    const isCorr = doc.type === 'CORRECTION';
    const docTitle = isCorr ? (state.language === 'en' ? 'Correction Invoice (COR)' : 'Faktura Korygująca (KOR)') : (state.language === 'en' ? 'VAT Invoice' : 'Faktura VAT');

    const title = $('#dispatch-dialog-title');
    if (title) {
      title.textContent = state.language === 'en'
        ? `Send ${docTitle} to customer`
        : `Wysyłka dokumentu: ${doc.docNumber}`;
    }
    const subtitle = $('#dispatch-dialog-subtitle');
    if (subtitle) {
      subtitle.textContent = state.language === 'en'
        ? 'Send PDF document with optional message via Mailpit / SMTP'
        : 'Wysyłka pliku PDF z opcjonalną notatką do klienta (SMTP → Mailpit)';
    }

    const infoDoc = $('#dispatch-info-doc');
    if (infoDoc) infoDoc.textContent = `${isCorr ? '📜' : '📄'} ${doc.docNumber}`;
    const infoOrder = $('#dispatch-info-order');
    if (infoOrder) infoOrder.textContent = `#${doc.orderId || '—'}`;
    const infoBuyer = $('#dispatch-info-buyer');
    if (infoBuyer) infoBuyer.textContent = doc.buyerName || '—';
    const infoAmount = $('#dispatch-info-amount');
    if (infoAmount) {
      const amtNum = parseFloat(doc.amount);
      infoAmount.textContent = !isNaN(amtNum) ? `${amtNum.toFixed(2)} PLN` : '—';
      infoAmount.style.color = isCorr && amtNum < 0 ? '#f87171' : '#4ade80';
    }

    const recipientInp = $('#dispatch-recipient');
    if (recipientInp) recipientInp.value = doc.email || '';

    const subjectInp = $('#dispatch-subject');
    if (subjectInp) {
      subjectInp.value = state.language === 'en'
        ? (isCorr ? `[MiniTech] Correction Invoice #${doc.docNumber} for order #${doc.orderId}` : `[MiniTech] VAT Invoice #${doc.docNumber} for order #${doc.orderId}`)
        : (isCorr ? `[MiniTech] Faktura korygująca nr ${doc.docNumber} do zamówienia #${doc.orderId}` : `[MiniTech] Faktura VAT nr ${doc.docNumber} do zamówienia #${doc.orderId}`);
    }

    const msgInp = $('#dispatch-custom-message');
    if (msgInp) msgInp.value = '';

    const errEl = $('#dispatch-error');
    if (errEl) { errEl.textContent = ''; errEl.hidden = true; }

    const safeNr = String(doc.docNumber || '').replace(/[^\w\-_.]/g, '_');
    const safeFile = `${isCorr ? 'korekta' : 'faktura'}-${safeNr}.pdf`;
    const hintEl = $('#dispatch-attachment-hint');
    if (hintEl) {
      hintEl.textContent = state.language === 'en'
        ? `PDF attachment: ${safeFile} (downloaded live from Billing Service)`
        : `Do wiadomości zostanie dołączony plik PDF: ${safeFile} (pobrany z Billing Service).`;
    }

    dialog.showModal();
  }

export async function loadDispatch(force = false) {
    const tbody = $('#dispatch-tbody');
    if (!tbody) return;
    if (force || !runtime.dispatchInvoices.length) {
      try {
        const [invRes, histRes] = await Promise.all([
          fetchListPage('dispatch', '/api/data/invoices', { search: $('#dispatch-search-input')?.value.trim() || '' }),
          api('/api/data/invoices/dispatch-history')
        ]);
        if (invRes.stale) return;
        const corrRes = await fetchListPage('dispatch-corrections', '/api/data/corrections');
        if (corrRes.stale) return;
        runtime.dispatchInvoices = invRes.invoices || []; runtime.dispatchCorrections = corrRes.items || [];
        pageControls('dispatch', tbody, loadDispatch);
        let correctionAnchor = $('#dispatch-corrections-anchor');
        if (!correctionAnchor) { correctionAnchor = document.createElement('p'); correctionAnchor.id = 'dispatch-corrections-anchor'; correctionAnchor.textContent = 'Historia korekt / Corrections history'; $('#list-page-dispatch').after(correctionAnchor); }
        pageControls('dispatch-corrections', correctionAnchor, loadDispatch);
        runtime.cachedDispatchHistory = histRes.history || [];
      } catch (err) {
        toast(err.message, true);
        return;
      }
    }
    renderDispatchTable();
  }

export function renderDispatchTable() {
    const tbody = $('#dispatch-tbody');
    if (!tbody) return;

    const query = ($('#dispatch-search-input')?.value || '').toLowerCase().trim();
    const loc = state.language === 'en' ? 'en-US' : 'pl-PL';

    // 1. Flatten all dispatchable documents: invoices and corrections
    const docs = [];
    for (const inv of runtime.dispatchInvoices) {
      docs.push({
        type: 'INVOICE',
        id: inv.id,
        orderId: inv.orderId,
        docNumber: inv.invoiceNumber || `FV #${inv.id}`,
        buyerName: inv.companyName || inv.buyerName || '—',
        email: inv.customerEmail || '',
        amount: inv.grossAmount,
        currency: inv.currency || 'PLN',
        createdAt: inv.createdAt,
        pdfUrl: `http://127.0.0.1:18080/api/invoices/order/${inv.orderId}/pdf`
      });

    }
    for (const c of runtime.dispatchCorrections) {
      docs.push({ type: 'CORRECTION', id: c.id, parentInvoiceId: c.invoiceId, orderId: c.orderId,
        docNumber: c.correctionNumber, originalDocNumber: c.originalInvoiceNumber, reason: c.reason,
        buyerName: c.companyName || c.buyerName || '—', email: c.customerEmail || '', amount: c.correctionGross,
        finalGross: c.finalGross, currency: c.currency || 'PLN', createdAt: c.createdAt,
        pdfUrl: `/api/data/invoices/corrections/${c.id}/pdf` });
    }

    // Calculate overall stats before search filtering
    const totalAll = docs.length;
    const totalFv = docs.filter(d => d.type === 'INVOICE').length;
    const totalKor = docs.filter(d => d.type === 'CORRECTION').length;
    const totalSent = runtime.cachedDispatchHistory.length;

    const elCountAll = $('#dispatch-count-all');
    if (elCountAll) elCountAll.textContent = totalAll;
    const elCountFv = $('#dispatch-count-fv');
    if (elCountFv) elCountFv.textContent = totalFv;
    const elCountKor = $('#dispatch-count-kor');
    if (elCountKor) elCountKor.textContent = totalKor;
    const elCountSent = $('#dispatch-count-sent');
    if (elCountSent) elCountSent.textContent = totalSent;

    // Filter by type
    let list = docs;
    if (runtime.dispatchFilter === 'FV') list = list.filter(d => d.type === 'INVOICE');
    else if (runtime.dispatchFilter === 'KOR') list = list.filter(d => d.type === 'CORRECTION');

    // Filter by search query
    if (query) {
      list = list.filter(d =>
        String(d.docNumber || '').toLowerCase().includes(query) ||
        String(d.orderId || '').includes(query) ||
        String(d.buyerName || '').toLowerCase().includes(query) ||
        String(d.email || '').toLowerCase().includes(query) ||
        String(d.reason || '').toLowerCase().includes(query) ||
        String(d.originalDocNumber || '').toLowerCase().includes(query)
      );
    }

    // Sort by createdAt descending
    list.sort((a, b) => {
      const dtA = a.createdAt ? new Date(a.createdAt).getTime() : 0;
      const dtB = b.createdAt ? new Date(b.createdAt).getTime() : 0;
      return dtB - dtA;
    });

    const badge = $('#dispatch-count-badge');
    if (badge) {
      badge.textContent = `${list.length} ${state.language === 'en' ? (list.length === 1 ? 'document' : 'documents') : (list.length === 1 ? 'dokument' : (list.length < 5 ? 'dokumenty' : 'dokumentów'))}`;
    }

    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="9" class="table-empty">${state.language === 'en' ? 'No documents match criteria.' : 'Brak dokumentów spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Map history for fast lookup
    const sentMap = new Map();
    for (const h of runtime.cachedDispatchHistory) {
      const key = `${h.documentType}_${h.documentId}`;
      if (!sentMap.has(key)) {
        sentMap.set(key, h);
      }
      if (h.documentNumber && !sentMap.has(h.documentNumber)) {
        sentMap.set(h.documentNumber, h);
      }
    }

    tbody.innerHTML = list.map(d => {
      const isCorr = d.type === 'CORRECTION';
      const dt = d.createdAt ? new Date(d.createdAt).toLocaleString(loc) : '—';
      const typeBadge = isCorr
        ? '<span class="dispatch-doc-badge kor">📜 KOR</span>'
        : '<span class="dispatch-doc-badge fv">📄 FV</span>';

      const docDisplay = isCorr
        ? `<strong>${escapeHtml(d.docNumber)}</strong><br><small style="color:var(--muted); font-size:11px;">do: ${escapeHtml(d.originalDocNumber || 'FV')}</small>`
        : `<strong style="font-family:monospace; color:var(--primary);">${escapeHtml(d.docNumber)}</strong>`;

      let amtDisplay = '—';
      if (isCorr) {
        const delta = Number(d.amount) || 0;
        const deltaStr = `${delta > 0 ? '+' : ''}${delta.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}`;
        const finalNum = Number(d.finalGross) || 0;
        const finalStr = `${finalNum.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}`;
        amtDisplay = `<span class="${delta < 0 ? 'correction-delta-neg' : 'correction-delta-pos'}" style="font-weight:700;">${deltaStr}</span><br><small style="color:var(--muted); font-size:11px;">po: ${finalStr}</small>`;
      } else {
        const gross = Number(d.amount) || 0;
        amtDisplay = `<strong>${gross.toLocaleString(loc, { minimumFractionDigits: 2, maximumFractionDigits: 2 })} ${d.currency || 'PLN'}</strong>`;
      }

      const sentRecord = sentMap.get(`${d.type}_${d.id}`) || sentMap.get(d.docNumber);
      const isSent = !!sentRecord;
      const isEn = state.language === 'en';
      const statusDisplay = isSent
        ? `<span class="badge-status completed" title="${isEn ? 'Sent' : 'Wysłano'}: ${escapeHtml(sentRecord.timestamp)}">🟢 ${isEn ? 'Sent' : 'Wysłano'} (${escapeHtml(sentRecord.timestamp.split(' ')[0])})</span>`
        : `<span class="badge-status pending" style="background:transparent; color:var(--muted); border-color:var(--line);">⚪ ${isEn ? 'Ready' : 'Gotowy'}</span>`;

      return `
        <tr>
          <td>${typeBadge}</td>
          <td>${docDisplay}</td>
          <td><span class="badge" style="font-weight:700;">#${d.orderId || '—'}</span></td>
          <td style="font-size:12px;"><strong>${escapeHtml(d.buyerName)}</strong></td>
          <td style="font-size:12px; font-family:monospace; color:var(--muted);">${escapeHtml(d.email || '—')}</td>
          <td style="text-align:right; font-variant-numeric:tabular-nums;">${amtDisplay}</td>
          <td style="font-size:11px; color:var(--muted);">${dt}</td>
          <td>${statusDisplay}</td>
          <td style="text-align:right; white-space:nowrap;">
            <button type="button" class="btn-mini btn-dispatch-send"
              data-type="${d.type}"
              data-id="${d.id}"
              data-order-id="${d.orderId || ''}"
              data-doc-num="${escapeHtml(d.docNumber)}"
              data-buyer="${escapeHtml(d.buyerName)}"
              data-email="${escapeHtml(d.email)}"
              data-amount="${escapeHtml(String(d.amount || ''))}"
              title="${isSent ? (isEn ? 'Resend to customer email' : 'Wyślij ponownie na e-mail') : (isEn ? 'Send document to customer email' : 'Wyślij dokument na e-mail klienta')}">
              ✉️ ${isSent ? (state.language === 'en' ? 'Resend' : 'Wyślij pon.') : (state.language === 'en' ? 'Send email' : 'Wyślij e-mail')}
            </button>
            <a href="${d.pdfUrl}" target="_blank" download class="btn-mini" style="margin-left:4px;" title="${isEn ? 'Download PDF' : 'Pobierz PDF'}">📥 PDF</a>
          </td>
        </tr>
      `;
    }).join('');

    tbody.querySelectorAll('.btn-dispatch-send').forEach(btn => {
      btn.onclick = () => {
        openDispatchModal({
          type: btn.dataset.type,
          id: btn.dataset.id,
          orderId: btn.dataset.orderId,
          docNumber: btn.dataset.docNum,
          buyerName: btn.dataset.buyer,
          email: btn.dataset.email,
          amount: btn.dataset.amount
        });
      };
    });
  }
