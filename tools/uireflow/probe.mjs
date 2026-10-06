// tools/uireflow/probe.mjs —— 针对本轮修复与可疑行为路径的定点探测
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], process.env['ProgramFiles'], 'C:/Program Files (x86)']
  .filter(Boolean)
  .map(d => d + '/Microsoft/Edge/Application/msedge.exe')
  .find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://127.0.0.1:11111';
const PORT = 9336;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'ui-reflow-probe');
fs.rmSync(PROFILE, { recursive: true, force: true });
fs.mkdirSync(OUT, { recursive: true });

const browser = spawn(EDGE, [
  '--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`,
  '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
  '--ignore-certificate-errors', 'about:blank'
], { stdio: ['ignore', 'ignore', 'pipe'] });
browser.on('error', (e) => { console.error('启动 Edge 失败:', e.message, EDGE); process.exit(1); });
browser.stderr.on('data', d => { const t = d.toString().trim(); if (t && /DevTools|error/i.test(t)) console.log('[edge]', t.slice(0, 120)); });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const errors = [];
const results = [];
function check(ok, name, detail) {
  results.push({ 结论: ok ? 'PASS' : 'FAIL', 检查项: name, 实测: detail || '' });
  console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? '  [' + detail + ']' : ''));
}

let ws, mid = 0; const pending = new Map(); const events = [];
function cmd(m, p = {}) {
  const id = ++mid;
  return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); });
}
async function connect() {
  let t = null;
  for (let i = 0; i < 80; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
  if (!t) throw new Error('Edge 未就绪');
  const pg = t.find(x => x.type === 'page');
  ws = new WebSocket(pg.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.rej(new Error(JSON.stringify(m.error))) : q.res(m.result); }
    else if (m.method) {
      events.push(m);
      if (m.method === 'Page.javascriptDialogOpening') cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
      if (m.method === 'Runtime.exceptionThrown') errors.push('JS异常: ' + (m.params.exceptionDetails.exception?.description || m.params.exceptionDetails.text || '').split('\n')[0]);
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT/.test(s)) errors.push('console.error: ' + s.slice(0, 160)); }
      if (m.method === 'Network.responseReceived') { const r = m.params.response; if (r.status >= 400 && !/favicon/.test(r.url)) errors.push('HTTP ' + r.status + ' ' + (r.url || '').replace(ORIGIN, '')); }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable'); await cmd('Network.enable');
}
async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function goto(u) { events.length = 0; await cmd('Page.navigate', { url: u }); for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); } await sleep(900); }
let TOKEN = '';
async function api(p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: b ? JSON.stringify(b) : null });
  const t = await r.text(); if (!r.ok) throw new Error(p + ' → ' + r.status + ' ' + t.slice(0, 120)); try { return JSON.parse(t); } catch (_) { return t; }
}
async function openApp() { await goto(ORIGIN + '/'); await ev(`localStorage.clear();localStorage.setItem('ww_token','${TOKEN}');localStorage.setItem('ww_guide','done');'ok'`); await goto(ORIGIN + '/'); await sleep(2300); }

async function main() {
  await connect();
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });
  const u = 'probe2_' + Date.now().toString(36).slice(-5);
  TOKEN = (await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '缺陷探测' })).token;

  console.log('== A. 外观状态持久化 ==');
  await openApp();
  await ev(`document.getElementById('btn-theme').click();'ok'`); await sleep(300);
  await ev(`document.querySelector('[data-theme="aurora"]').click();'ok'`); await sleep(200);
  await ev(`document.querySelector('[data-accent="mint"]').click();'ok'`); await sleep(200);
  await ev(`document.querySelector('[data-font="lg"]').click();'ok'`); await sleep(200);
  await ev(`document.querySelector('[data-density="dense"]').click();'ok'`); await sleep(200);
  await ev(`document.getElementById('motion-toggle').click();'ok'`); await sleep(200);
  const stored = await ev(`JSON.stringify(['ww_theme','ww_accent','ww_font','ww_density','ww_motion'].map(k=>localStorage.getItem(k)))`);
  await goto(ORIGIN + '/'); await sleep(2400);
  const after = JSON.parse(await ev(`JSON.stringify([document.body.classList.contains('theme-aurora'),document.body.classList.contains('fs-lg'),document.body.classList.contains('dense'),document.body.classList.contains('no-motion'),getComputedStyle(document.body).getPropertyValue('--accent').trim()])`));
  check(after[0] && after[1] && after[2] && after[3] && after[4] === '#45e0b8', '主题/字号/密度/动效/强调色刷新后全部保持', stored + ' → ' + JSON.stringify(after));
  const activeCell = await ev(`document.querySelector('[data-theme="aurora"]')?.classList.contains('active')`);
  const activeAcc = await ev(`document.querySelector('[data-accent="mint"]')?.classList.contains('active')`);
  check(activeCell === true && activeAcc === true, '重进面板后选中态回填正确', 'theme=' + activeCell + ' accent=' + activeAcc);
  await ev(`THEME.setMode('night');THEME.setFont('md');THEME.setDensity(false);THEME.setMotion(true);THEME.setAccent('auto');'ok'`);

  console.log('== B. 动态层的 Esc 与遮罩关闭 ==');
  await ev(`document.getElementById('modal-hotkeys').classList.remove('hidden');'ok'`); await sleep(300);
  const locked = await ev(`document.body.classList.contains('layer-open')`);
  check(locked, '未注册的层打开时也会锁背景滚动', 'body.layer-open=' + locked);
  await cmd('Input.dispatchKeyEvent', { type: 'keyDown', key: 'Escape', code: 'Escape' });
  await cmd('Input.dispatchKeyEvent', { type: 'keyUp', key: 'Escape', code: 'Escape' });
  await sleep(300);
  const closed = await ev(`document.getElementById('modal-hotkeys').classList.contains('hidden')`);
  check(closed, 'Esc 能关闭动态创建的快捷键层', 'hidden=' + closed);
  await ev(`document.getElementById('modal-hotkeys').classList.remove('hidden');'ok'`); await sleep(200);
  await cmd('Input.dispatchMouseEvent', { type: 'mousePressed', x: 8, y: 400, button: 'left', clickCount: 1 });
  await cmd('Input.dispatchMouseEvent', { type: 'mouseReleased', x: 8, y: 400, button: 'left', clickCount: 1 });
  await sleep(300);
  const closedByBg = await ev(`document.getElementById('modal-hotkeys').classList.contains('hidden')`);
  check(closedByBg, '点遮罩空白处能关闭动态层', 'hidden=' + closedByBg);

  console.log('== C. 无房间时点“房间”标签 ==');
  await ev(`document.getElementById('modal-hotkeys').classList.add('hidden');'ok'`);
  const before = await ev(`document.getElementById('view-lobby').classList.contains('hidden') ? 'not-lobby' : 'lobby'`);
  await ev(`document.querySelector('.nav-item[data-route="room"]').click();'ok'`); await sleep(600);
  const st = JSON.parse(await ev(`JSON.stringify({lobby:!document.getElementById('view-lobby').classList.contains('hidden'),room:!document.getElementById('view-room').classList.contains('hidden'),toast:document.querySelector('#toast-box .toast')?.textContent||''})`));
  check(st.lobby && !st.room && /还没有加入房间/.test(st.toast), '无房间时点房间 → 留在大厅并提示，不出现空白页', JSON.stringify(st));
  await ev(`document.querySelector('.nav-item[data-route="game"]').classList.contains('hidden') ? 'ok' : 'game-visible'`);

  console.log('== D. 在房间但看别的页 → 房间标签出提示点 ==');
  await api('/api/room/create', 'POST');
  await openApp(); await sleep(1200);
  await ev(`document.querySelector('.nav-item[data-route="profile"]').click();'ok'`); await sleep(500);
  const dotVisible = await ev(`!document.getElementById('nav-dot-room').classList.contains('hidden')`);
  await ev(`document.querySelector('.nav-item[data-route="room"]').click();'ok'`); await sleep(500);
  const dotHidden = await ev(`document.getElementById('nav-dot-room').classList.contains('hidden')`);
  check(dotVisible && dotHidden, '离开房间页时房间标签出点、回到房间页时消失', '离开=' + dotVisible + ' 回到=' + dotHidden);
  await ev(`document.querySelector('.nav-item[data-route="room"]') && document.getElementById('btn-leave-room').click();'ok'`); await sleep(600);

  console.log('== E. 防连点闸门不能误锁麦克风 ==');
  await ev(`game.view={roomNo:'900002',phase:'DAY_SPEAK',day:1,actionKind:'SPEECH',myTurn:true,mySeat:1,
    seats:[1,2,3].map(s=>({seat:s,nickname:'玩家'+s,alive:true,userId:-s,avatarId:s})),
    feed:[{day:1,type:'VOTE_DETAIL',actor:0,target:0,detail:'警长投票 1→2号  2→弃'}],winner:null,voiceMode:true,voiceAvailable:true,anonymous:false,
    myInfo:{role:'村民',faction:'GOOD'}};
    renderGame(game.view);'ok'`);
  await sleep(500);
  const micExists = await ev(`!!document.getElementById('ga-mic')`);
  await ev(`document.getElementById('ga-mic')?.click();'ok'`); await sleep(250);
  const othersLocked = await ev(`!!(document.getElementById('ga-speak-send')?.disabled)`);
  const micLocked = await ev(`!!(document.getElementById('ga-mic')?.disabled)`);
  check(micExists && !micLocked && !othersLocked, '点麦克风不触发提交锁（否则无法停止录音）', '按钮存在=' + micExists + ' 麦克风被锁=' + micLocked);
  await ev(`document.getElementById('ga-speak-send')?.click();'ok'`); await sleep(200);
  const sendLocked = await ev(`!!(document.getElementById('ga-speak-send')?.disabled)`);
  check(sendLocked, '真正提交动作的按钮仍被锁住防连点', 'disabled=' + sendLocked);

  console.log('== F. 投票条不得拿别的投票凑数 ==');
  await ev(`game.view={roomNo:'900002',phase:'DAY_VOTE',day:1,actionKind:'VOTE',myTurn:false,mySeat:1,
    seats:[1,2,3].map(s=>({seat:s,nickname:'玩家'+s,alive:true,userId:-s,avatarId:s})),
    feed:[{day:1,type:'VOTE_DETAIL',actor:0,target:0,detail:'警长投票 1→2号  2→弃'}],winner:null,myInfo:{role:'村民',faction:'GOOD'}};
    renderGame(game.view);__WW_UX.renderVoteBar();'ok'`);
  await sleep(300);
  const staleHidden = await ev(`document.getElementById('vote-bar').classList.contains('hidden')`);
  check(staleHidden, '放逐阶段只有“警长投票”记录时不显示投票条（不拿旧票凑数）', 'hidden=' + staleHidden);
  await ev(`game.view.feed.push({day:1,type:'VOTE_DETAIL',actor:0,target:0,detail:'放逐投票 1→3号  2→3号  3→弃'});
    __WW_UX.renderVoteBar();'ok'`); await sleep(300);
  const fresh = await ev(`(()=>{const b=document.getElementById('vote-bar');return JSON.stringify({hidden:b.classList.contains('hidden'),text:b.innerText.replace(/\\s+/g,' ')})})()`);
  const f = JSON.parse(fresh);
  check(!f.hidden && /3号/.test(f.text) && /2 票/.test(f.text), '本轮放逐票出现后正常渲染', f.text.slice(0, 60));

  console.log('== G. 记录筛选的索引对齐 ==');
  await ev(`game.view.feed=[
    {day:1,type:'SPEECH',actor:2,target:0,detail:'发言甲'},
    {day:1,type:'VOTE_DETAIL',actor:0,target:0,detail:'放逐投票 1→2号'},
    {day:1,type:'PLAYER_DIED',actor:2,target:0,detail:'EXILE'},
    {day:1,type:'NIGHT_ACTION',actor:1,target:3,detail:'WOLF_VOTE'}];
    renderGame(game.view);__WW_UX.feedFilter.cat='death';__WW_UX.applyFeedFilter();'ok'`);
  await sleep(300);
  const dimState = await ev(`(()=>{const c=[...document.getElementById('game-feed').children];return JSON.stringify(c.map(x=>({t:x.dataset.cat,d:x.classList.contains('dim')})))})()`);
  const ds = JSON.parse(dimState);
  const deathKept = ds.find(x => x.t === 'death'); const speechDimmed = ds.find(x => x.t === 'speech');
  check(!!deathKept && deathKept.d === false && !!speechDimmed && speechDimmed.d === true, '按分类筛选时逐条对齐不错位', dimState);

  console.log('== H. 引导“不再显示”后不再出现 ==');
  await goto(ORIGIN + '/'); await ev(`localStorage.removeItem('ww_guide');'ok'`); await goto(ORIGIN + '/'); await sleep(2400);
  const g1 = await ev(`!!document.getElementById('guide-overlay')`);
  await ev(`document.getElementById('gc-skip')?.click();'ok'`); await sleep(300);
  await goto(ORIGIN + '/'); await sleep(2400);
  const g2 = await ev(`!!document.getElementById('guide-overlay')`);
  check(g1 && !g2, '引导首访出现、点“不再显示”后彻底不再弹', '首访=' + g1 + ' 再次=' + g2);

  console.log('\n== 汇总 ==');
  console.table(results);
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 20).join('\n'));
  fs.writeFileSync(path.join(OUT, 'probe-report.txt'), JSON.stringify({ results, errors }, null, 2));
}

main().catch(e => { console.error('探测失败:', e); process.exitCode = 1; })
  .finally(() => { try { ws && ws.close(); } catch (_) {} try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
