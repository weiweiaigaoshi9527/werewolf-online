/* 演示页逻辑：进入 → 轮询 → 行动 */
'use strict';
const $ = s => document.querySelector(s);
const esc = s => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const ROOMS = { werewolf: '狼人', seer: '预言家', guard: '守卫', villager: '村民' };

let room = sessionStorage.getItem('ww_demo_room') || '';
let timer = null;

function toast(m) { const t = $('#toast'); t.textContent = m; t.style.display = 'block'; clearTimeout(toast._t); toast._t = setTimeout(() => t.style.display = 'none', 2400); }
async function api(path, body, get) {
  const r = await fetch('/api/' + path, get
    ? { method: 'GET' }
    : { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body || {}) });
  return r.json();
}

async function join() {
  const name = $('#name').value.trim() || '游客';
  const j = await api('join', { name });
  if (!j.ok) return toast(j.err || '进入失败');
  room = j.room;
  sessionStorage.setItem('ww_demo_room', room);
  $('#join-pane').classList.add('hidden');
  $('#game-pane').classList.remove('hidden');
  poll();
  timer = setInterval(poll, 1200);
}

async function poll() {
  const j = await api('state?room=' + encodeURIComponent(room), null, true);
  if (!j.ok) { toast(j.err || '对局已过期'); clearInterval(timer); room = ''; sessionStorage.removeItem('ww_demo_room'); return reset(); }
  render(j.game);
}

function render(g) {
  $('#g-phase').textContent = { NIGHT: '黑夜', DAY: '白天', GAME_OVER: '结算' }[g.phase] || g.phase;
  $('#g-day').textContent = g.day ? ('第 ' + g.day + ' 天') : '';
  $('#g-hint').textContent = g.phase === 'GAME_OVER' ? '' : (g.cur === 1 ? '轮到你行动' : (g.cur ? g.cur + '号（AI）思考中…' : ''));
  const wl = $('#g-winline');
  if (g.phase === 'GAME_OVER' && g.winner) {
    wl.innerHTML = '<div class="winline ' + (g.winner === 'good' ? 'good' : 'wolf') + '">' +
      (g.winner === 'good' ? '🌅 好人阵营胜利！' : '🐺 狼人阵营胜利！') + '</div>' +
      '<div class="row" style="justify-content:center"><button class="btn gold" onclick="reset()">再来一局</button> <a class="btn" href="#results" onclick="showResults()">查看战绩</a></div>';
    clearInterval(timer);
  } else wl.innerHTML = '';
  const feed = $('#g-feed');
  const stick = feed.scrollHeight - feed.scrollTop <= feed.clientHeight + 40;
  feed.innerHTML = g.events.slice(-60).map(e =>
    '<div class="fitem ' + (e.type === 'NARR' ? 'narr' : (['SPEECH', 'VOTE_RESULT'].indexOf(e.type) >= 0 ? '' : 'sys')) + '">' + esc(e.detail) + '</div>').join('');
  if (stick) feed.scrollTop = feed.scrollHeight;
  $('#g-myrole').innerHTML = '<div><div class="muted">你的身份（1号）</div><div class="role-name">' + esc((g.my && g.my.role) || '？') + '</div></div>' +
    '<div class="grow"></div>' + (g.my && g.my.role === '守卫' ? '<span class="tag">上轮守护：' + (g.my.guardLast || '空') + '号</span>' : '');
  $('#g-seats').innerHTML = g.seats.map(s =>
    '<div class="seat gseat' + (s.alive ? '' : ' dead') + (sel === s.seat ? ' sel' : '') + '" data-seat="' + s.seat + '">' +
    '<div class="no">' + s.seat + '号' + (s.ai ? ' · AI' : ' · 你') + '</div><div class="nm">' + esc(s.name) + '</div>' +
    '<div class="rl">' + (s.role ? '<span class="tag ' + (s.role === '狼人' ? 'red' : 'green') + '">' + esc(s.role) + '</span>' : '<span class="tag">？</span>') + '</div></div>').join('');
  $$('#g-seats .gseat').forEach(el => el.onclick = () => { sel = +el.dataset.seat; render(g); });
  renderAct(g);
}

let sel = 0;
function renderAct(g) {
  const box = $('#g-act');
  if (g.phase === 'GAME_OVER') { box.innerHTML = ''; return; }
  if (g.cur !== 1) { box.innerHTML = '<span class="muted">等待' + (g.cur ? g.cur + '号' : '') + '行动…</span>'; return; }
  const alive = g.seats.filter(s => s.alive && s.seat !== 1);
  const btns = list => list.map(s => '<button class="btn sm' + (sel === s.seat ? ' gold' : '') + '" data-t="' + s.seat + '">' + s.seat + '号 ' + esc(s.name) + '</button>').join(' ');
  let html = '';
  if (g.phase === 'NIGHT') {
    if (g.kind === 'GUARD') html = '🛡 守护一名玩家（可空守，不能连守）：<div class="targets">' + btns(alive.filter(s => s.seat !== g.my.guardLast)) + '<button class="btn sm" data-t="0">空守</button></div>';
    else if (g.kind === 'WOLF_KILL') html = '🐺 你是狼人！选择今晚的猎物：<div class="targets">' + btns(alive.filter(s => s.role !== '狼人')) + '</div>';
    else if (g.kind === 'SEER') html = '🔮 查验一名玩家：<div class="targets">' + btns(alive) + '</div>';
    else html = '<span class="muted">闭眼中…</span>';
  } else if (g.phase === 'DAY') {
    if (g.kind === 'SPEECH') html = '<textarea id="line" placeholder="说出你的推理…（留空=过）"></textarea><div class="targets"><button class="btn gold sm" id="do-speak">发言</button></div>';
    else if (g.kind === 'VOTE' || g.kind === 'PK_VOTE') html = '🗳 投票放逐：' + (g.kind === 'PK_VOTE' ? '（PK 台）' : '') + '<div class="targets">' + btns(alive) + '</div>';
    else html = '<span class="muted">…</span>';
  }
  box.innerHTML = html;
  box.querySelectorAll('[data-t]').forEach(b => b.onclick = () => {
    const t = +b.dataset.t;
    if (g.kind === 'GUARD') send({ kind: 'GUARD', target: t });
    else if (g.kind === 'WOLF_KILL') send({ kind: 'WOLF_KILL', target: t });
    else if (g.kind === 'SEER') send({ kind: 'SEER', target: t });
    else if (g.kind === 'VOTE' || g.kind === 'PK_VOTE') send({ kind: g.kind, target: t });
  });
  const sp = $('#do-speak');
  if (sp) sp.onclick = () => send({ kind: 'SPEECH', line: ($('#line') || {}).value || '' });
}

async function send(d) {
  const j = await api('act', Object.assign({ room }, d));
  if (!j.ok) return toast(j.err || '动作失败');
  sel = 0;
  render(j.game);
}

function reset() {
  $('#game-pane').classList.add('hidden');
  $('#join-pane').classList.remove('hidden');
  $('#g-feed').innerHTML = '';
  clearInterval(timer);
}

async function showResults() {
  const j = await api('results', null, true);
  const pane = $('#results-pane');
  pane.classList.remove('hidden');
  if (!j.ok || !j.results || !j.results.length) { $('#results-box').innerHTML = '<div class="muted">暂无战绩' + (j.persisted ? '' : '（未连接数据库）') + '</div>'; return; }
  $('#results-box').innerHTML = '<table><tr><th>对局</th><th>胜负</th><th>天数</th><th>玩家</th><th>底牌</th></tr>' +
    j.results.map(r => '<tr><td>' + esc(r.game_id) + '</td><td>' + (r.winner === 'good' ? '🌅 好人' : '🐺 狼人') + '</td><td>' + r.days + '</td><td>' + esc(r.player) + '</td><td class="muted">' + esc(r.roles || '') + '</td></tr>').join('') + '</table>';
  pane.scrollIntoView({ behavior: 'smooth' });
}

$('#btn-join').onclick = join;
$('#name').addEventListener('keydown', e => { if (e.key === 'Enter') join(); });
api('results', null, true).then(j => {
  $('#persist-tag').textContent = j.ok && j.persisted ? '✓ 已连接 Supabase 持久化' : '（未连接持久化，战绩不入库）';
  if (j.ok && j.results && j.results.length) {
    $('#results-line').innerHTML = '已进行 <b>' + j.results.length + '</b> 场演示局（近 10 场入库）· <a href="javascript:showResults()" style="color:var(--accent)">查看</a>';
  }
});
if (room) { // 刷新续局
  $('#join-pane').classList.add('hidden');
  $('#game-pane').classList.remove('hidden');
  poll(); timer = setInterval(poll, 1200);
}
