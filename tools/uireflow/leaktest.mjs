// tools/uireflow/leaktest.mjs —— 全自动对局中，以普通玩家视角抓 HTTP 状态与 WS 消息，查信息泄露
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';

const ROOT = 'G:/狼人杀';
const EDGE = [process.env['ProgramFiles(x86)'], 'C:/Program Files (x86)'].filter(Boolean).map(d => d + '/Microsoft/Edge/Application/msedge.exe').find(fs.existsSync) || 'msedge';
const ORIGIN = 'https://localhost:11111';
const PORT = 9346;
const PROFILE = path.join(ROOT, 'screenshots', 'leak-profile');
fs.rmSync(PROFILE, { recursive: true, force: true });

const sleep = (ms) => new Promise(r => setTimeout(r, ms));
let ws, mid = 0; const pending = new Map(); const events = [];
function cmd(m, p = {}) { const id = ++mid; return new Promise((res, rej) => { pending.set(id, { res, rej }); ws.send(JSON.stringify({ id, method: m, params: p })); setTimeout(() => { if (pending.has(id)) { pending.delete(id); rej(new Error('超时 ' + m)); } }, 20000); }); }

const browser = spawn(EDGE, ['--headless=new', `--remote-debugging-port=${PORT}`, `--user-data-dir=${PROFILE}`, '--no-first-run', '--disable-gpu', 'about:blank'], { stdio: 'ignore' });
browser.on('error', (e) => { console.error('Edge 启动失败:', e.message); process.exit(1); });

const findings = [];
function check(ok, name, detail) { console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? '  [' + String(detail).slice(0, 160) + ']' : '')); if (!ok) findings.push(name + ' → ' + String(detail).slice(0, 300)); }

async function main() {
  let t = null;
  for (let i = 0; i < 60; i++) { try { const r = await fetch(`http://127.0.0.1:${PORT}/json/list`); if (r.ok) { t = await r.json(); break; } } catch (_) {} await sleep(400); }
  const pg = t.find(x => x.type === 'page');
  ws = new WebSocket(pg.webSocketDebuggerUrl);
  await new Promise((r, j) => { ws.onopen = r; ws.onerror = j; });
  ws.onmessage = (ev) => { const m = JSON.parse(ev.data); if (m.id && pending.has(m.id)) { const q = pending.get(m.id); pending.delete(m.id); m.error ? q.reject(new Error(JSON.stringify(m.error))) : q.res(m.result); } };
  await cmd('Page.enable'); await cmd('Runtime.enable');
  await cmd('Page.navigate', { url: ORIGIN + '/' });
  await sleep(2000);

  // 注册并建房补 AI 开局（注册走 Node 侧，避免页面内 fetch 受导航时序影响）
  const u = 'leak_' + Date.now().toString(36).slice(-5);
  async function raw(p, m = 'GET', b = null, tk2 = null) {
    const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(tk2 ? { Authorization: 'Bearer ' + tk2 } : {}) }, body: b ? JSON.stringify(b) : null });
    return { status: r.status, body: await r.text() };
  }
  const reg = await raw('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '泄露检测' });
  if (reg.status !== 200) throw new Error('注册失败 ' + reg.status + ' ' + reg.body.slice(0, 120));
  const tk = JSON.parse(reg.body).token;
  const H = { 'Content-Type': 'application/json', Authorization: 'Bearer ' + tk };
  const api = async (p, m = 'GET', b = null) => { const r = await fetch(ORIGIN + p, { method: m, headers: H, body: b ? JSON.stringify(b) : null }); const x = await r.text(); return { status: r.status, body: x }; };
  await api('/api/room/create', 'POST');
  for (let i = 0; i < 5; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  console.log('对局已开始，开始抓取客户端视角状态…');

  // 全程轮询 /api/game/state（客户端刷新时用的同一接口），检查泄露
  let sawOver = false, polls = 0, roleLeaks = 0, privateFeedLeaks = [], rolesSeenInFeed = new Set();
  const leakedSeats = new Set();
  const feedTypes = {};
  for (let i = 0; i < 120 && !sawOver; i++) {
    await sleep(600);
    const r = await api('/api/game/state');
    if (r.status !== 200) continue;
    polls++;
    const v = JSON.parse(r.body);
    if (v.phase === 'GAME_OVER') sawOver = true;
    const over = v.phase === 'GAME_OVER';
    for (const s of (v.seats || [])) {
      // 状态接口用 alive（不是 dead）；且按既定规则“出局即翻牌、死者身份全场公开”，
      // 只有“仍存活”的他人身份出现在我的视图才算泄露。
      if (s.role && !over && s.seat !== v.mySeat && s.alive !== false) { roleLeaks++; leakedSeats.add(s.seat + ':' + s.role); }
      if (s.role && s.seat !== v.mySeat) rolesSeenInFeed.add(s.role + (s.alive === false ? '(出局)' : '(存活)'));
    }
    for (const e of (v.feed || [])) { feedTypes[e.type] = (feedTypes[e.type] || 0) + 1; }
    // 非狼视角不应看到夜晚私密行动明细
    if (!over && v.myInfo && v.myInfo.faction !== 'WOLF') {
      for (const e of (v.feed || [])) if (e.type === 'NIGHT_ACTION' || /提议刀|统一刀口/.test(e.detail || '')) privateFeedLeaks.push(e);
    }
  }
  console.log(`轮询 ${polls} 次`);
  check(roleLeaks === 0, '存活他人身份不出现在我的视图', '泄露次数=' + roleLeaks + (leakedSeats.size ? ' 座位=' + [...leakedSeats].join(',') : '') + ' seen=' + [...rolesSeenInFeed].join(','));
  check(privateFeedLeaks.length === 0, '非狼视角的记录里无夜晚私密行动', '泄露条=' + privateFeedLeaks.length + ' ' + JSON.stringify(privateFeedLeaks.slice(0, 3)));
  console.log('客户端可见的 feed 事件类型: ' + JSON.stringify(feedTypes));

  // 同一局里用旁白 WS 记录旁白序列（bug9/12）
  try { await api('/api/game/end', 'POST'); } catch (_) {}
  try { await api('/api/room/leave', 'POST'); } catch (_) {}

  console.log('\n结论：' + (findings.length ? findings.length + ' 个泄露点' : '未发现状态接口泄露'));
}
main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => { try { browser.kill(); } catch (_) {} process.exit(process.exitCode || 0); });
