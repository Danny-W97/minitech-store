import { updateTableSortHeaders } from './panel-data.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { api, fetchListPage, pageControls } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$, escapeHtml } from './panel-utils.js?v=20261027-a42';

export async function loadOrders(force = false) {
    const tbody = $('#orders-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedOrders.length) {
      try {
        const res = await fetchListPage('orders', '/api/data/orders', { search: $('#orders-search-input')?.value.trim() || '', status: runtime.ordersStatusFilter });
        if (res.stale) return;
        pageControls('orders', tbody, loadOrders);
        runtime.cachedOrders = res.orders || [];
      } catch (err) {
        toast(err.message, true);
        return;
      }
    }
    renderOrdersTable();
  }

export function renderOrdersTable() {
    const tbody = $('#orders-tbody');
    if (!tbody) return;
    const query = ($('#orders-search-input')?.value || '').toLowerCase().trim();
    let list = runtime.cachedOrders;
    if (runtime.ordersStatusFilter !== 'ALL') {
      list = list.filter(o => String(o.status || '').toUpperCase() === runtime.ordersStatusFilter);
    }
    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="6" class="table-empty">${state.language === 'en' ? 'No orders match criteria.' : 'Brak zamówień spełniających kryteria.'}</td></tr>`;
      return;
    }

    // Sort orders
    list = [...list].sort((a, b) => {
      if (runtime.ordersSortField === 'id') {
        const idA = Number(a.id) || 0;
        const idB = Number(b.id) || 0;
        return runtime.ordersSortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (runtime.ordersSortField === 'amount') {
        const amtA = Number(a.total_price ?? a.totalPrice ?? a.total_amount ?? a.totalAmount ?? 0);
        const amtB = Number(b.total_price ?? b.totalPrice ?? b.total_amount ?? b.totalAmount ?? 0);
        return runtime.ordersSortDir === 'asc' ? amtA - amtB : amtB - amtA;
      }
      if (runtime.ordersSortField === 'date') {
        const dtA = (a.created_at || a.createdAt) ? new Date(a.created_at || a.createdAt).getTime() : 0;
        const dtB = (b.created_at || b.createdAt) ? new Date(b.created_at || b.createdAt).getTime() : 0;
        return runtime.ordersSortDir === 'asc' ? dtA - dtB : dtB - dtA;
      }
      if (runtime.ordersSortField === 'client') {
        const clA = String(a.customer_email || a.customerEmail || (a.customer_id ? `Klient #${a.customer_id}` : '') || (a.customerId ? `Klient #${a.customerId}` : '')).toLowerCase();
        const clB = String(b.customer_email || b.customerEmail || (b.customer_id ? `Klient #${b.customer_id}` : '') || (b.customerId ? `Klient #${b.customerId}` : '')).toLowerCase();
        return runtime.ordersSortDir === 'asc' ? clA.localeCompare(clB) : clB.localeCompare(clA);
      }
      if (runtime.ordersSortField === 'status') {
        const stA = String(a.status || '').toLowerCase();
        const stB = String(b.status || '').toLowerCase();
        return runtime.ordersSortDir === 'asc' ? stA.localeCompare(stB) : stB.localeCompare(stA);
      }
      if (runtime.ordersSortField === 'invoice') {
        const invA = Boolean(a.hasInvoice || a.has_invoice || a.invoiceNumber || a.invoice_number);
        const invB = Boolean(b.hasInvoice || b.has_invoice || b.invoiceNumber || b.invoice_number);
        return runtime.ordersSortDir === 'asc' ? (invA === invB ? 0 : invA ? 1 : -1) : (invA === invB ? 0 : invA ? -1 : 1);
      }
      return 0;
    });

    updateTableSortHeaders('#orders-table', runtime.ordersSortField, runtime.ordersSortDir);

    tbody.innerHTML = list.map(o => {
      const orderId = o.id;
      const custEmail = o.customer_email || o.customerEmail || '';
      const custId = o.customer_id || o.customerId;
      const clientLabel = custEmail ? escapeHtml(custEmail) : (custId ? (state.language === 'en' ? `Customer #${escapeHtml(custId)}` : `Klient #${escapeHtml(custId)}`) : (state.language === 'en' ? 'Anonymous customer' : 'Klient anonimowy'));
      const amt = Number(o.total_price ?? o.totalPrice ?? o.total_amount ?? o.totalAmount ?? 0);
      const cur = o.currency || 'PLN';
      const st = (o.status || 'PENDING').toUpperCase();
      const dt = o.created_at || o.createdAt ? new Date(o.created_at || o.createdAt).toLocaleString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      const isPaid = st === 'PAID' || st === 'COMPLETED';
      const hasInvoice = Boolean(o.hasInvoice || o.has_invoice || o.invoiceNumber || o.invoice_number);
      const invNum = o.invoiceNumber || o.invoice_number || '';

      let invoiceBtn = '';
      if (hasInvoice) {
        const pdfTitle = invNum ? (state.language === 'en' ? `Download ${invNum}` : `Pobierz ${invNum}`) : (state.language === 'en' ? 'Download PDF' : 'Pobierz PDF');
        invoiceBtn = `<a href="http://127.0.0.1:18080/api/invoices/order/${orderId}/pdf" target="_blank" class="btn-mini" style="background:rgba(34,197,94,0.12); color:#4ade80; border-color:rgba(34,197,94,0.3); font-weight:700;" title="${pdfTitle}">📄 ${invNum ? escapeHtml(invNum) : 'PDF'} ↗</a>`;
      } else if (isPaid) {
        invoiceBtn = `
          <div style="display:inline-flex; align-items:center; gap:6px;">
            <span style="color:var(--muted); font-size:11px;">${state.language === 'en' ? 'No invoice' : 'Brak FV'}</span>
            <button class="btn-mini generate-invoice-btn" data-order-id="${orderId}" style="border-color:#f59e0b; color:#fbbf24; padding:2px 6px;" title="${state.language === 'en' ? 'Issue VAT invoice for this paid order' : 'Wystaw fakturę VAT dla tego opłaconego zamówienia'}">${state.language === 'en' ? '➕ Issue' : '➕ Wystaw'}</button>
          </div>
        `;
      } else {
        invoiceBtn = `<span style="color:var(--muted); font-size:11px;">—</span>`;
      }
      const stClass = st.toLowerCase();
      return `
        <tr>
          <td><strong>#${orderId}</strong></td>
          <td title="${clientLabel}">${clientLabel}</td>
          <td><strong>${amt.toFixed(2)} ${escapeHtml(cur)}</strong></td>
          <td><span class="badge-status ${stClass}">${escapeHtml(st)}</span></td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
          <td>${invoiceBtn}</td>
        </tr>
      `;
    }).join('');

    $$('.generate-invoice-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const orderId = btn.dataset.orderId;
        btn.disabled = true;
        btn.textContent = state.language === 'en' ? 'Issuing...' : 'Wystawianie...';
        try {
          const res = await api('/api/data/orders/generate-invoice', {
            method: 'POST',
            body: JSON.stringify({ orderId }),
          });
          toast(state.language === 'en' ? `Invoice ${res.invoiceNumber} issued for order #${orderId}!` : `Wystawiono fakturę ${res.invoiceNumber} dla zamówienia #${orderId}!`);
          await loadOrders(true);
          window.open(`http://127.0.0.1:18080/api/invoices/order/${orderId}/pdf`, '_blank');
        } catch (err) {
          toast(err.message, true);
          btn.disabled = false;
          btn.textContent = state.language === 'en' ? '➕ Issue' : '➕ Wystaw';
        }
      };
    });
  }
