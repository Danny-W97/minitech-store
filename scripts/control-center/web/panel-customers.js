import { updateTableSortHeaders } from './panel-data.js?v=20261027-a42';
import { fetchListPage, pageControls } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, escapeHtml } from './panel-utils.js?v=20261027-a42';

export async function loadCustomers(force = false) {
    const tbody = $('#customers-tbody');
    if (!tbody) return;
    if (force || !runtime.customersLoaded || runtime.customersError) {
      const generation = ++runtime.customersRequestGeneration;
      runtime.customersLoading = true;
      runtime.customersError = '';
      renderCustomersTable();
      try {
        const res = await fetchListPage('customers', '/api/data/customers', { search: $('#customers-search-input')?.value.trim() || '' });
        if (res.stale || generation !== runtime.customersRequestGeneration) return;
        if (!Array.isArray(res.customers)) throw new Error(state.language === 'en' ? 'Invalid customer list response.' : 'Nieprawidłowa odpowiedź listy klientów.');
        pageControls('customers', tbody, loadCustomers);
        runtime.cachedCustomers = res.customers;
        runtime.customersLoaded = true;
      } catch (err) {
        if (generation !== runtime.customersRequestGeneration) return;
        runtime.customersError = err.message;
      }
      runtime.customersLoading = false;
    }
    renderCustomersTable();
  }

export function renderCustomersTable() {
    const tbody = $('#customers-tbody');
    if (!tbody) return;
    const errorPanel = $('#customers-error');
    errorPanel.hidden = !runtime.customersError;
    $('#customers-error-message').textContent = runtime.customersError;
    $('#customers-table').setAttribute('aria-busy', String(runtime.customersLoading));
    const controls = $('#list-page-customers');
    if (controls) controls.hidden = runtime.customersLoading || Boolean(runtime.customersError);
    if (runtime.customersError || runtime.customersLoading || !runtime.customersLoaded) {
      const message = runtime.customersError
        ? (state.language === 'en' ? 'Customer list unavailable.' : 'Lista klientów niedostępna.')
        : (state.language === 'en' ? 'Loading customers…' : 'Ładowanie klientów…');
      tbody.innerHTML = `<tr><td colspan="5" class="table-empty">${message}</td></tr>`;
      return;
    }
    const query = ($('#customers-search-input')?.value || '').toLowerCase().trim();
    let list = runtime.cachedCustomers;
    if (!list.length) {
      const message = query
        ? (state.language === 'en' ? 'No customers match this search.' : 'Brak klientów dla podanego filtra.')
        : (state.language === 'en' ? 'No registered customers.' : 'Brak zarejestrowanych klientów.');
      tbody.innerHTML = `<tr><td colspan="5" class="table-empty">${message}</td></tr>`;
      return;
    }

    // Sort customers
    list = [...list].sort((a, b) => {
      if (runtime.customersSortField === 'id') {
        const idA = Number(a.id) || 0;
        const idB = Number(b.id) || 0;
        return runtime.customersSortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (runtime.customersSortField === 'email') {
        const eA = String(a.email || '').toLowerCase();
        const eB = String(b.email || '').toLowerCase();
        return runtime.customersSortDir === 'asc' ? eA.localeCompare(eB) : eB.localeCompare(eA);
      }
      if (runtime.customersSortField === 'name') {
        const nA = `${a.first_name || ''} ${a.last_name || ''}`.trim().toLowerCase();
        const nB = `${b.first_name || ''} ${b.last_name || ''}`.trim().toLowerCase();
        return runtime.customersSortDir === 'asc' ? nA.localeCompare(nB) : nB.localeCompare(nA);
      }
      if (runtime.customersSortField === 'city') {
        return runtime.customersSortDir === 'asc' ? a.city.localeCompare(b.city) : b.city.localeCompare(a.city);
      }
      if (runtime.customersSortField === 'date') {
        const dA = a.created_at ? new Date(a.created_at).getTime() : 0;
        const dB = b.created_at ? new Date(b.created_at).getTime() : 0;
        return runtime.customersSortDir === 'asc' ? dA - dB : dB - dA;
      }
      return 0;
    });

    updateTableSortHeaders('#customers-table', runtime.customersSortField, runtime.customersSortDir);

    tbody.innerHTML = list.map(c => {
      const cid = c.id;
      const email = c.email || '—';
      const name = `${c.first_name || ''} ${c.last_name || ''}`.trim() || '—';
      const dt = c.created_at ? new Date(c.created_at).toLocaleDateString(state.language === 'en' ? 'en-US' : 'pl-PL') : '—';
      return `
        <tr>
          <td><strong>#${cid}</strong></td>
          <td><a href="mailto:${escapeHtml(email)}" style="color:#38bdf8;">${escapeHtml(email)}</a></td>
          <td>${escapeHtml(name)}</td>
          <td>${escapeHtml(c.city || '—')}</td>
          <td style="font-size:11px; color:var(--muted);">${escapeHtml(dt)}</td>
        </tr>
      `;
    }).join('');
  }
