import { i18n } from './i18n.js?v=20261028-a43-32';
import { saveCompareIds, stockFor } from './store-cart.js?v=20261028-a43-32';
import { renderProducts } from './store-catalog.js?v=20261028-a43-32';
import {
    compareChips, compareClear, compareClose, compareHint, compareModal, compareOpen, compareTable,
    compareTray, dialogs,
} from './store-dom.js?v=20261028-a43-32';
import { showToast } from './store-feedback.js?v=20261028-a43-32';
import { escapeHtml, formatMoney } from './store-format.js?v=20261028-a43-32';
import { renderHighlights } from './store-highlights.js?v=20261028-a43-32';
import { MAX_COMPARE_PRODUCTS, TECHNICAL_PARAMETER_LABELS, state } from './store-state.js?v=20261028-a43-32';

export function selectedCompareProducts() {
    return [...state.compareIds]
        .map(id => state.products.find(product => Number(product.id) === Number(id)))
        .filter(Boolean);
}

export function toggleCompare(productId) {
    const id = Number(productId);
    const product = state.products.find(item => Number(item.id) === id);
    if (!product) return;

    if (state.compareIds.has(id)) {
        state.compareIds.delete(id);
        showToast('Usunięto produkt z porównania');
    } else if (state.compareIds.size >= MAX_COMPARE_PRODUCTS) {
        showToast(`Możesz porównać maksymalnie ${MAX_COMPARE_PRODUCTS} produkty.`);
        return;
    } else {
        state.compareIds.add(id);
        showToast('Dodano produkt do porównania');
    }

    saveCompareIds();
    renderProducts();
    renderHighlights();
    renderCompareControls();
}

export function clearCompare() {
    if (!state.compareIds.size) return;
    state.compareIds.clear();
    saveCompareIds();
    renderProducts();
    renderHighlights();
    renderCompareControls();
    showToast('Wyczyszczono porównanie');
}

export function pruneCompareIds() {
    const availableIds = new Set(state.products.map(product => Number(product.id)));
    state.compareIds = new Set([...state.compareIds].filter(id => availableIds.has(Number(id))));
    saveCompareIds();
}

export function renderCompareControls() {
    if (!compareTray) return;
    const selected = selectedCompareProducts();
    compareTray.hidden = selected.length === 0;
    if (compareHint) {
        compareHint.textContent = selected.length < 2
            ? `Wybierz co najmniej 2 produkty. ${selected.length}/${MAX_COMPARE_PRODUCTS} wybranych.`
            : `${selected.length}/${MAX_COMPARE_PRODUCTS} wybranych. Gotowe do porównania.`;
    }
    if (compareOpen) compareOpen.disabled = selected.length < 2;
    if (compareClear) compareClear.disabled = selected.length === 0;
    if (compareChips) {
        compareChips.innerHTML = selected.map(product => `
            <span class="compare-chip">
                <span class="compare-chip-label">${escapeHtml(product.name)}</span>
                <button type="button" data-compare-remove="${escapeHtml(product.id)}" aria-label="Usuń ${escapeHtml(product.name)} z porównania">×</button>
            </span>
        `).join('');
    }
    document.querySelectorAll('[data-action="compare"]').forEach(button => {
        const active = state.compareIds.has(Number(button.dataset.productId));
        button.classList.toggle('is-active', active);
        button.setAttribute('aria-pressed', String(active));
        button.setAttribute('title', active ? 'Usuń z porównania' : 'Dodaj do porównania');
        button.textContent = active ? '✓ Porównaj' : 'Porównaj';
    });
}

export function openCompareModal() {
    const selected = selectedCompareProducts();
    if (selected.length < 2 || !compareModal || !compareTable) return;
    compareTable.innerHTML = renderComparisonTable(selected);
    dialogs.open(compareModal);
    compareClose?.focus();
}

export function closeCompareModal() {
    if (compareModal) dialogs.close(compareModal);
}

export function renderComparisonTable(products) {
    const isEn = i18n && i18n.getLanguage() === 'en';
    const parameterKeys = [...new Set(products.flatMap(product => Object.keys(product.technicalParameters || {})))].sort((left, right) => left.localeCompare(right, isEn ? 'en' : 'pl'));
    const rows = [
        [isEn ? 'Category' : 'Kategoria', product => (i18n ? i18n.translateCategory(product.category) : product.category) || '—'],
        [isEn ? 'Manufacturer' : 'Producent', product => product.manufacturer || '—'],
        [isEn ? 'Model' : 'Model', product => product.model || '—'],
        [isEn ? 'SKU' : 'SKU', product => product.sku || '—'],
        [isEn ? 'Price' : 'Cena', product => formatMoney(product.price)],
        [isEn ? 'Availability' : 'Dostępność', product => {
            const quantity = stockFor(product.id)?.availableQuantity;
            return Number.isInteger(quantity) ? (isEn ? `${quantity} pcs` : `${quantity} szt.`) : (isEn ? 'Unavailable' : 'Niedostępna');
        }],
        ...parameterKeys.map(key => [i18n ? i18n.translateParamKey(key) : (TECHNICAL_PARAMETER_LABELS[key] || key), product => product.technicalParameters?.[key] ?? '—'])
    ];
    const header = products.map(product => `<th scope="col">${escapeHtml(product.name)}</th>`).join('');
    const body = rows.map(([label, value]) => `
        <tr>
            <th scope="row">${escapeHtml(label)}</th>
            ${products.map(product => `<td>${escapeHtml(String(value(product)))}</td>`).join('')}
        </tr>
    `).join('');
    return `<div class="comparison-table-scroll" tabindex="0" role="region" aria-label="${isEn ? 'Scrollable product comparison' : 'Przewijane porównanie produktów'}"><table class="comparison-table">
        <thead><tr><th scope="col">${isEn ? 'Parameter' : 'Parametr'}</th>${header}</tr></thead>
        <tbody>${body}</tbody>
    </table></div>`;
}
