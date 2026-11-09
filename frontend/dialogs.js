
    'use strict';
    const bindings = new Map();
    const visible = element => element instanceof HTMLElement && element.isConnected
        && !element.closest('[hidden], [inert]') && element.getClientRects().length > 0;
    const focusable = dialog => [...dialog.querySelectorAll('a[href], button, input, select, textarea, [tabindex]')]
        .filter(element => visible(element) && !element.matches(':disabled') && element.tabIndex >= 0);
    const canFocus = element => visible(element)
        && element.matches('a[href], button, input, select, textarea, [tabindex]') && !element.matches(':disabled');
    const syncScroll = () => document.body.classList.toggle('store-dialog-open', Boolean(document.querySelector('dialog[open]')));

    function bind(dialog, onDismiss, fallback) {
        if (!dialog || bindings.has(dialog)) return;
        const entry = { onDismiss, fallback, opener: null };
        bindings.set(dialog, entry);
        dialog.addEventListener('cancel', event => {
            event.preventDefault();
            entry.onDismiss();
        });
        dialog.addEventListener('close', () => {
            // A queued close event must not hide a dialog reopened in the same turn.
            if (dialog.open) return;
            if (!dialog.hidden) entry.onDismiss();
            syncScroll();
        });
        dialog.addEventListener('keydown', event => {
            if (event.key !== 'Tab') return;
            const controls = focusable(dialog);
            const index = controls.indexOf(document.activeElement);
            if (!controls.length) {
                event.preventDefault();
                dialog.querySelector('h2')?.focus();
            } else if (event.shiftKey ? index <= 0 : index === -1 || index === controls.length - 1) {
                event.preventDefault();
                controls[event.shiftKey ? controls.length - 1 : 0].focus();
            }
        });
    }

    function open(dialog, target, opener) {
        if (!dialog) return;
        const entry = bindings.get(dialog);
        if (!entry) throw new Error('Dialog must be bound before opening');
        if (!dialog.open) {
            entry.opener = opener || document.activeElement;
            dialog.hidden = false;
            dialog.showModal();
            syncScroll();
        }
        // Wait for synchronous panel/form changes made by the caller before focusing.
        queueMicrotask(() => {
            if (!dialog.open) return;
            const initial = typeof target === 'string' ? dialog.querySelector(target) : target;
            const fallback = dialog.querySelector('h2');
            const next = visible(initial) ? initial : visible(fallback) ? fallback : focusable(dialog)[0];
            next?.focus({ preventScroll: true });
        });
    }

    function close(dialog) {
        if (!dialog || (!dialog.open && dialog.hidden)) return;
        const entry = bindings.get(dialog);
        if (dialog.open) dialog.close();
        dialog.hidden = true;
        syncScroll();
        const opener = entry?.opener;
        const fallback = document.querySelector(entry?.fallback || '#auth-toggle');
        const target = canFocus(opener) ? opener : fallback;
        if (canFocus(target)) target.focus({ preventScroll: true });
        if (entry) entry.opener = null;
    }
    export const dialogs = Object.freeze({ bind, open, close });
window.MiniTechDialogs = dialogs;

