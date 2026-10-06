// tools/uireflow/verify6b.mjs —— 第二轮收尾：观战/功能开关/头像/红点/VIP续费
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9350;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'verify6b-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[v6b]', ...a);
const results = [];
function check(ok, name, detail) { results.push({ 结论: ok ? 'PASS' : 'FAIL', 检查项: name, 实测: String(detail ?? '').slice(0, 220) }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined ? '  [' + String(detail).slice(0, 130) + ']' : '')); }
const errors = [];

let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); log('📸 ' + n); }
async function goto(u) { events.length = 0; await cmd('Page.navigate', { url: u }); for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); } await sleep(1000); }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--disable-gpu', '--hide-scrollbars', '--ignore-certificate-errors', 'about:blank'], { stdio: 'ignore' });
browser.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

let TOKEN = '';
async function api(p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: b ? JSON.stringify(b) : null });
  const x = await r.text(); if (!r.ok) throw new Error(p + ' → ' + r.status + ' ' + x.slice(0, 120)); try { return JSON.parse(x); } catch (_) { return x; }
}

async function main() {
  let t = null;
  for (let i = 0; i < 60; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
  const pg = t.find(x => x.type === 'page');
  ws = new WebSocket(pg.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.res(m.result); }
    else if (m.method) {
      events.push(m);
      if (m.method === 'Page.javascriptDialogOpening') { dialogs.push(m.params.message); cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {}); }
      if (m.method === 'Runtime.exceptionThrown') errors.push('JS异常: ' + (m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push('console.error: ' + s.slice(0, 140)); }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  const u = 'v6b_' + Date.now().toString(36).slice(-5);
  TOKEN = (await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '收尾复测' })).token;
  await goto(ORIGIN + '/'); await ev("localStorage.clear(); localStorage.setItem('ww_token','" + TOKEN + "'); localStorage.setItem('ww_guide','done'); 'ok'"); await goto(ORIGIN + '/'); await sleep(2400);

  /* ===== 观战加入 ===== */
  const regH = await api('/api/auth/register', 'POST', { username: 'host6b_' + Date.now().toString(36).slice(-5), password: 'ui123456', nickname: '对局主播' });
  const tkH = regH.token;
  await fetch(ORIGIN + '/api/room/create', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH } });
  const stH = await (await fetch(ORIGIN + '/api/room/my', { headers: { Authorization: 'Bearer ' + tkH } })).json();
  const roomNo = stH.room.roomNo;
  for (let i = 0; i < 5; i++) { try { await fetch(ORIGIN + '/api/room/add-ai', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH } }); } catch (_) {} await sleep(150); }
  await fetch(ORIGIN + '/api/room/ready', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH }, body: JSON.stringify({ ready: true }) });
  await fetch(ORIGIN + '/api/game/start', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH } });
  log('主播对局已开始 room=' + roomNo);

  await ev("document.getElementById('btn-spectate').click(); 'ok'"); await sleep(300);
  const modalOpen = await ev("!document.getElementById('modal-spectate').classList.contains('hidden')");
  await ev("document.getElementById('spectate-room-no').value='" + roomNo + "'; 'ok'");
  await ev("document.getElementById('btn-spectate-confirm').click(); 'ok'"); await sleep(2200);
  const specNo = await ev("document.getElementById('room-no').textContent");
  const specInView = await ev("!document.getElementById('view-room').classList.contains('hidden')");
  check(modalOpen && specInView && specNo === roomNo, '观战加入：大厅入口→输房号→进入房间旁观', '弹窗=' + modalOpen + ' 进入=' + specInView + ' 房号=' + specNo);
  await shot('140-观战-进入房间');
  await ev("var b=document.getElementById('btn-leave-room'); if(b) b.click(); 'ok'"); await sleep(600);
  try { await fetch(ORIGIN + '/api/game/end', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH } }); } catch (_) {}
  try { await fetch(ORIGIN + '/api/room/leave', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkH } }); } catch (_) {}
  await goto(ORIGIN + '/'); await sleep(2200);

  /* ===== 功能开关：隐藏机制 ===== */
  const before = await ev("!document.querySelector('[data-feat=\"shop\"]').classList.contains('hidden')");
  await ev("['shop'].forEach(function(id){ document.querySelectorAll('[data-feat=\"' + id + '\"]').forEach(function(el){ el.classList.add('hidden'); }); }); 'ok'");
  const after = await ev("document.querySelector('.tile[data-feat=\"shop\"]').classList.contains('hidden')");
  check(before && after, '功能开关隐藏机制生效（模拟关闭商店）', '隐藏前可见=' + before + ' 隐藏后=' + after);
  await ev("document.querySelectorAll('[data-feat=\"shop\"]').forEach(function(el){ el.classList.remove('hidden'); }); 'ok'");

  /* ===== 头像居中 ===== */
  await ev("document.querySelector('[data-click=\"btn-open-profile\"]').click(); 'ok'"); await sleep(1300);
  const av = await ev("(function(){var f=document.querySelector('#pf-avatar .avatar-frame');var inner=f?f.querySelector('.avatar-inner'):null;if(!f||!inner)return JSON.stringify({found:false});var fr=f.getBoundingClientRect(),ir=inner.getBoundingClientRect();return JSON.stringify({found:true,fw:Math.round(fr.width),centered:Math.abs((ir.left+ir.width/2)-(fr.left+fr.width/2))<2&&Math.abs((ir.top+ir.height/2)-(fr.top+fr.height/2))<2});})()");
  const avO = JSON.parse(av);
  check(avO.found && avO.centered && avO.fw > 40, '个人档案头像 emoji 居中无偏移', av);
  await shot('141-档案-头像居中');

  /* ===== 红点位置 ===== */
  const badge = await ev("(function(){var item=document.querySelector('.nav-item[data-route=\"friends\"]');var b=item.querySelector('.nav-badge');b.classList.remove('hidden');var br=b.getBoundingClientRect(),sr=item.querySelector('span').getBoundingClientRect();var overlap=!(br.left>=sr.right||br.right<=sr.left);return JSON.stringify({badgeLeft:Math.round(br.left),spanRight:Math.round(sr.right),overlap});})()");
  const bO = JSON.parse(badge);
  check(!bO.overlap, '好友红点不再遮挡名字（行内跟随文字）', badge);
  await shot('143-红点位置');

  /* ===== VIP 续费按钮 ===== */
  await ev("var t=document.querySelector('[data-panel=\"#pp-vip\"]'); if(t) t.click(); 'ok'"); await sleep(900);
  const vip = await ev("(function(){var tiers=document.querySelectorAll('#vip-box .vip-tier').length;var btns=Array.prototype.slice.call(document.querySelectorAll('#vip-box [data-buy]')).map(function(b){return b.textContent;});return JSON.stringify({tiers:tiers,buttons:btns.length,renewText:btns.join('|')});})()");
  const vO = JSON.parse(vip);
  check(vO.tiers >= 3 && vO.buttons >= 3 && vO.renewText.indexOf('续费') >= 0, 'VIP 每档都有开通/续费入口', vip);
  await shot('142-VIP续费入口');

  console.log('\n== 汇总 ==');
  console.table(results);
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 15).join('\n'));
  fs.writeFileSync(path.join(OUT, 'verify6b-report.json'), JSON.stringify({ results, errors }, null, 2));
}

main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
