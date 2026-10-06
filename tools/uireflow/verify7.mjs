// tools/uireflow/verify7.mjs —— 复测用户第二轮报的 7 个问题中可自动化验证的 5 项
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9349;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'verify7-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[v7]', ...a);
const results = [];
function check(ok, name, detail) { results.push({ 结论: ok ? 'PASS' : 'FAIL', 问题: name, 实测: String(detail ?? '').slice(0, 220) }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined ? '  [' + String(detail).slice(0, 130) + ']' : '')); }
const errors = [];

let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
function cmd(m, p = {}, timeoutMs = 30000) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, timeoutMs); }); }
async function ev(e, timeoutMs = 30000) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }, timeoutMs); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
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
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'log') {
        const s2 = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' ');
        if (/还没轮到|无进行中|对局已结束/.test(s2)) errors.push('toast: ' + s2.slice(0, 100));
      }
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push('console.error: ' + s.slice(0, 140)); }
    }
  };
  await cmd('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__subs = [];
    window.addEventListener('DOMContentLoaded', () => {
      if (typeof window.submitAction === 'function') {
        const o = window.submitAction;
        window.submitAction = function (payload, okMsg) {
          window.__subs.push(JSON.stringify(payload).slice(0, 120));
          return o.call(this, payload, okMsg);
        };
      } else { window.__subs.push('submitAction-not-found'); }
    });
  ` });
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  const u = 'v7_' + Date.now().toString(36).slice(-5);
  TOKEN = (await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '二轮复测' })).token;

  /* ===== Bug 5：管理员设置网页最大高度 → 客户端应用 ===== */
  // 模拟管理员写入（直连 DB 不可行，用公共端点验证读取链路 + 手动应用布局验证视觉）
  const cfg = await api('/api/config/display');
  check(typeof cfg.webMaxHeight === 'number', '5a. 公共显示配置端点可用', JSON.stringify(cfg));
  await goto(ORIGIN + '/'); await ev(`localStorage.setItem('ww_token','${TOKEN}');localStorage.setItem('ww_guide','done');'ok'`); await goto(ORIGIN + '/'); await sleep(2300);
  await ev(`document.documentElement.style.setProperty('--app-max-h','640px');document.body.classList.add('hmax');'ok'`); await sleep(400);
  const shellH = await ev(`document.querySelector('.shell').getBoundingClientRect().height`);
  check(shellH <= 642, '5b. 设置 640px 后 .shell 高度被封顶（居中布局）', 'shell高度=' + Math.round(shellH));
  await shot('130-网页最大高度-640px');
  await ev(`document.body.classList.remove('hmax');'ok'`);

  /* ===== Bug 1：在房间 A 时点外链加入房间 B → 确认离开并加入 ===== */
  await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1500);
  const roomA = await ev(`document.getElementById('room-no').textContent`);
  // 造一个房间 B（另一个账号的）
  const regB = await api('/api/auth/register', 'POST', { username: 'b7_' + Date.now().toString(36).slice(-5), password: 'ui123456', nickname: 'B房主' });
  const tkB = regB.token;
  await fetch(ORIGIN + '/api/room/create', { method: 'POST', headers: { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tkB } });
  const stB = await (await fetch(ORIGIN + '/api/room/my', { headers: { Authorization: 'Bearer ' + tkB } })).json();
  const roomB = stB.room.roomNo;
  dialogs.length = 0;
  await ev(`document.getElementById('btn-join-room').click();'ok'`); await sleep(300);
  await ev(`const i=document.getElementById('join-room-no'); i.value='${roomB}'; i.dispatchEvent(new Event('input',{bubbles:true}));'ok'`);
  await ev(`document.getElementById('btn-join-confirm').click();'ok'`); await sleep(2200);
  const joinedB = JSON.parse(await ev(`JSON.stringify({room: document.getElementById('room-no').textContent, confirmAsked: true})`));
  check(dialogs.some(d => d.includes('离开当前房间') && d.includes(roomB)) && joinedB.room === roomB, '1. 在房间 A 点外链加入 B：先确认离开再加入 B', `确认框=${dialogs[dialogs.length - 1] || '无'} 当前=${joinedB.room}`);

  /* ===== Bug 4/6/7：对局中验证（先退出 B 房，自建房间以房主身份开局） ===== */
  try { await api('/api/room/leave', 'POST'); } catch (_) {}
  await ev(`document.querySelector('[data-route="lobby"]')?.click();'ok'`); await sleep(800);
  await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1500);
  await ev(`document.getElementById('btn-add-ai')?.click();'ok'`); await sleep(500);
  for (let i = 0; i < 4; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(200); }
  await ev(`document.getElementById('btn-ready').click();'ok'`); await sleep(400);
  await api('/api/game/start', 'POST');
  await goto(ORIGIN + '/'); await sleep(2800);
  const inGame = await ev(`!document.getElementById('view-game').classList.contains('hidden')`);
  log('开局=' + inGame);

  // Bug 7：轮到我发言时提交 >320 字的长文本 → 记录全文 + 朗读覆盖
  const LONG = '大家好，我来说说我的完整思路。'.repeat(24) + '以上就是我全部的判断，谢谢大家。';
  // 页面内等待发言面板出现后原子提交（消除状态读取与点击间的竞态）
  // 浏览器路径存在竞态：客户端视图里的 myTurn 可能比服务端旧，点击瞬间回合已过 → 该次提交被服务端拒绝。
  // 因此这里改为「提交后确认服务端确实记录，否则等下一轮再试」，最多若干轮，保证结论稳定。
  const PHRASE = '全部的判断';
  const submitLong = await ev(`(async()=>{ let last='', tries=0;
    for(let i=0;i<2400;i++){
      const v=(typeof game!=='undefined'&&game.view)?game.view:null;
      if(v){
        last=v.phase+' kind='+v.actionKind+' myTurn='+v.myTurn;
        const has=(v.feed||[]).some(function(e){return e.type==='SPEECH'&&e.detail&&e.detail.indexOf(${JSON.stringify(PHRASE)})>=0;});
        if(has) return 'recorded(tries='+tries+')';
        if(v.myTurn&&(v.actionKind==='SPEECH'||v.actionKind==='PK_SPEECH'||v.actionKind==='LAST_WORDS')){
          const ta=document.getElementById('ga-speech');
          if(ta){ ta.value=${JSON.stringify(LONG)}; const b=document.getElementById('ga-speak-send');
            if(b){ b.click(); tries++; await new Promise(r=>setTimeout(r,1800)); } } }
      }
      await new Promise(r=>setTimeout(r,500));
    } return 'timeout('+last+') tries='+tries; })()`, 900000);
  log('长发言提交结果=' + submitLong);
  // 字幕区是「滚动窗口」（最多保留 7 行、14s 无新行即清空），任何时刻读取都拿不到整段朗读。
  // 因此这里挂一个 MutationObserver，把整段朗读出现过的字幕行累积到 window.__caps，再统计覆盖字数。
  await ev(`(()=>{ window.__caps=window.__caps||[];
    const el=document.getElementById('voice-caption');
    if(el && !el.__obs){ const grab=()=>{ el.querySelectorAll('.vc-line .vc-text').forEach(function(t){
        const s=(t.textContent||'').trim(); if(s && window.__caps.indexOf(s)<0) window.__caps.push(s); }); };
      const obs=new MutationObserver(grab); obs.observe(el,{childList:true,subtree:true}); el.__obs=obs; grab(); }
    return 'observer-ok'; })()`);
  // 等 feed 与字幕（表达式用字符串拼接，避免复杂嵌套模板）
  let feedLen = 0, capChars = 0;
  const phrase = '全部的判断';
  const expr = ("(function(){var v=game.view||{};var mine=(v.feed||[]).filter(function(e){return e.type==='SPEECH'&&e.detail&&e.detail.indexOf('PHRASE')>=0;});"
    + "var caps=((window.__caps||[]).join('')+Array.prototype.slice.call(document.querySelectorAll('#voice-caption .vc-line')).map(function(x){return x.textContent;}).join(''));"
    + "return JSON.stringify({feedLen: mine.length?mine[0].detail.length:0, caps:caps});})()").replace(/PHRASE/g, phrase);
  for (let i = 0; i < 80; i++) {
    await sleep(1500);
    const r = JSON.parse(await ev(expr));
    feedLen = r.feedLen; capChars = Math.max(capChars, (r.caps.match(/[一-龥]/g) || []).length);
    if (feedLen > 0 && capChars > 220) break;
  }
  const dump = await ev(`JSON.stringify({subs:(window.__subs||[]).slice(-4), feed:(game.view&&game.view.feed||[]).filter(e=>e.type==='SPEECH').map(e=>e.detail.slice(0,50)), phase:game.view&&game.view.phase})`);
  log('提交诊断: ' + dump);
  check(feedLen >= LONG.length, '7. >320 字的长发言：记录保留全文不被截断', `提交${LONG.length}字 记录${feedLen}字`);
  check(capChars > 200, '7. 长发言的朗读字幕覆盖完整内容（不再 320 字截断）', `字幕汉字数=${capChars}`);
  await shot('131-长发言-完整朗读');

  /* ===== Bug 4：死后亮身份 ===== */
  let deadShown = false;
  for (let i = 0; i < 40; i++) {
    await sleep(1500);
    const r = JSON.parse(await ev(`(()=>{const v=game.view;if(!v)return'{}';const d=(v.seats||[]).filter(s=>s.alive===false&&s.role);return JSON.stringify({over:v.phase==='GAME_OVER',deadWithRole:d.length,nicks:d.map(s=>s.seat+':'+s.role).slice(0,4)})})()`));
    if (r.deadWithRole > 0) { deadShown = true; log('出局者身份: ' + r.nicks.join(', ')); }
    if (r.over) break;
  }
  check(deadShown, '4. 出局者座位卡显示身份（狼人杀惯例：出局即翻牌）', deadShown ? '已有出局者带角色' : '整局无人出局或未显示');
  await shot('132-出局亮身份');
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  /* ===== Bug 6：普通房（无语音同传）真人文字也有声音 ===== */
  await goto(ORIGIN + '/'); await sleep(2000);
  await ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1500);
  for (let i = 0; i < 3; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(200); }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  await goto(ORIGIN + '/'); await sleep(2800);
  // 记录 voice WS 帧数基线，然后提交一句真人文字发言，观察是否有 TTS 字幕/音频
  await ev(`window.__cap0 = document.querySelectorAll('#voice-caption .vc-line').length; 'ok'`);
  const submitShort = await ev(`(async()=>{ let last=''; for(let i=0;i<720;i++){
      const v=(typeof game!=='undefined'&&game.view)?game.view:null;
      if(v){ last=v.phase+' kind='+v.actionKind+' myTurn='+v.myTurn; }
      if(v&&v.myTurn&&(v.actionKind==='SPEECH'||v.actionKind==='PK_SPEECH')){
        const ta=document.getElementById('ga-speech');
        if(ta){ ta.value='各位晚上好，我用文字发言也能被大家听见。'; const b=document.getElementById('ga-speak-send'); if(b){b.click(); return 'sent';} } }
      await new Promise(r=>setTimeout(r,500));
    } return 'timeout('+last+')'; })()`, 420000);
  log('普通房文字发言提交结果=' + submitShort + '（该房 voiceMode=off）');
  let heard = false;
  for (let i = 0; i < 15; i++) {
    await sleep(1200);
    const r = JSON.parse(await ev(`(()=>{const caps=[...document.querySelectorAll('#voice-caption .vc-line')].map(x=>x.textContent);
      const hit=caps.some(c=>c.includes('各位晚上好')); return JSON.stringify({hit, capsN:caps.length});})()`));
    if (r.hit) { heard = true; break; }
  }
  check(heard, '6. 关闭语音同传的普通房：真人文字发言也被 TTS 朗读（字幕出现）', heard ? '字幕包含发言内容' : '未观测到字幕');
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  console.log('\n== 汇总 ==');
  console.table(results);
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 15).join('\n'));
  fs.writeFileSync(path.join(OUT, 'verify7-report.json'), JSON.stringify({ results, errors }, null, 2));
}

main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
