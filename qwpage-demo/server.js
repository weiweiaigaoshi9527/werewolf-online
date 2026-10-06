/**
 * 狼人杀 Online 虚拟主机版 · 在线试玩演示
 * Node.js 内置模块实现；Supabase 持久化（环境变量由 Page 运行时注入）。
 * 6 人快局：1 真人 + 5 AI（2狼 预言家 守卫 2村民），节奏加速。
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const PORT = parseInt(process.env.PORT || '8081', 10);
const HOST = process.env.HOST || '0.0.0.0';
const SB_URL = process.env.SUPABASE_URL || '';
const SB_KEY = process.env.SUPABASE_ANON_KEY || '';
const VERSION = '2.0.0-demo';

const ROLE_NAMES = { werewolf: '狼人', seer: '预言家', guard: '守卫', villager: '村民' };
const AI_NAMES = ['月月', '阿灰', '夜夜', '小村', '团子', '眠眠', '蜡蜡', '多多'];
const rnd = (a, b) => a + Math.floor(Math.random() * (b - a + 1));
const pick = (arr) => arr[rnd(0, arr.length - 1)];

/* ---------------- 对局 ---------------- */
const rooms = new Map(); // id -> game

function newGame(playerName) {
  const id = Math.random().toString(36).slice(2, 8).toUpperCase();
  const roles = ['werewolf', 'werewolf', 'seer', 'guard', 'villager', 'villager'];
  for (let i = roles.length - 1; i > 0; i--) { const j = rnd(0, i); [roles[i], roles[j]] = [roles[j], roles[i]]; }
  const names = [...AI_NAMES].sort(() => Math.random() - 0.5);
  const g = {
    id, day: 0, phase: 'NIGHT', kind: 'GUARD', cur: 0, winner: '',
    nextAt: Date.now() + 800,
    seats: roles.map((role, i) => ({
      seat: i + 1, name: i === 0 ? playerName : names[i % names.length] + (i > 4 ? '2' : ''),
      ai: i !== 0, role, alive: true, revealed: false, isSheriff: false,
    })),
    night: {}, guardLast: 0, saveUsed: false, votes: {}, speech: { order: [], idx: 0 },
    events: [], seq: 0,
  };
  ev(g, 'NARR', '欢迎来到演示局！你是 1 号，6 人快局：2 狼 / 预言家 / 守卫 / 2 村民。夜晚开始…');
  step(g);
  return g;
}
function ev(g, type, detail, pub = 1, to = 0) {
  g.seq++;
  g.events.push({ seq: g.seq, day: g.day, type, detail, public: pub, to });
  if (g.events.length > 200) g.events.splice(0, g.events.length - 200);
}
const bySeat = (g, s) => g.seats.find(p => p.seat === s) || null;
const aliveSeats = (g) => g.seats.filter(p => p.alive).map(p => p.seat);
const isWolf = (p) => p.role === 'werewolf';
const wolvesAlive = (g) => g.seats.filter(p => p.alive && isWolf(p)).map(p => p.seat);

function step(g) {
  // 夜晚队列：GUARD -> WOLF_KILL -> SEER -> 结算 -> 白天
  if (g.phase === 'NIGHT') {
    const queue = g.nightQueue || [];
    while (queue.length) {
      const kind = queue.shift();
      const roleMap = { GUARD: 'guard', WOLF_KILL: 'werewolf', SEER: 'seer' };
      const seats = kind === 'WOLF_KILL' ? wolvesAlive(g) : g.seats.filter(p => p.alive && p.role === roleMap[kind]).map(p => p.seat);
      if (!seats.length) continue;
      g.kind = kind; g.cur = seats[0];
      g.nextAt = Date.now() + 600;
      return;
    }
    resolveNight(g);
    return;
  }
}
function setNight(g) {
  g.day++;
  g.phase = 'NIGHT';
  g.night = { guarded: 0, wolfTarget: 0, seerTarget: 0, seerResult: '' };
  g.nightQueue = ['GUARD', 'WOLF_KILL', 'SEER'];
  ev(g, 'NARR', `🌙 第 ${g.day} 夜降临，村庄陷入沉睡…`);
  g.kind = ''; g.cur = 0;
  g.nextAt = Date.now() + 1000;
  setTimeout(() => step(g), 1100);
}
function resolveNight(g) {
  const n = g.night;
  const dead = [];
  if (n.wolfTarget && n.wolfTarget !== n.guarded) dead.push(n.wolfTarget);
  g.pendingShot = null;
  for (const s of dead) {
    const p = bySeat(g, s);
    p.alive = false; p.revealed = true;
  }
  ev(g, 'DAWN', dead.length ? `☀️ 天亮了。昨夜 ${dead.map(s => s + '号').join('、')} 出局，翻牌【${ROLE_NAMES[bySeat(g, dead[0]).role]}】。` : '☀️ 天亮了，昨夜是平安夜，无人出局。');
  beginDay(g);
}
function beginDay(g) {
  g.phase = 'DAY';
  g.votes = {};
  const alive = aliveSeats(g);
  g.speech = { order: alive, idx: 0 };
  ev(g, 'NARR', `第 ${g.day} 天，依次发言，找出狼人！`);
  nextSpeech(g);
}
function nextSpeech(g) {
  const sp = g.speech;
  while (sp.idx < sp.order.length) {
    const seat = sp.order[sp.idx];
    const p = bySeat(g, seat);
    if (p && p.alive) { g.kind = 'SPEECH'; g.cur = seat; g.nextAt = Date.now() + 900; return; }
    sp.idx++;
  }
  beginVote(g);
}
function beginVote(g) {
  g.votes = {};
  g.kind = 'VOTE';
  g.cur = aliveSeats(g)[0];
  ev(g, 'NARR', '🗳 发言结束，请投票放逐一名嫌疑者。');
  g.nextAt = Date.now() + 900;
}
function resolveVote(g, round) {
  const tally = {};
  for (const t of Object.values(g.votes)) tally[t] = (tally[t] || 0) + 1;
  const detail = Object.entries(g.votes).map(([s, t]) => s + '号 → ' + t + '号').join('，');
  ev(g, 'VOTE_RESULT', '票型：' + detail);
  let max = 0, tops = [];
  for (const [t, v] of Object.entries(tally)) { if (v > max) { max = v; tops = [+t]; } else if (v === max) tops.push(+t); }
  if (tops.length > 1 && round === 1) {
    ev(g, 'NARR', '平票！' + tops.map(s => s + '号').join('、') + ' 进入 PK，直接二次投票。');
    g.votes = {}; g.kind = 'PK_VOTE'; g.cur = aliveSeats(g).find(s => !tops.includes(s)) || aliveSeats(g)[0];
    g.pkList = tops; g.pkRound = 2;
    return;
  }
  if (tops.length > 1) { ev(g, 'NARR', '二次平票，本轮无人放逐。'); return setNight(g); }
  exile(g, tops[0]);
}
function exile(g, seat) {
  const p = bySeat(g, seat);
  p.alive = false; p.revealed = true;
  ev(g, 'EXILE', `⚖️ ${p.name}（${seat}号）被放逐出局，翻牌【${ROLE_NAMES[p.role]}】。`);
  checkWin(g);
  if (!g.winner) setNight(g);
}
function checkWin(g) {
  const w = wolvesAlive(g).length;
  const goods = aliveSeats(g).length - w;
  if (w === 0) return gameOver(g, 'good');
  if (w >= goods) return gameOver(g, 'wolf');
  return false;
}
function gameOver(g, side) {
  g.phase = 'GAME_OVER'; g.winner = side; g.kind = ''; g.cur = 0;
  ev(g, 'GAME_OVER', side === 'good' ? '🌅 最后一头狼倒下——好人阵营胜利！' : '🐺 狼群撕下伪装——狼人阵营胜利！');
  persist(g);
}

/* ---------------- AI 托管 ---------------- */
const SPEECH_POOL = ['我先听一听大家的发言。', '票型我先记着，稍后再表态。', '这轮先过，重点听下一位。',
  '我还在理思路，大家继续。', '我暂时保留意见，先听后置位怎么说。', '前面几位说的我都记下了。'];
function autoAct(g, seat) {
  const me = bySeat(g, seat);
  const others = aliveSeats(g).filter(s => s !== seat);
  switch (g.kind) {
    case 'GUARD': { const c = others.filter(s => s !== g.guardLast); const t = c.length ? pick(c) : 0; g.night.guarded = t; g.guardLast = t; return; }
    case 'WOLF_KILL': {
      const goods = others.filter(s => !isWolf(bySeat(g, s)));
      g.night.wolfTarget = goods.length ? pick(goods) : (others[0] || 0);
      return;
    }
    case 'SEER': { const t = others[0] ? pick(others) : seat; g.night.seerTarget = t; g.night.seerResult = isWolf(bySeat(g, t)) ? '狼人' : '好人'; return; }
    case 'SPEECH': return ev(g, 'SPEECH', `${seat}号 ${me.name}：${pick(SPEECH_POOL)}`);
    case 'VOTE': case 'PK_VOTE': {
      const list = g.kind === 'PK_VOTE' ? (g.pkList || others) : others;
      const c = list.filter(s => s !== seat);
      if (!c.length) c.push(others[0] || seat);
      g.votes[seat] = pick(c);
      const left = aliveSeats(g).filter(s => g.votes[s] === undefined);
      if (left.length && g.kind === 'VOTE') { g.cur = left[0]; g.nextAt = Date.now() + 700; return; }
      if (left.length && g.kind === 'PK_VOTE') { g.cur = left[0]; g.nextAt = Date.now() + 700; return; }
      resolveVote(g, g.kind === 'PK_VOTE' ? g.pkRound : 1);
      return;
    }
  }
}
function humanAct(g, seat, body) {
  const kind = body.kind;
  const me = bySeat(g, seat);
  if (!me || !me.alive || g.cur !== seat || g.phase === 'GAME_OVER') return '现在轮不到你行动';
  if (g.phase === 'NIGHT') {
    if (kind === 'GUARD' && g.kind === 'GUARD') {
      const t = +body.target || 0;
      if (t === g.guardLast) return '守卫不能连守同一人';
      g.night.guarded = t; g.guardLast = t;
      ev(g, 'GUARD', t ? `你守护了 ${t}号` : '你选择空守', 0, seat);
      step(g); return '';
    }
    if (kind === 'WOLF_KILL' && g.kind === 'WOLF_KILL') {
      const t = +body.target || 0;
      if (!t || !bySeat(g, t) || !bySeat(g, t).alive || isWolf(bySeat(g, t))) return '狼刀目标无效';
      g.night.wolfTarget = t;
      step(g); return '';
    }
    if (kind === 'SEER' && g.kind === 'SEER') {
      const t = +body.target || 0;
      if (!t || t === seat || !bySeat(g, t) || !bySeat(g, t).alive) return '验人目标无效';
      g.night.seerTarget = t;
      g.night.seerResult = isWolf(bySeat(g, t)) ? '狼人' : '好人';
      ev(g, 'SEER', `${t}号 的身份是：${g.night.seerResult}`, 0, seat);
      step(g); return '';
    }
    return '现在轮不到你行动';
  }
  if (g.phase === 'DAY') {
    if (kind === 'SPEECH' && g.kind === 'SPEECH') {
      const line = String(body.line || '（过）').slice(0, 120);
      ev(g, 'SPEECH', `${seat}号 ${me.name}：${line}`);
      g.speech.idx++;
      nextSpeech(g); return '';
    }
    if ((kind === 'VOTE' || kind === 'PK_VOTE') && g.kind === kind) {
      const t = +body.target || 0;
      const list = kind === 'PK_VOTE' ? (g.pkList || []) : aliveSeats(g);
      if (!t || t === seat || (kind === 'PK_VOTE' && !list.includes(t)) || !bySeat(g, t) || !bySeat(g, t).alive) return '投票目标无效';
      g.votes[seat] = t;
      const left = aliveSeats(g).filter(s => g.votes[s] === undefined);
      if (left.length) { g.cur = left[0]; return ''; }
      resolveVote(g, kind === 'PK_VOTE' ? g.pkRound : 1);
      return '';
    }
    return '现在轮不到你行动';
  }
  return '对局已结束';
}

/* ---------------- 时钟推进 ---------------- */
setInterval(() => {
  const now = Date.now();
  for (const g of rooms.values()) {
    if (g.phase === 'GAME_OVER') continue;
    if (!g.nextAt || now < g.nextAt) continue;
    const p = bySeat(g, g.cur);
    if (g.phase === 'NIGHT') {
      if (g.cur && p && p.ai) { autoAct(g, g.cur); step(g); }
      else if (g.cur && p && !p.ai) { g.nextAt = now + 60000; } // 等真人
      else g.nextAt = now + 400;
    } else if (g.phase === 'DAY') {
      if (g.kind === 'SPEECH') {
        if (p && p.ai) { autoAct(g, g.cur); g.speech.idx++; nextSpeech(g); }
        else if (p && !p.ai) { g.nextAt = now + 60000; }
      } else if (g.kind === 'VOTE' || g.kind === 'PK_VOTE') {
        if (p && p.ai) autoAct(g, g.cur);
        else if (p && !p.ai) { g.nextAt = now + 60000; }
        else { const left = aliveSeats(g).filter(s => g.votes[s] === undefined); if (left.length) { g.cur = left[0]; g.nextAt = now + 500; } else resolveVote(g, g.kind === 'PK_VOTE' ? g.pkRound : 1); }
      }
    }
  }
}, 250);

/* ---------------- Supabase 持久化 ---------------- */
async function persist(g) {
  if (!SB_URL || !SB_KEY) return;
  try {
    const rows = {
      game_id: g.id, winner: g.winner, days: g.day,
      player: (g.seats[0] || {}).name || '游客',
      roles: g.seats.map(p => `${p.seat}号${p.name}=${ROLE_NAMES[p.role]}${p.alive ? '' : '(出局)'}`).join('；'),
    };
    await fetch(SB_URL.replace(/\/$/, '') + '/rest/v1/demo_results', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json', 'apikey': SB_KEY, 'Authorization': 'Bearer ' + SB_KEY, 'Prefer': 'return=minimal' },
      body: JSON.stringify(rows),
    });
    const evRows = g.events.filter(e => e.public).map(e => ({ game_id: g.id, seq: e.seq, type: e.type, detail: e.detail }));
    if (evRows.length) {
      await fetch(SB_URL.replace(/\/$/, '') + '/rest/v1/demo_events', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'apikey': SB_KEY, 'Authorization': 'Bearer ' + SB_KEY, 'Prefer': 'return=minimal' },
        body: JSON.stringify(evRows),
      });
    }
    g.persisted = true;
  } catch (e) { /* 持久化失败不影响演示 */ }
}
async function loadResults() {
  if (!SB_URL || !SB_KEY) return [];
  try {
    const r = await fetch(SB_URL.replace(/\/$/, '') + '/rest/v1/demo_results?select=*&order=created_at.desc&limit=10', {
      headers: { 'apikey': SB_KEY, 'Authorization': 'Bearer ' + SB_KEY },
    });
    if (!r.ok) return [];
    return await r.json();
  } catch { return []; }
}

/* ---------------- 视图 ---------------- */
function viewFor(g) {
  const seat = 1;
  const me = bySeat(g, seat);
  const isW = me && isWolf(me);
  return {
    id: g.id, day: g.day, phase: g.phase, kind: g.kind, cur: g.cur, winner: g.winner,
    seats: g.seats.map(p => ({
      seat: p.seat, name: p.name, ai: p.ai, alive: p.alive, isSheriff: p.isSheriff,
      role: (p.revealed || g.phase === 'GAME_OVER' || p.seat === seat || (isW && isWolf(p))) ? ROLE_NAMES[p.role] : '',
    })),
    events: g.events.filter(e => e.public === 1 || (e.to === seat) || (e.to === -2 && isW)),
    my: me ? { role: ROLE_NAMES[me.role], roleKey: me.role,
      guardLast: g.guardLast || 0 } : null,
    voted: Object.keys(g.votes).length,
    now: Date.now(),
  };
}

/* ---------------- HTTP ---------------- */
const MIME = { '.html': 'text/html; charset=utf-8', '.css': 'text/css; charset=utf-8', '.js': 'application/javascript; charset=utf-8', '.json': 'application/json' };
function serveStatic(req, res, urlPath) {
  let p = urlPath === '/' ? '/index.html' : urlPath;
  p = p.split('?')[0];
  const file = path.join(__dirname, 'public', path.normalize(p).replace(/^([.][.][/\\])+/, ''));
  if (!file.startsWith(path.join(__dirname, 'public'))) { res.writeHead(403); return res.end('forbidden'); }
  fs.readFile(file, (err, data) => {
    if (err) { res.writeHead(404); return res.end('not found'); }
    res.writeHead(200, { 'Content-Type': MIME[path.extname(file)] || 'application/octet-stream' });
    res.end(data);
  });
}
function json(res, code, obj) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Access-Control-Allow-Origin': '*' });
  res.end(JSON.stringify(obj));
}

const server = http.createServer(async (req, res) => {
  const u = req.url || '/';
  if (u.startsWith('/api/')) {
    const pathname = u.split('?')[0].replace(/\/+$/, '') || '/api/';
    const query = new URL('http://x' + u).searchParams;
    let body = '';
    req.on('data', c => { body += c; if (body.length > 1e6) req.destroy(); });
    req.on('end', async () => {
      let b = {};
      try { b = body ? JSON.parse(body) : {}; } catch {}
      if (req.method === 'OPTIONS') return json(res, 204, {});
      if (pathname === '/api/join' && req.method === 'POST') {
        const name = String(b.name || '游客').slice(0, 12) || '游客';
        const g = newGame(name);
        rooms.set(g.id, g);
        if (rooms.size > 200) { const k = rooms.keys().next().value; rooms.delete(k); }
        return json(res, 200, { ok: 1, room: g.id, seat: 1 });
      }
      if (pathname === '/api/state') {
        const g = rooms.get(String(b.room || query.get('room') || '').toUpperCase());
        if (!g) return json(res, 404, { ok: 0, err: '对局不存在或已过期，请重新进入' });
        return json(res, 200, { ok: 1, game: viewFor(g) });
      }
      if (pathname === '/api/act' && req.method === 'POST') {
        const g = rooms.get(String(b.room || '').toUpperCase());
        if (!g) return json(res, 404, { ok: 0, err: '对局不存在' });
        const err = humanAct(g, 1, b);
        if (err) return json(res, 400, { ok: 0, err });
        return json(res, 200, { ok: 1, game: viewFor(g) });
      }
      if (pathname === '/api/results') {
        const rows = await loadResults();
        return json(res, 200, { ok: 1, persisted: !!SB_URL, results: rows });
      }
      if (pathname === '/api/health') return json(res, 200, { ok: 1, version: VERSION, rooms: rooms.size, persisted: !!SB_URL });
      return json(res, 404, { ok: 0, err: '未知接口' });
    });
    return;
  }
  serveStatic(req, res, u);
});
server.listen(PORT, HOST, () => console.log(`werewolf demo listening on ${HOST}:${PORT} persisted=${!!SB_URL}`));
