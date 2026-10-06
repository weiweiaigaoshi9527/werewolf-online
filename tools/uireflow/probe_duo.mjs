// tools/uireflow/probe_duo.mjs —— 双人组队 + 对局内语音链路（两个独立浏览器实例，两个真实账号）
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], process.env['ProgramFiles'], 'C:/Program Files (x86)']
  .filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://127.0.0.1:11111';
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[duo]', ...a);
const results = [];
function check(ok, name, detail) { results.push({ 结论: ok ? 'PASS' : 'FAIL', 检查项: name, 实测: String(detail ?? '') }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined ? '  [' + detail + ']' : '')); }
const errors = [];

function launch(name, port) {
  const profile = path.join(ROOT, 'screenshots', 'duo-' + name);
  fs.rmSync(profile, { recursive: true, force: true });
  const b = spawn(EDGE, [
    '--headless=new', `--remote-debugging-port=${port}`, `--user-data-dir=${profile}`,
    '--no-first-run', '--no-default-browser-check', '--disable-gpu', '--hide-scrollbars',
    '--ignore-certificate-errors',
    '--use-fake-device-for-media-stream', '--use-fake-ui-for-media-stream',   // 假麦克风 + 自动授权
    'about:blank'
  ], { stdio: ['ignore', 'ignore', 'pipe'] });
  b.on('error', (e) => { console.error('启动失败(' + name + '):', e.message); process.exit(1); });
  b.stderr.on('data', d => { const t = d.toString().trim(); if (t && !/DevTools|GroupMarker|font|gpu|GCM/i.test(t)) log('[' + name + ' stderr]', t.slice(0, 120)); });
  return b;
}

function client(tag, port) {
  let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
  async function connect() {
    let t = null;
    for (let i = 0; i < 80; i++) { try { const r = await fetch(`http://127.0.0.1:${port}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
    if (!t) throw new Error(tag + ' Edge 未就绪');
    const pg = t.find(x => x.type === 'page');
    ws = new WebSocket(pg.webSocketDebuggerUrl);
    await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
    ws.onmessage = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.resolve(m.result); }
      else if (m.method) {
        events.push(m);
        if (m.method === 'Page.javascriptDialogOpening') { dialogs.push(m.params.message); log(`[${tag}] 对话框: ` + (m.params.message || '').slice(0, 60)); cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {}); }
        if (m.method === 'Runtime.exceptionThrown') errors.push(`[${tag}] JS异常: ` + (m.params.exceptionDetails.exception?.description || '').split('\n')[0]);
        if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push(`[${tag}] console.error: ` + s.slice(0, 140)); }
      }
    };
    await cmd('Page.addScriptToEvaluateOnNewDocument', { source: `
      window.__wsLog = [];
      (()=>{ const OW = window.WebSocket;
        window.WebSocket = function(url, protocols){
          const ws = protocols === undefined ? new OW(url) : new OW(url, protocols);
          const rec = { url: String(url), frames: 0, binary: 0, bytes: 0, types: {} };
          window.__wsLog.push(rec);
          ws.addEventListener('message', (ev) => {
            rec.frames++;
            if (ev.data instanceof ArrayBuffer) { rec.binary++; rec.bytes += ev.data.byteLength; }
            else if (typeof ev.data === 'string') { try { const j = JSON.parse(ev.data); if (j.type) rec.types[j.type] = (rec.types[j.type]||0)+1; } catch(_){}}
          });
          return ws;
        };
        window.WebSocket.prototype = OW.prototype; window.WebSocket.CONNECTING=0; window.WebSocket.OPEN=1; window.WebSocket.CLOSING=2; window.WebSocket.CLOSED=3;
      })();
      window.__audioPlays = 0;
      (()=>{ const o = AudioBufferSourceNode.prototype.connect;
        AudioBufferSourceNode.prototype.connect = function(...a){ try{ if(this.buffer) window.__audioPlays++; }catch(_){} return o.apply(this,a); };
      })();
    ` });
    await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable'); await cmd('Network.enable');
  }
  function cmd(m, p = {}) {
    const id = ++mid;
    return new Promise((res, rej) => { pending.set(id, { resolve: res, reject: rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 30000); });
  }
  async function ev(e) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }); if (r.exceptionDetails) throw new Error(tag + ' eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
  async function goto(u) { events.length = 0; await cmd('Page.navigate', { url: u }); for (let i = 0; i < 60; i++) { if (events.some(x => x.method === 'Page.loadEventFired')) break; await sleep(250); } await sleep(900); }
  async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); log('📸 [' + tag + '] ' + n); }
  return { tag, connect, cmd, ev, goto, shot, events, dialogs };
}

let TOKEN_A = '', TOKEN_B = '';
async function api(tk, p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(tk ? { Authorization: 'Bearer ' + tk } : {}) }, body: b ? JSON.stringify(b) : null });
  const t = await r.text();
  if (!r.ok) throw new Error(p + ' → ' + r.status + ' ' + t.slice(0, 120));
  try { return JSON.parse(t); } catch (_) { return t; }
}

async function main() {
  const A = client('A', 9341); const B = client('B', 9342);
  const bA = launch('A', 9341); const bB = launch('B', 9342);
  try {
    await A.connect(); await B.connect();
    for (const c of [A, B]) { await c.cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false }); await c.cmd('Emulation.setTouchEmulationEnabled', { enabled: false }); }

    /* 注册两个真实账号 */
    const ua = 'duo_a_' + Date.now().toString(36).slice(-5), ub = 'duo_b_' + Date.now().toString(36).slice(-5);
    TOKEN_A = (await api(null, '/api/auth/register', 'POST', { username: ua, password: 'ui123456', nickname: '队长阿夜' })).token;
    TOKEN_B = (await api(null, '/api/auth/register', 'POST', { username: ub, password: 'ui123456', nickname: '队友小星' })).token;

    /* 1. A 通过 UI 建房 */
    await A.goto(ORIGIN + '/'); await A.ev(`localStorage.clear();localStorage.setItem('ww_token','${TOKEN_A}');localStorage.setItem('ww_guide','done');'ok'`); await A.goto(ORIGIN + '/'); await sleep(2400);
    await A.ev(`document.getElementById('btn-create-room').click();'ok'`); await sleep(1600);
    check(await A.ev(`!document.getElementById('view-room').classList.contains('hidden')`), '1. A 经 UI 建房进入房间页', await A.ev(`document.getElementById('room-no').textContent`));
    const roomNo = await A.ev(`document.getElementById('room-no').textContent`);

    /* 2. A（房主）勾选 语音同传 + 双人组队 并应用 */
    await A.ev(`document.getElementById('vm-toggle').checked = true;
                document.getElementById('duo-toggle').checked = true;
                document.getElementById('vm-toggle').disabled = false;
                document.getElementById('btn-save-mode').click();'ok'`);
    await sleep(1200);
    const mine = await api(TOKEN_A, '/api/room/my');
    check(!!(mine.room && mine.room.voiceMode) && !!(mine.room && mine.room.duoMode), '2. 语音同传+双人组队两种模式保存成功',
      `voiceMode=${mine.room?.voiceMode} duoMode=${mine.room?.duoMode}`);

    /* 3. B 经 UI 输房间号加入 */
    await B.goto(ORIGIN + '/'); await B.ev(`localStorage.clear();localStorage.setItem('ww_token','${TOKEN_B}');localStorage.setItem('ww_guide','done');'ok'`); await B.goto(ORIGIN + '/'); await sleep(2400);
    await B.ev(`document.getElementById('btn-join-room').click();'ok'`); await sleep(400);
    await B.ev(`const i=document.getElementById('join-room-no'); i.value='${roomNo}'; i.dispatchEvent(new Event('input',{bubbles:true}));'ok'`);
    await B.ev(`document.getElementById('btn-join-confirm').click();'ok'`); await sleep(1800);
    check(await B.ev(`!document.getElementById('view-room').classList.contains('hidden')`), '3. B 经 UI 输入房号加入同一房间', roomNo);
    const aSeesB = await A.ev(`document.getElementById('seat-grid').innerText.includes('队友小星')`);
    check(aSeesB, '4. A 的座位表里出现 B', '队友小星 visible=' + aSeesB);
    await A.shot('90-组队-邀请前-A视角'); await B.shot('90-组队-邀请前-B视角');

    /* 4. A 点 B 座位卡上的「组队」→ B 自动确认 → 双方出现 👫 与解除组队 */
    const click1 = await A.ev(`(()=>{const cards=[...document.querySelectorAll('#seat-grid .seat')];
      const card=cards.find(c=>c.innerText.includes('队友小星'));
      const btn=card&&card.querySelector('.seat-duo:not(.on)');
      if(btn){btn.click();return 'clicked'} return 'no-button:'+ (card?card.innerText.slice(0,30):'no-card')})()`);
    log('邀请点击结果: ' + click1);
    let duos = null, paired = false;
    for (let i = 0; i < 10; i++) { await sleep(800); duos = await api(TOKEN_A, '/api/room/my');
      if (Array.isArray(duos.room?.duos) && duos.room.duos.length === 1) { paired = true; break; } }
    check(paired, '5. 组队邀请被接受，服务端记录一对（8s 内）', 'click=' + click1 + ' duos=' + JSON.stringify(duos?.room?.duos) + ' B确认框=' + B.dialogs.length);
    const badgeA = await A.ev(`document.getElementById('seat-grid').innerText.includes('👫')`);
    const unpairBtnA = await A.ev(`!!document.querySelector('#seat-grid .seat-duo.on')`);
    const badgeB = await B.ev(`document.getElementById('seat-grid').innerText.includes('👫')`);
    check(badgeA && badgeB && unpairBtnA, '6. 两侧座位卡出现 👫 徽章与「解除组队」按钮', `A徽章=${badgeA} B徽章=${badgeB} 解除钮=${unpairBtnA}`);
    await A.shot('91-组队-已配对-A视角'); await B.shot('91-组队-已配对-B视角');

    /* 5. 解除组队 → 重新组队（验证 cancel 链路） */
    const unpair = await A.ev(`(()=>{const b=document.querySelector('#seat-grid .seat-duo.on'); if(b){b.click(); return 'clicked'} return 'no-on-button'})()`);
    let duos2 = null, cleared = false;
    for (let i = 0; i < 8; i++) { await sleep(800); duos2 = await api(TOKEN_A, '/api/room/my');
      if (!duos2.room?.duos || duos2.room.duos.length === 0) { cleared = true; break; } }
    check(cleared, '7. 解除组队后服务端清空配对', 'click=' + unpair + ' duos=' + JSON.stringify(duos2?.room?.duos));
    const click2 = await A.ev(`(()=>{const cards=[...document.querySelectorAll('#seat-grid .seat')];
      const card=cards.find(c=>c.innerText.includes('队友小星'));
      const btn=card&&card.querySelector('.seat-duo:not(.on)'); if(btn){btn.click(); return 'clicked'} return 'no-button'})()`);
    let duos3 = null, repaired = false;
    for (let i = 0; i < 10; i++) { await sleep(800); duos3 = await api(TOKEN_A, '/api/room/my');
      if (Array.isArray(duos3.room?.duos) && duos3.room.duos.length === 1) { repaired = true; break; } }
    check(repaired, '8. 重新组队成功（cancel→invite→accept 全链）', 'click=' + click2 + ' duos=' + JSON.stringify(duos3?.room?.duos) + ' B确认框=' + B.dialogs.length);

    /* 6. 补 AI、双方准备、开局 */
    for (let i = 0; i < 4; i++) { try { await api(TOKEN_A, '/api/room/add-ai', 'POST'); } catch (_) {} await sleep(300); }
    await A.ev(`document.getElementById('btn-ready').click();'ok'`); await sleep(400);
    await B.ev(`document.getElementById('btn-ready').click();'ok'`); await sleep(600);
    await A.ev(`document.getElementById('btn-start')?.click();'ok'`); await sleep(1500);
    let started = await A.ev(`!document.getElementById('view-game').classList.contains('hidden')`);
    if (!started) { for (let i = 0; i < 3; i++) { try { await api(TOKEN_A, '/api/room/add-ai', 'POST'); } catch (_) {} await sleep(300); } await A.ev(`document.getElementById('btn-start')?.click();'ok'`); await sleep(2000); started = await A.ev(`!document.getElementById('view-game').classList.contains('hidden')`); }
    check(started, '9. 组队后正常开局（进入对局视图）', 'inGame=' + started);

    /* 音频计数已在页面加载前挂好（addScriptToEvaluateOnNewDocument），开局即生效 */

    /* 8. 等身份牌 + 语音字幕/音频，验证同阵营 */
    let factionA = '', factionB = '', capA = 0, capB = 0, playA = 0, voiceA = false, voiceB = false;
    for (let i = 0; i < 14; i++) {
      await sleep(2000);
      try { factionA = await A.ev(`(game.view&&game.view.myInfo&&game.view.myInfo.faction)||''`); } catch (_) {}
      try { factionB = await B.ev(`(game.view&&game.view.myInfo&&game.view.myInfo.faction)||''`); } catch (_) {}
      try { capA = await A.ev(`document.querySelectorAll('#voice-caption .vc-line').length`); } catch (_) {}
      try { capB = await B.ev(`document.querySelectorAll('#voice-caption .vc-line').length`); } catch (_) {}
      try { voiceA = await A.ev(`typeof VOICE!=='undefined' && VOICE.isConnected()`); voiceB = await B.ev(`typeof VOICE!=='undefined' && VOICE.isConnected()`); } catch (_) {}
      if (factionA && factionB && capA > 0 && capB > 0) break;
    }
    try { playA = await A.ev(`window.__audioPlays||0`); } catch (_) {}
    check(factionA && factionA === factionB, '10. 双人组队同阵营（核心保证）', `A=${factionA || '未知'} B=${factionB || '未知'}`);
    // 语音模式服务端强制匿名（GameController: anonymous = room.anonymous || voiceMode，用于藏 AI），
    // 所以座位昵称都是 N号 —— B 的座位号要从 B 自己的客户端取 mySeat。
    const mate = await A.ev(`(()=>{const i=game.view&&game.view.myInfo;return i&&i.teammates?i.teammates.join(','):'-'})()`);
    const aSeat = await A.ev(`(game.view&&game.view.mySeat)||'-'`);
    const bSeat = await B.ev(`(game.view&&game.view.mySeat)||'-'`);
    if (factionA === 'WOLF') check(mate.split(',').includes(String(bSeat)) && !mate.split(',').includes(String(aSeat)),
        '11. 狼队视角：B 的座位在 A 的狼队友名单里（且不含 A 自己）', `A座=${aSeat} B座=${bSeat} teammates=${mate}`);
    else check(true, '11. 好人阵营（狼队友断言不适用）', `faction=${factionA}`);
    check(voiceA && voiceB, '12. 双方 /ws/voice 已连接', `A=${voiceA} B=${voiceB}`);
    check(capA > 0 && capB > 0, '13. 双方都收到旁白字幕（voice.caption）', `A=${capA}条 B=${capB}条`);
    const wsVoice = await A.ev(`(()=>{const r=(window.__wsLog||[]).filter(x=>x.url.includes('/ws/voice'));return JSON.stringify(r.length?r[0]:{none:true})})()`);
    const vinfo = JSON.parse(wsVoice);
    check(playA > 0, '14. A 端有 TTS 音频真实播放（AudioBuffer 回放计数）', `plays=${playA}`);
    check(vinfo.binary > 0, '15. /ws/voice 收到服务端下发的 TTS 二进制音频帧', `frames=${vinfo.frames} binary=${vinfo.binary} bytes=${vinfo.bytes} types=${JSON.stringify(vinfo.types||{})}`);
    await A.shot('92-对局-语音字幕-A'); await B.shot('93-对局-语音字幕-B');

    /* 收尾 */
    try { await api(TOKEN_A, '/api/game/end', 'POST'); } catch (_) {}
    await sleep(1200);
    try { await api(TOKEN_A, '/api/room/leave', 'POST'); } catch (_) {}
    try { await api(TOKEN_B, '/api/room/leave', 'POST'); } catch (_) {}

    console.log('\n== 汇总 ==');
    console.table(results);
    console.log('运行期报错 ' + errors.length + ' 条');
    if (errors.length) console.log(errors.slice(0, 20).join('\n'));
    fs.writeFileSync(path.join(OUT, 'duo-report.txt'), JSON.stringify({ results, errors }, null, 2));
  } finally {
    try { bA.kill(); bB.kill(); } catch (_) {}
  }
}

main().catch(e => { console.error('验证失败:', e); process.exitCode = 1; })
  .finally(() => process.exit(process.exitCode || 0));
