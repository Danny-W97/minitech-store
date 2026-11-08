/** Forget this panel's view; shared server jobs, other sessions and vaults stay independent. */
export function clearPanelView({state,runtime,document,disposeTerminal,clearInterval:cancel=globalThis.clearInterval.bind(globalThis)}) {
    cancel(runtime.dynamicPollTimer);runtime.dynamicPollTimer=null;
    runtime.assistantConversation?.stop();runtime.jobMonitor?.dispose();runtime.commandPalette?.close?.();
    disposeTerminal();
    for(const key of Object.keys(state)) {
        if(['profile','language'].includes(key))continue;
        state[key]=Array.isArray(state[key])?[]:null;
    }
    for(const key of Object.keys(runtime)) {
        if(Array.isArray(runtime[key]))runtime[key]=[];
        else if(typeof runtime[key]==='string')runtime[key]='';
    }
    const language=state.language;
    // Keep empty control nodes alive for finally blocks of requests just aborted.
    // The old view is inert, its values/text/URLs/data attributes are discarded.
    for(const node of document.body.querySelectorAll('*')) {
        if('value' in node)node.value='';
        for(const attribute of [...node.attributes])if(!['id','class','type','name','role'].includes(attribute.name))node.removeAttribute(attribute.name);
        if('disabled' in node)node.disabled=true;
        if(node.tagName==='IFRAME')node.remove();
    }
    const walker=document.createTreeWalker(document.body,4);const texts=[];
    while(walker.nextNode())if(!['SCRIPT','STYLE'].includes(walker.currentNode.parentElement?.tagName))texts.push(walker.currentNode);
    for(const text of texts)text.textContent='';
    for(const child of document.body.children){child.hidden=true;child.inert=true;}
    document.title=language==='en'?'Session ended':'Sesja zakończona';
    const panel=document.createElement('main');panel.className='panel';
    const title=document.createElement('h1');title.textContent=language==='en'?'Panel session ended':'Sesja panelu zakończona';
    const status=document.createElement('p');status.id='panel-session-status';status.textContent=language==='en'?'Local data cleared. Running server jobs continue.':'Wyczyszczono lokalne dane. Uruchomione zadania serwera działają dalej.';
    const reconnect=document.createElement('button');reconnect.className='primary-button';reconnect.textContent=language==='en'?'Reconnect':'Połącz ponownie';reconnect.onclick=()=>globalThis.location.reload();
    panel.append(title,status,reconnect);document.body.append(panel);
}
