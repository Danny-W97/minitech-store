import { createControlClient } from './panel-client.js?v=20261026-1';
import { toast } from './panel-feedback.js?v=20261027-a42';
import { createPanelLists } from './panel-lists.js?v=20261026-1';
import { runtime, state } from './panel-state.js?v=20261027-a42';
import { $ } from './panel-utils.js?v=20261027-a42';
import { disposeTerminal } from './panel-terminal.js?v=20261027-a42';
import { clearPanelView } from './panel-session-cleanup.js?v=20261030-a43-38';
const downloadUrls = new Set();

export async function downloadControlFile(path, name = 'dokument.pdf') {
    const blob = await panelClient.blob(path);
    const blobUrl = URL.createObjectURL(blob);
    downloadUrls.add(blobUrl);
    const link = document.createElement('a');
    link.href = blobUrl;
    link.download = name;
    link.click();
    setTimeout(() => {URL.revokeObjectURL(blobUrl);downloadUrls.delete(blobUrl);}, 1000);
  }

export const panelClient = createControlClient({
    baseUrl: location.href,
    getLanguage: () => state.language,
    onSessionExpired(error) {
      for(const url of downloadUrls)URL.revokeObjectURL(url);
      downloadUrls.clear();
      listPager.dispose();
      clearPanelView({state,runtime,document,disposeTerminal});
    }
  });

export async function logoutPanel() {
    const result=await panelClient.logout();
    const status=document.getElementById('panel-session-status');
    if(status&&!result.serverRevoked)status.textContent=state.language==='en'?'Local data cleared. Server session revocation could not be confirmed; it still has its normal expiry.':'Wyczyszczono lokalne dane. Brak potwierdzenia unieważnienia sesji serwera; obowiązuje jej dotychczasowe wygaśnięcie.';
}

export const api = (path, options = {}) => panelClient.json(path, options);

export const listPager = createPanelLists({
    request: api, document, getLanguage: () => state.language,
    onError: error => toast(error.message, true)
  });

export const fetchListPage = (...args) => listPager.fetch(...args);

export const pageControls = (...args) => listPager.render(...args);

export const reloadFiltered = (...args) => listPager.filter(...args);
