package com.smaznet.teleplay.setup

/** The phone-facing setup page: one self-contained document, no external assets. */
object SetupPage {
    val html: String = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="referrer" content="no-referrer">
<title>TelePlay setup</title>
<style>
:root{color-scheme:light dark;--bg:#fff;--fg:#17181c;--mut:#5b5f6a;--line:#c9ccd4;--acc:#2f5bea;--err:#b3261e;--ok:#1b7a3e}
@media (prefers-color-scheme:dark){:root{--bg:#121316;--fg:#ecedf1;--mut:#a0a4b0;--line:#3a3d46;--acc:#8ea8ff;--err:#ff8a80;--ok:#7ddc9b}}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font:18px/1.45 system-ui,-apple-system,Roboto,sans-serif}
main{max-width:30rem;margin:0 auto;padding:24px 16px}
h1{font-size:1.5rem;margin:0 0 .5rem}
p{color:var(--mut);margin:.25rem 0 1rem}
label{display:block;font-weight:600;margin-bottom:.4rem}
.row{display:flex;gap:8px}
input{flex:1;min-width:0;font:inherit;padding:14px;border:1px solid var(--line);border-radius:10px;background:transparent;color:inherit}
button{font:inherit;padding:14px 18px;border-radius:10px;border:1px solid var(--line);background:transparent;color:inherit}
#go{width:100%;margin-top:14px;background:var(--acc);border-color:var(--acc);color:#fff;font-weight:600}
#go:disabled{opacity:.6}
#msg{margin-top:16px;min-height:1.5em;font-weight:600}
details{margin:0 0 1.25rem;border:1px solid var(--line);border-radius:10px;padding:12px 14px}
summary{font-weight:600;cursor:pointer}
details label{margin-top:.8rem}
select{font:inherit;padding:14px;border:1px solid var(--line);border-radius:10px;background:transparent;color:inherit;width:100%}
#pgo{width:100%;margin-top:14px}
#pmsg{margin-top:12px;min-height:1.5em;font-weight:600}
.err{color:var(--err)}.ok{color:var(--ok)}
</style></head>
<body><main>
<h1>Connect your bot to TelePlay</h1>
<p>Paste the bot token you got from BotFather. It goes straight to your TV.</p>
<noscript><p class="err">JavaScript is required on this page.</p></noscript>
<details id="px">
<summary>Telegram blocked where you are? Add a proxy first</summary>
<p>A proxy saved here is turned on right away so your TV can reach Telegram. It stays saved until you delete it on the TV.</p>
<form id="pf" autocomplete="off">
<label for="pl">Proxy link</label>
<input id="pl" type="text" inputmode="url" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off" placeholder="https://t.me/proxy?server=...">
<p>Or enter the details:</p>
<label for="pk">Type</label>
<select id="pk"><option value="socks5">SOCKS5</option><option value="mtproto">MTProto</option></select>
<label for="ps">Server</label><input id="ps" type="text" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off">
<label for="pp">Port</label><input id="pp" type="text" inputmode="numeric" autocomplete="off">
<div id="pso"><label for="pu">Username (optional)</label><input id="pu" type="text" autocapitalize="off" autocomplete="off">
<label for="pw">Password</label><input id="pw" type="password" autocomplete="off"></div>
<div id="pmt" hidden><label for="pt">Secret</label><input id="pt" type="password" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off"></div>
<button id="pgo" type="submit">Save proxy</button>
</form>
<div id="pmsg" role="status" aria-live="polite"></div>
</details>
<form id="f" autocomplete="off">
<label for="t">Bot token</label>
<div class="row"><input id="t" type="password" autocapitalize="off" autocorrect="off" spellcheck="false" autocomplete="off" required>
<button type="button" id="show" aria-pressed="false">Show</button></div>
<button id="go" type="submit">Send to TV</button>
</form>
<div id="msg" role="status" aria-live="polite"></div>
</main>
<script>
(function(){
var t=document.getElementById('t'),m=document.getElementById('msg'),go=document.getElementById('go'),sh=document.getElementById('show');
var M={rejected:'That token was not accepted. Check it and try again.',offline:'Your TV cannot reach Telegram right now. Try again in a moment.',rate_limited:'Telegram asks to wait a little. Try again shortly.',format:'That does not look like a bot token. Copy it again from BotFather.',busy:'Setup is already in progress on the TV. Wait a moment.'};
function say(s,c){m.className=c||'';m.textContent=s}
sh.onclick=function(){var p=t.type==='password';t.type=p?'text':'password';sh.textContent=p?'Hide':'Show';sh.setAttribute('aria-pressed',p)};
document.getElementById('f').onsubmit=function(e){
e.preventDefault();var v=t.value.trim();if(!v){say('Enter your bot token.','err');return}
go.disabled=true;say('Sending…');
var x=new XMLHttpRequest();x.open('POST',location.pathname.replace(/\/+$/,'')+'/token');x.setRequestHeader('Content-Type','application/json');x.timeout=30000;
x.onload=function(){go.disabled=false;var r={};try{r=JSON.parse(x.responseText)}catch(_){}
if(x.status===200&&r.ok){t.value='';say('Done. Look at your TV.','ok');go.disabled=true}
else if(x.status===410){say('This link expired. Look at the TV for a new code.','err');go.disabled=true}
else if(x.status===429){say('Too many tries. Wait a minute.','err')}
else{say(M[r.reason]||'Something went wrong. Try again.','err')}};
x.onerror=x.ontimeout=function(){go.disabled=false;say('Could not reach the TV. Is your phone on the same Wi-Fi?','err')};
x.send(JSON.stringify({token:v}));t.value=''};
var pk=document.getElementById('pk'),pso=document.getElementById('pso'),pmt=document.getElementById('pmt'),pm=document.getElementById('pmsg'),pgo=document.getElementById('pgo');
var PM={format:'That proxy is not valid. Check the link or the details.',limit:'The TV already has the maximum number of proxies.',error:'The TV could not save that proxy.',expired:'This link expired. Look at the TV for a new code.'};
function psay(s,c){pm.className=c||'';pm.textContent=s}
pk.onchange=function(){var m=pk.value==='mtproto';pso.hidden=m;pmt.hidden=!m};
document.getElementById('pf').onsubmit=function(e){
e.preventDefault();var l=document.getElementById('pl').value.trim(),b;
if(l){b={link:l}}else{b={kind:pk.value,server:document.getElementById('ps').value.trim(),port:document.getElementById('pp').value.trim()};
if(pk.value==='mtproto'){b.secret=document.getElementById('pt').value.trim()}else{b.user=document.getElementById('pu').value;b.pass=document.getElementById('pw').value}}
pgo.disabled=true;psay('Saving…');
var x=new XMLHttpRequest();x.open('POST',location.pathname.replace(/\/+$/,'')+'/proxy');x.setRequestHeader('Content-Type','application/json');x.timeout=15000;
x.onload=function(){pgo.disabled=false;var r={};try{r=JSON.parse(x.responseText)}catch(_){}
if(x.status===200&&r.ok){['pl','ps','pp','pu','pw','pt'].forEach(function(i){document.getElementById(i).value=''});psay(r.duplicate?'That proxy was already saved. It is now turned on.':'Proxy saved and turned on.','ok');t.focus()}
else if(x.status===410){psay(PM.expired,'err');pgo.disabled=true}
else if(x.status===429){psay('Too many tries. Wait a minute.','err')}
else{psay(PM[r.reason]||'Something went wrong. Try again.','err')}};
x.onerror=x.ontimeout=function(){pgo.disabled=false;psay('Could not reach the TV. Is your phone on the same Wi-Fi?','err')};
x.send(JSON.stringify(b))};
})();
</script></body></html>"""
}
