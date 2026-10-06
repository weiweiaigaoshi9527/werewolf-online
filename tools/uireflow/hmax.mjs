// tools/uireflow/hmax.mjs —— 验证百分比最大高度（80% → shell ≤ 80% 视口）
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const PORT = 9352;
const PROFILE = path.join(ROOT, 'screenshots', 'hmax-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, mid = 0; const pending = new Map();
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }

const b = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--disable-gpu', '--ignore-certificate-errors', 'about:blank'], { stdio: 'ignore' });
b.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

async function main() {
  let t = null;
  for (let i = 0; i < 60; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
  const pg = t.find(x => x.type === 'page');
  ws = new WebSocket(pg.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.res(m.result); } };
  await cmd('Page.enable'); await cmd('Runtime.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cmd('Page.navigate', { url: 'https://localhost:11111/' }); await sleep(2000);

  const u = 'hm_' + Date.now().toString(36).slice(-5);
  const reg = await ev("fetch('/api/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'" + u + "',password:'ui123456',nickname:'高度验收'})}).then(function(r){return r.json()})");
  await ev("localStorage.setItem('ww_token','" + reg.token + "'); localStorage.setItem('ww_guide','done'); 'ok'");
  await cmd('Page.navigate', { url: 'https://localhost:11111/' }); await sleep(2600);

  await ev("document.documentElement.style.setProperty('--app-max-h','80vh'); document.body.classList.add('hmax'); 'ok'"); await sleep(400);
  const diag = await ev("JSON.stringify({token: !!localStorage.getItem('ww_token'), shellHidden: document.getElementById('app-shell').classList.contains('hidden'), lobbyHidden: document.getElementById('view-lobby').classList.contains('hidden')})");
  console.log('诊断: ' + diag);
  const h = await ev("Math.round(document.querySelector('.shell').getBoundingClientRect().height)");
  console.log(h <= 728 && h > 500 ? '  PASS 网页最大高度 80%：shell=' + h + 'px（视口 900 × 80% = 720±）' : '  FAIL shell=' + h);
  b.kill(); process.exit(0);
}
main().catch(e => { console.error('失败:', e); process.exit(1); });
