// 并发一致性 + 双人组队链路 + 输入截断
const BASE = 'http://127.0.0.1:8899';
let pass = 0, fail = 0; const fails = [];
const ok = (n, c, x) => { c ? (pass++, console.log('  ✓ ' + n + (x ? '  ' + x : ''))) : (fail++, fails.push(n), console.log('  ✗ ' + n + (x ? '  ' + x : ''))); };
function jar() { return { cookie: '', token: '' }; }
async function req(J, m, u, b) {
  const h = {}; if (J.cookie) h.Cookie = J.cookie;
  if (m === 'POST') { h['Content-Type'] = 'application/json'; if (J.token) h['X-WW-Token'] = J.token; }
  const r = await fetch(BASE + u, { method: m, headers: h, body: m === 'POST' ? JSON.stringify(b || {}) : undefined });
  const sc = r.headers.get('set-cookie'); if (sc) J.cookie = sc.split(';')[0];
  let j = null; try { j = await r.json(); } catch {}
  return { status: r.status, j };
}
async function login(name, pw) {
  const J = jar(); await req(J, 'GET', '/api.php?a=cfg');
  let r = await req(J, 'POST', '/api.php?a=login', { name, pass: pw });
  if (!r.j || !r.j.ok) await req(J, 'POST', '/api.php?a=register', { name, pass: pw });
  J.token = (await req(J, 'GET', '/api.php?a=cfg')).j.token;
  J.me = (await req(J, 'GET', '/api.php?a=cfg')).j.me;
  return J;
}

async function main() {
  const admin = await login('村长', 'boss123');
  await req(admin, 'POST', '/api.php?a=a_setting', { k: 'ai_delay_scale', v: 0.1 });
  const alice = await login('并发甲', 'aa123456');
  const bob = await login('组队乙', 'bb123456');

  // —— 双人组队完整链路 ——
  const r1 = (await req(alice, 'POST', '/api.php?a=create', { name: '组队房', board: 0 })).j;
  ok('甲建房', r1.ok === 1);
  const inv = await req(alice, 'POST', '/api.php?a=duo_invite', { to: bob.me.id });
  ok('甲发起组队邀请', inv.j.ok === 1);
  await new Promise(r => setTimeout(r, 300));
  const pend = (await req(bob, 'GET', '/api.php?a=duo_pending')).j;
  ok('乙能看到待处理邀请', pend.ok === 1 && pend.invites.length >= 1 && pend.invites[0].from === '并发甲', 'invites=' + pend.invites.length);
  const acc = await req(bob, 'POST', '/api.php?a=duo_accept', { id: pend.invites[0].id });
  ok('乙接受邀请并自动进房', acc.j.ok === 1 && acc.j.no === r1.no);
  const rs = (await req(alice, 'GET', '/api.php?a=room_state&no=' + r1.no)).j;
  ok('房间内两人到位', rs.room.players.filter(p => !p.ai).length === 2, '真人=' + rs.room.players.filter(p => !p.ai).length);
  const pend2 = (await req(bob, 'GET', '/api.php?a=duo_pending')).j;
  ok('已处理邀请不再重复出现（handled 清理）', pend2.invites.length === 0);
  const again = (await req(alice, 'POST', '/api.php?a=duo_invite', { to: bob.me.id })).j;
  ok('可再次邀请（不被吞）', again.ok === 1);
  await req(bob, 'POST', '/api.php?a=duo_decline', { id: (await req(bob, 'GET', '/api.php?a=duo_pending')).j.invites[0].id });

  // —— 并发一致性：开局后多路并发轮询（含真人回合自动出招，制造真实写压力）——
  await req(alice, 'POST', '/api.php?a=addai');
  const st0 = (await req(alice, 'GET', '/api.php?a=start')).j;
  ok('开局', st0.ok === 1);
  const no = r1.no;
  const pick = a => a[Math.floor(Math.random() * a.length)];
  async function botAct(J) {
    const s = await req(J, 'GET', '/api.php?a=gstate&no=' + no);
    if (!s.j || !s.j.ok || !s.j.game) return s;
    const g = s.j.game, me = s.j.you.seat;
    if (g.phase === 'GAME_OVER' || g.cur !== me || !g.kind) return s;
    const alive = g.seats.filter(x => x.alive && x.seat !== me).map(x => x.seat);
    if (!alive.length) return s;
    const kinds = {
      SHERIFF_SIGNUP: { kind: 'SHERIFF_SIGNUP', yes: 0 },
      SHERIFF_VOTE: { kind: 'SHERIFF_VOTE', target: pick(g.sheriffCandidates.length ? g.sheriffCandidates : alive) },
      SPEECH: { kind: 'SPEECH', line: '我先听大家。' },
      PK_SPEECH: { kind: 'PK_SPEECH', line: '我是好人。' },
      VOTE: { kind: 'VOTE', target: pick(alive) },
      PK_VOTE: { kind: 'PK_VOTE', target: pick((g.pkList && g.pkList.length ? g.pkList : alive).filter(t => t !== me)) },
      GUARD: { kind: 'GUARD', target: pick(alive) }, WOLF_KILL: { kind: 'WOLF_KILL', target: pick(alive) },
      SEER: { kind: 'SEER', target: pick(alive) }, CROW: { kind: 'CROW', target: pick(alive) },
      SILENCER: { kind: 'SILENCER', target: pick(alive) },
      WITCH: { kind: 'WITCH', save: 0, poison: 0 }, LAST_WORDS: { kind: 'LAST_WORDS', line: '再见。' },
      SHOOT: { kind: 'SHOOT', target: pick(alive) },
    };
    if (kinds[g.kind]) await req(J, 'POST', '/api.php?a=act', Object.assign({ no }, kinds[g.kind]));
    return s;
  }
  let corrupt = 0, errs = 0, acts = 0, over = false, maxSeq = 0;
  for (let round = 0; round < 400 && !over; round++) {
    const calls = [];
    for (let i = 0; i < 8; i++) { const J = i % 2 ? alice : bob; calls.push(i < 2 ? botAct(J) : req(J, 'GET', '/api.php?a=gstate&no=' + no)); }
    const res = await Promise.all(calls);
    acts += 2;
    for (const r of res) {
      if (!r.j || !r.j.ok) { errs++; continue; }
      const seats = r.j.game.seats;
      if (new Set(seats.map(s => s.seat)).size !== seats.length) corrupt++;
      if (seats.some(s => !s.name)) corrupt++;
      maxSeq = Math.max(maxSeq, r.j.game.events.length ? r.j.game.events[r.j.game.events.length - 1].seq : 0);
      if (r.j.game.phase === 'GAME_OVER') over = true;
    }
    await new Promise(r => setTimeout(r, 120));
  }
  ok('并发+真人行动无接口错误', errs === 0, 'errs=' + errs);
  ok('并发下无座位腐坏', corrupt === 0, 'corrupt=' + corrupt);
  ok('并发压力下对局可完整打完（写路径无死锁/无丢步）', over === true, `轮次内结束=${over} 最大seq=${maxSeq}`);
  const fin = (await req(alice, 'GET', '/api.php?a=gstate&no=' + no)).j;
  const seqs = fin.game.events.map(e => e.seq);
  ok('事件 seq 严格递增（无重复/乱序写入）', seqs.every((v, i) => i === 0 || v > seqs[i - 1]), 'n=' + seqs.length);
  const aliveN = fin.game.seats.filter(s => s.alive).length;
  ok('存活人数合理（并发未复活/未超杀）', aliveN >= 1 && aliveN <= 9, 'alive=' + aliveN);
  const dupVote = await req(alice, 'POST', '/api.php?a=act', { no, kind: 'VOTE', target: aliveN > 1 ? pick(fin.game.seats.filter(s => s.alive && s.seat !== fin.you.seat).map(s => s.seat)) : 0 });
  ok('非投票回合的重复投票被拒', dupVote.status === 400, 'http=' + dupVote.status);

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
  if (fails.length) console.log('失败项：', fails.join(' | '));
  process.exit(fail ? 1 : 0);
}
main().catch(e => { console.error('异常', e); process.exit(2); });
