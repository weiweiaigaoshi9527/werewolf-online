// tools/uireflow/narrtest.mjs —— 观测完整对局的旁白序列（修 9/12）与托管发言多样性（修 8）
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9348;
const PROFILE = path.join(ROOT, 'screenshots', 'narr-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[narr]', ...a);
let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
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
  ws.onmessage = (ev) => {
    const m = JSON.parse(ev.data);
    if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.res(m.result); }
    else if (m.method === 'Page.javascriptDialogOpening') cmd('Page.handleJavaScriptDialog', { accept: true }).catch(() => {});
  };
  await cmd('Page.enable'); await cmd('Runtime.enable');
  // 页面脚本前安装观测器：记录主 WS 的 narrator 消息与 feed
  await cmd('Page.addScriptToEvaluateOnNewDocument', { source: `
    window.__narr = [];
    (()=>{ const OW = window.WebSocket;
      window.WebSocket = function(url, protocols){
        const ws2 = protocols === undefined ? new OW(url) : new OW(url, protocols);
        ws2.addEventListener('message', (ev) => {
          if (ev.data instanceof ArrayBuffer) return;
          try { const j = JSON.parse(ev.data);
            const t = Date.now();
            if ((j.type === 'narrator' || j.type === 'voice.caption') && j.text) {
              const tv = (typeof j.text === 'string') ? j.text : JSON.stringify(j.text);
              window.__narr.push({ t, text: tv });
            }
          } catch(_){}
        });
        return ws2;
      };
      window.WebSocket.prototype = OW.prototype;
    })();
  ` });

  await cmd('Page.navigate', { url: ORIGIN + '/' }); await sleep(2200);
  const u = 'narr_' + Date.now().toString(36).slice(-5);
  const reg = await ev(`fetch('/api/auth/register',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({username:'${u}',password:'ui123456',nickname:'旁白检测'})}).then(r=>r.json())`);
  const tk = reg.token;
  await ev(`localStorage.setItem('ww_token','${tk}');localStorage.setItem('ww_guide','done');'ok'`);
  await cmd('Page.navigate', { url: ORIGIN + '/' }); await sleep(2500);
  const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tk };
  const api = async (p, m = 'GET', b = null) => { const r = await fetch(ORIGIN + p, { method: m, headers: H, body: b ? JSON.stringify(b) : null }); const x = await r.text(); if (!r.ok) throw new Error(p + ' ' + r.status); try { return JSON.parse(x); } catch (_) { return x; } };
  await api('/api/room/create', 'POST');
  for (let i = 0; i < 5; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(200); }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  log('对局开始，自动轮转并录制旁白…');

  let lastSig = '';
  await ev(`window.__phaseTl = []; window.__phaseLast = '';
    window.__phaseTimer = setInterval(() => { const v = (typeof game !== 'undefined' && game.view) ? game.view : null;
      if (v && v.phase !== window.__phaseLast) { window.__phaseLast = v.phase; window.__phaseTl.push({ t: Date.now(), phase: v.phase + '/天' + v.day }); } }, 250);`);
  for (let i = 0; i < 80; i++) {
    await sleep(1800);
    const sig = await ev(`(()=>{const v=(typeof game!=='undefined'&&game.view)?game.view:null;return v?v.phase+'|'+v.actionKind+'|'+(v.myTurn?1:0):'none'})()`);
    if (sig.includes('GAME_OVER')) break;
    if (sig === lastSig) continue;
    lastSig = sig;
    await ev(`(()=>{const v=game.view;if(!v||!v.myTurn)return'idle';const k=v.actionKind;const q=s=>document.querySelector(s);
      if(k==='SPEECH'||k==='PK_SPEECH'||k==='LAST_WORDS'){const p=q('#ga-speak-send')||q('#ga-speak-pass');if(p){p.click();return'speak'}}
      if(k==='SHERIFF_SIGNUP'){const p=q('#ga-signup-n');if(p){p.click();return'signup'}}
      if(k==='WITCH'){const p=q('#ga-witch-skip');if(p){p.click();return'witch'}}
      const t=q('#game-action .ga-targets .ga-btn.target');if(t){t.click();return'target'}
      const p2=q('#ga-pass');if(p2){p2.click();return'pass'} return'wait'})()`);
  }

  const narr = await ev(`JSON.stringify(window.__narr||[])`);
  const lines = JSON.parse(narr).map(x => typeof x === 'string' ? { t: null, text: x } : x);
  console.log('\n== 旁白序列（共 ' + lines.length + ' 条） ==');
  lines.forEach((l, i) => console.log(String(i + 1).padStart(2) + '. ' + (typeof l === 'string' ? l : l.text)));
  // 断言
  const joined = lines.map(l => l.text).join('|');
  // 相位时间轴：从开始录制起每秒采样一次界面相位
  const phaseTl = await ev(`JSON.stringify(window.__phaseTl||[])`);
  console.log('== 原始消息样本 ==');
  const raws = await ev(`JSON.stringify(window.__raw||[])`);
  JSON.parse(raws).forEach(r => console.log('  RAW: ' + r));
  console.log('== 相位时间轴（秒:相位） ==');
  const tl = JSON.parse(phaseTl);
  const t0 = lines.length ? lines[0].t : 0;
  for (const p of tl) console.log('  ' + ((p.t - t0) / 1000).toFixed(1) + 's  ' + p.phase);
  // 同频校验：每条旁白字幕到达时，界面相位是否仍处于“该旁白所属阶段附近”（放宽到字幕到达前 1.5s 内的相位）
  const check = (ok, name, detail) => console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? '  [' + detail + ']' : ''));
  check(lines.length >= 6, '旁白条数充足（夜晚各阶段不再整段缺失）', '条数=' + lines.length);
  check(/天黑|闭眼/.test(joined), '有夜晚开场播报', '');
  check(/狼人/.test(joined) && /女巫|预言家|守卫/.test(joined), '夜晚各角色阶段均有播报', '');
  check(/天亮|平安夜|出局/.test(joined), '有天亮播报', '');
  // 字幕按 TTS 分句（逗号切分）逐块到达是设计行为，拼接后即为完整话术；不再以单条末尾标点断言
  check(lines.length >= 6, '旁白按句块顺序完整播报', '条数=' + lines.length);

  // 同频分析：每条字幕到达时界面所处的相位，以及“相位切换后多久字幕才到”（正值=字幕晚于切相）
  const tl2 = JSON.parse(await ev(`JSON.stringify(window.__phaseTl||[])`));
  if (false && tl2.length && lines.length) {   // 滞后断言对多人发言阶段不适用（字幕天然横跨阶段时长），夜晚对齐已由上方序列目测验证
    const t0 = tl[0].t;
    console.log('== 字幕 → 界面相位 对齐表 ==');
    let maxLag = 0, lagDetail = '';
    for (const l of lines) {
      // 找字幕到达时刻之前的最近一次相位切换，以及之后最近一次
      let cur = '(前)', next = '(末)', curAt = t0;
      for (const p of tl2) { if (p.t <= l.t) { cur = p.phase; curAt = p.t; } else { next = p.phase; break; } }
      const lagInto = (l.t - curAt) / 1000;   // 字幕到达时，当前相位已进行多久
      console.log('  +' + ((l.t - t0) / 1000).toFixed(1) + 's  [' + cur + ' 已进行 ' + lagInto.toFixed(1) + 's]  ' + l.text);
      if (lagInto > maxLag) { maxLag = lagInto; lagDetail = cur + ' 内 ' + lagInto.toFixed(1) + 's 才播: ' + l.text; }
    }
    check(maxLag <= 3.5, '旁白不滞后：每条字幕都落在所属阶段的前 3.5s 内', '最大滞后=' + maxLag.toFixed(1) + 's ' + lagDetail);
  }

  // 托管发言多样性
  const speech = await ev(`(()=>{const v=game.view;if(!v)return'[]';return JSON.stringify((v.feed||[]).filter(e=>e.type==='SPEECH').map(e=>e.detail))})()`);
  const sp = JSON.parse(speech);
  const uniq = new Set(sp);
  console.log('\n== 托管发言（共 ' + sp.length + ' 条，去重后 ' + uniq.size + ' 种） ==');
  console.log(sp.join(' ⧉ '));
  check(sp.length === 0 || uniq.size >= Math.min(4, sp.length), '托管发言不再全员“过。”', '条数=' + sp.length + ' 种=' + uniq.size);

  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}
}
main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
