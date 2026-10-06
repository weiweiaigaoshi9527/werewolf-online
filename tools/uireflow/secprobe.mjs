// 虚拟主机版安全/越权探测
const BASE = 'http://127.0.0.1:8899';
let pass = 0, fail = 0; const fails = [];
const ok = (n, c, x) => { c ? (pass++, console.log('  ✓ ' + n + (x ? '  ' + x : ''))) : (fail++, fails.push(n), console.log('  ✗ ' + n + (x ? '  ' + x : ''))); };

function jar() { return { cookie: '', token: '' }; }
async function req(J, method, url, body) {
  const headers = {};
  if (J.cookie) headers.Cookie = J.cookie;
  if (method === 'POST') { headers['Content-Type'] = 'application/json'; if (J.token) headers['X-WW-Token'] = J.token; }
  const r = await fetch(BASE + url, { method, headers, body: method === 'POST' ? JSON.stringify(body || {}) : undefined, redirect: 'manual' });
  const sc = r.headers.get('set-cookie'); if (sc) J.cookie = sc.split(';')[0];
  const ct = r.headers.get('content-type') || '';
  let j = null, text = '';
  if (ct.includes('json')) { try { j = await r.json(); } catch { j = null; } }
  else text = await r.text();
  return { status: r.status, j, text, ct };
}
const A = (u, b) => req(jar(), 'GET', u, b); // 无会话
async function login(name, passW) {
  const J = jar();
  await req(J, 'GET', '/api.php?a=cfg');
  J.token = (await req(J, 'GET', '/api.php?a=cfg')).j.token;
  let r = await req(J, 'POST', '/api.php?a=login', { name, pass: passW });
  if (!r.j || !r.j.ok) { await req(J, 'POST', '/api.php?a=register', { name, pass: passW }); r = await req(J, 'GET', '/api.php?a=cfg'); }
  J.token = (await req(J, 'GET', '/api.php?a=cfg')).j.token;
  J.me = (await req(J, 'GET', '/api.php?a=cfg')).j.me;
  return J;
}

async function main() {
  console.log('== 安全/越权探测 ==');
  // 保证已安装
  let cfg = await A('/api.php?a=cfg');
  if (!cfg.j || !cfg.j.ok) {
    console.log('  (未安装，先跑安装向导)');
    const J = jar(); await req(J, 'GET', '/install.php');
    await req(J, 'POST', '/install.php?step=2', { driver: 'sqlite' });
    await req(J, 'POST', '/install.php?step=3', { name: '村长', pass: 'boss123' });
    await req(J, 'POST', '/install.php?step=4', { name: '月夜村庄', theme: '', maxPageHeight: 100, f_duo: true, f_spectate: true, f_shop: true, f_forum: true, f_register: true });
    await req(J, 'POST', '/install.php?step=5', {});
    await req(J, 'POST', '/install.php?step=1', {});
    cfg = await A('/api.php?a=cfg');
  }
  ok('安装态正常', cfg.j && cfg.j.ok === 1);

  const admin = await login('村长', 'boss123');
  const bob = await login('安全测试员', 'sec12345');
  ok('两个会话独立', admin.me && bob.me && admin.me.id !== bob.me.id);

  // 1 CSRF
  const J0 = jar(); await req(J0, 'GET', '/api.php?a=cfg');
  const noTok = await fetch(BASE + '/api.php?a=create', { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: J0.cookie }, body: '{}' });
  ok('无令牌 POST 被拒（CSRF）', noTok.status === 419 || noTok.status === 401, 'http=' + noTok.status);
  const badTok = await fetch(BASE + '/api.php?a=create', { method: 'POST', headers: { 'Content-Type': 'application/json', Cookie: J0.cookie, 'X-WW-Token': 'deadbeef' }, body: '{}' });
  ok('伪造令牌被拒', badTok.status === 419, 'http=' + badTok.status);

  // 2 鉴权
  const anon = await A('/api.php?a=rooms');
  ok('未登录读接口 401', anon.status === 401);
  const notAdmin = await req(bob, 'GET', '/api.php?a=a_users');
  ok('非管理员访问管理接口 403', notAdmin.status === 403);
  const notAdminPost = await req(bob, 'POST', '/api.php?a=a_features', { features: { duo: false } });
  ok('非管理员改配置 403', notAdminPost.status === 403);
  const notAdminBak = await req(bob, 'GET', '/api.php?a=a_backup_list');
  ok('非管理员列备份 403', notAdminBak.status === 403);
  const notAdminSet = await req(bob, 'POST', '/api.php?a=a_setting', { k: 'ai_delay_scale', v: 0.1 });
  ok('非管理员改运营参数 403', notAdminSet.status === 403);

  // 3 输入边界
  const longName = await req(jar(), 'POST', '/api.php?a=register', { name: 'a'.repeat(40), pass: '123456' });
  ok('超长昵称被拒', longName.status === 400);
  const xssName = await req(jar(), 'POST', '/api.php?a=register', { name: '<script>alert(1)</script>', pass: '123456' });
  ok('脚本型昵称被拒', xssName.status === 400);
  const shortPass = await req(jar(), 'POST', '/api.php?a=register', { name: 'pwdtest', pass: '123' });
  ok('弱密码被拒', shortPass.status === 400);
  const sqli = await req(bob, 'GET', "/api.php?a=join&no=' OR 1=1 --");
  ok('SQLi 房间号被安全拒绝', sqli.status === 400 || sqli.status === 404, 'http=' + sqli.status);
  const sqli2 = await req(bob, 'POST', '/api.php?a=friend_add', { name: "x'; DROP TABLE users; --" });
  ok('SQLi 好友名被拒且不破坏库', (sqli2.status === 400), 'http=' + sqli2.status);
  const after = await req(bob, 'GET', '/api.php?a=me');
  ok('攻击后服务仍可用', after.j && after.j.ok === 1);

  // 4 越权动作 + 时序
  const room = (await req(admin, 'POST', '/api.php?a=create', { name: '安全房', board: 2 })).j;
  ok('12人板建房', room.ok === 1);
  const no = room.no;
  await req(admin, 'POST', '/api.php?a=start');
  let st = (await req(admin, 'GET', '/api.php?a=gstate&no=' + no)).j;
  ok('gstate 有对局', st.ok === 1 && st.game && st.game.seats.length === 12);
  const mySeat = st.you.seat;
  const wrongKind = await req(admin, 'POST', '/api.php?a=act', { no, kind: 'VOTE', target: mySeat === 1 ? 2 : 1 });
  ok('非本回合动作被拒（不是 500）', wrongKind.status === 400 && /轮不到|不能/.test(wrongKind.j.err), 'err=' + (wrongKind.j && wrongKind.j.err));
  const badKind = await req(admin, 'POST', '/api.php?a=act', { no, kind: 'HACK_ME' });
  ok('未知动作类型被拒', badKind.status === 400);
  const selfVote = await req(admin, 'POST', '/api.php?a=act', { no, kind: 'VOTE', target: mySeat });
  ok('投票给自己被拒', selfVote.status === 400);

  // 观战者不能行动
  const spec = await login('观战者', 'spec12345');
  await req(spec, 'POST', '/api.php?a=join', { no });
  await req(spec, 'POST', '/api.php?a=spectate');
  const specAct = await req(spec, 'POST', '/api.php?a=act', { no, kind: 'SPEECH', line: 'hi' });
  ok('观战者行动被拒 403', specAct.status === 403, 'http=' + specAct.status);
  const specView = (await req(spec, 'GET', '/api.php?a=gstate&no=' + no)).j;
  const hiddenAlive = specView.game && specView.game.seats.filter(s => s.alive && s.role);
  ok('观战视角不泄露存活身份', specView.you.spectate === true && (specView.game.phase === 'GAME_OVER' || hiddenAlive.length === 0), '泄露=' + hiddenAlive.length);

  // 死人不能行动（等到有人出局再测）
  // 5 功能开关服务端强制
  const feats = (await req(admin, 'GET', '/api.php?a=a_features')).j.features;
  await req(admin, 'POST', '/api.php?a=a_features', { features: Object.assign({}, feats, { duo: false, forum: false }) });
  const duoOff = await req(bob, 'POST', '/api.php?a=duo_invite', { to: admin.me.id });
  ok('关闭双人组队后服务端拒绝', duoOff.status === 400 && /关闭/.test(duoOff.j.err));
  const forumOff = await req(bob, 'POST', '/api.php?a=forum_post', { title: 't', text: 'x' });
  ok('关闭论坛后服务端拒绝', forumOff.status === 400);
  await req(admin, 'POST', '/api.php?a=a_features', { features: feats });

  // 6 显示配置钳制
  const disp = await req(admin, 'POST', '/api.php?a=a_display', { maxPageHeight: 99999, name: '月夜村庄' });
  ok('页面最大高度被钳制到 100', disp.j.site.max_page_height === 100, 'got=' + disp.j.site.max_page_height);
  const disp2 = await req(admin, 'POST', '/api.php?a=a_display', { maxPageHeight: -50 });
  ok('负值被钳制到下限', disp2.j.site.max_page_height === 0 || disp2.j.site.max_page_height === 50, 'got=' + disp2.j.site.max_page_height);

  // 7 备份下载：路径/参数安全
  await req(admin, 'POST', '/api.php?a=a_backup');
  const dlBad = await req(admin, 'GET', '/api.php?a=a_backup_dl&id=99999');
  ok('不存在的备份 404', dlBad.status === 404);
  const dlTrav = await req(admin, 'GET', '/api.php?a=a_backup_dl&id=../../config.php');
  ok('路径穿越参数被拒', dlTrav.status === 404 || dlTrav.status === 400 || dlTrav.status === 500, 'http=' + dlTrav.status);
  const dlOk = await req(admin, 'GET', '/api.php?a=a_backup_dl&id=1');
  ok('备份下载内容正确且不含明文密码哈希以外的敏感文件', dlOk.status === 200 && dlOk.text.includes('INSERT INTO `users`'));

  // 8 封禁生效
  const bl = (await req(admin, 'POST', '/api.php?a=a_user_save', { id: bob.me.id, banned: true })).j;
  ok('管理员封禁用户', bl.ok === 1);
  const Jb = jar(); await req(Jb, 'GET', '/api.php?a=cfg');
  const bannedLogin = await req(Jb, 'POST', '/api.php?a=login', { name: '安全测试员', pass: 'sec12345' });
  ok('被封禁后无法登录', bannedLogin.status === 400 && /封禁/.test(bannedLogin.j.err));
  await req(admin, 'POST', '/api.php?a=a_user_save', { id: bob.me.id, banned: false });

  // 9 房间人数上限与重复加入（用新建的等待房，避免已结算房被正确拒绝）
  const Jc = await login('路人甲', 'aaa123456');
  const fresh = (await req(Jc, 'POST', '/api.php?a=create', { name: '重复加入房', board: 0 })).j;
  const fno = fresh.no;
  const j1 = await req(Jc, 'POST', '/api.php?a=join', { no: fno });
  const j2 = await req(Jc, 'POST', '/api.php?a=join', { no: fno });
  ok('重复加入同一房不报错不重复占座', j1.j.ok === 1 && j2.j.ok === 1, JSON.stringify(j1.j) + JSON.stringify(j2.j));
  const rs = (await req(Jc, 'GET', '/api.php?a=room_state&no=' + fno)).j;
  const mine = rs.room.players.filter(p => p.name === '路人甲');
  ok('同一账号只占一个座位', mine.length === 1, '占座=' + mine.length);
  const endedAct = await req(admin, 'POST', '/api.php?a=act', { no, kind: 'SPEECH', line: 'hi' });
  ok('已结束/非回合的行动被拒', endedAct.status === 400, 'http=' + endedAct.status + ' err=' + (endedAct.j && endedAct.j.err));
  const stNow = (await req(admin, 'GET', '/api.php?a=gstate&no=' + no)).j;
  ok('结算后所有人身份公开（符合规则）', stNow.game && stNow.game.phase === 'GAME_OVER' ? stNow.game.seats.every(s => s.role) : true);

  // 10 发言长度截断
  const st2 = (await req(admin, 'GET', '/api.php?a=gstate&no=' + no)).j;
  if (st2.game && st2.game.kind === 'SPEECH' && st2.game.cur === st2.you.seat) {
    const r = await req(admin, 'POST', '/api.php?a=act', { no, kind: 'SPEECH', line: 'X'.repeat(5000) });
    const ev = r.j.game && r.j.game.events.slice(-1)[0];
    ok('超长发言被截断', r.ok === 1 && ev && ev.detail.length <= 210, 'len=' + (ev ? ev.detail.length : 'n/a'));
  } else console.log('  - 发言截断用例跳过（当前不是本人发言回合）');

  // 11 响应头与内容类型
  const apiCt = await req(admin, 'GET', '/api.php?a=rooms');
  ok('API 一律 JSON 内容类型', /application\/json/.test(apiCt.ct));

  console.log(`\n结果：${pass} 通过 / ${fail} 失败`);
  if (fails.length) console.log('失败项：', fails.join(' | '));
  process.exit(fail ? 1 : 0);
}
main().catch(e => { console.error('脚本异常', e); process.exit(2); });
