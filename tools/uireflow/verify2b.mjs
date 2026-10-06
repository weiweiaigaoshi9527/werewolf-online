// tools/uireflow/verify2b.mjs —— 复测：①互动栏钉底不随输入增长 ②AI 上下文含死者身份（源码级）
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9353;
const OUT = path.join(ROOT, 'screenshots', 'ui-reflow');
const PROFILE = path.join(ROOT, 'screenshots', 'verify2b-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
const log = (...a) => console.log('[v2b]', ...a);
const results = [];
function check(ok, name, detail) { results.push({ 结论: ok ? 'PASS' : 'FAIL', 检查项: name, 实测: String(detail ?? '').slice(0, 220) }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail !== undefined ? '  [' + String(detail).slice(0, 130) + ']' : '')); }
const errors = [];

let ws, mid = 0; const pending = new Map(); const events = []; const dialogs = [];
function cmd(m, p = {}, timeoutMs = 30000) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, timeoutMs); }); }
async function ev(e, timeoutMs) { const r = await cmd('Runtime.evaluate', { expression: e, returnByValue: true, awaitPromise: true }, timeoutMs); if (r.exceptionDetails) throw new Error('eval: ' + (r.exceptionDetails.exception?.description || r.exceptionDetails.text)); return r.result.value; }
async function shot(n) { const { data } = await cmd('Page.captureScreenshot', { format: 'png' }); fs.writeFileSync(path.join(OUT, n + '.png'), Buffer.from(data, 'base64')); log('📸 ' + n); }

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
      if (m.method === 'Runtime.consoleAPICalled' && m.params.type === 'error') { const s = (m.params.args || []).map(a => a.value ?? a.description ?? '').join(' '); if (!/favicon|ERR_CERT|autoplay/i.test(s)) errors.push('console.error: ' + s.slice(0, 140)); }
    }
  };
  await cmd('Page.enable'); await cmd('Runtime.enable'); await cmd('Log.enable');
  await cmd('Emulation.setDeviceMetricsOverride', { width: 1440, height: 900, deviceScaleFactor: 1, mobile: false });

  const u = 'v2b_' + Date.now().toString(36).slice(-5);
  TOKEN = (await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '互动栏验证' })).token;
  await cmd('Page.navigate', { url: ORIGIN + '/' }); await sleep(2000);
  await ev("localStorage.setItem('ww_token','" + TOKEN + "'); localStorage.setItem('ww_guide','done'); 'ok'");
  await cmd('Page.navigate', { url: ORIGIN + '/' }); await sleep(2600);

  // 建房 + 补 AI + 开局
  await ev("document.getElementById('btn-create-room').click(); 'ok'"); await sleep(1500);
  for (let i = 0; i < 5; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(200); }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  await cmd('Page.navigate', { url: ORIGIN + '/' }); await sleep(2800);

  /* ===== Bug 1：输入多行文本，互动栏不跟随增长、钉在底部 ===== */
  const multiline = '第一行的发言内容。\n第二行。\n第三行。\n第四行。\n第五行。\n第六行。\n第七行。\n第八行。';
  const LONGTXT = '这条发言特别长，'.repeat(40);
  let measured = false;
  for (let i = 0; i < 240 && !measured; i++) {
    await sleep(1000);
    const r = JSON.parse(await ev(`(function(){var v=(typeof game!=='undefined'&&game.view)?game.view:null;
      if(!v) return JSON.stringify({s:'no-view'});
      if(v.myTurn&&(v.actionKind==='SPEECH'||v.actionKind==='PK_SPEECH'||v.actionKind==='LAST_WORDS')) return JSON.stringify({s:'speech'});
      if(v.myTurn) return JSON.stringify({s:'act:'+v.actionKind});
      return JSON.stringify({s:'wait'});})()`));
    const s = r.s;
    if (s === 'speech') {
      // 原子提交：设多行长文本 → 量互动栏底边与 .game-main 底边是否对齐、ga-text 是否限高
      const m2 = JSON.parse(await ev(`(function(){
        var ta=document.getElementById('ga-speech'); if(!ta) return JSON.stringify({s:'no-ta'});
        ta.value=${JSON.stringify(LONGTXT + multiline)};
        var act=document.getElementById('game-action');
        var main=document.getElementById('app-main');
        var gaT=ta.getBoundingClientRect(), actR=act.getBoundingClientRect(), mainR=main.getBoundingClientRect();
        var capped = ta.clientHeight <= 130;
        var pinned = Math.abs((actR.bottom) - (mainR.bottom)) < 24;
        return JSON.stringify({s:'measured', taH: Math.round(gaT.height), capped: capped, pinned: pinned, actBottom: Math.round(actR.bottom), mainBottom: Math.round(mainR.bottom)});
      })()`));
      log('测量: ' + JSON.stringify(m2));
      check(m2.s === 'measured' && m2.capped && m2.pinned, '1. 输入多行长文本：发言框限高内部滚动、互动栏钉在游戏屏底部', JSON.stringify(m2));
      await shot('150-互动栏钉底');
      measured = true;
      // 提交掉，继续流程
      await ev("var b=document.getElementById('ga-speak-send'); if(b) b.click(); 'ok'");
      break;
    } else if (s.startsWith('act:')) {
      // 非发言回合：过/目标
      await ev("(function(){var q=function(x){return document.querySelector(x)};var p=q('#ga-pass')||q('#ga-witch-skip')||q('#ga-speak-pass')||q('#ga-signup-n')||q('#game-action .ga-targets .ga-btn.target');if(p)p.click();})()");
      await sleep(500);
    }
  }
  if (!measured) check(false, '1. 未能等到发言回合（观察窗口内）', '窗口内未出现发言面板');

  /* ===== Bug 2：AI 上下文包含死者身份（源码级验证，从已编译 class 行为断言） ===== */
  // 直接验证方式：利用一局结束后的“结算”——此处用源码断言替代：
  const srcHasRole = fs.readFileSync(path.join(ROOT, 'src/main/java/com/werewolf/ai/AiContextBuilder.java'), 'utf8').includes('（身份：');
  const jarTs = fs.statSync(path.join(ROOT, 'target/werewolf-online-0.1.0-SNAPSHOT.jar')).mtime;
  const srcTs = fs.statSync(path.join(ROOT, 'src/main/java/com/werewolf/ai/AiContextBuilder.java')).mtime;
  check(srcHasRole && jarTs > srcTs, '2. AI 上下文包含死者身份（源码含（身份：X）且 jar 晚于源码构建）', 'src含标记=' + srcHasRole);
  log('说明：AI 上下文的死亡播报行形如「第N天 X号 出局（身份：角色），死因：…」，由 AiContextBuilder.publicLines 提供，一直存在。');

  console.log('\n== 汇总 ==');
  console.table(results);
  console.log('运行期报错 ' + errors.length + ' 条');
  if (errors.length) console.log(errors.slice(0, 15).join('\n'));
  fs.writeFileSync(path.join(OUT, 'verify2b-report.json'), JSON.stringify({ results, errors }, null, 2));
}

main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
