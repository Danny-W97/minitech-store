import { updateTableSortHeaders } from './panel-data.js?v=20261027-a42';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { finishStockIntent, readPendingStock, stockIntent } from './panel-operations.js?v=20261027-a42';
import { api, fetchListPage, pageControls } from './panel-services.js?v=20261027-a42';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $, $$, escapeHtml } from './panel-utils.js?v=20261027-a42';

export async function loadInventory(force = false) {
    const tbody = $('#inventory-tbody');
    if (!tbody) return;
    if (force || !runtime.cachedInventory.length) {
      try {
        const res = await fetchListPage('inventory', '/api/data/inventory', { search: $('#inventory-search-input')?.value.trim() || '' });
        if (res.stale) return;
        pageControls('inventory', tbody, loadInventory);
        runtime.cachedInventory = res.inventory || [];
      } catch (err) {
        toast(err.message, true);
        return;
      }
    }
    renderInventoryTable();
  }

export function renderInventoryTable() {
    const tbody = $('#inventory-tbody');
    if (!tbody) return;
    const query = ($('#inventory-search-input')?.value || '').toLowerCase().trim();
    let list = runtime.cachedInventory;
    const badge = $('#inventory-count-badge');
    if (badge) badge.textContent = `${list.length} ${state.language === 'en' ? 'items' : 'pozycji'}`;
    if (!list.length) {
      tbody.innerHTML = `<tr><td colspan="8" class="table-empty">${state.language === 'en' ? 'No inventory items found.' : 'Brak pozycji magazynowych.'}</td></tr>`;
      return;
    }

    // Sort inventory
    list = [...list].sort((a, b) => {
      if (runtime.inventorySortField === 'id') {
        const idA = Number(a.product_id || a.productId || a.id) || 0;
        const idB = Number(b.product_id || b.productId || b.id) || 0;
        return runtime.inventorySortDir === 'asc' ? idA - idB : idB - idA;
      }
      if (runtime.inventorySortField === 'name') {
        const nA = String(a.name || '').toLowerCase();
        const nB = String(b.name || '').toLowerCase();
        return runtime.inventorySortDir === 'asc' ? nA.localeCompare(nB) : nB.localeCompare(nA);
      }
      if (runtime.inventorySortField === 'sku') {
        const sA = String(a.sku || '').toLowerCase();
        const sB = String(b.sku || '').toLowerCase();
        return runtime.inventorySortDir === 'asc' ? sA.localeCompare(sB) : sB.localeCompare(sA);
      }
      if (runtime.inventorySortField === 'category') {
        const cA = String(a.category || '').toLowerCase();
        const cB = String(b.category || '').toLowerCase();
        return runtime.inventorySortDir === 'asc' ? cA.localeCompare(cB) : cB.localeCompare(cA);
      }
      if (runtime.inventorySortField === 'available') {
        const avA = Number(a.available_quantity != null ? a.available_quantity : a.availableQuantity ?? 0);
        const avB = Number(b.available_quantity != null ? b.available_quantity : b.availableQuantity ?? 0);
        return runtime.inventorySortDir === 'asc' ? avA - avB : avB - avA;
      }
      if (runtime.inventorySortField === 'reserved') {
        const rA = Number(a.reserved_quantity != null ? a.reserved_quantity : a.reservedQuantity ?? 0);
        const rB = Number(b.reserved_quantity != null ? b.reserved_quantity : b.reservedQuantity ?? 0);
        return runtime.inventorySortDir === 'asc' ? rA - rB : rB - rA;
      }
      if (runtime.inventorySortField === 'price') {
        const pA = Number(a.price ?? 0);
        const pB = Number(b.price ?? 0);
        return runtime.inventorySortDir === 'asc' ? pA - pB : pB - pA;
      }
      return 0;
    });

    updateTableSortHeaders('#inventory-table', runtime.inventorySortField, runtime.inventorySortDir);

    tbody.innerHTML = list.map(item => {
      const pid = item.product_id || item.productId || item.id;
      const name = item.name || `Produkt #${pid}`;
      const sku = item.sku || '—';
      const cat = item.category || '—';
      const avail = item.available_quantity != null ? item.available_quantity : item.availableQuantity ?? 0;
      const res = item.reserved_quantity != null ? item.reserved_quantity : item.reservedQuantity ?? 0;
      const price = item.price ? `${Number(item.price).toFixed(2)} PLN` : '—';
      const lowStock = avail < 5;
      let pending = null, storageBlocked = false;
      try { pending = readPendingStock(String(pid)); } catch { storageBlocked = true; }
      return `
        <tr>
          <td><strong>#${pid}</strong></td>
          <td><strong>${escapeHtml(name)}</strong></td>
          <td><code>${escapeHtml(sku)}</code></td>
          <td><span style="font-size:11px; color:var(--muted);">${escapeHtml(cat)}</span></td>
          <td><strong style="color: ${lowStock ? 'var(--danger)' : 'var(--green)'};">${avail} szt.</strong></td>
          <td style="color:var(--muted);">${res} szt.</td>
          <td>${escapeHtml(price)}</td>
          <td>
            <div class="restock-btn-group">
              <button class="btn-mini restock-btn" data-product-id="${pid}" data-qty="10" ${storageBlocked || pending && pending.quantity !== 10 ? 'disabled' : ''} title="${pending ? pending.operationId : 'Dodaj 10 sztuk'}">${pending?.quantity === 10 ? 'Ponów +10' : '+10'}</button>
              <button class="btn-mini restock-btn" data-product-id="${pid}" data-qty="50" ${storageBlocked || pending && pending.quantity !== 50 ? 'disabled' : ''} title="${pending ? pending.operationId : 'Dodaj 50 sztuk'}">${pending?.quantity === 50 ? 'Ponów +50' : '+50'}</button>
            </div>
          </td>
        </tr>
      `;
    }).join('');

    $$('.restock-btn', tbody).forEach(btn => {
      btn.onclick = async () => {
        const prodId = btn.dataset.productId;
        const qty = parseInt(btn.dataset.qty, 10);
        $$('button', btn.closest('.restock-btn-group')).forEach(button => { button.disabled = true; });
        try {
          const intent = await stockIntent(prodId, qty);
          const result = await api('/api/data/inventory/restock', { method: 'POST', body: JSON.stringify({ productId: prodId, quantity: qty, operationId: intent.operationId }) });
          if (result.status !== 'COMMITTED' || result.operationId !== intent.operationId || result.quantity !== qty || result.kind !== 'RECEIPT') {
            throw new Error(`Nie potwierdzono operacji ${intent.operationId}. Ponów ten sam identyfikator.`);
          }
          await finishStockIntent(intent);
          toast(state.language === 'en' ? `Stock replenished (+${qty} pcs)` : `Doładowano stan magazynowy (+${qty} szt.)`);
          await loadInventory(true);
        } catch (err) {
          toast(err.message, true);
        } finally {
          renderInventoryTable();
        }
      };
    });
  }
