// wolfweb 虚拟主机版 端到端冒烟测试：安装向导全流程 → 打一整局 → 社区/管理接口
const BASE = 'http://127.0.0.1:8899';
let cookie = '';
let token = '';
let pass = 0, fail = 0;
const fails = [];

function ok(name, cond, extra) {
  if (cond) { pass++; console.log('  ✓ ' + name + (extra ? '  ' + extra : '')); }
  else { fail++; fails.push(name); console.log('  ✗ ' + name + (extra ? '  ' + extra : '')); }
}

async function req(method, url, body, json = true) {
  const opt = { method, headers: {} };
  if (cookie) opt.headers['Cookie'] = cookie;
  if (json) {
    opt.headers['Content-Type'] = 'application/json';
    opt.headers['X-WW-Token'] = token;
    opt.body = JSON.stringify(body || {});
  }
  const r = await fetch(BASE + url, opt);
  const sc = r.headers.get('set-cookie');
  if (sc) cookie = sc.split(';')[0];
  if (!json) return { status: r.status, text: await r.text() };
  let j = null;
  try { j = await r.json(); } catch { j = { ok: 0, err: 'non-json' }; }
  return { status: r.status, j };
}
const GET = (u) => req('GET', u, null, false);
const api = async (a, body, get) => (get ? GET('/api.php?a=' + a + (body ? '&' + new URLSearchParams(body) : '')) : req('POST', '/api.php?a=' + a, body)).j ?? (await req('POST', '/api.php?a=' + a, body)).j;
const apiPost = async (a, body) => (await req('POST', '/api.php?a=' + a, body)).j;
const apiGet = async (a, qs) => { const r = await GET('/api.php?a=' + a + (qs ? '&' + new URLSearchParams(qs) : '')); try { return JSON.parse(r.text); } catch { return { ok: 0, err: 'bad json: ' + r.text.slice(0, 120) }; } };

function pick(arr) { return arr[Math.floor(Math.random() * arr.length)]; }

// —— 人类座位机器人：自动完成自己的回合 ——
async function humanBot(no, myName) {
  const s = await apiGet('gstate', { no });
  if (!s.ok || !s.game || s.game.phase === 'GAME_OVER') return false;
  const g = s.game;
  if (!g.my || !g.kind || g.cur !== s.you.seat) return true;
  const alive = g.seats.filter(x => x.alive && x.seat !== s.you.seat).map(x => x.seat);
  const kinds = {
    SHERIFF_SIGNUP: { kind: 'SHERIFF_SIGNUP', yes: 0 },
    SHERIFF_VOTE: { kind: 'SHERIFF_VOTE', target: pick(g.sheriffCandidates || [1]) },
    SPEECH: { kind: 'SPEECH', line: '我先听听大家的发言。' },
    PK_SPEECH: { kind: 'PK_SPEECH', line: '我是好人，冷静。' },
    VOTE: { kind: 'VOTE', target: pick(alive) },
    PK_VOTE: { kind: 'PK_VOTE', target: pick((g.pkList && g.pkList.length ? g.pkList : alive).filter(t => t !== s.you.seat)) },
    GUARD: { kind: 'GUARD', target: pick(alive) },
    WOLF_KILL: { kind: 'WOLF_KILL', target: pick(alive.filter(t => !g.seats.find(x => x.seat === t && x.role === '狼人')).length ? alive.filter(t => !g.seats.find(x => x.seat === t && x.role === '狼人')) : alive) },
    SEER: { kind: 'SEER', target: pick(alive) },
    CROW: { kind: 'CROW', target: pick(alive) },
    WITCH: { kind: 'WITCH', save: 0, poison: 0 },
    LAST_WORDS: { kind: 'LAST_WORDS', line: '谢谢大家。' },
    SHOOT: { kind: 'SHOOT', target: pick(alive.filter(t => !g.seats.find(x => x.seat === t && x.role === '狼人')).length ? alive.filter(t => !g.seats.find(x => x.seat === t && x.role === '狼人')) : alive) },
  };
  const act = kinds[g.kind];
  if (act) {
    const r = await apiPost('act', Object.assign({ no }, act));
    if (!r.ok) console.log('    [bot] act err:', g.kind, r.err);
  }
  return true;
}

async function main() {
  console.log('== ① 安装向导 ==');
  const page = await GET('/install.php');
  ok('install.php 页面可访问', page.status === 200 && page.text.includes('安装向导'));

  const s1 = (await req('POST', '/install.php?step=1', {})).j;
  ok('step1 环境体检', s1.ok === 1 && s1.pass === true, `php=${s1.php} os=${s1.os}`);

  const s2 = (await req('POST', '/install.php?step=2', { driver: 'sqlite' })).j;
  ok('step2 SQLite 建库建表+补丁', s2.ok === 1, s2.err || '');

  const s3 = (await req('POST', '/install.php?step=3', { name: '村长', pass: 'boss123' })).j;
  ok('step3 管理员创建', s3.ok === 1, s3.err || '');

  const s4 = (await req('POST', '/install.php?step=4', {
    name: '月夜村庄', theme: '', maxPageHeight: 90,
    f_duo: true, f_spectate: true, f_shop: true, f_forum: true, f_register: true,
  })).j;
  ok('step4 网站设定+功能开关', s4.ok === 1, s4.err || '');

  const s5 = (await req('POST', '/install.php?step=5', {})).j;
  ok('step5 完成并上锁', s5.ok === 1 && s5.key, s5.key ? 'key=' + s5.key.slice(0, 6) + '…' : '');

  const locked = await GET('/install.php');
  ok('重装防护（已上锁）', locked.text.includes('已完成安装'));

  const home = await GET('/');
  ok('首页 SPA 输出', home.status === 200 && home.text.includes('狼人杀'));

  console.log('== ② 账户与房间 ==');
  const cfg = await apiGet('cfg');
  token = cfg.token;
  ok('cfg 会话令牌', cfg.ok === 1 && !!token && cfg.me && cfg.me.isAdmin === true);

  // 站长会话：调快 AI 节奏系数（冒烟测试专用，正常部署默认 1.0）
  const sc = await apiPost('a_setting', { k: 'ai_delay_scale', v: 0.1 });
  ok('AI 节奏系数可调（运营参数）', sc.ok === 1 && Math.abs(sc.ai_delay_scale - 0.1) < 1e-6, 'scale=' + sc.ai_delay_scale);

  const reg = await apiPost('register', { name: '测试玩家', pass: 'test123' });
  ok('注册玩家', reg.ok === 1, reg.err || '');
  const cfg2 = await apiGet('cfg');
  ok('注册后自动登录', cfg2.ok === 1 && cfg2.me && cfg2.me.name === '测试玩家');

  const dup = await apiPost('register', { name: '测试玩家', pass: 'test123' });
  ok('重名注册被拒', dup.ok === 0);

  const room = await apiPost('create', { name: '冒烟测试房', board: 0 }); // 标准 9 人
  ok('建房', room.ok === 1 && room.no, 'no=' + room.no);
  const no = room.no;

  const st = await apiGet('room_state', { no });
  ok('房间状态（含板子）', st.ok === 1 && st.room.players.length === 1 && st.room.board.players === 9);

  const ai = await apiPost('addai');
  ok('房主添加 AI 座位', ai.ok === 1, ai.err || '');
  const st2 = await apiGet('room_state', { no });
  ok('AI 座位出现', st2.room.players.some(p => p.ai === true));

  console.log('== ③ 开局与对局（含懒调度 tick） ==');
  const start = await apiPost('start');
  ok('开局（自动补 AI 至 9 人）', start.ok === 1, start.err || '');
  const g0 = await apiGet('gstate', { no });
  ok('gstate 初始视图', g0.ok === 1 && g0.game && g0.game.seats.length === 9);
  ok('AI 座位补齐', g0.game.seats.filter(x => x.ai).length === 8);

  // 轮询推进整局（人类 bot 自动行动）
  let over = false, steps = 0, lastDay = 0, sawSpeech = false, sawVote = false, sawNight = false;
  const t0 = Date.now();
  while (steps < 6000 && Date.now() - t0 < 900000) {
    await new Promise(r => setTimeout(r, 120));
    await humanBot(no, '测试玩家');
    const s = await apiGet('gstate', { no });
    if (!s.ok || !s.game) continue;
    if (s.game.day > lastDay) { lastDay = s.game.day; }
    const types = s.game.events.map(e => e.type);
    if (types.includes('SPEECH')) sawSpeech = true;
    if (types.includes('VOTE_RESULT')) sawVote = true;
    if (types.includes('NARR') || types.includes('DAWN')) sawNight = true;
    if (s.game.phase === 'GAME_OVER') { over = true; break; }
    steps++;
  }
  ok('对局正常打完', over, `轮询 ${steps} 次，第 ${lastDay} 天`);
  if (!over) {
    const g = await apiGet('gstate', { no });
    console.log('    [debug] phase=', g.game && g.game.phase, 'day=', g.game && g.game.day, 'kind=', g.game && g.game.kind, 'cur=', g.game && g.game.cur);
    const dbg = await GET('/api.php?a=cfg');
  }
  ok('有发言事件', sawSpeech);
  ok('有投票结算', sawVote);
  ok('有旁白/昼夜', sawNight);

  const gEnd = await apiGet('gstate', { no });
  const winner = gEnd.game && gEnd.game.winner;
  ok('结算有胜负', winner === 'good' || winner === 'wolf', 'winner=' + winner);
  const rolesAll = gEnd.game && gEnd.game.seats.every(x => x.role); // 结算全翻牌
  ok('结算全翻牌', rolesAll === true);

  const me = await apiGet('me');
  ok('战绩入库（games+1）', me.ok === 1 && me.me.games >= 1, `games=${me.me.games}`);

  const evs = await GET('/api.php?a=gstate&no=' + no);
  console.log('    事件总数：', JSON.parse(evs.text).game.events.length);

  console.log('== ④ 社区 ==');
  const fa = await apiPost('friend_add', { name: '村长' });
  ok('添加好友', fa.ok === 1, fa.err || '');
  const fm = await apiPost('fmsg_send', { to: 1, text: '村长你好' });
  ok('发私聊', fm.ok === 1, fm.err || '');
  const fp = await apiPost('forum_post', { title: '冒烟测试帖', text: '大家好' });
  ok('发帖', fp.ok === 1, fp.err || '');
  const rank = await apiGet('rank');
  ok('排行榜', rank.ok === 1 && rank.rank.length >= 1);
  const vip = await apiPost('vip_buy', { plan: 'month' });
  ok('VIP 开通', vip.ok === 1, vip.err || '');
  const cl = await apiGet('changelog');
  ok('更新日志', cl.ok === 1 && cl.logs.length >= 3);

  console.log('== ⑤ 管理后台 ==');
  await apiPost('logout');
  const lg = await apiPost('login', { name: '村长', pass: 'boss123' });
  ok('切回管理员会话', lg.ok === 1, lg.err || '');
  const ov = await apiGet('a_overview');
  ok('概览', ov.ok === 1 && ov.stats.users >= 2);
  const feat = await apiGet('a_features');
  ok('功能开关读取', feat.ok === 1 && typeof feat.features.forum === 'boolean');
  const featOff = await apiPost('a_features', { features: Object.assign({}, feat.features, { forum: false }) });
  ok('关闭论坛开关', featOff.ok === 1);
  const forumBlocked = await apiPost('forum_post', { title: 'x', text: 'y' });
  ok('服务端拒绝被关功能', forumBlocked.ok === 0);
  await apiPost('a_features', { features: Object.assign({}, feat.features, { forum: true }) });
  const disp = await apiPost('a_display', { maxPageHeight: 85, name: '月夜村庄' });
  ok('显示配置（页面最大高度）', disp.ok === 1 && disp.site.max_page_height === 85);
  const bk = await apiPost('a_backup');
  ok('一键备份', bk.ok === 1 && bk.file, bk.file || '');
  const bdl = await GET('/api.php?a=a_backup_dl&id=1');
  ok('备份下载', bdl.status === 200 && bdl.text.includes('INSERT INTO'));
  const upd = await apiGet('a_update');
  ok('更新检查（补丁已随安装应用）', upd.ok === 1 && upd.pending.length === 0);
  const pl = await apiGet('a_plugins');
  ok('插件列表（3 个内置）', pl.ok === 1 && pl.plugins.length === 3, pl.plugins.map(p => p.name).join('/'));
  const plOn = await apiPost('a_plugins', { name: 'battle_report', on: true });
  ok('启用 battle_report 插件', plOn.ok === 1);
  const rooms = await apiGet('a_rooms');
  ok('房间管理', rooms.ok === 1 && rooms.rooms.length >= 1);

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
  if (fails.length) { console.log('失败项：', fails.join(' | ')); process.exit(1); }
}

main().catch(e => { console.error('测试脚本异常:', e); process.exit(2); });
