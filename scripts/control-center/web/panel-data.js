import { loadCustomers, renderCustomersTable } from './panel-customers.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { loadInventory, renderInventoryTable } from './panel-inventory.js?v=20261027-a42';
import {
    loadDispatch, loadInvoices, renderDispatchTable, renderInvoicesTable,
} from './panel-invoices.js?v=20261027-a42';
import { loadOrders, renderOrdersTable } from './panel-orders.js?v=20261027-a42';
import { downloadControlFile, reloadFiltered } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$ } from './panel-utils.js?v=20261027-a42';

export function updateTableSortHeaders(tableSelector, sortField, sortDir) {
    $$(`${tableSelector} th.th-sortable`).forEach(th => {
      const f = th.dataset.sort;
      const isSorted = f === sortField;
      th.classList.toggle('sorted-asc', isSorted && sortDir === 'asc');
      th.classList.toggle('sorted-desc', isSorted && sortDir === 'desc');
      const ind = $('.sort-indicator', th);
      if (ind) {
        ind.textContent = isSorted ? (sortDir === 'asc' ? '▲' : '▼') : '⇅';
      }
    });
  }

export function handleSortHeaderClick(tableSelector, sortSelectSelector, field, currentFieldGetter, currentDirGetter, sortSetter, renderFn) {
    const curF = currentFieldGetter();
    const curD = currentDirGetter();
    let newD = 'asc';
    if (curF === field) {
      newD = curD === 'asc' ? 'desc' : 'asc';
    } else {
      newD = (field === 'date' || field === 'amount' || field === 'id' || field === 'invoice' || field === 'available' || field === 'reserved' || field === 'price' || field === 'balance') ? 'desc' : 'asc';
    }
    sortSetter(field, newD);
    const sel = $(sortSelectSelector);
    if (sel) {
      const match = `${field}_${newD}`;
      if ([...sel.options].some(opt => opt.value === match)) {
        sel.value = match;
      }
    }
    updateTableSortHeaders(tableSelector, field, newD);
    renderFn();
  }

export function setupDataViewOnce() {
    if (runtime.dataViewSetupDone) return;
    runtime.dataViewSetupDone = true;

    $$('.sub-tab-btn[data-subtab]').forEach(btn => {
      btn.onclick = () => {
        runtime.dataSubTab = btn.dataset.subtab;
        $$('.sub-tab-btn[data-subtab]').forEach(b => b.classList.toggle('active', b === btn));
        $$('#view-data .subpane').forEach(p => p.classList.toggle('active', p.id === `data-pane-${runtime.dataSubTab}`));
        loadActiveDataTab();
      };
    });

    const refBtn = $('#data-refresh-btn');
    if (refBtn) refBtn.onclick = () => loadActiveDataTab(true);

    $$('.filter-pill[data-status-filter]').forEach(pill => {
      pill.onclick = () => {
        runtime.ordersStatusFilter = pill.dataset.statusFilter;
        $$('.filter-pill[data-status-filter]').forEach(p => p.classList.toggle('active', p === pill));
        reloadFiltered('orders', loadOrders);
      };
    });

    const ordersSearch = $('#orders-search-input');
    if (ordersSearch) ordersSearch.oninput = () => reloadFiltered('orders', loadOrders);

    const ordersSortSelect = $('#orders-sort-select');
    if (ordersSortSelect) {
      ordersSortSelect.onchange = () => {
        const val = ordersSortSelect.value;
        const [f, d] = val.split('_');
        runtime.ordersSortField = f;
        runtime.ordersSortDir = d;
        updateTableSortHeaders('#orders-table', runtime.ordersSortField, runtime.ordersSortDir);
        renderOrdersTable();
      };
    }

    $$('#orders-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#orders-table', '#orders-sort-select', th.dataset.sort,
          () => runtime.ordersSortField, () => runtime.ordersSortDir,
          (f, d) => { runtime.ordersSortField = f; runtime.ordersSortDir = d; },
          renderOrdersTable
        );
      };
    });

    const invoicesSearch = $('#invoices-search-input');
    if (invoicesSearch) invoicesSearch.oninput = () => reloadFiltered('invoices', loadInvoices);

    const invoicesSortSelect = $('#invoices-sort-select');
    if (invoicesSortSelect) {
      invoicesSortSelect.onchange = () => {
        const val = invoicesSortSelect.value;
        const [f, d] = val.split('_');
        runtime.invoicesSortField = f;
        runtime.invoicesSortDir = d;
        updateTableSortHeaders('#invoices-table', runtime.invoicesSortField, runtime.invoicesSortDir);
        renderInvoicesTable();
      };
    }

    const invoicesExportZipBtn = $('#invoices-export-zip-btn');
    if (invoicesExportZipBtn) {
      invoicesExportZipBtn.onclick = async () => {
        const params = new URLSearchParams();
        const from = $('#invoices-export-from')?.value, to = $('#invoices-export-to')?.value;
        if (from && to && new Date(from) > new Date(to)) {
          toast(state.language === 'en' ? 'Start must not be after end.' : 'Początek zakresu nie może być późniejszy niż koniec.', true); return;
        }
        if (from) params.set('from', new Date(from).toISOString());
        if (to) params.set('to', new Date(to).toISOString());
        invoicesExportZipBtn.disabled = true;
        toast(state.language === 'en' ? 'Preparing ZIP archive...' : 'Przygotowanie archiwum ZIP...');
        try { await downloadControlFile('/api/data/invoices/export-zip' + (params.size ? `?${params}` : ''), 'faktury.zip'); }
        catch (error) { toast(error.message, true); }
        finally { invoicesExportZipBtn.disabled = false; }
      };
    }

    $$('#invoices-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#invoices-table', '#invoices-sort-select', th.dataset.sort,
          () => runtime.invoicesSortField, () => runtime.invoicesSortDir,
          (f, d) => { runtime.invoicesSortField = f; runtime.invoicesSortDir = d; },
          renderInvoicesTable
        );
      };
    });

    const invSearch = $('#inventory-search-input');
    if (invSearch) invSearch.placeholder = state.language === 'en' ? 'Product ID' : 'ID produktu';
    if (invSearch) invSearch.oninput = () => reloadFiltered('inventory', loadInventory);

    const invSortSelect = $('#inventory-sort-select');
    if (invSortSelect) {
      invSortSelect.onchange = () => {
        const val = invSortSelect.value;
        const [f, d] = val.split('_');
        runtime.inventorySortField = f;
        runtime.inventorySortDir = d;
        updateTableSortHeaders('#inventory-table', runtime.inventorySortField, runtime.inventorySortDir);
        renderInventoryTable();
      };
    }

    $$('#inventory-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#inventory-table', '#inventory-sort-select', th.dataset.sort,
          () => runtime.inventorySortField, () => runtime.inventorySortDir,
          (f, d) => { runtime.inventorySortField = f; runtime.inventorySortDir = d; },
          renderInventoryTable
        );
      };
    });

    const custSearch = $('#customers-search-input');
    if (custSearch) custSearch.oninput = () => reloadFiltered('customers', loadCustomers);
    const customersRetry = $('#customers-retry');
    if (customersRetry) customersRetry.onclick = () => loadCustomers(true);

    const custSortSelect = $('#customers-sort-select');
    if (custSortSelect) {
      custSortSelect.onchange = () => {
        const val = custSortSelect.value;
        const [f, d] = val.split('_');
        runtime.customersSortField = f;
        runtime.customersSortDir = d;
        updateTableSortHeaders('#customers-table', runtime.customersSortField, runtime.customersSortDir);
        renderCustomersTable();
      };
    }

    $$('#customers-table th.th-sortable').forEach(th => {
      th.onclick = () => {
        handleSortHeaderClick('#customers-table', '#customers-sort-select', th.dataset.sort,
          () => runtime.customersSortField, () => runtime.customersSortDir,
          (f, d) => { runtime.customersSortField = f; runtime.customersSortDir = d; },
          renderCustomersTable
        );
      };
    });

    const dispatchSearch = $('#dispatch-search-input');
    if (dispatchSearch) dispatchSearch.oninput = () => reloadFiltered('dispatch', loadDispatch);

    $$('.filter-pill[data-dispatch-filter]').forEach(pill => {
      pill.onclick = () => {
        runtime.dispatchFilter = pill.dataset.dispatchFilter;
        $$('.filter-pill[data-dispatch-filter]').forEach(p => p.classList.toggle('active', p === pill));
        renderDispatchTable();
      };
    });
  }

export function loadActiveDataTab(force = false) {
    if (runtime.dataSubTab === 'orders') loadOrders(force);
    else if (runtime.dataSubTab === 'invoices') loadInvoices(force);
    else if (runtime.dataSubTab === 'dispatch') loadDispatch(force);
    else if (runtime.dataSubTab === 'inventory') loadInventory(force);
    else if (runtime.dataSubTab === 'customers') loadCustomers(force);
  }

export async function loadDataView() {
    setupDataViewOnce();
    loadActiveDataTab();
  }
