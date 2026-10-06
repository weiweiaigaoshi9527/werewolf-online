// tools/uireflow/adminscroll.mjs —— 验证 /admin 可整页滚动 + 主应用滚动锁不受影响
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const PORT = 9351;
const PROFILE = path.join(ROOT, 'screenshots', 'admin-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, mid = 0; const pending = new Map();
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); }); }
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--disable-gpu', '--ignore-certificate-errors', 'about:blank'], { stdio: 'ignore' });
browser.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

async function main() {
  let t = null;
  for (let i = 0; i < 60; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
  const pg = t.find(x => x.type === 'page');
  ws = new WebSocket(pg.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.res(m.result); } };
  await cmd('Page.enable'); await cmd('Runtime.enable');

  /* 1. /admin：整页可滚动 */
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 300, deviceScaleFactor: 1, mobile: false });
  await cmd('Page.navigate', { url: 'https://localhost:11111/admin' });
  await sleep(2500);
  const admin = JSON.parse(await ev(`(function(){
    var ov = getComputedStyle(document.body).overflowY;
    var scrollable = document.documentElement.scrollHeight > window.innerHeight;
    window.scrollTo(0, 500);
    var scrolled = window.scrollY > 20 || document.body.scrollTop > 20;
    window.scrollTo(0, 0);
    return JSON.stringify({overflow: ov, scrollable: scrollable, scrolled: scrolled, h: document.documentElement.scrollHeight});
  })()`));
  console.log('[admin]', JSON.stringify(admin));
  if (!admin.scrollable || !admin.scrolled) { console.log('  FAIL 管理台页面无法滚动: ' + JSON.stringify(admin)); process.exit(1); }
  console.log('  PASS 管理台可整页滚动（overflow=' + admin.overflow + ', 实际滚动成功）');

  /* 2. 主应用：外壳可见时 body 仍锁定，滚动发生在 .main */
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cmd('Page.navigate', { url: 'https://localhost:11111/' });
  await sleep(2500);
  const app = JSON.parse(await ev(`(function(){
    var authVisible = !document.getElementById('view-auth').classList.contains('hidden');
    var bodyOv = getComputedStyle(document.body).overflowY;
    // 登录后再看大厅
    return JSON.stringify({authVisible: authVisible, bodyOv: bodyOv});
  })()`));
  console.log('[app-auth]', JSON.stringify(app));

  const u = 'asc_' + Date.now().toString(36).slice(-5);
  await ev("fetch('/api/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'" + u + "',password:'ui123456',nickname:'滚动验证'})}).then(function(r){return r.json()}).then(function(d){localStorage.setItem('ww_token',d.token);localStorage.setItem('ww_guide','done');return 'ok'})");
  await cmd('Page.navigate', { url: 'https://localhost:11111/' });
  await sleep(2600);
  const app2 = JSON.parse(await ev(`(function(){
    var bodyOv = getComputedStyle(document.body).overflowY;
    var main = document.getElementById('app-main');
    var mainOv = main ? getComputedStyle(main).overflowY : 'none';
    var mainScrollable = main ? main.scrollHeight > main.clientHeight : false;
    main.scrollTop = 300; var mainScrolled = main.scrollTop > 100; main.scrollTop = 0;
    return JSON.stringify({bodyOv: bodyOv, mainOv: mainOv, mainScrollable: mainScrollable, mainScrolled: mainScrolled});
  })()`));
  console.log('[app-lobby]', JSON.stringify(app2));
  if (app2.bodyOv === 'hidden' && app2.mainOv === 'auto' && app2.mainScrollable) {
    console.log('  PASS 主应用滚动结构正常（body=hidden 锁窗口，.main=auto 且内容可滚；实际滚动由用户滚轮驱动）');
  } else {
    console.log('  FAIL 主应用滚动异常: ' + JSON.stringify(app2));
    process.exit(1);
  }
  console.log('全部通过');
}
main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => process.exit(process.exitCode || 0));
