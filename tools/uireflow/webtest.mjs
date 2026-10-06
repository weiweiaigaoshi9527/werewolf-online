// tools/uireflow/webtest.mjs —— 以真实用户身份在网页里完整走一遍，逐步截图
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], process.env['ProgramFiles'], 'C:/Program Files (x86)']
  .filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9345;
const OUT = path.join(ROOT, 'screenshots', 'webtest');
fs.rmSync(path.join(ROOT, 'screenshots', 'webtest-profile'), { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[web]', ...a);
const errors = [];
let ws, mid = 0; const pending = new Map(); const events = [];
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); log('📸 ' + n); }
async function goto(u) { events.length = 0; await cmd('Page.navigate', { url: u }); for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); } await sleep(1000); }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${path.join(ROOT, 'screenshots', 'webtest-profile')}`,
  '--no-first-run', '--disable-gpu', '--hide-scrollbars', '--ignore-certificate-errors', 'about:blank'], { stdio: 'ignore' });
browser.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

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
      if (m.method === 'Page.javascriptDialogOpening') cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
      if (m.method === 'Runtime.exceptionThrown') errors.push('JS异常: ' + (m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push('console.error: ' + s.slice(0, 160)); }
      if (m.method === 'Network.responseReceived') { const r = m.params.response; if (r.status >= 400 && !/favicon/.test(r.url)) errors.push('HTTP ' + r.status + ' ' + (r.url || '').replace(ORIGIN, '')); }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable'); await cmd('Network.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  /* === 1. 全新访客：登录页 === */
  await goto(ORIGIN + '/');
  log('登录页可见=' + await ev(`!document.getElementById('view-auth').classList.contains('hidden')`));
  await shot('100-网页-登录页');

  /* === 2. 用已有账号登录（模拟老用户回来） === */
  await ev(`document.getElementById('login-username').value='duo_a_test';'ok'`);
  // 账号若不存在就现场注册一个同名逻辑：先试登录，失败则注册
  await ev(`document.getElementById('login-password').value='ui123456';
    document.getElementById('form-login').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));'ok'`);
  await sleep(2500);
  let loggedIn = await ev(`!document.getElementById('view-lobby').classList.contains('hidden')`);
  if (!loggedIn) {
    log('登录失败（账号不存在），改走注册');
    await goto(ORIGIN + '/'); await sleep(1500);
    await ev(`document.querySelector('[data-tab="register"]').click();'ok'`); await sleep(300);
    // 注意：固定用户名在重复跑自检时会因“用户名已存在”注册失败（400），导致后续全部 401。
    // 这里用唯一用户名，保证脚本可重复执行。
    const wtUser = 'wt_' + Date.now().toString(36).slice(-6);
    log('注册网页验收账号 ' + wtUser);
    await ev(`document.getElementById('reg-username').value=${JSON.stringify(wtUser)};document.getElementById('reg-password').value='ui123456';document.getElementById('reg-nickname').value='网页验收';
      document.getElementById('form-register').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));'ok'`);
    await sleep(2600);
  }
  loggedIn = await ev(`!document.getElementById('view-lobby').classList.contains('hidden')`);
  log('进入大厅=' + loggedIn);
  await shot('101-网页-大厅');

  /* === 3. 外观面板：现场切换三套主题（用户能亲眼看到的"没消失"） === */
  await ev(`document.getElementById('btn-theme').click();'ok'`); await sleep(400);
  await shot('102-网页-外观面板');
  await ev(`document.querySelector('[data-theme="bloodmoon"]').click();'ok'`); await sleep(400);
  await ev(`document.getElementById('btn-theme-close').click();'ok'`); await sleep(400);
  await shot('103-网页-血月主题大厅');
  await ev(`document.getElementById('btn-theme').click();document.querySelector('[data-theme="sakura"]').click();'ok'`); await sleep(400);
  await ev(`document.getElementById('btn-theme-close').click();'ok'`); await sleep(400);
  await shot('104-网页-樱粉浅色大厅');
  await ev(`THEME.setMode('night');'ok'`); await sleep(300);

  /* === 4. 建房 → 房间 → 补 AI → 开局 === */
  await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1600);
  await shot('105-网页-建房进房间');
  for (let i = 0; i < 3; i++) { await ev(`document.getElementById('btn-add-ai')?.click();'ok'`); await sleep(400); }
  await shot('106-网页-座位已补齐');
  await ev(`document.getElementById('btn-ready').click();'ok'`); await sleep(600);
  await ev(`document.getElementById('btn-start')?.click();'ok'`); await sleep(2600);
  const inGame = await ev(`!document.getElementById('view-game').classList.contains('hidden')`);
  log('开局=' + inGame);
  await shot('107-网页-对局');

  /* === 5. 导航回大厅（新底部/侧栏路由） === */
  await ev(`fetch('/api/game/end',{method:'POST',headers:{'Content-Type':'application/json',Authorization:'Bearer '+localStorage.getItem('ww_token')}});'ok'`);
  await sleep(1500);
  await ev(`document.querySelector('[data-route="lobby"]')?.click();'ok'`); await sleep(800);
  await shot('108-网页-返回大厅');

  /* === 6. 旧 token 回退（老用户凭证失效时的体验） === */
  await ev(`localStorage.setItem('ww_token','obsolete-token-123');'ok'`);
  await goto(ORIGIN + '/'); await sleep(2200);
  const fallback = await ev(`!document.getElementById('view-auth').classList.contains('hidden')`);
  log('旧 token 失效 → 回到登录页=' + fallback + '，token 已清除=' + await ev(`localStorage.getItem('ww_token')===null`));
  await shot('109-网页-旧token回退登录');

  /* === 7. 手机宽度 === */
  await cmd('Emulation.setDeviceMetricsOverride', { width: 390, height: 844, deviceScaleFactor: 2, mobile: true });
  await goto(ORIGIN + '/'); await sleep(2000);
  await shot('110-网页-手机端大厅');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  console.log('\n运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 20).join('\n'));
  fs.writeFileSync(path.join(OUT, 'webtest-errors.txt'), errors.length ? errors.join('\n') : '无报错');
}
main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
