// 长发言 REST 直测：绕开浏览器，验证服务端是否完整记录 >320 字的发言
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
const ORIGIN = 'https://localhost:11111';
const sleep = ms => new Promise(r => setTimeout(r, ms));
let TOKEN = '';
async function api(p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: b ? JSON.stringify(b) : null });
  const t = await r.text();
  let j; try { j = JSON.parse(t); } catch { j = t; }
  return { status: r.status, j };
}

async function main() {
  const u = 'ls_' + Date.now().toString(36).slice(-5);
  let r = await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '长发言探针' });
  TOKEN = r.j.token;
  console.log('注册:', r.status, TOKEN ? 'token ok' : JSON.stringify(r.j).slice(0, 120));

  r = await api('/api/room/create', 'POST', {});
  const view0 = r.j.view || r.j;
  console.log('建房:', r.status, 'roomNo=', view0.roomNo || view0.no, 'max=', view0.maxPlayers || view0.max);
  // 房间越小、真人发言回合来得越快（AI 由真实 LLM+音频闸门驱动，一整轮白天发言会很久）
  for (let i = 0; i < 5; i++) { const x = await api('/api/room/add-ai', 'POST', {}); if (x.status !== 200 || (x.j && x.j.error)) { console.log('补 AI 停止于', i, JSON.stringify(x.j).slice(0, 80)); break; } await sleep(120); }
  r = await api('/api/game/start', 'POST', {});
  console.log('开局:', r.status, JSON.stringify(r.j).slice(0, 100));

  // 等到我的 SPEECH 回合（真实字段：mySeat / actionKind / currentActor / phase）
  // 注意：AI 由真实 LLM 驱动，一整轮夜+警长竞选可能耗时数分钟，等待窗口要足够长。
  let mySeat = 0, got = null, lastLine = '';
  for (let i = 0; i < 1200; i++) {
    // 注意：api() 返回 {status, j}，对局状态在 .j 里（此前直接读 v.phase 永远为空 → 误报“没等到我的回合”）
    const resp = await api('/api/game/state');
    const v = resp && resp.j;
    if (!v || !v.phase) { await sleep(500); continue; }
    mySeat = v.mySeat || 0;
    const line = v.phase + ' kind=' + v.actionKind + ' actor=' + v.currentActor + ' myTurn=' + v.myTurn + ' day=' + v.day;
    if (line !== lastLine) { console.log('  ' + String(Math.round(i * 0.5)) + 's  ' + line); lastLine = line; }
    if (v.myTurn && /SPEAK|LAST_WORDS|PK/.test(v.phase || '')) { got = v; break; }
    await sleep(500);
  }
  if (!got) { console.log('!! 没等到我的发言回合'); process.exit(1); }
  console.log('轮到我了: seat=' + mySeat + ' kind=' + got.actionKind + ' phase=' + got.phase);

  const LONG = '大家好，我来说说我的完整思路。'.repeat(24) + '以上就是我全部的判断，谢谢大家。';
  const sub = await api('/api/game/action', 'POST', { text: LONG });
  console.log('提交长发言:', sub.status, '长度=' + LONG.length, JSON.stringify(sub.j).slice(0, 120));

  await sleep(1200);
  const v2resp = await api('/api/game/state');
  const v2 = (v2resp && v2resp.j) || {};
  const feed = v2.feed || [];
  const speeches = feed.filter(e => e.type === 'SPEECH');
  console.log('feed 总条数=' + feed.length, 'SPEECH 条数=' + speeches.length);
  for (const e of speeches.slice(0, 6)) console.log('   seat=' + e.actor + ' len=' + (e.detail || '').length + ' 头=' + (e.detail || '').slice(0, 24));
  const mine = speeches.find(e => e.actor === mySeat);
  console.log('\n== 结论 ==');
  console.log('我的发言是否入 feed:', !!mine, mine ? '记录长度=' + mine.detail.length : '');
  if (mine) console.log('全文保留:', mine.detail.length >= LONG.length ? 'PASS' : 'FAIL（被截断到 ' + mine.detail.length + '）');
  const types = {};
  feed.forEach(e => types[e.type] = (types[e.type] || 0) + 1);
  console.log('feed 类型分布:', JSON.stringify(types));
}
main().catch(e => { console.error('异常', e.message); process.exit(2); });
