// tools/uireflow/shot.mjs —— 界面重排实跑验证：Edge 无头 + CDP，逐屏截图并收集报错
// 用法：node tools/uireflow/shot.mjs
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const ORIGIN = 'https://127.0.0.1:11111';
const PORT = 9333;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'ui-reflow-profile');
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const errors = [];
const log = (...a) => console.log('[shot]', ...a);

/* ---------------- 浏览器与 CDP ---------------- */
const browser = spawn(EDGE, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
  '--ignore-certificate-errors', 'about:blank'
], { stdio: 'ignore' });

async function waitForDevtools() {
  for (let i = 0; i < 60; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) return r.json(); } catch (_) {}
    await sleep(500);
  }
  throw new Error('Edge 远程调试未就绪');
}

let ws, mid = 0;
const pending = new Map();
function cmd(method, params = {}) {
  const id = ++mid;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); reject(new Error(`CDP 超时 ${method}`)); } }, 30000);
  });
}
const events = [];

async function connect() {
  const targets = await waitForDevtools();
  const page = targets.find(t => t.type === 'page') || targets[0];
  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej; });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id); pending.delete(m.id);
      m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.resolve(m.result);
    } else if (m.method) {
      events.push(m);
      if (m.method === 'Page.javascriptDialogOpening') cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
      if (m.method === 'Runtime.exceptionThrown') {
        const d = m.params.exceptionDetails;
        errors.push('JS异常: ' + (d.exception?.description || d.text || '').split('\n')[0]);
      }
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        const t = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ');
        if (!/favicon|ERR_INTERNET|ERR_CERT/.test(t)) errors.push('console.error: ' + t.slice(0, 200));
      }
      if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
        const e = m.params.entry;
        if (!/favicon|certificate|ERR_CERT/.test(e.text || '')) errors.push(`网络/日志: ${(e.text || '').slice(0, 160)}`);
      }
    }
  };
  await cmd('Page.enable');
  await cmd('Runtime.enable');
  await cmd('Log.enable');
  await cmd('Network.enable');
}

async function evalJs(expression, awaitPromise = false) {
  const r = await cmd('Runtime.evaluate', { expression, returnByValue: true, awaitPromise });
  if (r.exceptionDetails) throw new Error('eval 失败: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}

async function goto(url) {
  events.length = 0;
  await cmd('Page.navigate', { url });
  for (let i = 0; i < 60; i++) { if (events.some(e => e.method === 'Page.loadEventFired')) break; await sleep(250); }
  await sleep(900);
}

async function setViewport(w, h, mobile = false) {
  await cmd('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile });
  await cmd('Emulation.setTouchEmulationEnabled', { enabled: mobile });
}

async function shot(name) {
  const { data } = await cmd('Page.captureScreenshot', { format: 'png' });
  const file = path.join(OUT, name + '.png');
  fs.writeFileSync(file, Buffer.from(data, 'base64'));
  log('📸 ' + name);
  return file;
}

/* ---------------- 业务 API（服务端直连，模拟真实玩家动作） ---------------- */
let TOKEN = '';
async function api(p, method = 'GET', body = null) {
  const res = await fetch(ORIGIN + p, {
    method,
    headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) },
    body: body ? JSON.stringify(body) : null
  });
  const text = await res.text();
  let json = null; try { json = JSON.parse(text); } catch (_) {}
  if (!res.ok) throw new Error(`${p} → ${res.status} ${text.slice(0, 160)}`);
  return json;
}

async function loginOrRegister() {
  const uname = 'ui_flow_' + Date.now().toString(36).slice(-6);
  const r = await api('/api/auth/register', 'POST', { username: uname, password: 'ui123456', nickname: 'UI验收' });
  TOKEN = r.token;
  log('已注册测试账号 ' + uname);
}

/* ---------------- 场景编排 ---------------- */
async function openApp({ token = true, theme = null, viewport = [1440, 900, false] }) {
  await setViewport(...viewport);
  await goto(ORIGIN + '/');
  await evalJs(`localStorage.clear(); ${token ? `localStorage.setItem('ww_token','${TOKEN}');` : ''} ${theme ? `localStorage.setItem('ww_theme','${theme}');` : ''} 'ok'`);
  await goto(ORIGIN + '/');
  await sleep(token ? 2200 : 1200);   // 等 boot() 拉 /api/auth/me + 进入大厅
}

async function main() {
  await connect();
  await loginOrRegister();

  /* --- 登录页 --- */
  await openApp({ token: false });
  await shot('01-auth-桌面');
  await evalJs(`document.querySelector('[data-tab=register]').click();'ok'`); await sleep(300);
  await shot('02-auth-注册tab');
  await openApp({ token: false, viewport: [390, 844, true] });
  await shot('03-auth-手机');

  /* --- 大厅三主题 --- */
  await openApp({ token: true });
  await shot('04-大厅-夜间');
  await openApp({ token: true, theme: 'day' });
  await shot('05-大厅-白天');
  await openApp({ token: true, theme: 'glass' });
  await shot('06-大厅-液态玻璃');
  await openApp({ token: true, viewport: [390, 844, true] });
  await shot('07-大厅-手机');
  await openApp({ token: true, viewport: [1100, 800, false] });
  await shot('08-大厅-中屏图标栏');

  /* --- 房间：建房 + 补 AI --- */
  const room = await api('/api/room/create', 'POST');
  await api('/api/room/add-ai', 'POST');
  await api('/api/room/add-ai', 'POST');
  await api('/api/room/add-ai', 'POST');
  await openApp({ token: true });
  await sleep(1200);
  await evalJs(`document.getElementById('btn-save-mode')&&0;'ok'`);
  await shot('09-房间-座位与设置');
  await evalJs(`document.getElementById('board-editor')?.classList.remove('hidden');
                document.getElementById('btn-save-mode')?.closest('.mode-panel')?.classList.remove('hidden');'ok'`);
  await sleep(300);
  await shot('10-房间-板子与模式面板');
  await evalJs(`document.getElementById('custom-editor')?.classList.remove('hidden');
                document.getElementById('preset-row') ? (document.querySelector('[data-bt=custom]')?.click(),1):0;'ok'`);
  await sleep(400);
  await shot('11-房间-自定义板子');
  await openApp({ token: true, viewport: [390, 844, true] });
  await sleep(600);
  await shot('12-房间-手机');

  /* --- 子页面 --- */
  await openApp({ token: true });
  await evalJs(`document.querySelector('[data-route=profile]').click();'ok'`); await sleep(900);
  await shot('13-主页-概览');
  await evalJs(`document.querySelector('[data-panel="#pp-info"]').click();'ok'`); await sleep(400);
  await shot('14-主页-资料tab');
  await evalJs(`document.querySelector('[data-panel="#pp-vip"]').click();'ok'`); await sleep(700);
  await shot('15-主页-会员tab');
  await evalJs(`document.querySelector('[data-panel="#pp-more"]').click();'ok'`); await sleep(400);
  await shot('16-主页-其他tab');

  await evalJs(`document.querySelector('[data-route=shop]').click();'ok'`); await sleep(900);
  await shot('17-商店');
  await evalJs(`document.querySelector('[data-route=friends]').click();'ok'`); await sleep(900);
  await shot('18-好友');
  await evalJs(`document.querySelector('[data-route=tickets]').click();'ok'`); await sleep(900);
  await shot('19-工单');

  /* --- 由弹窗升级的整页层 --- */
  await evalJs(`document.querySelector('[data-click=btn-ranking]').click();'ok'`); await sleep(1000);
  await shot('20-排行榜-整页');
  await evalJs(`document.getElementById('btn-ranking-close').click();
                document.querySelector('[data-click=btn-forum]').click();'ok'`); await sleep(1100);
  await shot('21-论坛-整页');
  await evalJs(`document.querySelector('.pop')&&0;
                document.getElementById('btn-forum-close')?.click();
                document.getElementById('btn-news')?.click();'ok'`); await sleep(900);
  await shot('22-更新动态-整页');
  await evalJs(`document.getElementById('btn-news-close')?.click();
                document.getElementById('btn-theme')?.click();'ok'`); await sleep(600);
  await shot('23-主题抽屉');
  await evalJs(`document.getElementById('btn-theme-close')?.click();
                document.getElementById('btn-join-room')?.click();'ok'`); await sleep(600);
  await shot('24-加入房间抽屉');
  await evalJs(`document.getElementById('btn-join-cancel')?.click();
                document.getElementById('btn-more')?.click();'ok'`); await sleep(500);
  await shot('25-顶栏更多菜单');

  /* --- 对局：开局后抓夜间行动界面 --- */
  await evalJs(`document.getElementById('btn-more')?.click();'ok'`);
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  log('已开局，等待发牌动画与首个行动面板');
  await sleep(3000);
  await shot('26-对局-发牌或开局');
  await sleep(4500);
  await shot('27-对局-座位与行动');
  await evalJs(`document.querySelector('[data-panel="#dock-marker"]')?.click();'ok'`); await sleep(400);
  await shot('28-对局-标记面板');
  await evalJs(`document.querySelector('[data-panel="#dock-feed"]')?.click();'ok'`); await sleep(400);
  await shot('29-对局-记录面板');
  await evalJs(`document.querySelector('[data-panel="#dock-chat"]')?.click();'ok'`); await sleep(400);
  await shot('30-对局-聊天面板');
  await openApp({ token: true, viewport: [390, 844, true] });
  await sleep(2500);
  await shot('31-对局-手机');
  await setViewport(1440, 900, false);

  /* --- 收尾：结束本局，别把房间挂在对局中 --- */
  try { await api('/api/game/end', 'POST'); log('已结束测试对局'); } catch (e) { log('结束对局失败：' + e.message); }
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  /* --- 结果汇总 --- */
  const state = await evalJs(`JSON.stringify({
    view: [...document.querySelectorAll('.page')].filter(e=>!e.classList.contains('hidden')).map(e=>e.id),
    hash: location.hash, shell: !document.getElementById('app-shell').classList.contains('hidden'),
    cssVarsOk: getComputedStyle(document.body).getPropertyValue('--accent').trim(),
    navCount: document.querySelectorAll('.nav-item').length,
    seatCount: document.querySelectorAll('.gseat').length,
  })`);
  log('末态 ' + state);
  fs.writeFileSync(path.join(OUT, 'errors.txt'), errors.length ? errors.join('\n') : '无控制台报错');
  log('截图输出目录 ' + OUT);
  log('报错条数 ' + errors.length);
  if (errors.length) console.log(errors.slice(0, 40).join('\n'));
}

main().catch(e => { console.error('验证脚本失败:', e); process.exitCode = 1; })
  .finally(() => { try { ws && ws.close(); } catch (_) {} browser.kill(); process.exit(process.exitCode || 0); });
