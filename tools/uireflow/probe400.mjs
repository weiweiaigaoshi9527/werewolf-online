process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const O = 'https://127.0.0.1:11111';
let TK = '';
const api = async (p, m = 'GET', b = null) => {
  const r = await fetch(O + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TK ? { Authorization: 'Bearer ' + TK } : {}) }, body: b ? JSON.stringify(b) : null });
  const t = await r.text();
  return { status: r.status, body: t, short: t.slice(0, 200) };
};
const u = 'probe_' + Date.now().toString(36).slice(-5);
TK = JSON.parse((await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '探针' })).body).token;
console.log('建房', (await api('/api/room/create', 'POST')).status);
await api('/api/room/add-ai', 'POST'); await api('/api/room/add-ai', 'POST'); await api('/api/room/add-ai', 'POST');
await api('/api/room/ready', 'POST', { ready: true });
console.log('开局', (await api('/api/game/start', 'POST')).status);
for (let i = 0; i < 6; i++) {
  const st = await api('/api/game/state');
  const v = JSON.parse(st.body);
  const mine = v.myTurn ? '我的回合 kind=' + v.actionKind : '非我回合 phase=' + v.phase;
  const bad = await api('/api/game/action', 'POST', { target: 99, text: '探针' });
  console.log(`第${i}次 ${mine} → 提交越界动作 status=${bad.status} body=${bad.short}`);
  if (bad.status === 200) { await new Promise(r => setTimeout(r, 1200)); continue; }
  break;
}
await api('/api/game/end', 'POST'); await api('/api/room/leave', 'POST');
