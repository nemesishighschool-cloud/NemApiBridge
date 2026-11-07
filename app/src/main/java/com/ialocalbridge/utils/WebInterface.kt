package com.ialocalbridge.utils

object WebInterface {
    fun getHtml(): String {
        return """<!DOCTYPE html>
<html lang="fr">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>API Bridge - Admin Panel</title>
    <link rel="icon" type="image/svg+xml" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>N</text></svg>">
    <style>
        *,*::before,*::after{box-sizing:border-box;margin:0;padding:0}
        :root{
            --background:#0f1117;--foreground:#e1e4ed;--card:#1a1d27;--card-foreground:#e1e4ed;
            --primary:#6366f1;--primary-foreground:#ffffff;--muted:#252836;--muted-foreground:#8b8fa3;
            --border:#2a2d3a;--ring:#6366f1;--radius:0.75rem;--destructive:#ef4444;
            --success:#22c55e;--warning:#f59e0b;--sidebar:#1a1d27;--font-sans:'Inter',system-ui,-apple-system,sans-serif;
        }
        body{font-family:var(--font-sans);background:var(--background);color:var(--foreground);display:flex;min-height:100vh;line-height:1.5;font-size:14px}
        .sidebar{width:220px;background:var(--sidebar);border-right:1px solid var(--border);display:flex;flex-direction:column;position:fixed;top:0;left:0;bottom:0;z-index:100}
        .sidebar-header{display:flex;align-items:center;gap:10px;padding:16px;border-bottom:1px solid var(--border)}
        .sidebar-header .logo{width:28px;height:28px;background:var(--primary);border-radius:6px;display:flex;align-items:center;justify-content:center;font-weight:700;font-size:14px;color:#fff}
        .sidebar-header .title{font-weight:600;font-size:14px}
        .nav{flex:1;padding:8px;display:flex;flex-direction:column;gap:2px}
        .nav-item{display:flex;align-items:center;gap:10px;padding:8px 12px;border-radius:6px;cursor:pointer;color:var(--muted-foreground);font-size:13px;font-weight:500;transition:all 0.15s;border:none;background:none;width:100%;text-align:left}
        .nav-item:hover{background:#ffffff08;color:var(--foreground)}
        .nav-item.active{background:#6366f11a;color:var(--primary)}
        .nav-item svg{width:16px;height:16px;flex-shrink:0}
        .sidebar-footer{padding:12px;border-top:1px solid var(--border);font-size:12px;color:var(--muted-foreground)}
        .status-dot{width:6px;height:6px;border-radius:50%;display:inline-block;margin-right:6px}
        .status-dot.online{background:var(--success)}
        .status-dot.offline{background:var(--destructive)}
        .main{margin-left:220px;flex:1;display:flex;flex-direction:column;min-width:0}
        .topbar{display:flex;align-items:center;justify-content:space-between;padding:12px 24px;border-bottom:1px solid var(--border);background:var(--background);position:sticky;top:0;z-index:50}
        .topbar h2{font-size:16px;font-weight:600}
        .content{flex:1;padding:24px;overflow-y:auto}
        .page{display:none}
        .page.active{display:block}
        .card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:20px;margin-bottom:16px}
        .card-header{display:flex;align-items:center;justify-content:space-between;margin-bottom:12px}
        .card-title{font-size:12px;font-weight:600;color:var(--muted-foreground);text-transform:uppercase;letter-spacing:0.5px}
        .card-value{font-size:26px;font-weight:700;color:var(--foreground)}
        .card-icon{width:36px;height:36px;border-radius:8px;display:flex;align-items:center;justify-content:center;flex-shrink:0}
        .card-icon.blue{background:#6366f11a;color:var(--primary)}
        .card-icon.green{background:#22c55e1a;color:var(--success)}
        .card-icon.amber{background:#f59e0b1a;color:var(--warning)}
        .card-icon.red{background:#ef44441a;color:var(--destructive)}
        .stats-grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:16px;margin-bottom:24px}
        .stat-card{background:var(--card);border:1px solid var(--border);border-radius:var(--radius);padding:16px;display:flex;align-items:flex-start;justify-content:space-between}
        .stat-info{display:flex;flex-direction:column;gap:4px}
        .btn{display:inline-flex;align-items:center;gap:6px;padding:7px 14px;border-radius:6px;font-size:13px;font-weight:500;cursor:pointer;border:1px solid var(--border);background:var(--card);color:var(--foreground);transition:all 0.15s;font-family:inherit}
        .btn:hover{background:var(--muted)}
        .btn-primary{background:var(--primary);color:var(--primary-foreground);border-color:var(--primary)}
        .btn-primary:hover{background:#5558e6}
        .btn-sm{padding:4px 10px;font-size:12px}
        .btn-ghost{background:transparent;border-color:transparent;color:var(--muted-foreground)}
        .btn-ghost:hover{background:var(--muted);color:var(--foreground)}
        table{width:100%;border-collapse:collapse}
        th{text-align:left;padding:10px 14px;font-size:11px;font-weight:600;color:var(--muted-foreground);text-transform:uppercase;letter-spacing:0.5px;border-bottom:1px solid var(--border)}
        td{padding:10px 14px;font-size:13px;border-bottom:1px solid var(--border)}
        tr:last-child td{border-bottom:none}
        .badge{display:inline-flex;align-items:center;padding:2px 8px;border-radius:20px;font-size:11px;font-weight:600;border:1px solid}
        .badge-success{background:#22c55e1a;color:var(--success);border-color:#22c55e33}
        .badge-warning{background:#f59e0b1a;color:var(--warning);border-color:#f59e0b33}
        .badge-danger{background:#ef44441a;color:var(--destructive);border-color:#ef444433}
        .badge-info{background:#6366f11a;color:var(--primary);border-color:#6366f133}
        .badge-outline{background:transparent;color:var(--muted-foreground);border-color:var(--border)}
        .input{width:100%;padding:8px 12px;background:var(--background);border:1px solid var(--border);border-radius:6px;color:var(--foreground);font-size:13px;font-family:inherit;outline:none}
        .input:focus{border-color:var(--ring);box-shadow:0 0 0 2px #6366f120}
        .input-group{display:flex;gap:8px}
        .separator{height:1px;background:var(--border);margin:16px 0}
        .text-muted{color:var(--muted-foreground);font-size:12px}
        .code-block{background:var(--muted);border:1px solid var(--border);border-radius:6px;padding:12px 16px;font-family:'JetBrains Mono','Fira Code',monospace;font-size:12px;color:var(--foreground);overflow-x:auto;white-space:pre-wrap;word-break:break-all}
        .flex-row{display:flex;align-items:center;gap:8px}
        .grid-2{display:grid;grid-template-columns:1fr 1fr;gap:12px}
        .mb-2{margin-bottom:8px}.mb-4{margin-bottom:16px}.mt-2{margin-top:8px}
        .toggle{position:relative;display:inline-block;width:40px;height:22px}
        .toggle input{opacity:0;width:0;height:0}
        .toggle-slider{position:absolute;cursor:pointer;top:0;left:0;right:0;bottom:0;background:var(--muted);border-radius:22px;transition:0.2s;border:1px solid var(--border)}
        .toggle-slider:before{content:"";position:absolute;height:16px;width:16px;left:2px;bottom:2px;background:var(--muted-foreground);border-radius:50%;transition:0.2s}
        .toggle input:checked+.toggle-slider{background:var(--primary);border-color:var(--primary)}
        .toggle input:checked+.toggle-slider:before{transform:translateX(18px);background:#fff}
        .log-viewer{background:var(--muted);border:1px solid var(--border);border-radius:var(--radius);padding:16px;max-height:400px;overflow-y:auto;font-family:'JetBrains Mono','Fira Code',monospace;font-size:12px;line-height:1.8;color:var(--foreground)}
        .log-entry{display:flex;gap:12px;padding:2px 0}
        .log-time{color:var(--muted-foreground);flex-shrink:0}
        .log-info{color:var(--primary)}
        .log-success{color:var(--success)}
        .log-error{color:var(--destructive)}
        .log-warn{color:var(--warning)}
        @media(max-width:768px){.sidebar{display:none}.main{margin-left:0}.stats-grid{grid-template-columns:1fr}}
    </style>
</head>
<body>
<aside class="sidebar">
    <div class="sidebar-header">
        <div class="logo">N</div>
        <span class="title">API Bridge</span>
    </div>
    <nav class="nav">
        <button class="nav-item active" data-page="dashboard">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="7"/><rect x="14" y="3" width="7" height="7"/><rect x="14" y="14" width="7" height="7"/><rect x="3" y="14" width="7" height="7"/></svg>
            Dashboard
        </button>
        <button class="nav-item" data-page="models">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16z"/></svg>
            Models
        </button>
        <button class="nav-item" data-page="config">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 0 1 0 2.83 2 2 0 0 1-2.83 0l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 0 1-2.83-2.83l.06-.06A1.65 1.65 0 0 0 4.68 15a1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 0 1 2.83-2.83l.06.06A1.65 1.65 0 0 0 9 4.68a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 0 1 2.83 2.83l-.06.06A1.65 1.65 0 0 0 19.4 9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z"/></svg>
            Config
        </button>
        <button class="nav-item" data-page="logs">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/><polyline points="14 2 14 8 20 8"/><line x1="16" y1="13" x2="8" y2="13"/><line x1="16" y1="17" x2="8" y2="17"/></svg>
            Logs
        </button>
    </nav>
    <div class="sidebar-footer">
        <div class="flex-row mb-2"><span class="status-dot" id="statusDot"></span><span id="statusLabel">Checking...</span></div>
        <div class="text-muted" id="serverAddress">--</div>
    </div>
</aside>
<div class="main">
    <div class="topbar"><h2 id="pageTitle">Dashboard</h2><span class="text-muted" id="clock"></span></div>
    <div class="content">
        <div id="page-dashboard" class="page active">
            <div class="stats-grid">
                <div class="stat-card"><div class="stat-info"><span class="card-title">Uptime</span><span class="card-value" id="uptime">--</span></div><div class="card-icon blue"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="12" cy="12" r="10"/><polyline points="12 6 12 12 16 14"/></svg></div></div>
                <div class="stat-card"><div class="stat-info"><span class="card-title">Total Requests</span><span class="card-value" id="reqCount">0</span></div><div class="card-icon green"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="22 12 18 12 15 21 9 3 6 12 2 12"/></svg></div></div>
                <div class="stat-card"><div class="stat-info"><span class="card-title">Prompt Tokens</span><span class="card-value" id="promptTokens">0</span></div><div class="card-icon blue"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20V10"/><path d="M18 20V4"/><path d="M6 20v-4"/></svg></div></div>
                <div class="stat-card"><div class="stat-info"><span class="card-title">Completion Tokens</span><span class="card-value" id="compTokens">0</span></div><div class="card-icon green"><svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 20V10"/><path d="M18 20V4"/><path d="M6 20v-4"/></svg></div></div>
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">Quick Test</span><span class="badge" id="streamBadge">Stream ON</span></div>
                <div class="input-group mb-2"><input class="input" id="quickPrompt" placeholder="Type a test prompt..." onkeydown="if(event.key==='Enter')quickTest()"><button class="btn btn-primary" onclick="quickTest()">Send</button></div>
                <div id="quickResult" class="code-block" style="display:none;margin-top:8px;max-height:300px;overflow-y:auto;"></div>
            </div>
        </div>
        <div id="page-models" class="page">
            <div class="card">
                <div class="card-header"><span class="card-title">Available Models</span><button class="btn btn-sm" onclick="loadModels()">Refresh</button></div>
                <table><thead><tr><th>ID</th><th>Object</th><th>Owned By</th><th>Created</th></tr></thead><tbody id="modelsTable"><tr><td colspan="4" class="text-muted">Loading...</td></tr></tbody></table>
            </div>
        </div>
        <div id="page-config" class="page">
            <div class="card">
                <div class="card-header"><span class="card-title">Streaming</span></div>
                <div class="flex-row" style="justify-content:space-between;">
                    <div><strong>Simulate SSE Stream</strong><br><span class="text-muted">Activez pour compatibilite avec Qwen Code, Cursor, etc.</span></div>
                    <label class="toggle"><input type="checkbox" id="streamToggle" onchange="toggleStream()"><span class="toggle-slider"></span></label>
                </div>
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">Authentification</span></div>
                <div class="mb-2"><span class="text-muted">Colle la clé API créée dans l’application Android. Elle reste dans cette session du navigateur.</span></div>
                <div class="input-group">
                    <input class="input" id="apiKey" type="password" placeholder="sk-bridge-..." autocomplete="off">
                    <button class="btn btn-primary" onclick="saveApiKey()">Connexion</button>
                </div>
                <div class="text-muted mt-2" id="authStatus">Clé API requise pour le tableau de bord.</div>
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">Server Configuration</span></div>
                <div class="grid-2">
                    <div><span class="card-title mb-2">Host</span><div class="code-block" id="serverHost">--</div></div>
                    <div><span class="card-title mb-2">Port</span><div class="code-block" id="serverPort">--</div></div>
                </div>
                <div class="separator"></div>
                <span class="card-title mb-2">API Endpoints</span>
                <div class="code-block">POST /v1/chat/completions
GET  /v1/models
GET  /status
GET  /stats
POST /config</div>
            </div>
            <div class="card">
                <div class="card-header"><span class="card-title">Connection String</span></div>
                <div class="code-block" id="connString">Authentifiez-vous pour afficher la configuration.</div>
            </div>
        </div>
        <div id="page-logs" class="page">
            <div class="card">
                <div class="card-header"><span class="card-title">System Logs</span><div class="flex-row"><button class="btn btn-sm" onclick="clearLogs()">Clear</button><button class="btn btn-sm" onclick="exportLogs()">Export</button></div></div>
                <div class="log-viewer" id="logsContainer"><div class="log-entry"><span class="log-time">--:--:--</span><span class="log-info">API Bridge admin panel ready.</span></div></div>
            </div>
        </div>
    </div>
</div>
<script>
const BASE=window.location.origin;
let streamEnabled=false;
const logs=[];

function E(id){return document.getElementById(id)}
function esc(value){return String(value??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function updateConnectionInfo(){
    const host=window.location.hostname;
    const port=window.location.port||(window.location.protocol==='https:'?'443':'80');
    E('serverAddress').textContent=host+':'+port;
    E('serverHost').textContent=host;
    E('serverPort').textContent=port;
    E('connString').textContent='curl http://'+host+':'+port+'/v1/chat/completions -H "Authorization: Bearer <CLE_API>" -H "Content-Type: application/json" -d \'{\"model\":\"nom-du-provider\",\"messages\":[{\"role\":\"user\",\"content\":\"Hello\"}]}\'';
}
function apiFetch(path,options={}){
    const key=sessionStorage.getItem('api_bridge_key')||'';
    const headers={...(options.headers||{}),...(key?{Authorization:'Bearer '+key}:{})};
    return fetch(BASE+path,{...options,headers});
}
function saveApiKey(){
    const key=E('apiKey').value.trim();
    if(!key){sessionStorage.removeItem('api_bridge_key');E('authStatus').textContent='Clé API requise.';return}
    sessionStorage.setItem('api_bridge_key',key);
    E('authStatus').textContent='Vérification de la clé…';
    checkStatus();
    fetchStats();
}
const savedKey=sessionStorage.getItem('api_bridge_key');
if(savedKey){E('apiKey').value=savedKey;E('authStatus').textContent='Clé enregistrée pour cette session.'}

document.querySelectorAll('.nav-item').forEach(btn=>{
    btn.addEventListener('click',()=>{
        document.querySelectorAll('.nav-item').forEach(b=>b.classList.remove('active'));
        btn.classList.add('active');
        const page=btn.dataset.page;
        document.querySelectorAll('.page').forEach(p=>p.classList.remove('active'));
        E('page-'+page).classList.add('active');
        E('pageTitle').textContent=btn.textContent.trim();
        if(page==='models')loadModels();
    })
});

function addLog(msg,type){
    const time=new Date().toLocaleTimeString();
    logs.unshift({time,msg,type});
    if(logs.length>100)logs.pop();
    renderLogs()
}
function renderLogs(){
    E('logsContainer').innerHTML=logs.map(l=>'<div class="log-entry"><span class="log-time">'+l.time+'</span><span class="log-'+l.type+'">'+l.msg.replace(/</g,'&lt;')+'</span></div>').join('')
}
function clearLogs(){logs.length=0;renderLogs();addLog('Logs cleared','info')}
function exportLogs(){
    const text=logs.map(l=>'['+l.time+'] ['+l.type.toUpperCase()+'] '+l.msg).join('\n');
    const blob=new Blob([text],{type:'text/plain'});
    const a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='nemapi-logs.txt';a.click()
}

async function fetchStats(){
    try{
        const r=await apiFetch('/stats');
        if(r.status===401){E('authStatus').textContent='Clé API absente ou invalide.';return}
        const d=await r.json();
        E('reqCount').textContent=d.total_requests;
        E('promptTokens').textContent=d.total_prompt_tokens.toLocaleString();
        E('compTokens').textContent=d.total_completion_tokens.toLocaleString();
        const s=Math.floor(d.uptime_seconds);
        const days=Math.floor(s/86400),hrs=Math.floor((s%86400)/3600),min=Math.floor((s%3600)/60);
        E('uptime').textContent=days+'d '+hrs+'h '+min+'m';
        streamEnabled=d.stream_enabled;
        E('streamToggle').checked=streamEnabled;
        E('streamBadge').textContent=streamEnabled?'Stream ON':'Stream OFF';
        E('streamBadge').className=streamEnabled?'badge badge-success':'badge badge-outline';
        updateConnectionInfo();
        E('authStatus').textContent='Clé API valide.';
    }catch(e){E('authStatus').textContent='Serveur inaccessible.'}
}

async function checkStatus(){
    try{
        const r=await apiFetch('/status');
        if(r.status===401){E('statusLabel').textContent='Authentification requise';E('authStatus').textContent='Clé API absente ou invalide.';return}
        const d=await r.json();
        const dot=E('statusDot'),label=E('statusLabel');
        if(d.status.includes('Ready')){dot.className='status-dot online';label.textContent='Online';E('authStatus').textContent='Clé API valide.'}
        else{dot.className='status-dot offline';label.textContent='Disabled'}
    }catch(e){E('statusLabel').textContent='Offline'}
}

async function loadModels(){
    try{
        const r=await apiFetch('/v1/models');
        const data=await r.json();
        E('modelsTable').innerHTML=data.data.map(m=>'<tr><td><strong>'+esc(m.id)+'</strong></td><td>'+esc(m.object)+'</td><td>'+esc(m.owned_by)+'</td><td>'+new Date(m.created*1000).toLocaleDateString()+'</td></tr>').join('')
    }catch(e){E('modelsTable').innerHTML='<tr><td colspan="4" style="color:var(--destructive)">Failed to load</td></tr>'}
}

async function toggleStream(){
    streamEnabled=E('streamToggle').checked;
    try{
        const response=await apiFetch('/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({stream_enabled:streamEnabled})});
        if(!response.ok)throw new Error('HTTP '+response.status);
        E('streamBadge').textContent=streamEnabled?'Stream ON':'Stream OFF';
        E('streamBadge').className=streamEnabled?'badge badge-success':'badge badge-outline';
        addLog('Stream '+(streamEnabled?'enabled':'disabled'),'info')
    }catch(e){addLog('Config update failed','error')}
}

async function quickTest(){
    const input=E('quickPrompt'),text=input.value.trim();
    if(!text)return;
    const resultDiv=E('quickResult');
    resultDiv.style.display='block';
    resultDiv.textContent='Sending...';
    input.disabled=true;
    addLog('API test: '+text.substring(0,60),'info');
    try{
        const modelResponse=await apiFetch('/v1/models');
        if(!modelResponse.ok)throw new Error('Authentification requise ou modèles indisponibles');
        const modelData=await modelResponse.json();
        const model=modelData.data&&modelData.data[0]?modelData.data[0].id:null;
        if(!model)throw new Error('Aucun provider configuré');
        const r=await apiFetch('/v1/chat/completions',{
            method:'POST',headers:{'Content-Type':'application/json'},
            body:JSON.stringify({model,messages:[{role:'user',content:text}],stream:false})
        });
        const data=await r.json();
        if(data.choices&&data.choices[0]){
            const content=data.choices[0].message.content;
            resultDiv.textContent=content;
            const usage=data.usage;
            addLog('Response: '+(content?content.length:0)+' chars | tokens: '+(usage?usage.total_tokens:'?'),'success')
        }else if(data.error){
            resultDiv.textContent='Error: '+data.error;
            addLog('API error: '+data.error,'error')
        }
    }catch(e){
        resultDiv.textContent='Error: '+e.message;
        addLog('Error: '+e.message,'error')
    }
    input.disabled=false;input.focus();
    fetchStats()
}

function updateClock(){E('clock').textContent=new Date().toLocaleTimeString()}

setInterval(fetchStats,3000);
setInterval(checkStatus,5000);
setInterval(updateClock,1000);
fetchStats();checkStatus();updateClock();
addLog('API Bridge admin panel ready','info');
</script>
</body>
</html>"""
    }
}
