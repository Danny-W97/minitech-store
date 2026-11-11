import { toast } from './store-dom.js?v=20261028-a43-32';

export function showToast(message, duration = 2600) {
    toast.textContent = message;
    toast.classList.add('visible');
    window.clearTimeout(showToast.timer);
    showToast.timer = window.setTimeout(() => toast.classList.remove('visible'), duration);
}
