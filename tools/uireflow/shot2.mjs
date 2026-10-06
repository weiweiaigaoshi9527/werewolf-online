// tools/uireflow/shot2.mjs —— 主题系统 + 体验增强的实跑验证
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const ORIGIN = 'https://127.0.0.1:11111';
const PORT = 9334;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'ui-reflow-profile2');
fs.rmSync(PROFILE, { recursive: true, force: true });   // 全新 profile：引导/外观断言不受上一轮污染
fs.mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const errors = [];
const log = (...a) => console.log('[v2]', ...a);

const browser = spawn(EDGE, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
  '--ignore-certificate-errors', 'about:blank'
], { stdio: 'ignore' });

let ws, mid = 0; const pending = new Map(); const events = [];
function cmd(method, params = {}) {
  const id = ++mid;
  return new Promise((res, rej) => {
    pending.set(id, { res, rej });
    ws.send(JSON.stringify({ id, method, params }));
    setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('CDP 超时 ' + method)); } }, 30000);
  });
}
async function connect() {
  let targets = null;
  for (let i = 0; i < 80; i++) {
    try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { targets = await r.json(); break; } } catch (_) {}
    await sleep(400);
  }
  if (!targets) throw new Error('Edge 未就绪');
  const page = targets.find(t => t.type === 'page');
  ws = new WebSocket(page.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { const p = pending.get(m.id); pending.delete(m.id); m.error ? p.reject(new Error(JSON.stringify(m.error))) : p.res(m.result); }
    else if (m.method) {
      events.push(m);
      if (m.method === 'Page.javascriptDialogOpening') cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
      if (m.method === 'Runtime.exceptionThrown') errors.push('JS异常: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text || '').split('\n')[0]);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') {
        const t = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ');
        if (!/favicon|ERR_CERT/.test(t)) errors.push('console.error: ' + t.slice(0, 200));
      }
      if (m.method === 'Network.responseReceived') {
        const r = m.params.response, u = r.url || '';
        if (r.status >= 400 && !/favicon/.test(u)) errors.push('HTTP ' + r.status + ' ' + u.replace(ORIGIN, '') + ' ' + (r.statusText || ''));
      }
      if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
        const e = m.params.entry;
        if (!/favicon|certificate|ERR_CERT/.test(e.text || '')) errors.push('日志: ' + (e.text || '').slice(0, 160));
      }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable'); await cmd('Network.enable');
}
async function evalJs(expr) {
  const r = await cmd('Runtime.evaluate', { expression: expr, returnByValue: true, awaitPromise: true });
  if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text));
  return r.result.value;
}
async function goto(url) {
  events.length = 0;
  await cmd('Page.navigate', { url });
  for (let i = 0; i < 60; i++) { if (events.some(e => e.method === 'Page.loadEventFired')) break; await sleep(250); }
  await sleep(800);
}
async function shot(name) {
  const { data } = await cmd('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(path.join(OUT, name + '.png'), Buffer.from(data, 'base64'));
  log('📸 ' + name);
}
async function api(p, method = 'GET', body = null) {
  const res = await fetch(ORIGIN + p, { method, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: body ? JSON.stringify(body) : null });
  const t = await res.text(); let j = null; try { j = JSON.parse(t); } catch (_) {}
  if (!res.ok) throw new Error(p + ' → ' + res.status + ' ' + t.slice(0, 140));
  return j;
}
let TOKEN = '';

async function openApp(theme) {
  await goto(ORIGIN + '/');
  // 引导只在“本机首次访问”出现；脚本每次清库会反复触发，这里顺手标记为已完成
  await evalJs(`localStorage.clear(); localStorage.setItem('ww_token','${TOKEN}'); localStorage.setItem('ww_guide','done'); ${theme ? `localStorage.setItem('ww_theme','${theme}');` : ''} 'ok'`);
  await goto(ORIGIN + '/');
  await sleep(2300);
}

async function main() {
  await connect();
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  await cmd('Emulation.setTouchEmulationEnabled', { enabled: false });

  const uname = 'ui_v2_' + Date.now().toString(36).slice(-6);
  const reg = await api('/api/auth/register', 'POST', { username: uname, password: 'ui123456', nickname: '外观验收' });
  TOKEN = reg.token;

  /* --- 新手引导（新账号首访应出现） --- */
  await goto(ORIGIN + '/');
  await evalJs(`localStorage.setItem('ww_token','${TOKEN}');'ok'`);
  await goto(ORIGIN + '/');
  await sleep(2400);
  const guideShown = await evalJs(`!!document.getElementById('guide-overlay')`);
  log('新手引导出现：' + guideShown);
  await shot('60-新手引导');
  await evalJs(`for(let k=0;k<6;k++){const b=document.getElementById('gc-next'); if(!b)break; b.click();} 'ok'`);
  await sleep(400);
  log('引导走完已消失：' + !(await evalJs(`!!document.getElementById('guide-overlay')`)));

  /* --- 13 套主题逐一截图 --- */
  const themeIds = ['night', 'day', 'glass', 'bloodmoon', 'deepsea', 'moss', 'neon', 'parchment', 'slate', 'sakura', 'aurora', 'ember', 'mono'];
  const contrast = {};
  for (const t of themeIds) {
    await openApp(t);
    contrast[t] = await evalJs(`(()=>{const cs=getComputedStyle(document.body);return [cs.getPropertyValue('--bg-deep').trim(),cs.getPropertyValue('--accent').trim(),document.body.classList.contains('theme-light')].join('|')})()`);
    await shot('61-主题-' + t);
  }
  console.table(contrast);

  /* --- 强调色 / 字号 / 密度 / 动效 --- */
  await openApp('night');
  await evalJs(`document.getElementById('btn-theme').click();'ok'`); await sleep(500);
  await shot('65-外观面板');
  await evalJs(`document.querySelector('[data-accent="violet"]').click();'ok'`); await sleep(200);
  await evalJs(`document.querySelector('[data-font="lg"]').click();'ok'`); await sleep(200);
  await evalJs(`document.getElementById('btn-theme-close').click();'ok'`); await sleep(400);
  await shot('66-紫强调+大字号');
  const accentOk = await evalJs(`getComputedStyle(document.body).getPropertyValue('--accent').trim()`);
  const fontOk = await evalJs(`getComputedStyle(document.body).getPropertyValue('--fs-md').trim()`);
  log('强调色覆盖=' + accentOk + '，字号=' + fontOk);
  await evalJs(`THEME.setDensity(true);THEME.setFont('md');'ok'`); await sleep(300);
  await shot('67-紧凑密度');
  await evalJs(`THEME.setDensity(false);THEME.setAccent('auto');'ok'`);

  /* --- 快捷键帮助层（按 ?） --- */
  await cmd('Input.dispatchKeyEvent', { type: 'keyDown', key: '?', text: '?', code: 'Slash', modifiers: 1 });
  await cmd('Input.dispatchKeyEvent', { type: 'keyUp', key: '?', text: '?', code: 'Slash', modifiers: 1 });
  await sleep(500);
  const helpOpen = await evalJs(`!document.getElementById('modal-hotkeys').classList.contains('hidden')`);
  log('? 打开帮助层：' + helpOpen);
  await shot('68-快捷键帮助');
  await evalJs(`document.getElementById('modal-hotkeys').classList.add('hidden');'ok'`);

  /* --- 对局：记录工具条 / 筛选 / 计时环 / @补全 / 投票条 / 结算大事记 --- */
  await api('/api/room/create', 'POST');
  for (let i = 0; i < 3; i++) await api('/api/room/add-ai', 'POST');
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  await openApp('night');
  await sleep(2500);

  const tools = await evalJs(`!!document.querySelector('.feed-tools')`);
  const ring = await evalJs(`!!document.getElementById('timer-ring')`);
  log('记录工具条=' + tools + '，计时环元素=' + ring);
  await shot('69-对局-记录工具条');

  // 切到记录面板并点“投票”筛选
  await evalJs(`document.querySelector('[data-panel="#dock-feed"]')?.click();'ok'`); await sleep(600);
  await evalJs(`document.querySelector('[data-fc="vote"]')?.click();'ok'`); await sleep(300);
  await shot('70-记录筛选-投票');
  await evalJs(`document.querySelector('[data-fc="all"]')?.click();'ok'`);

  // @ 补全
  await evalJs(`document.querySelector('[data-panel="#dock-chat"]')?.click();'ok'`); await sleep(300);
  await evalJs(`(()=>{const i=document.getElementById('gc-input');i.value='@';i.focus();i.dispatchEvent(new Event('input',{bubbles:true}));return 1})()`);
  await sleep(400);
  const atPop = await evalJs(`!!document.querySelector('.at-pop')`);
  log('@ 补全浮层：' + atPop);
  await shot('71-聊天@补全');
  await evalJs(`document.getElementById('gc-input').value='';'ok'`);

  // 推进对局：轮到“我”时自动出手（否则对局会一直卡在人类回合），
  // 借此真实走到放逐投票与结算阶段，验证投票条与结算大事记。
  const playMyTurn = () => evalJs(`(()=>{
    const v=(typeof game!=='undefined'&&game.view)?game.view:null;
    if(!v||!v.myTurn) return 'idle';
    const k=v.actionKind;
    const q=(s)=>document.querySelector(s);
    if(k==='SPEECH'||k==='PK_SPEECH'||k==='LAST_WORDS'){ const p=q('#ga-speak-pass')||q('#ga-speak-send'); if(p){p.click();return 'speak'} }
    if(k==='SHERIFF_SIGNUP'){ const p=q('#ga-signup-n'); if(p){p.click();return 'signup'} }
    if(k==='WITCH'){ const p=q('#ga-witch-skip')||q('#ga-witch-save'); if(p){p.click();return 'witch'} }
    const t=q('#game-action .ga-targets .ga-btn.target'); if(t){t.click();return 'target'}
    const pass=q('#ga-pass'); if(pass){pass.click();return 'pass'}
    const any=q('#game-action .ga-btn.confirm'); if(any){any.click();return 'confirm'}
    return 'wait:'+k;
  })()`);
  let lastSig = '', sawVoteBar = false, sawSettle = false;
  for (let i = 0; i < 60; i++) {
    await sleep(2200);
    const sig = await evalJs(`(()=>{const v=(typeof game!=='undefined'&&game.view)?game.view:null;return v?v.phase+'|'+v.actionKind+'|'+(v.myTurn?1:0):'none'})()`);
    let acted = 'idle';
    if (sig !== lastSig) { acted = await playMyTurn(); lastSig = sig; }   // 状态没变就别再点一次
    if (acted !== 'idle') log('代打第 ' + i + ' 拍：' + acted);
    const st = await evalJs(`(()=>{const v=(typeof game!=='undefined'&&game.view)?game.view:null;return JSON.stringify({phase:v&&v.phase,day:v&&v.day,voteBar:!document.getElementById('vote-bar')?.classList.contains('hidden'),settle:!document.getElementById('modal-settle')?.classList.contains('hidden'),digest:!!document.querySelector('.settle-digest')})})()`);
    const s = JSON.parse(st);
    if (s.voteBar && !sawVoteBar) {
      sawVoteBar = true;
      await shot('72-投票实时条');
      log('抓到投票条 phase=' + s.phase + ' day=' + s.day);
    }
    if (s.settle) { sawSettle = true; await sleep(700); await shot('73-结算大事记'); log('结算页，大事记块=' + s.digest); break; }
  }
  log('投票条出现=' + sawVoteBar + '，结算到达=' + sawSettle);
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  fs.writeFileSync(path.join(OUT, 'errors-v2.txt'), errors.length ? errors.join('\n') : '无报错');
  log('报错条数 ' + errors.length);
  if (errors.length) console.log(errors.slice(0, 30).join('\n'));
}

main().catch(e => { console.error('验证失败:', e); process.exitCode = 1; })
  .finally(() => { try { ws && ws.close(); } catch (_) {} browser.kill(); process.exit(process.exitCode || 0); });
