// 线上实例实测：健康检查 → 进入对局 → 人类座位行动 → 打到结算
// 目标站点用环境变量 WW_BASE 指定（如 set WW_BASE=https://your-host:11111），默认本机
const BASE = process.env.WW_BASE || 'https://localhost:11111';
let pass = 0, fail = 0; const fails = [];
const ok = (n, c, x) => { c ? (pass++, console.log('  ✓ ' + n + (x ? '  ' + x : ''))) : (fail++, fails.push(n), console.log('  ✗ ' + n + (x ? '  ' + x : ''))); };
const pick = a => a[Math.floor(Math.random() * a.length)];

async function j(url, opt) {
  const r = await fetch(BASE + url, opt);
  let b = null; try { b = await r.json(); } catch { b = { ok: 0, err: 'non-json http=' + r.status }; }
  return b;
}
const post = (url, body) => j(url, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
const get = (url) => j(url);

async function main() {
  console.log('== 线上演示页实测 ' + BASE + ' ==');
  const h = await get('/api/health');
  ok('健康检查 /api/health', h.ok === 1, 'version=' + h.version + ' rooms=' + h.rooms + ' persisted=' + h.persisted);

  const page = await fetch(BASE + '/');
  const html = await page.text();
  ok('首页可访问', page.status === 200 && html.includes('狼人杀'));
  const css = await fetch(BASE + '/css/demo.css');
  ok('静态资源 css 可加载', css.status === 200);
  const js = await fetch(BASE + '/js/demo.js');
  ok('静态资源 js 可加载', js.status === 200);
  const th = await fetch(BASE + '/css/themes.css');
  ok('主题表可加载', th.status === 200);

  const join = await post('/api/join', { name: '实测员' });
  ok('进入演示局', join.ok === 1 && !!join.room, 'room=' + join.room + ' seat=' + join.seat);
  const room = join.room;

  let over = false, steps = 0, sawNight = false, sawDay = false, sawSpeech = false, sawVote = false, myActs = 0, lastDay = 0;
  const t0 = Date.now();
  while (steps < 900 && Date.now() - t0 < 180000) {
    const s = await get('/api/state?room=' + room);
    if (!s.ok || !s.game) { await new Promise(r => setTimeout(r, 300)); continue; }
    const g = s.game;
    if (g.phase === 'NIGHT') sawNight = true;
    if (g.phase === 'DAY') sawDay = true;
    const types = g.events.map(e => e.type);
    if (types.includes('SPEECH')) sawSpeech = true;
    if (types.includes('VOTE_RESULT')) sawVote = true;
    if (g.day > lastDay) lastDay = g.day;
    if (g.phase === 'GAME_OVER') { over = true; break; }
    // 轮到我 → 行动
    if (g.cur === 1 && g.kind && g.my) {
      const alive = g.seats.filter(x => x.alive && x.seat !== 1).map(x => x.seat);
      let act = null;
      if (g.kind === 'SPEECH') act = { kind: 'SPEECH', line: '我好人，先听后置位。' };
      else if (g.kind === 'VOTE' || g.kind === 'PK_VOTE') act = { kind: g.kind, target: pick(alive) };
      else if (['GUARD', 'WOLF_KILL', 'SEER'].includes(g.kind)) act = { kind: g.kind, target: pick(alive) };
      if (act) {
        const a = await post('/api/act', { room, ...act });
        if (a.ok) myActs++; else console.log('    [act]', g.kind, a.err);
      }
    }
    await new Promise(r => setTimeout(r, 350));
    steps++;
  }
  ok('对局可打到结算', over, `轮询 ${steps} 次，第 ${lastDay} 天`);
  ok('有黑夜阶段', sawNight);
  ok('有白天阶段', sawDay);
  ok('有发言', sawSpeech);
  ok('有投票结算', sawVote);
  ok('人类座位可行动', myActs > 0, `出招 ${myActs} 次`);

  const fin = await get('/api/state?room=' + room);
  const seats = fin.game ? fin.game.seats : [];
  ok('结算全翻牌', seats.length === 6 && seats.every(x => x.role), seats.map(x => x.role).join('/'));
  ok('结算有胜负', ['good', 'wolf'].includes(fin.game && fin.game.winner), 'winner=' + (fin.game && fin.game.winner));

  const res = await get('/api/results');
  ok('战绩接口可用', res.ok === 1, 'persisted=' + res.persisted + '（当前套餐无数据库→内存模式）');

  const bad = await get('/api/state?room=ZZZZZZ');
  ok('无效房间返回错误而非崩溃', bad.ok === 0);

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
  if (fails.length) console.log('失败项：', fails.join(' | '));
  process.exit(fail ? 1 : 0);
}
main().catch(e => { console.error('脚本异常', e); process.exit(2); });
