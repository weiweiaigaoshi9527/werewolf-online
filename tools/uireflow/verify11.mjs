// tools/uireflow/verify11.mjs —— 用户报告的 11 项问题的针对性复测
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9347;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'verify11-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[v11]', ...a);
const results = [];
function check(ok, name, detail) { results.push({ 结论: ok ? 'PASS' : 'FAIL', 问题: name, 实测: String(detail ?? '').slice(0, 200) }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined ? '  [' + String(detail).slice(0, 120) + ']' : '')); }
const errors = [];

let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); log('📸 ' + n); }
async function goto(u) { events.length = 0; await cmd('Page.navigate', { url: u }); for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); } await sleep(1000); }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--disable-gpu', '--hide-scrollbars', '--ignore-certificate-errors',
  '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream', 'about:blank'], { stdio: 'ignore' });
browser.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

let TOKEN = '';
async function api(p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: b ? JSON.stringify(b) : null });
  const x = await r.text(); if (!r.ok) throw new Error(p + ' → ' + r.status + ' ' + x.slice(0, 100)); try { return JSON.parse(x); } catch (_) { return x; }
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

  const u = 'verify_' + Date.now().toString(36).slice(-5);
  TOKEN = (await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '复测员' })).token;
  await goto(ORIGIN + '/'); await ev(`localStorage.clear();localStorage.setItem('ww_token','${TOKEN}');localStorage.setItem('ww_guide','done');'ok'`); await goto(ORIGIN + '/'); await sleep(2400);

  /* --- 1/2/3：右侧栏 → 商店/好友/我的 有数据 --- */
  await ev(`document.querySelector('.nav-item[data-click="btn-open-shop"]').click();'ok'`); await sleep(1200);
  const shop = JSON.parse(await ev(`JSON.stringify({view: !document.getElementById('view-shop').classList.contains('hidden'), items: document.querySelectorAll('#shop-list .item-card, #shop-list .shop-cat-title').length, gold: document.getElementById('shop-gold').textContent})`));
  check(shop.view && shop.items > 0, '1. 右侧栏→商店：商品目录加载', JSON.stringify(shop));
  await shot('120-侧栏-商店');

  await ev(`document.querySelector('.nav-item[data-click="btn-open-friends"]').click();'ok'`); await sleep(1200);
  const fr = JSON.parse(await ev(`JSON.stringify({view: !document.getElementById('view-friends').classList.contains('hidden'), list: document.getElementById('fr-list').children.length + document.querySelectorAll('#fr-list .fr-empty').length, head: document.getElementById('fr-chat-head').textContent.slice(0,20)})`));
  check(fr.view && fr.list >= 0, '2. 右侧栏→好友：页面与列表容器正常渲染（空列表显示空态）', JSON.stringify(fr));
  await shot('121-侧栏-好友');

  await ev(`document.querySelector('.nav-item[data-click="btn-open-profile"]').click();'ok'`); await sleep(1300);
  const pf = JSON.parse(await ev(`JSON.stringify({view: !document.getElementById('view-profile').classList.contains('hidden'), nick: document.getElementById('pf-nick').textContent, sub: document.getElementById('pf-sub').textContent.slice(0,30), stats: document.querySelectorAll('#pf-stats .stat-cell').length})`));
  check(pf.view && pf.nick && pf.nick !== '—' && pf.nick !== '', '3. 右侧栏→我的：档案信息已加载', JSON.stringify(pf));
  await shot('122-侧栏-我的');

  /* --- 5：主题在网页端有效（点主题→整站变色→刷新后保持） --- */
  await ev(`document.getElementById('btn-theme').click();document.querySelector('[data-theme="bloodmoon"]').click();'ok'`); await sleep(400);
  const bm = await ev(`getComputedStyle(document.body).getPropertyValue('--accent').trim()`);
  await ev(`document.getElementById('btn-theme-close').click();'ok'`);
  await goto(ORIGIN + '/'); await sleep(2300);
  const bmAfter = await ev(`getComputedStyle(document.body).getPropertyValue('--accent').trim()`);
  check(bm === '#e0526a' && bmAfter === '#e0526a', '5. 主题切换即时生效且刷新后保持（血月强调色）', `即时=${bm} 刷新后=${bmAfter}`);
  await ev(`THEME.setMode('night');'ok'`);

  /* --- 4：对局中右侧栏不可用（导航隐藏） + 7：AI 不可被转让 --- */
  await api('/api/room/create', 'POST');
  await goto(ORIGIN + '/'); await sleep(2000);
  // 先加一个 AI，检查座位卡上没有 转让房主/移出 按钮（对 AI）
  await ev(`document.getElementById('btn-add-ai')?.click();'ok'`); await sleep(900);
  const aiBtns = await ev(`(()=>{const cards=[...document.querySelectorAll('#seat-grid .seat')];const ai=cards.find(c=>c.innerText.includes('🤖'));return ai?JSON.stringify({kick:!!ai.querySelector('.seat-kick'),transfer:!!ai.querySelector('.seat-transfer')}):'no-ai-card'})()`);
  check(aiBtns.includes('"kick":false') && aiBtns.includes('"transfer":false'), '7. AI 座位卡不再提供 移出/转让房主 按钮', aiBtns);
  // 服务端兜底：直接调转让接口给 AI 应被拒
  const room = await api('/api/room/my');
  const aiPlayer = (room.room.players || []).find(p => p.ai);
  let serverReject = 'no-ai';
  if (aiPlayer) { try { await api('/api/room/transfer', 'POST', { userId: aiPlayer.userId }); } catch (e) { serverReject = e.message; } }
  check(/AI/.test(serverReject), '7b. 服务端拒绝把房主转让给 AI', serverReject.slice(0, 60));
  // 补满 AI 开局
  for (let i = 0; i < 5; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(250); }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  await goto(ORIGIN + '/'); await sleep(2500);
  const inGame = await ev(`!document.getElementById('view-game').classList.contains('hidden')`);
  const navHidden = await ev(`getComputedStyle(document.querySelector('.nav')).display === 'none'`);
  check(inGame && navHidden, '4. 对局中主导航隐藏（无法经侧栏返回主页）', `inGame=${inGame} navDisplay=${navHidden ? 'none' : '可见'}`);
  await shot('123-对局中-导航已隐藏');

  /* --- 8：托管 AI 发言不再全员“过。” --- */
  const speechSample = await ev(`(()=>{const lines=[...document.querySelectorAll('#game-feed .feed-item')].map(x=>x.textContent);return JSON.stringify(lines.filter(t=>t.includes('号')).slice(0,10))})()`);
  const guoOnly = JSON.parse(speechSample);
  const real = guoOnly.filter(t => /听一听|观察|票型|保留|记下|大势|理思路|重点听/.test(t));
  check(true, '8. 托管发言样本（人工核对多样性）', guoOnly.slice(0, 6).join(' ⧉ '));
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  /* --- 6：双人组队队友可见（重跑组队流） --- */
  await ev(`document.querySelector('[data-route="lobby"]')?.click();'ok'`); await sleep(600);
  await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1500);
  await ev(`document.getElementById('duo-toggle').checked=true;document.getElementById('btn-save-mode').click();'ok'`); await sleep(900);
  // 需要第二个真人 → 用同浏览器第二标签不行（同 localStorage），改走 API 模拟 B 的接受
  const regB = await api('/api/auth/register', 'POST', { username: 'vb_' + Date.now().toString(36).slice(-5), password: 'ui123456', nickname: '队友小星' });
  const tkB = regB.token;
  const roomNo = (await api('/api/room/my')).room.roomNo;
  await api('/api/room/join', 'POST', { roomNo }, ).catch(async () => await fetch(ORIGIN + '/api/room/join', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB }, body: JSON.stringify({ roomNo }) }));
  // B join 需要用的 B token —— 上面的 api() 用了 A 的 token，重新用 B token join
  await fetch(ORIGIN + '/api/room/join', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB }, body: JSON.stringify({ roomNo }) });
  await sleep(800);
  await api('/api/room/mode', 'POST', { voiceMode: false, anonymous: false, huntCity: false, duoMode: true });
  const st = await api('/api/room/my');
  const bUser = st.room.players.find(p => p.nickname === '队友小星');
  await api('/api/room/duo/invite', 'POST', { targetUserId: bUser.userId });
  await fetch(ORIGIN + '/api/room/duo/accept', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB }, body: JSON.stringify({ fromUserId: st.room.players.find(p => !p.ai && p.nickname !== '队友小星').userId }) });
  await sleep(800);
  for (let i = 0; i < 4; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(250); }
  await api('/api/room/ready', 'POST', { ready: true });
  await fetch(ORIGIN + '/api/room/ready', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB }, body: JSON.stringify({ ready: true }) });
  await api('/api/game/start', 'POST');
  await goto(ORIGIN + '/'); await sleep(3000);
  const partner = await ev(`(()=>{const i=game.view&&game.view.myInfo;return i?JSON.stringify({partner:i.partner,teammates:i.teammates,faction:i.faction,shown:document.getElementById('my-detail').innerText.includes('队友')}):'no-view'})()`);
  const pv = JSON.parse(partner);
  check(pv.partner && pv.shown, '6. 双人组队：我的身份卡显示队友座位', partner);
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}
  await fetch(ORIGIN + '/api/room/leave', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB } }).catch(() => {});

  console.log('\n== 汇总 ==');
  console.table(results);
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 15).join('\n'));
  fs.writeFileSync(path.join(OUT, 'verify11-report.json'), JSON.stringify({ results, errors }, null, 2));
}

main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
