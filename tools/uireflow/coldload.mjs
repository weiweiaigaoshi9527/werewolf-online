// tools/uireflow/coldload.mjs —— 全新访客视角冷加载复现：登录→大厅→关键页面，收集报错
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], process.env['ProgramFiles'], 'C:/Program Files (x86)']
  .filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9344;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'cold-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const errors = [];
let ws, mid = 0; const pending = new Map(); const events = [];
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); console.log('📸 ' + n); }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
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
    if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.rej(new Error(JSON.stringify(m.error))) : q.res(m.result); }
    else if (m.method) {
      events.push(m);
      if (m.method === 'Runtime.exceptionThrown') errors.push('JS异常: ' + (m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push('console.error: ' + s.slice(0, 160)); }
      if (m.method === 'Network.responseReceived') { const r = m.params.response; if (r.status >= 400 && !/favicon/.test(r.url)) errors.push('HTTP ' + r.status + ' ' + (r.url || '').replace(ORIGIN, '')); }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable'); await cmd('Network.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  // 全新访客：无 token → 应看到新版登录页
  await cmd('Page.navigate', { url: ORIGIN + '/' });
  for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); }
  await sleep(1500);
  const loginNew = await ev(`JSON.stringify({auth: !document.getElementById('view-auth').classList.contains('hidden'), split: !!document.querySelector('.auth-split'), shellHidden: document.getElementById('app-shell').classList.contains('hidden')})`);
  console.log('登录页状态 ' + loginNew);
  await shot('95-冷加载-登录页');

  // 用一个真实老账号登录（验证既有数据），失败则注册新的
  const u = 'cold_' + Date.now().toString(36).slice(-5);
  await ev(`document.querySelector('[data-tab="register"]').click();'ok'`); await sleep(300);
  await ev(`document.getElementById('reg-username').value='${u}';document.getElementById('reg-password').value='ui123456';document.getElementById('reg-nickname').value='冷加载验收';
    document.getElementById('form-register').dispatchEvent(new Event('submit',{bubbles:true,cancelable:true}));'ok'`);
  await sleep(2600);
  const lobby = JSON.parse(await ev(`JSON.stringify({lobby: !document.getElementById('view-lobby').classList.contains('hidden'), shell: !document.getElementById('app-shell').classList.contains('hidden'), hero: document.querySelector('.hero-kicker')?.textContent||'', nav: document.querySelectorAll('.nav-item').length, tiles: document.querySelectorAll('.tile').length, hash: location.hash})`));
  console.log('大厅状态 ' + JSON.stringify(lobby));
  await shot('96-冷加载-大厅');
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 15).join('\n'));
  fs.writeFileSync(path.join(OUT, 'coldload.txt'), JSON.stringify({ loginNew: JSON.parse(loginNew), lobby, errors }, null, 2));
}
main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
