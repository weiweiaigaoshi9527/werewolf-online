/* ========== 狼人杀 Online · 前端逻辑（M0 认证 + M1 房间） ========== */

const API = {
  async request(path, method = 'GET', body = null) {
    const headers = { 'Content-Type': 'application/json' };
    const token = localStorage.getItem('ww_token');
    if (token) headers['Authorization'] = 'Bearer ' + token;
    const res = await fetch(path, {
      method, headers,
      body: body ? JSON.stringify(body) : undefined
    });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || ('请求失败 ' + res.status));
    return data;
  },
  get: (p) => API.request(p),
  getList: (p) => API.request(p),
  post: (p, b) => API.request(p, 'POST', b),
};

/* ---------- 全局状态 ---------- */
const state = {
  user: null,
  ws: null,
  wsTimer: null,
  wsRetry: 0,
  wsAlive: false,
  room: null,       // 当前所在房间 RoomView
  mySeat: null,     // 我的座位号
  isSpectator: false,
  voiceAvail: false, // 服务端语音是否可用（/api/voice/status）
};

/* ---------- 音效（Web Audio 合成，离线无外部文件） ---------- */
const SFX = (() => {
  let ctx = null;
  let enabled = localStorage.getItem('ww_sfx') !== 'off';
  function ac() { if (!ctx) ctx = new (window.AudioContext || window.webkitAudioContext)(); if (ctx.state === 'suspended') ctx.resume(); return ctx; }
  function tone(freq, dur, type, vol, delay) {
    if (!enabled) return;
    type = type || 'sine'; vol = vol == null ? 0.14 : vol; delay = delay || 0;
    try {
      const c = ac(), o = c.createOscillator(), g = c.createGain();
      o.type = type; o.frequency.value = freq; o.connect(g); g.connect(c.destination);
      const t = c.currentTime + delay;
      g.gain.setValueAtTime(0.0001, t); g.gain.linearRampToValueAtTime(vol, t + 0.012);
      g.gain.exponentialRampToValueAtTime(0.0001, t + dur);
      o.start(t); o.stop(t + dur + 0.03);
    } catch (e) {}
  }
  function noise(dur, vol) {
    if (!enabled) return;
    try {
      const c = ac(), b = c.createBuffer(1, Math.floor(c.sampleRate * dur), c.sampleRate), d = b.getChannelData(0);
      for (let i = 0; i < d.length; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / d.length, 2);
      const s = c.createBufferSource(); s.buffer = b; const g = c.createGain(); g.gain.value = vol || 0.3;
      s.connect(g); g.connect(c.destination); s.start();
    } catch (e) {}
  }
  const sounds = {
    click: () => tone(620, 0.05, 'square', 0.04),
    night: () => { tone(233, 0.5, 'sine', 0.12); tone(155, 0.8, 'sine', 0.1, 0.12); },
    day: () => { tone(523, 0.18); tone(659, 0.18, 'sine', 0.12, 0.12); tone(784, 0.3, 'sine', 0.12, 0.24); },
    turn: () => { tone(880, 0.12, 'triangle', 0.13); tone(1175, 0.14, 'triangle', 0.11, 0.1); },
    vote: () => tone(320, 0.1, 'square', 0.08),
    shot: () => noise(0.22, 0.32),
    death: () => { tone(210, 0.3, 'sawtooth', 0.1); tone(120, 0.5, 'sawtooth', 0.1, 0.12); },
    msg: () => tone(720, 0.05, 'sine', 0.05),
    win: () => [523, 659, 784, 1047].forEach((f, i) => tone(f, 0.28, 'sine', 0.13, i * 0.13)),
    lose: () => [420, 360, 300, 220].forEach((f, i) => tone(f, 0.32, 'sine', 0.11, i * 0.16)),
  };
  return {
    play(n) { try { sounds[n] && sounds[n](); } catch (e) {} },
    toggle() { enabled = !enabled; localStorage.setItem('ww_sfx', enabled ? 'on' : 'off'); return enabled; },
    isOn() { return enabled; },
    unlock() { try { ac(); } catch (e) {} },
  };
})();

// 全局点击音效（解锁 AudioContext + 按钮反馈）
document.addEventListener('pointerdown', (e) => {
  SFX.unlock();
  if (e.target.closest('button, .preset-chip, .tile, .ga-btn, .tab')) SFX.play('click');
}, { passive: true });

/* ---------- 工具 ---------- */
const $ = (id) => document.getElementById(id);

function toast(msg, isErr = false) {
  const el = document.createElement('div');
  el.className = 'toast' + (isErr ? ' err' : '');
  el.textContent = msg;
  $('toast-box').appendChild(el);
  setTimeout(() => el.remove(), 3200);
}

function addFeed(msg, listId = 'feed-list') {
  const box = $(listId);
  if (!box) return;
  const el = document.createElement('div');
  el.className = 'feed-item';
  el.textContent = msg;
  box.prepend(el);
  while (box.children.length > 30) box.lastChild.remove();
}

function levelTitle(level) {
  if (level >= 30) return '月夜主宰';
  if (level >= 25) return '狼群梦魇';
  if (level >= 20) return '猎魔圣手';
  if (level >= 15) return '资深守夜人';
  if (level >= 10) return '血月行者';
  if (level >= 5)  return '见习村民';
  return '初入狼窝';
}
function expNeeded(level) { return Math.floor(100 * Math.pow(level, 1.5)); }

/* ---------- 视图切换 ---------- */
let currentView = 'auth';
function showView(name) {
  currentView = name;
  $('view-auth').classList.toggle('hidden', name !== 'auth');
  $('view-lobby').classList.toggle('hidden', name !== 'lobby');
  $('view-room').classList.toggle('hidden', name !== 'room');
  $('view-game').classList.toggle('hidden', name !== 'game');
  $('view-profile').classList.toggle('hidden', name !== 'profile');
  $('view-shop').classList.toggle('hidden', name !== 'shop');
  $('view-friends').classList.toggle('hidden', name !== 'friends');
  $('view-tickets').classList.toggle('hidden', name !== 'tickets');
  $('view-replay').classList.toggle('hidden', name !== 'replay');
  $('topbar').classList.toggle('hidden', name === 'auth');
  if (window.Nav) Nav.sync(name);        // 外壳：导航高亮 / 壳层显隐 / URL 同步
}

/* ---------- 用户渲染 ---------- */
function renderUser() {
  const u = state.user;
  $('profile-mini').textContent = `${u.nickname} · Lv.${u.level}`;
  $('lobby-nickname').textContent = u.nickname;
  $('lobby-username').textContent = '@' + u.username;
  $('lobby-level').textContent = 'Lv.' + u.level;
  $('lobby-level-title').textContent = levelTitle(u.level);
  const need = expNeeded(u.level);
  $('lobby-exp-fill').style.width = Math.min(100, u.exp / need * 100) + '%';
  $('lobby-exp-text').textContent = `${u.exp} / ${need} EXP`;
  $('lobby-gold').textContent = u.gold;
  const adm = $('btn-admin'); if (adm) adm.classList.toggle('hidden', !u.admin);   // 管理员：主页显示后台入口
  // 大厅主页头像：与"我的"页同一套渲染（优先 QQ 头像/上传头像 avatarUrl，其次默认库 + 头像框）。
  // 此前这里是写死的 emoji，换装/绑定 QQ 后主页不更新；现在每次 renderUser 都重画。
  const la = $('lobby-avatar');
  if (la) la.innerHTML = renderAvatarHtml(u, 56);
}

/* ---------- 认证 ---------- */
async function handleAuth(action, payload) {
  const errBox = $('auth-error');
  errBox.classList.add('hidden');
  try {
    const data = await API.post('/api/auth/' + action, payload);
    localStorage.setItem('ww_token', data.token);
    state.user = data.user;
    await enterLobby();
    if (state.pendingJoin) { toast('收到房间 ' + state.pendingJoin + ' 的邀请'); confirmPendingJoin(); }
  } catch (e) {
    errBox.textContent = e.message;
    errBox.classList.remove('hidden');
  }
}

/* ---------- QQ 绑定：强制补填 + 1000 金币奖励 ---------- */
/** 未填 QQ 的玩家在进入大厅前强制弹窗（无取消按钮）；已填则不弹。 */
function checkQqRequired(user) {
  if (!user) return;
  if (user.needQq) {
    const m = $('modal-qq');
    if (m) { m.classList.remove('hidden'); setTimeout(() => { const i = $('qq-input'); if (i) i.focus(); }, 80); }
  }
}

/** 提交 QQ 号：成功后刷新用户态；领到奖励时弹提示。 */
async function saveQq() {
  const input = $('qq-input'), err = $('qq-err');
  const q = (input.value || '').trim();
  err.classList.add('hidden');
  if (!/^[1-9][0-9]{4,10}$/.test(q)) {
    err.textContent = 'QQ 号需为 5-11 位数字（不以 0 开头）';
    err.classList.remove('hidden');
    return;
  }
  const btn = $('btn-qq-save');
  btn.disabled = true; btn.textContent = '绑定中…';
  try {
    const r = await API.post('/api/user/qq', { qq: q });
    if (state.user) { state.user.qq = r.qq; state.user.needQq = false; if (r.avatarUrl) state.user.avatarUrl = r.avatarUrl; if (r.gold_balance != null) state.user.gold = r.gold_balance; renderUser(); }
    $('modal-qq').classList.add('hidden');
    if (r.rewarded > 0) toast('🐧 QQ 绑定成功！' + r.rewarded + ' 金币已到账');
    else toast('QQ 已更新');
  } catch (e) {
    err.textContent = e.message || '绑定失败';
    err.classList.remove('hidden');
  } finally {
    btn.disabled = false; btn.textContent = '🐧 绑定并领取 1000 金币';
  }
}

async function enterLobby() {
  renderUser();
  showView('lobby');
  connectWS();
  try { FR.loadUnread(); } catch (_) {}
  try { checkinStatus(); } catch (_) {}
  // 强制补填 QQ：存量玩家未填 QQ 号时弹出（无取消按钮），填完领 1000 金币
  checkQqRequired(state.user);
  // 若已在房间，直接跳房间页；若房间已在对局中，恢复到对局界面
  try {
    const r = await API.get('/api/room/my');
    if (r.room && r.room.roomNo) {
      applyRoomState(r.room);
      if (r.room.status === 'PLAYING') {
        try {
          const g = await API.get('/api/game/state');
          if (g && g.phase) renderGame(g);
        } catch (_) {}
      }
    }
  } catch (_) {}
}

async function logout() {
  try { await API.post('/api/auth/logout'); } catch (_) {}
  localStorage.removeItem('ww_token');
  state.user = null;
  state.room = null;
  try { VOICE.disconnect(); } catch (_) {}
  disconnectWS();
  showView('auth');
  toast('已退出登录');
}

/* ---------- WebSocket ---------- */
function setWsStatus(cls, text) {
  const el = $('ws-status');
  el.className = 'ws-status ' + cls;
  el.textContent = '● ' + text;
}

function disconnectWS() {
  if (state.wsTimer) { clearInterval(state.wsTimer); state.wsTimer = null; }
  if (state.ws && state.ws.readyState <= 1) { try { state.ws.close(); } catch (_) {} }
  state.ws = null;
  setWsStatus('ws-offline', '未连接');
}

function connectWS() {
  disconnectWS();
  const token = localStorage.getItem('ww_token');
  if (!token) return;
  setWsStatus('ws-connecting', '连接中…');
  const proto = location.protocol === 'https:' ? 'wss' : 'ws';
  const ws = new WebSocket(`${proto}://${location.host}/ws?token=${token}`);
  state.ws = ws;

  ws.onopen = () => {
    state.wsRetry = 0;
    setWsStatus('ws-online', '已连接');
    state.wsTimer = setInterval(() => {
      state.wsAlive = false;
      try { ws.send(JSON.stringify({ type: 'ping' })); } catch (_) {}
      setTimeout(() => {
        if (state.ws === ws && !state.wsAlive && ws.readyState === 1) {
          addFeed('⚠️ 心跳超时，正在重连…');
          ws.close();
        }
      }, 10000);
    }, 25000);
  };

  ws.onmessage = (ev) => {
    let msg;
    try { msg = JSON.parse(ev.data); } catch (_) { return; }
    switch (msg.type) {
      case 'pong':
        state.wsAlive = true;
        setWsStatus('ws-online', '已连接');
        break;
      case 'welcome':
        addFeed(msg.message);
        break;
      case 'system':
        addFeed('📢 ' + msg.message);
        break;
      case 'room.state':
        applyRoomState(msg.room);
        break;
      case 'room.event':
        addFeed('📣 ' + msg.message, 'room-feed-list');
        break;
      case 'room.left':
        state.room = null;
        state.mySeat = null;
        showView('lobby');
        toast(msg.reason || '已离开房间');
        break;
      case 'room.kicked':
        state.room = null;
        state.mySeat = null;
        showView('lobby');
        toast(msg.message, true);
        break;
      case 'game.state':
        renderGame(msg);
        break;
      case 'game.ended':
        onGameEnded(msg);
        break;
      case 'game.chat':
        onGameChat(msg); break;
      case 'narrator':
        showNarrator(msg); break;
      case 'maintenance':
        DM.notice(msg.message, msg.expiry);
        addFeed('🛠 维护通知：' + msg.message);
        toast('🛠 维护通知：' + msg.message, true);
        break;
      case 'friend.request':
        FR.onIncomingRequest(msg); break;
      case 'friend.accepted':
        FR.onAccepted(msg); break;
      case 'chat.msg':
        FR.onChat(msg); break;
      case 'room.invite':
        FR.onRoomInvite(msg); break;
      case 'ticket.update':
        toast('🎫 你的工单 #' + msg.id + ' 状态更新：' + (msg.status || ''));
        if (currentView === 'tickets') TK.loadMine();
        break;
      case 'error':
        console.warn('WS 错误:', msg.message);
        break;
    }
  };

  ws.onclose = (ev) => {
    if (state.wsTimer) { clearInterval(state.wsTimer); state.wsTimer = null; }
    if (!state.user) return;
    // 1008 = 服务端鉴权拒绝（token 失效，如服务重启），停止重连并回登录
    if (ev.code === 1008) {
      localStorage.removeItem('ww_token');
      state.user = null;
      showView('auth');
      toast('登录已失效，请重新登录', true);
      return;
    }
    setWsStatus('ws-offline', '已断开');
    const delay = Math.min(30000, 2000 * Math.pow(2, state.wsRetry++));
    setTimeout(() => { if (state.user) connectWS(); }, delay);
  };
}

/* ---------- 房间渲染 ---------- */
function applyRoomState(room) {
  if (!room || !room.roomNo) {
    state.room = null;
    state.mySeat = null;
    _vipInit = false; _seenVip = new Set();
    if (!state.isSpectator && (currentView === 'room' || currentView === 'lobby')) showView('lobby');
    return;
  }
  state.room = room;
  const me = room.players.find(p => p.userId === state.user.id);
  state.mySeat = me ? me.seat : null;
  state.isSpectator = !me;
  renderRoom();
  detectVipEntry(room);
  // 只有当前处于大厅/房间流程才自动进入房间页，避免打断主页/商店/设置/对局视图
  if (currentView === 'lobby' || currentView === 'room') showView('room');
}

function renderRoom() {
  const room = state.room;
  $('room-no').textContent = room.roomNo;
  $('room-status').textContent = room.status === 'WAITING' ? '等待中' : '游戏中';

  // 双人组队：座位对集合（用于 👫 标记）与我的搭档
  const duos = room.duos || [];
  const duoPartner = new Map();
  for (const pair of duos) { duoPartner.set(pair[0], pair[1]); duoPartner.set(pair[1], pair[0]); }
  const myPartnerSeat = state.mySeat ? duoPartner.get(state.mySeat) : null;
  const iAmPaired = !!myPartnerSeat;

  const grid = $('seat-grid');
  grid.innerHTML = '';
  const maxSeats = room.maxSeats || 16;
  const playersBySeat = new Map(room.players.map(p => [p.seat, p]));
  for (let s = 1; s <= maxSeats; s++) {
    const p = playersBySeat.get(s);
    const el = document.createElement('div');
    el.className = 'seat';
    if (!p) {
      el.classList.add('empty');
      el.innerHTML = `<div class="seat-badge">#${s}</div>
        <div class="seat-avatar">🪑</div>
        <div class="seat-name">空座位</div>`;
    } else {
      const isMe = p.userId === state.user.id;
      const isHost = p.userId === room.hostUserId;
      const partnerSeat = duoPartner.get(s);
      if (isMe) el.classList.add('me');
      if (!p.online) el.classList.add('offline');
      el.innerHTML = `
        <div class="seat-badge">#${p.seat}</div>
        ${isHost ? '<div class="seat-crown" title="房主">👑</div>' : ''}
        <div class="seat-avatar">${renderAvatarHtml(p, 44)}</div>
        <div class="seat-name" style="${colorStyle(p.nickColor, 'color')}">${escapeHtml(p.nickname)}${p.ai ? ' 🤖' : ''}${partnerSeat ? ' 👫' : ''}${isMe ? ' (我)' : ''} ${vipBadge(p.vip)}</div>
        ${p.title ? `<div class="seat-title">${escapeHtml(p.title)}</div>` : ''}
        <div class="seat-sub">Lv.${p.level} · ${p.online ? '在线' : '离线'}</div>
        <div class="seat-ready ${p.ready ? 'on' : 'off'}">${p.ready ? '已准备' : '未准备'}</div>
      `;
      // 组队模式：向他人发起/解除组队（仅等待中、自己未组队或解除自己的队伍）
      if (room.duoMode && room.status === 'WAITING' && !state.isSpectator) {
        if (!isMe && !iAmPaired && !p.ai) {
          const bt = document.createElement('button');
          bt.className = 'seat-duo';
          bt.textContent = '组队';
          bt.onclick = () => inviteDuo(p.userId, p.nickname);
          el.appendChild(bt);
        }
        if (!isMe && partnerSeat === state.mySeat) {
          const bt = document.createElement('button');
          bt.className = 'seat-duo on';
          bt.textContent = '解除组队';
          bt.onclick = () => { if (confirm(`解除与 ${p.nickname} 的组队？`)) cancelDuo(); };
          el.appendChild(bt);
        }
      }
      // 房主可对他人的座位操作（AI 不可被转让房主/移出意义不大，直接不提供入口）
      if (!isMe && !p.ai && state.user.id === room.hostUserId && !state.isSpectator) {
        const kick = document.createElement('button');
        kick.className = 'seat-kick';
        kick.textContent = '移出';
        kick.onclick = () => { if (confirm(`确认将 ${p.nickname} 移出房间？`)) kickPlayer(p.userId); };
        el.appendChild(kick);
        const tr = document.createElement('button');
        tr.className = 'seat-transfer';
        tr.textContent = '转让房主';
        tr.onclick = () => { if (confirm(`将房主转让给 ${p.nickname}？`)) transferHost(p.userId); };
        el.appendChild(tr);
      }
    }
    grid.appendChild(el);
  }

  // 组队邀请轮询（等待中、有人向我发出邀请则弹确认）
  if (room.status === 'WAITING' && !room.duoInviteShown) pollDuoInvite();
  else if (room.status !== 'WAITING') { room.duoInviteShown = false; }

  // 底部按钮
  $('btn-ready').classList.toggle('hidden', state.isSpectator);
  const isHost = state.user.id === room.hostUserId && !state.isSpectator;
  $('btn-start').classList.toggle('hidden', !isHost);
  $('btn-add-ai').classList.toggle('hidden', !(isHost && room.status === 'WAITING'));
  $('btn-invite-friend').classList.toggle('hidden', !(isHost && room.status === 'WAITING'));
  $('btn-invite-link').classList.remove('hidden');   // 外链邀请：房间内任何人都可复制分享
  const iAmReady = state.mySeat && room.players.find(p => p.seat === state.mySeat)?.ready;
  $('btn-ready').textContent = iAmReady ? window.t('room_unready') : window.t('room_ready');
  $('btn-ready').classList.toggle('btn-primary', !iAmReady);

  renderBoardPanel(room);
  renderModePanel();
}

function avatarEmoji(id) {
  const list = ['\u{1F9D1}','\u{1F9D9}','\u{1F9DD}','\u{1F9DB}','\u{1F9DF}','\u{1F478}','\u{1F934}','\u{1F315}','\u{1F56F}','\u{1F43A}','\u{1F319}','\u{1F52E}','\u{1F98A}','\u{1F409}','\u{1F9D9}','\u{1F47B}','\u{1F383}','\u{1F987}','\u{1F989}','\u{2620}'];
  return list[((id || 1) - 1) % list.length];
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}
/* 安全：颜色白名单校验。仅允许 #hex 或 rgb()/rgba() 形式，其余一律回退默认色，防止内联样式注入。 */
function safeColor(v, def) {
  if (typeof v !== 'string') return def || '';
  const s = v.trim();
  if (/^#[0-9a-fA-F]{3,8}$/.test(s)) return s;
  if (/^rgba?\(\s*\d{1,3}\s*,\s*\d{1,3}\s*,\s*\d{1,3}\s*(?:,\s*(?:0|1|0?\.\d+)\s*)?\)$/.test(s)) return s;
  return def || '';
}
/* 安全：昵称色/头像框色拼进内联 style 时统一走这里，非法值直接不输出样式。 */
function colorStyle(v, prop) {
  const c = safeColor(v);
  return c ? prop + ':' + c : '';
}
/* 安全：头像地址白名单校验（esc 转义 + 仅允许 /uploads/、http(s)://、data:image/ 前缀）。 */
function safeAvatarUrl(u) {
  if (typeof u !== 'string') return '';
  const s = u.trim();
  if (!(s.indexOf('/uploads/') === 0 || /^https?:\/\//i.test(s) || /^data:image\//i.test(s))) return '';
  return esc(s);
}

/* ---------- 房间动作 ---------- */
async function createRoom() {
  try {
    const view = await API.post('/api/room/create');
    applyRoomState(view);
    addFeed('🏠 你创建了房间 ' + view.roomNo, 'room-feed-list');
  } catch (e) { toast(e.message, true); }
}

/** 观战加入：进入进行中的对局旁观（服务端只下发公开信息）。 */
async function spectateJoin(roomNo) {
  try {
    const view = await API.post('/api/room/spectate', { roomNo });
    applyRoomState(view);
    toast('已进入房间 ' + roomNo + ' 观战');
    if (view.room && view.room.status === 'PLAYING') {
      try { const g = await API.get('/api/game/state'); if (g && g.phase) renderGame(g); } catch (_) {}
    }
  } catch (e) { toast(e.message, true); }
}

/** 大厅「AI 陪玩房」：建房后立即按空位补 AI，房主可随时开局。 */
async function quickAiRoom() {
  if (state.room && state.room.roomNo) { toast('你已在房间里'); return; }
  try {
    const view = await API.post('/api/room/create');
    applyRoomState(view);
    addFeed('🤖 已创建 AI 陪玩房 ' + view.roomNo, 'room-feed-list');
    await API.post('/api/room/add-ai', {});
    toast('已创建房间并补齐 AI，可随时开局');
  } catch (e) { toast(e.message, true); }
}

async function joinRoom(roomNo, skipConfirm) {
  // 外链/房号加入：若已在别的房间，先确认离开再进，避免“莫名出现在另一个房间”
  if (state.room && state.room.roomNo && state.room.roomNo !== roomNo) {
    if (!skipConfirm && !confirm(`你当前在房间 ${state.room.roomNo}。
要离开当前房间并加入 ${roomNo} 吗？`)) return;
    try { await API.post('/api/room/leave'); } catch (_) {}
    state.room = null; state.mySeat = null; state.isSpectator = false;
  }
  try {
    const view = await API.post('/api/room/join', { roomNo });
    applyRoomState(view);
    toast('已加入房间 ' + roomNo);
  } catch (e) { toast(e.message, true); }
}

/** 外链邀请的 pendingJoin：先向用户确认房号，再执行加入（不再“直接到了另一个房间”）。 */
function confirmPendingJoin() {
  const no = state.pendingJoin; state.pendingJoin = null;
  if (!no) return;
  if (state.room && state.room.roomNo === no) { toast('你已在这个房间里'); return; }
  joinRoom(no);
}

async function leaveRoom() {
  if (!confirm('确认离开房间？')) return;
  try {
    await API.post('/api/room/leave');
    // 立即回大厅，不依赖 WS 的 room.left（更稳，避免被随后的 room.state 拉回房间页）
    state.room = null; state.mySeat = null; state.isSpectator = false;
    showView('lobby');
  } catch (e) { toast(e.message, true); }
}

async function toggleReady() {
  if (!state.room || state.isSpectator) return;
  const me = state.room.players.find(p => p.seat === state.mySeat);
  try { await API.post('/api/room/ready', { ready: !me.ready }); } catch (e) { toast(e.message, true); }
}

async function kickPlayer(userId) {
  try { await API.post('/api/room/kick', { userId }); } catch (e) { toast(e.message, true); }
}

async function transferHost(userId) {
  try { await API.post('/api/room/transfer', { userId }); toast('房主已转让'); } catch (e) { toast(e.message, true); }
}

async function startGame() {
  try {
    const r = await API.post('/api/game/start', {});
    game.prevPhase = null; game.prevMyTurn = false; game.prevDeaths = 0;
    const gc = $('game-chat'); if (gc) gc.innerHTML = '';
    toast('对局开始！板子：' + (r.board || ''));
  } catch (e) { toast(e.message, true); }
}

/* ---------- 页面初始化 ---------- */
function bindAuthUI() {
  document.querySelectorAll('.tab').forEach(tab => {
    tab.addEventListener('click', () => {
      document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
      tab.classList.add('active');
      $('form-login').classList.toggle('hidden', tab.dataset.tab !== 'login');
      $('form-register').classList.toggle('hidden', tab.dataset.tab !== 'register');
      $('auth-error').classList.add('hidden');
    });
  });
  $('form-login').addEventListener('submit', (e) => {
    e.preventDefault();
    handleAuth('login', {
      username: $('login-username').value.trim(),
      password: $('login-password').value,
      rememberDays: +($('login-remember').value || 0),
    });
  });
  $('form-register').addEventListener('submit', (e) => {
    e.preventDefault();
    const qq = ($('reg-qq') && $('reg-qq').value.trim()) || '';
    if (qq && !/^[1-9][0-9]{4,10}$/.test(qq)) {
      const eb = $('auth-error');
      eb.textContent = 'QQ 号需为 5-11 位数字（不以 0 开头）';
      eb.classList.remove('hidden');
      return;
    }
    handleAuth('register', {
      username: $('reg-username').value.trim(),
      password: $('reg-password').value,
      nickname: $('reg-nickname').value.trim(),
      qq,
    });
  });
  const qqSave = $('btn-qq-save');
  if (qqSave) qqSave.addEventListener('click', saveQq);
  const qqInput = $('qq-input');
  if (qqInput) qqInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') saveQq(); });
  $('btn-logout').addEventListener('click', logout);
}

function bindLobbyUI() {
  $('btn-create-room').addEventListener('click', createRoom);
  $('btn-join-room').addEventListener('click', () => {
    $('modal-join').classList.remove('hidden');
    $('join-room-no').value = '';
    setTimeout(() => $('join-room-no').focus(), 50);
  });
  $('btn-join-cancel').addEventListener('click', () => $('modal-join').classList.add('hidden'));
  // 观战加入
  $('btn-spectate').addEventListener('click', () => {
    $('modal-spectate').classList.remove('hidden');
    $('spectate-room-no').value = '';
    setTimeout(() => $('spectate-room-no').focus(), 50);
  });
  $('btn-spectate-cancel').addEventListener('click', () => $('modal-spectate').classList.add('hidden'));
  $('btn-spectate-confirm').addEventListener('click', () => {
    const no = $('spectate-room-no').value.trim();
    if (!/^\d{6}$/.test(no)) { toast('房间号需为 6 位数字', true); return; }
    $('modal-spectate').classList.add('hidden');
    spectateJoin(no);
  });
  $('btn-join-confirm').addEventListener('click', () => {
    const no = $('join-room-no').value.trim();
    if (!/^\d{6}$/.test(no)) { toast('房间号需为 6 位数字', true); return; }
    $('modal-join').classList.add('hidden');
    joinRoom(no);
  });
  $('join-room-no').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') $('btn-join-confirm').click();
    if (e.key === 'Escape') $('btn-join-cancel').click();
  });
}

function bindRoomUI() {
  $('btn-leave-room').addEventListener('click', leaveRoom);
  $('btn-ready').addEventListener('click', toggleReady);
  $('btn-start').addEventListener('click', startGame);
  $('btn-add-ai').addEventListener('click', addRoomAi);
  const sm = $('btn-save-mode'); if (sm) sm.addEventListener('click', saveMode);
  const it = $('item-toggle');
  if (it) it.addEventListener('change', async () => {
    try {
      await API.post('/api/room/item', { itemMatch: it.checked });
      toast(it.checked ? '🎒 功能道具赛已开启' : '🎒 功能道具赛已关闭');
    } catch (e) { toast(e.message, true); it.checked = !it.checked; }
  });
  const il = $('btn-invite-link');
  if (il) il.addEventListener('click', () => {
    const no = state.room && state.room.roomNo;
    if (!no) return toast('还没进入房间', true);
    copyText(location.origin + '/?join=' + no);
  });
}

async function addRoomAi() {
  try { await API.post('/api/room/add-ai', {}); } catch (e) { toast(e.message, true); }
}

/* ---------- 双人组队 ---------- */
const duoState = { handled: new Set(), pollTimer: null };

async function inviteDuo(userId, nickname) {
  try {
    await API.post('/api/room/duo/invite', { targetUserId: userId });
    toast(`已向 ${nickname} 发起组队邀请，等待接受`);
  } catch (e) { toast(e.message, true); }
}
async function cancelDuo() {
  duoState.handled.clear();   // 解除队伍后，允许同一人再次邀请时重新弹窗
  try { await API.post('/api/room/duo/cancel', {}); } catch (e) { toast(e.message, true); }
}
function pollDuoInvite() {
  if (duoState.pollTimer) return; // 去重：渲染多次只挂一个轮询
  duoState.pollTimer = setTimeout(async () => {
    duoState.pollTimer = null;
    try {
      const r = await API.get('/api/room/duo/pending');
      const inv = r.invite;
      if (inv && inv.fromUserId != null && !duoState.handled.has(inv.fromUserId)) {
        duoState.handled.add(inv.fromUserId);
        let accepted = false;
        if (confirm(`👫 ${inv.fromName} 邀请你组队（同阵营），接受吗？`)) {
          accepted = true;
          try {
            await API.post('/api/room/duo/accept', { fromUserId: inv.fromUserId });
            toast('组队成功！你们将拿到同阵营身份');
          } catch (e) { toast(e.message, true); }
        } else {
          try { await API.post('/api/room/duo/cancel', {}); } catch (_) {}
        }
        // 邀请已消费：同一人解除组队后再次邀请时，必须还能弹窗（否则会被静默吞掉）
        duoState.handled.delete(inv.fromUserId);
      }
    } catch (_) {}
  }, 800);
}

/* ================= 板子配置 ================= */
const board = { presets: [], roles: [], loaded: false, counts: {}, mode: 'preset', presetId: null };
const ROLE_CN = {};

async function loadBoards() {
  if (board.loaded) return;
  try {
    const d = await API.get('/api/room/boards');
    board.presets = d.presets || [];
    board.roles = d.roles || [];
    board.roles.forEach(r => ROLE_CN[r.role] = r.cnName);
    board.loaded = true;
  } catch (e) { /* 忽略，稍后重试 */ }
}

function boardSummaryText(room) {
  if (!room.board || !Object.keys(room.board).length) return '默认（按人数自动选）';
  const t = Object.values(room.board).reduce((a, b) => a + b, 0);
  const name = room.boardName && room.boardName !== 'custom'
    ? (board.presets.find(p => p.id === room.boardName)?.name || room.boardName) : '自定义';
  const detail = Object.entries(room.board).map(([r, c]) => (ROLE_CN[r] || r) + c).join(' ');
  return `${name} · ${t}人 · ${detail}`;
}

function renderBoardPanel(room) {
  loadBoards().then(() => {
    $('board-summary').textContent = '· ' + boardSummaryText(room);
    const isHost = state.user.id === room.hostUserId && !state.isSpectator;
    const editable = isHost && room.status === 'WAITING';
    $('board-editor').classList.toggle('hidden', !editable);
    if (!editable) return;
    // 初始化编辑态为当前板子
    if (!Object.keys(board.counts).length && room.board) {
      board.counts = Object.assign({}, room.board);
      board.presetId = room.boardName;
    }
    renderPresets();
    renderCustom();
    validateBoardUi();
  });
}

function renderPresets() {
  const row = $('preset-row');
  row.innerHTML = '';
  for (const p of board.presets) {
    const chip = document.createElement('div');
    chip.className = 'preset-chip' + (board.presetId === p.id ? ' sel' : '');
    chip.innerHTML = `<span class="pc-name">${p.name}</span><span class="pc-desc">${p.desc}</span>`;
    chip.onclick = () => {
      board.mode = 'preset'; board.presetId = p.id;
      board.counts = Object.assign({}, p.counts);
      document.querySelectorAll('.board-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.bt === 'preset'));
      $('custom-editor').classList.add('hidden');
      renderPresets(); validateBoardUi();
    };
    row.appendChild(chip);
  }
}

function renderCustom() {
  const box = $('custom-editor');
  box.innerHTML = '';
  for (const r of board.roles) {
    const cur = board.counts[r.role] || 0;
    const el = document.createElement('div');
    el.className = 'role-step';
    el.innerHTML = `<div title="${esc(r.desc || '')}"><div class="rs-name">${r.cnName} <span class="rs-info">ⓘ</span></div>
      <div class="rs-fac ${r.faction === 'WOLF' ? 'WOLF' : 'GOOD'}">${r.faction === 'WOLF' ? '狼' : '好人'}</div>
      <div class="rs-desc">${esc(r.desc || '')}</div></div>
      <div class="rs-ctrl"><button class="rs-btn" data-r="${r.role}" data-d="-1">−</button>
      <span class="rs-count">${cur}</span>
      <button class="rs-btn" data-r="${r.role}" data-d="1">+</button></div>`;
    box.appendChild(el);
  }
  box.querySelectorAll('.rs-btn').forEach(b => b.onclick = () => {
    const role = b.dataset.r, d = +b.dataset.d;
    const meta = board.roles.find(x => x.role === role);
    let v = (board.counts[role] || 0) + d;
    if (v < 0) v = 0;
    if (v > meta.max) v = meta.max;
    if (v === 0) delete board.counts[role]; else board.counts[role] = v;
    board.mode = 'custom'; board.presetId = null;
    renderPresets(); renderCustom(); validateBoardUi();
  });
}

// 客户端镜像校验（与后端 BoardValidator 一致）
function validateBoard(counts) {
  const total = Object.values(counts).reduce((a, b) => a + b, 0);
  if (total === 0) return '请选择板子';
  if (total < 6 || total > 24) return `总人数需 6-24，当前 ${total}`;
  const wolf = Object.entries(counts).filter(([r]) => ['WEREWOLF', 'WOLF_KING', 'WHITE_WOLF_KING'].includes(r)).reduce((a, [, c]) => a + c, 0);
  if (wolf < 1) return '至少 1 名狼人';
  if (wolf > 6) return '狼人至多 6 名';
  if (wolf >= total - wolf) return '狼人数必须小于好人数';
  return null;
}

function validateBoardUi() {
  const err = validateBoard(board.counts);
  const el = $('board-valid');
  const total = Object.values(board.counts).reduce((a, b) => a + b, 0);
  if (err) { el.className = 'board-valid bad'; el.textContent = '⚠ ' + err; $('btn-save-board').disabled = true; }
  else { el.className = 'board-valid ok'; el.textContent = '✓ 合法 · 共 ' + total + ' 人'; $('btn-save-board').disabled = false; }
}

async function saveBoard() {
  if (validateBoard(board.counts)) return;
  try { await API.post('/api/room/board', { counts: board.counts, boardName: board.presetId || 'custom' }); toast('板子已保存'); }
  catch (e) { toast(e.message, true); }
}

function bindBoardUI() {
  document.querySelectorAll('.board-tabs .tab').forEach(t => t.onclick = () => {
    document.querySelectorAll('.board-tabs .tab').forEach(x => x.classList.remove('active'));
    t.classList.add('active');
    if (t.dataset.bt === 'custom') { board.mode = 'custom'; board.presetId = null; renderPresets(); renderCustom(); validateBoardUi(); $('custom-editor').classList.remove('hidden'); }
    else { $('custom-editor').classList.add('hidden'); }
  });
  $('btn-save-board').addEventListener('click', saveBoard);
}

function bindSfxUI() {
  const btn = $('btn-sfx');
  const sync = () => { btn.textContent = SFX.isOn() ? '🔊' : '🔇'; };
  sync();
  btn.addEventListener('click', () => { SFX.toggle(); sync(); SFX.play('click'); });
}

/* ================= 对局渲染 ================= */
const game = { view: null, tick: null, prevPhase: null, prevMyTurn: false, prevDeaths: 0,
  voiceMode: false, anon: false, prevVoiceMode: false, prevVoiceAvailable: false, talkingName: null, dealt: true };

function phaseLabel(p) {
  return ({
    SETUP: '准备', NIGHT_GUARD: '🌙 夜晚·守卫行动', NIGHT_WOLF: '🌙 夜晚·狼人行动',
    NIGHT_WITCH: '🌙 夜晚·女巫行动', NIGHT_SEER: '🌙 夜晚·预言家验人', NIGHT_CROW: '🌙 夜晚·乌鸦行动',
    NIGHT_SILENCER: '🌙 夜晚·禁言长老行动', DAWN: '🌅 天亮了', SHERIFF_ELECTION: '👑 警长竞选',
    LAST_WORDS: '💬 遗言', DAY_SPEAK: '☀️ 白天发言', DAY_VOTE: '🗳️ 放逐投票',
    DAY_VOTE_TIEBREAK: '⚔️ 平票 PK', SHOOT: '🔫 开枪', GAME_OVER: '🏁 游戏结束'
  })[p] || p;
}

// 阶段音效 + 横幅动画
function gameFx(v) {
  const prev = game.prevPhase;
  if (prev !== v.phase) {
    const banner = $('game-phase');
    if (banner) { banner.classList.remove('phase-flash'); void banner.offsetWidth; banner.classList.add('phase-flash'); }
    if (v.phase.startsWith('NIGHT') && !(prev || '').startsWith('NIGHT')) SFX.play('night');
    else if (v.phase === 'DAWN' || v.phase === 'DAY_SPEAK') SFX.play('day');
  }
  if (v.myTurn && !game.prevMyTurn) SFX.play('turn');
  game.prevMyTurn = v.myTurn;
  const deaths = (v.feed || []).filter(e => e.type === 'PLAYER_DIED').length;
  if (deaths > game.prevDeaths) {
    const last = (v.feed || []).filter(e => e.type === 'PLAYER_DIED').slice(-1)[0];
    if (last && (last.detail === 'SHOT')) SFX.play('shot'); else SFX.play('death');
  }
  game.prevDeaths = deaths;
  if (v.phase === 'GAME_OVER' && prev !== 'GAME_OVER') {
    const iWon = v.winner && ((v.myInfo && v.myInfo.faction === 'WOLF' && v.winner.includes('狼'))
      || (v.myInfo && v.myInfo.faction !== 'WOLF' && !v.winner.includes('狼')));
    SFX.play(iWon ? 'win' : 'lose');
  }
  game.prevPhase = v.phase;
}

function renderGame(v) {
  const prevView = game.view;
  game.view = v;
  game.voiceMode = !!v.voiceMode;
  game.anon = !!v.anonymous;
  // 仅当"从非对局状态进入新的一局"时才允许播放一次发牌动画；
  // 同一局内的后续状态推送（行动、旁白等）不再重复触发，避免特效反复出现。
  const prevActive = !!(prevView && prevView.phase && prevView.phase !== 'GAME_OVER');
  const nowActive = !!(v.phase && v.phase !== 'GAME_OVER');
  if (nowActive && !prevActive) game.dealt = false;
  showView('game');
  if (!game.dealt && v.myInfo && v.myInfo.role) { game.dealt = true; playDeal(v.myInfo.role, v.myInfo.faction); }
  $('game-roomno').textContent = v.roomNo || '------';
  $('game-phase').textContent = phaseLabel(v.phase);
  $('game-day').textContent = '第 ' + v.day + ' 天';
  renderModeBadge(v);
  const eg = $('btn-end-game'); if (eg) eg.classList.toggle('hidden', state.user.id !== v.hostUserId);
  const rrBtn = $('btn-return-room'); if (rrBtn) rrBtn.classList.toggle('hidden', v.phase !== 'GAME_OVER');
  // 只要语音服务可用就连上 /ws/voice（收听 AI 文本转语音 / 旁白 / 中继）；voiceMode 只决定能否用麦克风
  if (v.voiceAvailable) { VOICE.setEnabled(true); VOICE.connect(); }
  else if (game.prevVoiceAvailable) { VOICE.disconnect(); clearVoiceCaption(); }
  game.prevVoiceAvailable = v.voiceAvailable;
  game.prevVoiceMode = v.voiceMode;
  gameFx(v);
  renderGameSeats(v);
  renderMyCard(v);
  renderGameFeed(v);
  renderGameAction(v);
  renderMarkers(v);
  startCountdown(v);
  if (v.phase === 'GAME_OVER') showSettle(v);
}

function renderGameSeats(v) {
  const box = $('game-seats');
  box.innerHTML = '';
  for (const s of v.seats) {
    const el = document.createElement('div');
    el.className = 'gseat';
    if (!s.alive) el.classList.add('dead');
    if (s.seat === v.mySeat) el.classList.add('me');
    if (s.seat === v.currentActor && v.phase !== 'GAME_OVER') el.classList.add('acting');
    if (game.talkingName && s.nickname === game.talkingName) el.classList.add('talking');
    el.innerHTML = `
      <div class="gs-badge">#${s.seat}</div>
      ${s.isSheriff ? '<div class="gs-crown" title="警长">👑</div>' : (s.silenced ? '<div class="gs-mute" title="被禁言">🔇</div>' : '')}
      <div class="gs-avatar">${renderAvatarHtml(s, 36)}</div>
      <div class="gs-name" style="${colorStyle(s.nickColor, 'color')}">${escapeHtml(s.nickname)}${s.seat === v.mySeat ? ' (我)' : ''}${v.anonymous ? '' : ' ' + vipBadge(s.vip)}</div>
      ${s.title ? `<div class="gs-title">${escapeHtml(s.title)}</div>` : ''}
      ${s.role ? `<div class="gs-role">${s.role}</div>` : ''}
      ${!s.alive ? '<div class="gs-role">出局</div>' : ''}
    `;
    if (s.userId && !s.bot) { el.classList.add('clickable'); el.title = '点击查看档案'; el.onclick = () => FR.showProfile(s.userId); }
    box.appendChild(el);
  }
}

function renderMyCard(v) {
  const info = v.myInfo || {};
  $('my-role').textContent = info.role || '观战';
  $('my-faction').textContent = info.faction === 'WOLF' ? '🐺 狼人阵营'
    : (info.faction ? '🌕 好人阵营' : '👁 观战席');
  let d = '';
  if (info.partner) d += `<div>👫 队友：<span class="tag">${info.partner}号</span>（双人组队 · 同阵营）</div>`;
  if (info.teammates && info.teammates.length) {
    d += `<div>狼队友：<span class="tag">${info.teammates.map(s => s + '号').join(' ')}</span></div>`;
  }
  if (info.seerChecks && info.seerChecks.length) {
    d += '<div>验人记录：</div>' + info.seerChecks.map(c =>
      `<div class="tag">${c.day}夜 ${c.seat}号 → ${c.result}</div>`).join('');
  }
  if (info.role === '女巫') {
    d += `<div>解药：${info.witchSaveAvailable ? '未用' : '已用'} ｜ 毒药：${info.witchPoisonAvailable ? '未用' : '已用'}</div>`;
  }
  if (info.role === '守卫' && info.guardLastTarget) {
    d += `<div>上夜守护：${info.guardLastTarget}号（不可连续）</div>`;
  }
  if (info.role === '乌鸦' && info.accusedSeat) {
    d += `<div>🐦‍⬛ 今晚已污蔑 ${info.accusedSeat}号（今日其放逐票数 +1，保密）</div>`;
  }
  if (info.accused) d += '<div class="tag" style="border-color:var(--blood)">本日被乌鸦诽谤 +1 票</div>';
  if (info.itemReveal) d += `<div>🔎 查杀卡揭示：<b>${info.itemReveal.seat}号 → ${esc(info.itemReveal.result)}</b></div>`;
  if (info.itemDoubleVote) d += '<div class="tag" style="border-color:var(--accent)">⚖️ 铁票：下次放逐投票你计两票</div>';
  if (info.itemImmune) d += '<div class="tag" style="border-color:var(--green)">🧸 替死娃娃：首次被放逐将免死</div>';
  $('my-detail').innerHTML = d || '<div style="color:var(--text-dim)">—</div>';
}

function renderGameFeed(v) {
  const box = $('game-feed');
  box.innerHTML = '';
  const items = (v.feed || []).slice().reverse();
  for (const e of items) {
    const el = document.createElement('div');
    el.className = 'feed-item';
    el.innerHTML = feedLine(e);
    box.appendChild(el);
  }
}

function feedLine(e) {
  const who = e.actor ? `<span class="fd-actor">${e.actor}号</span> ` : '';
  const tgt = e.target ? `→ ${e.target}号` : '';
  switch (e.type) {
    case 'SPEECH': return `${who}${esc(e.detail)}`;
    case 'LAST_WORDS': return `💀 ${who}遗言：${esc(e.detail)}`;
    case 'PLAYER_DIED': return `☠️ ${e.actor}号 出局（${causeZh(e.detail)}）`;
    case 'DAWN_ANNOUNCE': return `🌅 ${esc(e.detail)}`;
    case 'VOTE_RESULT': return `🗳️ ${e.actor ? e.actor + '号 ' : ''}${esc(e.detail)}`;
    case 'VOTE_DETAIL': return `<span class="fd-vote">🗳️ ${esc(e.detail)}</span>`;
    case 'VOTE_TIE': return `⚔️ ${esc(e.detail)}`;
    case 'SHOOT': return `🔫 ${who}${esc(e.detail)}${tgt}`;
    case 'IDIOT_REVEAL': return `🤪 ${e.actor}号 白痴翻牌`;
    case 'WHITE_WOLF_BLOWUP': return `💥 ${e.actor}号 白狼王自爆${tgt}`;
    case 'SHERIFF_WIN': return `👑 ${e.actor ? e.actor + '号 ' : ''}${esc(e.detail)}`;
    case 'SHERIFF_SIGNUP': return `🙋 ${e.actor}号 ${esc(e.detail)}`;
    case 'GAME_OVER': return `🏁 ${esc(e.detail)}`;
    case 'NIGHT_ACTION':
      if (e.detail === 'WOLF_VOTE') return `<span class="fd-wolf">🐺 ${e.actor}号 提议刀 ${e.target}号</span>`;
      if (e.detail === 'WOLF_KILL_RESOLVED') return `<span class="fd-wolf">🐺 狼队统一刀口：${e.target}号</span>`;
      return `${who}${esc(e.detail)}`;
    default: return `${who}${esc(e.detail)}`;
  }
}
function causeZh(c) { return ({ WOLF: '狼人刀杀', POISON: '女巫毒杀', EXILE: '投票放逐', SHOT: '枪杀', BLOWUP: '自爆' })[c] || c; }
function esc(s) { return escapeHtml(s == null ? '' : s); }

/* ---------- 操作面板 ---------- */
function renderGameAction(v) {
  const box = $('game-action');
  box.innerHTML = '';
  if (v.phase === 'GAME_OVER') { box.innerHTML = '<div class="ga-hint">对局已结束，等待返回大厅。</div>'; return; }
  const aliveTargets = (v.seats || []).filter(s => s.alive && s.seat !== v.mySeat && !s.idiotRevealed);

  if (!v.myTurn) {
    let hint;
    if (v.phase && v.phase.startsWith('NIGHT')) {
      hint = `<div class="ga-hint">🌙 夜晚行动进行中…（${phaseLabel(v.phase)}）</div>`;
    } else {
      const actor = v.currentActor ? `${v.currentActor} 号` : '系统';
      hint = `<div class="ga-hint">⏳ 等待 ${actor} 行动…（${phaseLabel(v.phase)}）</div>`;
    }
    box.innerHTML = hint;
    return;
  }

  const kind = v.actionKind;
  // 白狼王自爆：白天的“附加可选动作”，不要求恰好轮到自己，只要自己是存活白狼王且本局未自爆过即可发起
  if (isBlowUpAvailable(v)) renderBlowUpUI(v);
  if (kind === 'SPEECH' || kind === 'PK_SPEECH' || kind === 'LAST_WORDS') {
    const title = kind === 'LAST_WORDS' ? '发表遗言' : (kind === 'PK_SPEECH' ? 'PK 拉票发言' : '轮到你发言');
    renderSpeechUI(title, !!v.voiceMode, !!v.voiceAvailable, !!v.anonymous);
    return;
  }

  if (kind === 'SHERIFF_SIGNUP') {
    box.innerHTML = `<div class="ga-title">👑 是否竞选警长？</div>
      <div class="ga-row"><button class="ga-btn confirm" id="ga-signup-y">竞选上警</button>
      <button class="ga-btn" id="ga-signup-n">不参与</button></div>`;
    $('ga-signup-y').onclick = () => submitAction({ signup: true });
    $('ga-signup-n').onclick = () => submitAction({ signup: false });
    return;
  }

  if (kind === 'WITCH') {
    const info = v.myInfo || {};
    const killed = info.killedTonight;
    let html = `<div class="ga-title">🧪 女巫行动${killed ? `（${killed} 号今晚被刀）` : '（今晚平安夜）'}</div><div class="ga-targets">`;
    if (info.witchSaveAvailable && killed) html += `<button class="ga-btn confirm" id="ga-witch-save">🧡 用解药救 ${killed} 号</button>`;
    if (info.witchPoisonAvailable) {
      html += `<span style="color:var(--text-dim);align-self:center">毒药毒杀：</span>`;
      for (const s of aliveTargets) html += `<button class="ga-btn danger witch-poison" data-seat="${s.seat}">☠ ${s.seat}号</button>`;
    }
    html += `<button class="ga-btn" id="ga-witch-skip">跳过</button></div>`;
    box.innerHTML = html;
    const sv = $('ga-witch-save'); if (sv) sv.onclick = () => submitAction({ saveTarget: killed });
    box.querySelectorAll('.witch-poison').forEach(b => b.onclick = () => submitAction({ poisonTarget: +b.dataset.seat }));
    $('ga-witch-skip').onclick = () => submitAction({ target: 0 });
    return;
  }

  // 目标选择类：GUARD/SEER_CHECK/CROW/SILENCER/WOLF_KILL/SHOOT/VOTE/PK_VOTE/SHERIFF_VOTE
  const hintMap = {
    GUARD: '🛡 选择今晚守护的人', SEER_CHECK: '🔮 选择查验对象', CROW: '🐦‍⬛ 选择诽谤对象（可不发动）',
    SILENCER: '🤫 选择禁言对象（可不发动）', WOLF_KILL: '🐺 选择刀杀目标（可空刀）',
    SHOOT: '🔫 选择开枪带走的人', VOTE: '🗳️ 选择放逐对象', PK_VOTE: '⚔️ PK 投票（只能投平票候选人）', SHERIFF_VOTE: '👑 警长投票'
  };
  const allowPass = ['CROW', 'SILENCER', 'WOLF_KILL', 'SHOOT', 'VOTE', 'SHERIFF_VOTE'].includes(kind);
  // PK 投票只允许在平票候选人之间选择（服务端也只接受这些目标）
  let pickTargets = aliveTargets;
  if (kind === 'PK_VOTE') {
    const pk = new Set(v.pkCandidates || []);
    pickTargets = (v.seats || []).filter(s => pk.has(s.seat));
  }
  let html = `<div class="ga-title">${hintMap[kind] || '选择目标'}</div><div class="ga-targets">`;
  for (const s of pickTargets) html += `<button class="ga-btn target" data-seat="${s.seat}">${s.seat}号 ${esc(s.nickname)}</button>`;
  if (allowPass) html += `<button class="ga-btn" id="ga-pass">放弃/弃票</button>`;
  html += '</div>';
  box.innerHTML = html;
  const verb = { GUARD: '已守护', SEER_CHECK: '已查验', CROW: '已污蔑', SILENCER: '已禁言',
    WOLF_KILL: '已刀杀', SHOOT: '已开枪带走', VOTE: '已投票放逐', PK_VOTE: '已投票', SHERIFF_VOTE: '已投给' };
  box.querySelectorAll('.target').forEach(b => b.onclick = () => submitAction({ target: +b.dataset.seat }, (verb[kind] || '已选择') + ' ' + b.dataset.seat + ' 号'));
  const pass = $('ga-pass'); if (pass) pass.onclick = () => submitAction({ target: 0 }, '✓ 已放弃/弃票');
}

async function submitAction(payload, okMsg) {
  try {
    await API.post('/api/game/action', payload);
    if (okMsg) toast(okMsg);
  } catch (e) { toast(e.message, true); }
}

/* ---------- 白狼王自爆（白天附加可选动作） ---------- */
/** 服务端约定的自爆标记，需与 LiveGameService.BLOWUP_MARKER 保持一致。 */
const BLOWUP_MARKER = '__WHITE_WOLF_BLOWUP__';
/** 白天阶段且自己是存活的白狼王时，显示自爆按钮。 */
function isBlowUpAvailable(v) {
  if (!v || v.phase === 'GAME_OVER') return false;
  const dayPhase = ['DAY_SPEAK', 'SHERIFF_ELECTION', 'DAY_VOTE'].includes(v.phase);
  if (!dayPhase) return false;
  const me = (v.seats || []).find(s => s.seat === v.mySeat);
  const info = v.myInfo || {};
  return !!(me && me.alive && info.role === '白狼王');
}
/** 渲染自爆按钮：点击后选择一个目标带走（复用目标选择列表）。 */
function renderBlowUpUI(v) {
  const box = $('game-action');
  const targets = (v.seats || []).filter(s => s.alive && s.seat !== v.mySeat && !s.idiotRevealed);
  const row = document.createElement('div');
  row.className = 'ga-blowup';
  row.innerHTML = `<button class="ga-btn danger" id="ga-blowup">💥 白狼王自爆（带走一人后直接入夜）</button>`;
  box.appendChild(row);
  $('ga-blowup').onclick = () => {
    if (targets.length === 0) { toast('没有可带走的目标', true); return; }
    row.innerHTML = `<div class="ga-title">💥 选择要带走的玩家</div><div class="ga-targets">` +
      targets.map(s => `<button class="ga-btn danger blowup-target" data-seat="${s.seat}">${s.seat}号 ${esc(s.nickname)}</button>`).join('') +
      `<button class="ga-btn" id="ga-blowup-cancel">取消</button></div>`;
    row.querySelectorAll('.blowup-target').forEach(b => b.onclick =
      () => submitAction({ text: BLOWUP_MARKER, target: +b.dataset.seat }));
    $('ga-blowup-cancel').onclick = () => { row.innerHTML = ''; renderGameAction(v); };
  };
}

/* ---------- 语音：发言面板 + 实时字幕 + 光环 + 模式标记 ---------- */
/* 语音与文字共存：语音模式下麦克风实时转文字填入文本框，也可直接打字，最后一起提交 */
function renderSpeechUI(title, voiceMode, voiceAvailable, anonymous) {
  const box = $('game-action');
  const canMic = !!(voiceMode || voiceAvailable);   // 语音服务在线即可用麦克风
  const direct = !voiceMode && !anonymous;          // 直接语音交流：不伪装、不同传
  const micHint = direct
    ? '（直接语音：说出的内容转文字后全场可见）'
    : (voiceMode ? '（你的声音会被统一音色转述）' : '（文字会以你的音色朗读给全场）');
  const micBtn = canMic ? `<button class="ga-btn confirm" id="ga-mic">🎙 说话</button>` : '';
  const placeholder = canMic
    ? '点🎙说话会自动转成文字，也可直接打字；说完点"结束发言"' + micHint
    : '输入你的发言…（留空则过）';
  box.innerHTML = `<div class="ga-title">🎤 ${title}</div>
    <div class="ga-row">${micBtn}<button class="ga-btn confirm" id="ga-speak-send">结束发言</button>
    <button class="ga-btn" id="ga-speak-pass">过</button></div>
    <textarea class="ga-text" id="ga-speech" placeholder="${placeholder}"></textarea>`;
  const ta = $('ga-speech');
  if (canMic) {
    const mb = $('ga-mic');
    mb.onclick = async () => {
      if (VOICE.isRecording()) { VOICE.stopMic(); mb.textContent = '🎙 说话'; mb.classList.remove('rec'); return; }
      mb.textContent = '开启麦克风…';
      const ok = await VOICE.startMic();
      mb.textContent = ok ? '🔴 停止说话' : '🎙 说话';
      if (ok) mb.classList.add('rec');
    };
  }
  $('ga-speak-send').onclick = () => { if (VOICE.isRecording()) VOICE.stopMic(); submitAction({ text: ta.value.trim() || '（过）' }); };
  $('ga-speak-pass').onclick = () => { if (VOICE.isRecording()) VOICE.stopMic(); submitAction({ text: '（过）' }); };
}

function appendVoiceCaption(m) {
  const el = $('voice-caption'); if (!el) return;
  el.classList.remove('hidden');
  const mySeat = game.view ? game.view.mySeat : -1;
  const line = document.createElement('div');
  line.className = 'vc-line' + (m.kind === 'ai' ? ' ai' : '') + (m.seat === mySeat ? ' me' : '');
  line.innerHTML = `<span class="vc-name">${esc(m.name)}</span><span class="vc-text">${esc(m.text)}</span>`;
  el.appendChild(line);
  while (el.children.length > 7) el.removeChild(el.firstChild);
  el.scrollTop = el.scrollHeight;
  clearTimeout(el._t); el._t = setTimeout(() => { el.innerHTML = ''; }, 14000);
}
function clearVoiceCaption() { const el = $('voice-caption'); if (el) { el.innerHTML = ''; el.classList.add('hidden'); } }
function highlightTalkingSeat() {
  document.querySelectorAll('.gseat').forEach(s => s.classList.remove('talking'));
  if (!game.talkingName || !game.view) return;
  const i = game.view.seats.findIndex(s => s.nickname === game.talkingName);
  if (i >= 0) { const els = document.querySelectorAll('.gseat'); if (els[i]) els[i].classList.add('talking'); }
}
function renderModeBadge(v) {
  const b = $('game-mode'); if (!b) return;
  if (v.anonymous && v.voiceMode) { b.textContent = '🕶 匿名 · 🎙 语音同传'; b.classList.remove('hidden'); }
  else if (v.anonymous) { b.textContent = '🕶 匿名'; b.classList.remove('hidden'); }
  else if (v.voiceMode) { b.textContent = '🎙 语音'; b.classList.remove('hidden'); }
  else b.classList.add('hidden');
}

function initVoiceHooks() {
  window.__onVoiceCaption = (m) => {
    appendVoiceCaption(m);
    if (m.name) { game.talkingName = m.name; highlightTalkingSeat(); }
    const mySeat = game.view ? game.view.mySeat : -1;
    // 我说话时，识别到的文字实时填进发言文本框（与手动打字共存）。
    // 兼容 relay/raw 两种字幕标记：raw=直接语音房原声转发附带的转写，relay=同传/匿名房回填。
    if (m.seat === mySeat && (m.kind === 'relay' || m.kind === 'raw') && $('ga-speech')) {
      $('ga-speech').value += (m.text || '');
    }
  };
  window.__onSpeakingChanged = () => { game.talkingName = null; highlightTalkingSeat(); };
  window.__onVoiceDenied = () => {
    const m = $('ga-mic'); if (m) { m.disabled = false; m.textContent = '🎙 开始说话'; }
  };
}

/* ---------- 房间模式面板 ---------- */
async function fetchVoiceStatus() {
  try {
    const s = await API.get('/api/voice/status');
    state.voiceAvail = !!(s.allowVoiceMode && s.health && s.health.asr && s.health.asr.ok && s.health.tts && s.health.tts.ok);
    if (s.enabled && !state.voiceAvail) {
      const a = (s.health && s.health.asr) || {}, t = (s.health && s.health.tts) || {};
      toast('语音服务未就绪：' + (a.ok ? '' : 'ASR ') + (t.ok ? '' : 'TTS '), true);
    }
  } catch (_) { state.voiceAvail = false; }
}

/* 恢复当前生效的维护通知（新登录/刷新页面也能看到底部航道） */
async function loadMaintenance() {
  try { const n = await API.get('/api/room/notice'); if (n && n.message) DM.notice(n.message, n.expiry); }
  catch (_) {}
}

function renderModePanel() {
  const room = state.room; if (!room) return;
  const isHost = state.user.id === room.hostUserId && !state.isSpectator;
  const panel = $('mode-panel');
  const show = isHost && room.status === 'WAITING';
  panel.classList.toggle('hidden', !show);
  if (!show) return;
  $('vm-toggle').checked = !!room.voiceMode;
  $('anon-toggle').checked = !!room.anonymous;
  $('hunt-toggle').checked = !!room.huntCity;
  $('duo-toggle').checked = !!room.duoMode;
  $('item-toggle').checked = room.itemMatch !== false;
  $('vm-toggle').disabled = !state.voiceAvail;
  $('voice-status').textContent = state.voiceAvail ? '· 语音服务在线' : '· 语音服务未就绪（匿名仍可用）';
}

async function saveMode() {
  try {
    await API.post('/api/room/mode', { voiceMode: $('vm-toggle').checked, anonymous: $('anon-toggle').checked, huntCity: $('hunt-toggle').checked, duoMode: $('duo-toggle').checked });
    try { await API.post('/api/room/item', { itemMatch: $('item-toggle').checked }); } catch (_) {}
    toast('模式已更新');
  } catch (e) { toast(e.message, true); }
}

/* ---------- 倒计时 ---------- */
function startCountdown(v) {
  if (game.tick) { clearInterval(game.tick); game.tick = null; }
  const el = $('game-timer');
  // 隐私：只在轮到自己时显示倒计时，他人的思考时长不可见
  if (!v.myTurn || !v.deadlineMs) { el.textContent = ''; return; }
  const upd = () => {
    const left = Math.max(0, Math.round((v.deadlineMs - Date.now()) / 1000));
    el.textContent = `⏳ 你 ${left}s`;
    el.classList.toggle('urgent', left <= 10);
    if (left <= 0) { clearInterval(game.tick); game.tick = null; }
  };
  upd();
  game.tick = setInterval(upd, 500);
}

/* ---------- 结算 ---------- */
function showSettle(v) {
  $('settle-winner').textContent = '胜利方：' + (v.winner || '—');
  const box = $('settle-roles');
  box.innerHTML = '';
  const gid = v.gameId;
  for (const s of v.seats) {
    const el = document.createElement('div');
    const isWolf = s.role && s.role.indexOf('狼') >= 0;
    el.className = 'sr' + (isWolf ? ' wolf' : '');
    const isMe = s.userId && s.userId === state.user.id;
    let acts = '';
    if (gid && s.userId && !isMe) {
      acts = `<span class="sr-kudos">`
        + `<button class="ku-btn" data-u="${s.userId}" data-t="PRAISE" title="点赞">👍</button>`
        + `<button class="ku-btn" data-u="${s.userId}" data-t="COMFORT" title="安慰">🫂</button>`
        + `<button class="ku-btn" data-u="${s.userId}" data-t="REPORT" title="举报">🚩</button></span>`;
    }
    el.innerHTML = `${s.seat}号 ${esc(s.nickname)}：<b>${s.role || '?'}</b>${s.alive ? '' : '（已出局）'}${acts}`;
    box.appendChild(el);
  }
  box.querySelectorAll('.ku-btn').forEach(b => b.onclick = () => {
    const type = b.dataset.t;
    const note = type === 'REPORT' ? (prompt('举报原因（可选）') || '') : '';
    API.post('/api/game/kudos', { gameId: gid, toUserId: +b.dataset.u, type, note })
      .then(() => { b.classList.add('done'); toast(type === 'PRAISE' ? '已点赞 👍' : type === 'COMFORT' ? '已安慰 🫂' : '已举报 🚩'); })
      .catch(e => toast(e.message, true));
  });
  $('modal-settle').classList.remove('hidden');
}

function bindGameUI() {
  $('btn-back-lobby').addEventListener('click', returnToRoom);
  const rr = $('btn-return-room'); if (rr) rr.addEventListener('click', returnToRoom);
  const mc = $('btn-marker-clear'); if (mc) mc.addEventListener('click', () => { MARK.clear(); renderMarkers(game.view); toast('已清空标记'); });
  const endBtn = $('btn-end-game');
  if (endBtn) endBtn.addEventListener('click', async () => {
    if (!confirm('确定强制结束本局？将终止所有 AI 调用并让房间回到等待中。')) return;
    try { await API.post('/api/game/end'); } catch (e) { toast(e.message, true); }
  });
  const leaveFn = () => leaveGameRoom();
  const leaveBtn = $('btn-leave-game'); if (leaveBtn) leaveBtn.addEventListener('click', leaveFn);
  const settleLeave = $('btn-settle-leave'); if (settleLeave) settleLeave.addEventListener('click', leaveFn);
}

/** 对局结束后返回房间：关闭结算弹窗，回到房间等待页（若房间已解散则回大厅），本局对局视图不再展示。 */
async function returnToRoom() {
  $('modal-settle').classList.add('hidden');
  if (game.tick) { clearInterval(game.tick); game.tick = null; }
  game.view = null;
  try { VOICE.disconnect(); } catch (_) {}
  clearVoiceCaption(); game.talkingName = null; game.prevVoiceMode = false;
  const rr = $('btn-return-room'); if (rr) rr.classList.add('hidden');
  try {
    const r = await API.get('/api/room/my');
    if (r.room && r.room.roomNo) { applyRoomState(r.room); showView('room'); }   // applyRoomState 从 game 视图不会自动切页，这里强制进入房间页
    else showView('lobby');
  }
  catch (_) { showView('lobby'); }
}

/** 退出房间（对局中或结算后均可）：调用离开接口并回大厅，你的回合由系统代打。 */
async function leaveGameRoom() {
  if (!confirm('退出房间？（对局中退出后你的回合将由系统代打）')) return;
  try {
    await API.post('/api/room/leave');
    try { VOICE.disconnect(); } catch (_) {}
    clearVoiceCaption(); game.view = null; game.prevVoiceMode = false;
    $('modal-settle').classList.add('hidden');
    state.room = null; state.mySeat = null; state.isSpectator = false;
    showView('lobby');
  } catch (e) { toast(e.message, true); }
}

/** 收到“对局结束”广播（房主强制结束）：退出对局视图，回到房间/大厅。 */
async function onGameEnded(msg) {
  if (game.tick) { clearInterval(game.tick); game.tick = null; }
  try { VOICE.disconnect(); } catch (_) {}
  clearVoiceCaption(); game.talkingName = null; game.prevVoiceMode = false; game.view = null;
  $('modal-settle').classList.add('hidden');
  toast(msg.reason || '本局已结束');
  try {
    const r = await API.get('/api/room/my');
    if (r.room && r.room.roomNo) applyRoomState(r.room); else showView('lobby');
  } catch (_) { showView('lobby'); }
}

/* ================= M4 装饰渲染 + 主页 + 商店 ================= */
const TYPE_LABEL = { AVATAR: '头像', FRAME: '头像框', TITLE: '称号', NICKCOLOR: '昵称颜色', FUNCTION: '功能道具' };

function renderAvatarHtml(o, size) {
  // 安全：头像地址仅允许白名单前缀且经 esc 转义；颜色值经白名单校验，非法回退默认色，防止内联样式/属性注入。
  const url = safeAvatarUrl(o.avatarUrl);
  const inner = url ? `<img src="${url}" alt="">` : `<span style="font-size:${Math.round(size * 0.6)}px">${avatarEmoji(o.avatarId)}</span>`;
  const frame = safeColor(o.frameColor);
  const bg = frame ? `background:${frame};` : 'background:var(--border-dim);';
  return `<div class="avatar-frame" style="width:${size}px;height:${size}px;${bg}"><div class="avatar-inner">${inner}</div></div>`;
}

async function openProfile() {
  showView('profile');
  try {
    const [profile, inventory, trans, stats] = await Promise.all([
      API.get('/api/user/profile'),
      API.get('/api/shop/inventory'),
      API.get('/api/user/transactions'),
      API.get('/api/user/stats'),
    ]);
    renderProfile(profile, inventory, trans, stats);
  } catch (e) { toast(e.message, true); }
}

function renderProfile(p, inv, trans, stats) {
  $('pf-avatar').innerHTML = renderAvatarHtml(p, 72);
  $('pf-nick').textContent = p.nickname;
  $('pf-nick').style.color = safeColor(p.nickColor);
  $('pf-title').textContent = p.title || '';
  $('pf-title').classList.toggle('hidden', !p.title);
  $('pf-sub').textContent = `@${p.username} · Lv.${p.level} · 💰${p.gold}`;
  $('pf-nick-input').value = p.nickname;
  $('pf-birthday').value = p.birthday || '';
  $('pf-location').value = p.location || '';
  $('pf-regloc').value = p.regLocation || '';
  $('pf-signature').value = p.signature || '';
  $('pf-voicegender').value = p.voiceGender || '';
  $('pf-info-line').innerHTML = `注册时间 ${(p.createdAt || '').slice(0, 10)} ｜ 累计在线 ${fmtDur(p.onlineSeconds)} ｜ 上次在线 ${(p.lastOnlineAt || '—').replace('T', ' ').slice(0, 16)}`;
  loadRoleStats();
  loadVip();

  // 当前装扮
  const eq = $('pf-equips');
  eq.innerHTML = '';
  const slots = [
    { label: '头像', value: p.avatarUrl ? '自定义头像' : ('默认 #' + p.avatarId), type: 'AVATAR' },
    { label: '头像框', value: p.frameName || '无', type: 'FRAME', color: p.frameColor },
    { label: '称号', value: p.title || '无', type: 'TITLE' },
    { label: '昵称颜色', value: p.nickColor || '默认', type: 'NICKCOLOR', color: p.nickColor },
  ];
  for (const s of slots) {
    const row = document.createElement('div');
    row.className = 'equip-row';
    const sc = safeColor(s.color);
    const sw = sc ? `<span class="er-swatch" style="background:${sc}"></span>` : '';
    row.innerHTML = `<span class="er-label">${s.label}</span>
      <span class="er-value">${sw}${escapeHtml(String(s.value))}</span>
      <button class="btn btn-sm btn-ghost" data-t="${s.type}">卸下</button>`;
    row.querySelector('button').onclick = () => unequip(s.type);
    eq.appendChild(row);
  }

  // 背包
  const box = $('pf-inventory');
  box.innerHTML = '';
  if (!inv.length) box.innerHTML = '<div style="color:var(--text-dim);font-size:13px">背包空空如也，去商店逛逛吧～</div>';
  for (const it of inv) {
    const card = document.createElement('div');
    card.className = 'item-card';
    const fn = it.type === 'FUNCTION';
    card.innerHTML = `<div class="ic-icon">${itemIcon(it)}</div>
      <div class="ic-name">${escapeHtml(it.name)}</div>
      <div class="ic-desc">${TYPE_LABEL[it.type] || it.type}</div>
      <button class="btn btn-sm">${fn ? '携带' : '穿戴'}</button>`;
    card.querySelector('button').onclick = () => fn ? carryItem(it.itemDefId) : equipItem(it.itemDefId);
    box.appendChild(card);
  }

  // 流水
  const tb = $('pf-trans');
  tb.innerHTML = '';
  if (!trans.length) tb.innerHTML = '<div style="color:var(--text-dim);font-size:13px">暂无流水记录</div>';
  for (const t of trans) {
    const cls = t.delta >= 0 ? 'plus' : 'minus';
    const el = document.createElement('div');
    el.className = 'trans-item ' + cls;
    el.innerHTML = `<div><div class="ti-reason">${escapeHtml(t.reason)}</div>
        <div class="ti-time">${(t.time || '').replace('T', ' ').slice(0, 19)} · 余额 ${t.balanceAfter}</div></div>
      <div class="ti-delta ${cls}">${t.delta >= 0 ? '+' : ''}${t.delta}</div>`;
    tb.appendChild(el);
  }

  // 战绩
  const st = stats || {};
  $('pf-stats').innerHTML = `
    <div class="stat-cell"><b>${st.total || 0}</b><span>总场次</span></div>
    <div class="stat-cell"><b>${st.winRate || 0}%</b><span>胜率</span></div>
    <div class="stat-cell"><b>${st.wolfWinRate || 0}%</b><span>狼人胜率(${st.wolfGames || 0})</span></div>
    <div class="stat-cell"><b>${st.goodWinRate || 0}%</b><span>好人胜率(${st.goodGames || 0})</span></div>`;
  const rl = $('pf-recent');
  rl.innerHTML = '';
  const recent = st.recent || [];
  if (!recent.length) rl.innerHTML = '<div style="color:var(--text-dim);font-size:13px">还没有对局记录</div>';
  for (const m of recent) {
    const el = document.createElement('div');
    el.className = 'recent-item' + (m.won ? ' win' : ' lose');
    el.innerHTML = `<span>${m.role}·${m.faction === 'WOLF' ? '狼' : '好人'} ${m.won ? '胜' : '负'}${m.mvp ? ' 🏆' : ''}${m.survived ? '' : ' ✝'}</span>
      <button class="btn btn-sm btn-ghost" data-g="${m.gameId}">回放</button>`;
    el.querySelector('button').onclick = () => openReplay(+m.gameId);
    rl.appendChild(el);
  }
}

function itemIcon(it) {
  if (it.type === 'AVATAR') return avatarEmoji(+it.asset);
  if (it.type === 'NICKCOLOR' || it.type === 'FRAME') return `<span style="display:inline-block;width:26px;height:26px;border-radius:50%;background:${safeColor(it.asset, 'var(--border-dim)')}"></span>`;
  if (it.type === 'FUNCTION') return ({ REVEAL: '🔍', SHIELD: '🛡', SILENCE: '🤐', DOUBLE: '✨', DOUBLE_VOTE: '🗳️', IMMUNE: '🎎', REVIVE: '💗', IRON: '🦾', EXPDOUBLE: '📈', SHIELD_FULL: '💠' })[it.asset] || '🧪';
  return '🏅';
}

async function openShop() {
  showView('shop');
  try {
    const [catalog, me] = await Promise.all([API.get('/api/shop/catalog'), API.get('/api/auth/me')]);
    renderShop(catalog, me.gold);
  } catch (e) { toast(e.message, true); }
}

function renderShop(catalog, gold) {
  $('shop-gold').textContent = gold;
  const box = $('shop-list');
  box.innerHTML = '';
  const groups = {};
  for (const it of catalog) (groups[it.type] ||= []).push(it);
  for (const type of ['FUNCTION', 'AVATAR', 'FRAME', 'TITLE', 'NICKCOLOR']) {
    const items = groups[type];
    if (!items || !items.length) continue;
    const h = document.createElement('div');
    h.className = 'shop-cat-title';
    h.textContent = '— ' + (TYPE_LABEL[type] || type) + ' —';
    box.appendChild(h);
    const grid = document.createElement('div');
    grid.className = 'item-grid';
    for (const it of items) {
      const card = document.createElement('div');
      card.className = 'item-card' + (it.owned ? ' owned' : '');
      card.innerHTML = `<div class="ic-icon">${itemIcon(it)}</div>
        <div class="ic-name">${escapeHtml(it.name)}</div>
        <div class="ic-desc">${escapeHtml(it.description || '')}</div>
        <div class="ic-price">${it.discounted ? `<s style="color:var(--text-dim);font-size:12px">💰${it.origPrice}</s> ` : ''}💰 ${it.price}${it.discounted ? ' <span class="vip-badge v1">VIP</span>' : ''}</div>
        ${it.owned ? '<span class="ic-badge">已拥有</span>' : '<button class="btn btn-sm">购买</button>'}`;
      const b = card.querySelector('button');
      if (b) b.onclick = () => buyItem(it.id);
      grid.appendChild(card);
    }
    box.appendChild(grid);
  }
}

async function buyItem(id) {
  try { const r = await API.post('/api/shop/buy', { itemDefId: id }); toast('购买成功'); openShop(); }
  catch (e) { toast(e.message, true); }
}
async function equipItem(id) {
  try { await API.post('/api/user/equip', { itemDefId: id }); toast('已穿戴'); openProfile(); state.user = await API.get('/api/auth/me'); renderUser(); }
  catch (e) { toast(e.message, true); }
}
async function carryItem(id) {
  try { await API.post('/api/user/carry', { itemDefId: id }); toast('已携带，下一局生效'); }
  catch (e) { toast(e.message, true); }
}

/* ================= 好友系统 ================= */
const FR = {
  peer: null, friends: [],
  async open() { showView('friends'); this.switchTab('list'); this.loadFriends(); this.loadRequests(); this.loadUnread(); },
  switchTab(t) {
    document.querySelectorAll('.fr-tabs .tab').forEach(x => x.classList.toggle('active', x.dataset.ft === t));
    $('fr-list').classList.toggle('hidden', t !== 'list');
    $('fr-req').classList.toggle('hidden', t !== 'req');
    $('fr-search').classList.toggle('hidden', t !== 'search');
    if (t === 'req') this.loadRequests();
  },
  async loadFriends() {
    try {
      this.friends = await API.get('/api/friends');
      const box = $('fr-list');
      if (!this.friends.length) { box.innerHTML = '<div class="fr-empty">还没有好友，去「查找」添加吧</div>'; return; }
      box.innerHTML = '';
      for (const f of this.friends) {
        const el = document.createElement('div'); el.className = 'fr-item' + (this.peer && this.peer.id === f.id ? ' sel' : '');
        el.innerHTML = `<div class="fr-ava">${renderAvatarHtml(f, 34)}</div>
          <div class="fr-main"><div class="fr-name" style="${colorStyle(f.nickColor, 'color')}">${esc(f.nickname)}</div>
          <div class="fr-sub">${f.online ? '<span class="fr-on">在线</span>' : '离线'} · Lv.${f.level}${f.title ? ' · ' + esc(f.title) : ''}</div></div>
          ${f.unread ? `<span class="fr-badge">${f.unread}</span>` : ''}
          <div class="fr-acts"><button class="btn btn-sm" data-a="chat">聊天</button><button class="btn btn-sm btn-ghost" data-a="prof">档案</button><button class="btn btn-sm btn-ghost" data-a="del">删除</button></div>`;
        el.querySelector('[data-a=chat]').onclick = () => this.openChat(f);
        el.querySelector('[data-a=prof]').onclick = () => this.showProfile(f.id);
        el.querySelector('[data-a=del]').onclick = () => { if (confirm('删除好友 ' + f.nickname + '？')) API.post('/api/friends/remove', { id: f.id }).then(() => this.loadFriends()); };
        box.appendChild(el);
      }
    } catch (e) { toast(e.message, true); }
  },
  async loadRequests() {
    try {
      const reqs = await API.get('/api/friends/requests');
      const box = $('fr-req');
      if (!reqs.length) { box.innerHTML = '<div class="fr-empty">暂无好友申请</div>'; return; }
      box.innerHTML = '';
      for (const r of reqs) {
        const u = r.from; const el = document.createElement('div'); el.className = 'fr-item';
        el.innerHTML = `<div class="fr-ava">${renderAvatarHtml(u, 34)}</div>
          <div class="fr-main"><div class="fr-name">${esc(u.nickname)}</div><div class="fr-sub">${esc(r.greeting || '请求添加你为好友')}</div></div>
          <div class="fr-acts"><button class="btn btn-sm btn-primary" data-a="ok">接受</button><button class="btn btn-sm" data-a="no">拒绝</button></div>`;
        el.querySelector('[data-a=ok]').onclick = () => API.post('/api/friends/requests/' + r.reqId + '/accept', {}).then(() => { this.loadRequests(); this.loadFriends(); this.loadUnread(); });
        el.querySelector('[data-a=no]').onclick = () => API.post('/api/friends/requests/' + r.reqId + '/reject', {}).then(() => { this.loadRequests(); this.loadUnread(); });
        box.appendChild(el);
      }
    } catch (e) {}
  },
  async doSearch() {
    const kw = $('fr-kw').value.trim(); if (!kw) { $('fr-results').innerHTML = ''; return; }
    try {
      const list = await API.get('/api/friends/search?kw=' + encodeURIComponent(kw));
      const box = $('fr-results');
      if (!list.length) { box.innerHTML = '<div class="fr-empty">没有找到用户</div>'; return; }
      box.innerHTML = '';
      for (const u of list) {
        const el = document.createElement('div'); el.className = 'fr-item';
        const act = u.isFriend ? '<span class="fr-tag">已添加</span>' : u.pending ? '<span class="fr-tag">待处理</span>' : '<button class="btn btn-sm btn-primary" data-a="add">添加</button>';
        el.innerHTML = `<div class="fr-ava">${renderAvatarHtml(u, 34)}</div>
          <div class="fr-main"><div class="fr-name">${esc(u.nickname)}</div><div class="fr-sub">@${esc(u.username)} · Lv.${u.level}</div></div><div class="fr-acts">${act}</div>`;
        const b = el.querySelector('[data-a=add]'); if (b) b.onclick = () => API.post('/api/friends/request', { userId: u.id }).then(() => { toast('已发送好友申请'); this.doSearch(); });
        box.appendChild(el);
      }
    } catch (e) { toast(e.message, true); }
  },
  openChat(f) {
    this.peer = f; this.switchTab('list'); this.loadFriends();
    $('fr-chat-head').innerHTML = `与 <b>${esc(f.nickname)}</b> 的对话`;
    this.loadConversation();
  },
  async loadConversation() {
    if (!this.peer) return;
    try {
      const msgs = await API.get('/api/friends/messages?peerId=' + this.peer.id);
      const box = $('fr-chat'); box.innerHTML = '';
      for (const m of msgs) box.appendChild(this.msgEl(m));
      box.scrollTop = box.scrollHeight;
    } catch (e) {}
  },
  msgEl(m) {
    const el = document.createElement('div'); el.className = 'pm ' + (m.mine ? 'mine' : '');
    if (m.type === 'invite') {
      el.innerHTML = `<div class="pm-invite">🏠 邀请你加入房间 <b>${esc(m.roomNo || '')}</b> <button class="btn btn-sm" data-j="${esc(m.roomNo || '')}">加入</button></div>`;
      const jb = el.querySelector('[data-j]'); if (jb) jb.onclick = () => { if (jb.dataset.j) joinRoom(jb.dataset.j); };
    } else {
      el.innerHTML = `<div class="pm-bubble">${esc(m.content)}</div><div class="pm-time">${(m.at || '').slice(11, 16)}</div>`;
    }
    return el;
  },
  async sendMsg() {
    if (!this.peer) { toast('先选择一位好友', true); return; }
    const t = $('fr-msg').value.trim(); if (!t) return;
    $('fr-msg').value = '';
    try { const m = await API.post('/api/friends/messages', { toId: this.peer.id, content: t }); m.mine = true; const box = $('fr-chat'); box.appendChild(this.msgEl(m)); box.scrollTop = box.scrollHeight; }
    catch (e) { toast(e.message, true); }
  },
  async showProfile(id) {
    try {
      const p = await API.get('/api/friends/profile/' + id);
      $('fp-body').innerHTML = `<div class="fp-top"><div>${renderAvatarHtml(p, 72)}</div>
        <div><div class="fp-nick" style="${colorStyle(p.nickColor, 'color')}">${esc(p.nickname)}</div>
        <div class="fp-sub">@${esc(p.username)} · Lv.${p.level}${p.online ? ' · <span class="fr-on">在线</span>' : ' · 离线'}</div>
        ${p.title ? `<div class="fp-title">${esc(p.title)}</div>` : ''}
        ${p.signature ? `<div class="fp-sig">📝 ${esc(p.signature)}</div>` : ''}</div></div>
        <div class="fp-grid"><div><b>${p.exp}</b><span>经验</span></div><div><b>${p.gold}</b><span>金币</span></div></div>
        <div class="fp-stats">
          <div class="fs-title">对局数据</div>
          <div class="fp-grid">
            <div><b>${p.games ?? 0}</b><span>总场次</span></div>
            <div><b>${p.winRate ?? 0}%</b><span>总胜率</span></div>
            <div><b>${p.mvpCount ?? 0}</b><span>MVP</span></div>
          </div>
          <div class="fp-grid">
            <div><b>${p.wolfWinRate ?? 0}%</b><span>狼人胜率 · ${p.wolfGames ?? 0}场</span></div>
            <div><b>${p.goodWinRate ?? 0}%</b><span>好人胜率 · ${p.goodGames ?? 0}场</span></div>
          </div>
        </div>
        <div class="fp-meta">${p.birthday ? '🎂 ' + esc(p.birthday) + '　' : ''}${p.location ? '📍 ' + esc(p.location) + '　' : ''}${p.regLocation ? '🏠 ' + esc(p.regLocation) : ''}</div>`;
      $('modal-friend-profile').classList.remove('hidden');
    } catch (e) { toast(e.message, true); }
  },
  async loadUnread() {
    try {
      const u = await API.get('/api/friends/unread'); const total = (u.messages || 0) + (u.requests || 0);
      const badge = $('friends-badge'); if (badge) { badge.textContent = total > 99 ? '99+' : total; badge.classList.toggle('hidden', !total); }
      const dot = $('fr-req-dot'); if (dot) dot.classList.toggle('hidden', !(u.requests > 0));
    } catch (e) {}
  },
  onIncomingRequest(msg) { toast('👥 ' + ((msg.from && msg.from.nickname) || '有人') + ' 请求添加你为好友'); this.loadRequests(); this.loadUnread(); },
  onAccepted(msg) { toast('✅ ' + ((msg.user && msg.user.nickname) || '对方') + ' 已是你的好友'); this.loadFriends(); },
  onChat(msg) {
    if (this.peer && this.peer.id === msg.from) { const box = $('fr-chat'); msg.mine = false; box.appendChild(this.msgEl(msg)); box.scrollTop = box.scrollHeight; }
    else toast('💬 ' + (msg.fromName || '收到新消息'));
    this.loadUnread(); this.loadFriends();
  },
  onRoomInvite(msg) {
    const from = msg.fromName || '好友'; const no = msg.roomNo;
    if (confirm('🏠 ' + from + ' 邀请你加入房间 ' + no + '，是否前往？')) joinRoom(no);
  },
};

async function openInviteModal() {
  try {
    const list = await API.get('/api/friends');
    const box = $('invite-list');
    box.innerHTML = '';
    if (!list.length) box.innerHTML = '<div class="fr-empty">还没有好友，先在好友页添加</div>';
    for (const f of list) {
      const el = document.createElement('div'); el.className = 'fr-item';
      el.innerHTML = `<div class="fr-ava">${renderAvatarHtml(f, 30)}</div><div class="fr-main"><div class="fr-name">${esc(f.nickname)}</div></div><div class="fr-acts"><button class="btn btn-sm btn-primary">邀请</button></div>`;
      el.querySelector('button').onclick = async () => { await API.post('/api/friends/messages', { toId: f.id, type: 'invite', content: '邀请你加入房间', roomNo: state.room ? state.room.roomNo : '' }); toast('已邀请 ' + f.nickname); };
      box.appendChild(el);
    }
    $('modal-invite').classList.remove('hidden');
  } catch (e) { toast(e.message, true); }
}

function bindFriendsUI() {
  $('btn-open-friends').addEventListener('click', () => FR.open());
  $('btn-friends-back').addEventListener('click', () => showView('lobby'));
  document.querySelectorAll('.fr-tabs .tab').forEach(t => t.onclick = () => FR.switchTab(t.dataset.ft));
  $('fr-search-btn').addEventListener('click', () => FR.doSearch());
  $('fr-kw').addEventListener('keydown', e => { if (e.key === 'Enter') FR.doSearch(); });
  $('fr-send').addEventListener('click', () => FR.sendMsg());
  $('fr-msg').addEventListener('keydown', e => { if (e.key === 'Enter') FR.sendMsg(); });
  $('btn-fp-close').addEventListener('click', () => $('modal-friend-profile').classList.add('hidden'));
  $('btn-invite-close').addEventListener('click', () => $('modal-invite').classList.add('hidden'));
  $('btn-invite-friend').addEventListener('click', openInviteModal);
}

/* ================= 开局抽牌动画 ================= */
function playDeal(role, faction) {
  const ov = $('deal-overlay'); if (!ov) return;
  ov.classList.remove('hidden');
  const card = $('deal-card'), deck = $('deal-deck');
  deck.innerHTML = '';
  for (let i = 0; i < 8; i++) { const c = document.createElement('div'); c.className = 'deal-mini'; c.style.animationDelay = (i * 120) + 'ms'; deck.appendChild(c); }
  card.classList.remove('flip');
  $('deal-role').textContent = '';   // 发牌阶段先不显示身份
  $('deal-fac').textContent = '';
  $('deal-text').textContent = '正在发牌…';
  // 先发牌（约 2s），再翻牌揭示身份
  setTimeout(() => {
    card.classList.add('flip');
    $('deal-role').textContent = role || '?';
    $('deal-fac').textContent = faction === 'WOLF' ? '🐺 狼人阵营' : '🌕 好人阵营';
    $('deal-text').textContent = '你的身份';
  }, 2000);
  setTimeout(() => ov.classList.add('hidden'), 4000);
}

/* ================= 对局回放 ================= */
const replay = { events: [], idx: 0, timer: null };

async function openReplay(gameId) {
  showView('replay');
  replay.events = []; replay.idx = 0; if (replay.timer) { clearInterval(replay.timer); replay.timer = null; }
  $('replay-list').innerHTML = '<div style="color:var(--text-dim)">加载中…</div>';
  try {
    const r = await API.get('/api/game/replay/' + gameId);
    $('replay-meta').textContent = `房间 ${r.roomNo} · ${r.winner || ''} 胜 · ${r.startedAt ? r.startedAt.replace('T',' ').slice(0,16) : ''}`;
    renderReplayRoles(r.seatRoles);
    replay.events = r.events || [];
    $('replay-list').innerHTML = '';
    replayStepAll();
  } catch (e) { $('replay-list').innerHTML = '<div style="color:var(--blood)">' + esc(e.message) + '</div>'; }
}

function renderReplayRoles(seatRoles) {
  const box = $('replay-roles'); box.innerHTML = '';
  if (!seatRoles) return;
  for (const tok of seatRoles.split(/\s+/)) {
    if (!tok) continue;
    const [seat, role] = tok.split(':');
    const el = document.createElement('div');
    el.className = 'sr' + (role && role.includes('狼') ? ' wolf' : '');
    el.innerHTML = `${seat}号：<b>${role || '?'}</b>`;
    box.appendChild(el);
  }
}

function replayLineHtml(e) {
  const kind = ({ SPEECH: '🎤', LAST_WORDS: '💀', PLAYER_DIED: '☠️', NIGHT_ACTION: '🌙', NIGHT_RESOLVE: '⚖️',
    DAWN_ANNOUNCE: '🌅', VOTE_RESULT: '🗳️', VOTE_DETAIL: '🗳', VOTE_TIE: '⚔️', SHOOT: '🔫', IDIOT_REVEAL: '🤪',
    WHITE_WOLF_BLOWUP: '💥', SHERIFF_WIN: '👑', SHERIFF_SIGNUP: '🙋', ITEM_REVEAL: '🔍', ITEM_EFFECT: '🧪',
    GAME_OVER: '🏁', GAME_START: '🎬' })[e.type] || '·';
  return `<div class="rp-item ${e.type.includes('NIGHT') ? 'rp-night' : ''}"><span class="rp-day">第${e.day}天</span> ${kind} ${esc(replayDetailZh(e))}</div>`;
}

function replayDetailZh(e) {
  const d = e.detail || '';
  const seat = (n) => n ? (n + '号') : '';
  switch (e.type) {
    case 'GAME_START': return '对局开始';
    case 'NIGHT_ACTION': {
      if (d.startsWith('SEER_CHECK=')) return `预言家查验 ${seat(e.target)} = ${d.endsWith('WOLF') ? '狼人' : '好人'}`;
      const base = d.split('=')[0];
      const map = {
        GUARD: `守卫守护 ${seat(e.target) || '空'}`,
        WOLF_VOTE: `狼人(${seat(e.actor)})提议刀 ${seat(e.target) || '空刀'}`,
        WOLF_KILL_RESOLVED: `狼队决定刀 ${seat(e.target) || '空刀'}`,
        WITCH_SAVE: `女巫(${seat(e.actor)})救 ${seat(e.target)}`,
        WITCH_POISON: `女巫(${seat(e.actor)})毒 ${seat(e.target)}`,
        CROW_ACCUSE: `乌鸦诽谤 ${seat(e.target) || '无'}`,
        SILENCER: `禁言长老禁言 ${seat(e.target) || '无'}`,
      };
      return map[base] || d;
    }
    case 'NIGHT_RESOLVE': {
      if (d.startsWith('wolfKill=')) {
        const p = {}; d.split(' ').forEach(kv => { const [k, v] = kv.split('='); p[k] = v; });
        return `夜晚结算：狼刀${seat(+p.wolfKill) || '无'} 守护${seat(+p.guard) || '无'} 救${seat(+p.save) || '无'} 毒${seat(+p.poison) || '无'}`;
      }
      return d;
    }
    case 'DAWN_ANNOUNCE': {
      const m = d.match(/\[([\d,\s]*)\]/); const ids = m ? m[1].split(',').map(s => s.trim()).filter(Boolean) : [];
      return ids.length ? `天亮了，夜晚出局：${ids.map(i => i + '号').join('、')}` : '天亮了，昨夜平安';
    }
    case 'PLAYER_DIED': return `${seat(e.actor)} 出局（${causeZh(d)}）`;
    case 'SPEECH': return `${seat(e.actor)} 发言：${d}`;
    case 'LAST_WORDS': return `${seat(e.actor)} 遗言：${d}`;
    case 'VOTE_RESULT': return e.actor ? `投票结果：${seat(e.actor)} 被放逐` : `投票结果：${d}`;
    case 'VOTE_DETAIL': return d;
    case 'VOTE_TIE': return d;
    case 'SHOOT': return `${seat(e.actor)} ${d}${e.target ? '，带走 ' + seat(e.target) : ''}`;
    case 'IDIOT_REVEAL': return `${seat(e.actor)} 白痴翻牌免死`;
    case 'WHITE_WOLF_BLOWUP': return `${seat(e.actor)} 白狼王自爆${e.target ? '，带走 ' + seat(e.target) : ''}`;
    case 'SHERIFF_WIN': return e.actor ? `${seat(e.actor)} 当选警长` : `警长：${d}`;
    case 'SHERIFF_SIGNUP': return `${seat(e.actor)} ${d === '上警' ? '上警竞选' : '不上警'}`;
    case 'ITEM_REVEAL': return `${seat(e.actor)} 使用查杀卡：${seat(e.target)} 是${d}`;
    case 'ITEM_EFFECT': return `${seat(e.actor)} ${d}`;
    case 'GAME_OVER': return `游戏结束，${d.replace('winner=', '')}获胜`;
    default: return `${seat(e.actor)} ${d}`;
  }
}

function replayStep() {
  if (replay.idx >= replay.events.length) { if (replay.timer) { clearInterval(replay.timer); replay.timer = null; } return; }
  const e = replay.events[replay.idx++];
  const el = document.createElement('div');
  el.innerHTML = replayLineHtml(e);
  $('replay-list').appendChild(el.firstElementChild);
  $('replay-list').scrollTop = $('replay-list').scrollHeight;
  $('replay-progress').textContent = replay.idx + ' / ' + replay.events.length;
}
function replayStepAll() {
  while (replay.idx < replay.events.length) replayStep();
}
function replayPlay() {
  if (replay.timer) return;
  if (replay.idx >= replay.events.length) { replay.idx = 0; $('replay-list').innerHTML = ''; }
  const spd = +($('replay-speed').value || 600);
  replay.timer = setInterval(replayStep, spd);
}
function replayPause() { if (replay.timer) { clearInterval(replay.timer); replay.timer = null; } }

function bindReplayUI() {
  // 返回前先暂停回放定时器，避免离开回放页后 setInterval 继续跑导致定时器泄漏
  $('btn-replay-back').addEventListener('click', () => { replayPause(); openProfile(); });
  $('btn-replay-play').addEventListener('click', replayPlay);
  $('btn-replay-pause').addEventListener('click', replayPause);
  $('btn-replay-step').addEventListener('click', () => { replayPause(); replayStep(); });
}
async function unequip(type) {
  try { await API.post('/api/user/unequip', { type }); openProfile(); state.user = await API.get('/api/auth/me'); renderUser(); }
  catch (e) { toast(e.message, true); }
}
async function changeNick() {
  const nick = $('pf-nick-input').value.trim();
  try { await API.post('/api/user/nickname', { nickname: nick }); toast('昵称已更新'); state.user.nickname = nick; renderUser(); openProfile(); }
  catch (e) { toast(e.message, true); }
}
async function uploadAvatar(file) {
  const fd = new FormData();
  fd.append('file', file);
  const token = localStorage.getItem('ww_token');
  try {
    const res = await fetch('/api/user/avatar', { method: 'POST', headers: { 'Authorization': 'Bearer ' + token }, body: fd });
    const data = await res.json().catch(() => ({}));
    if (!res.ok) throw new Error(data.error || '上传失败');
    // 上传成功：接口返回最新 user（含新 avatarUrl），同步本地态并重画大厅头像
    if (data && data.id) { state.user = data; renderUser(); }
    toast('头像已更新'); openProfile();
  } catch (e) { toast(e.message, true); }
}

function bindProfileShopUI() {
  $('btn-open-shop').addEventListener('click', openShop);
  $('btn-open-profile').addEventListener('click', openProfile);
  $('btn-shop-back').addEventListener('click', () => showView('lobby'));
  $('btn-profile-back').addEventListener('click', () => showView('lobby'));
  $('btn-change-nick').addEventListener('click', changeNick);
  $('btn-upload-avatar').addEventListener('click', () => $('pf-avatar-file').click());
  $('pf-avatar-file').addEventListener('change', (e) => { if (e.target.files[0]) uploadAvatar(e.target.files[0]); });
}

/* ================= AI 设置（已迁移到独立后台页 /admin） ================= */

/* ================= 附加功能：QQ / 签到 / 更新动态 / 对局聊天 / 旁白 / 兑换码 / 反馈举报 ================= */
const QQ_GROUP = '1036858989', QQ_NUM = '3141058569';
function copyText(t) { try { navigator.clipboard.writeText(t).then(() => toast('已复制：' + t), () => prompt('请手动复制', t)); } catch (e) { prompt('请手动复制', t); } }

async function doCheckin() { try { const r = await API.post('/api/user/checkin', {}); toast(`签到成功！连续${r.streak}天，+${r.gold}金币 +${r.exp}经验`); renderUser(); checkinStatus(); } catch (e) { toast(e.message, true); } }
async function checkinStatus() { try { const s = await API.get('/api/user/checkin-status'); const b = $('checkin-badge'), h = $('checkin-hint'); if (b) b.classList.toggle('hidden', s.doneToday); if (h) h.textContent = s.doneToday ? `已连续${s.streak}天` : '点击领取今日奖励'; } catch (e) {} }

async function openNews() {
  try {
    const r = await fetch('/data/changelog.json'); const d = await r.json();
    $('news-updated').textContent = '· 更新于 ' + (d.updated || '');
    const box = $('news-list'); box.innerHTML = '';
    for (const it of (d.items || [])) {
      const el = document.createElement('div'); el.className = 'news-item';
      el.innerHTML = `<span class="news-tag t-${esc(it.tag)}">${esc(it.tag)}</span><span class="news-text">${esc(it.text)}</span><span class="news-date">${esc(it.date || '')}</span>`;
      box.appendChild(el);
    }
    $('modal-news').classList.remove('hidden');
  } catch (e) { toast('加载更新动态失败', true); }
}

function fmtSize(n) {
  if (!n) return '';
  if (n < 1024) return n + ' B';
  if (n < 1048576) return (n / 1024).toFixed(0) + ' KB';
  return (n / 1048576).toFixed(1) + ' MB';
}

// 下载中心：列表与安装包均由服务端提供，改文件/版本无需动前端，更无需更新已装 App。
async function openDownloads() {
  try {
    const r = await fetch('/api/downloads');
    const d = await r.json();
    $('dl-updated').textContent = d.updated ? '· 更新于 ' + d.updated : '';
    const box = $('dl-list'); box.innerHTML = '';
    const banner = d.banner || '⚠️ 独立客户端已停止维护：推荐直接用浏览器打开本站，或「安装为应用 / 添加到主屏幕」（PWA），功能与语音完全一致、且始终最新。下方桌面安装包仅作历史用途。';
    box.innerHTML = `<div class="dl-banner">${esc(banner)}</div>`;
    const items = d.items || [];
    if (!items.length) { box.innerHTML = '<div class="fr-empty">暂无可下载的客户端</div>'; }
    for (const it of items) {
      const el = document.createElement('div');
      el.className = 'dl-item' + (it.available ? '' : ' off');
      const ver = it.version ? 'v' + esc(it.version) : '';
      const meta = [esc(it.os || ''), esc(it.arch || ''), ver].filter(Boolean).join(' · ');
      const isOpenLink = it.available && it.url && !it.url.startsWith('/download/');
      const act = it.available
        ? `<a class="btn btn-primary btn-sm" href="${esc(it.url)}"${isOpenLink ? ' target="_blank"' : ' download'}>${isOpenLink ? '打开本站' : '下载'}</a>`
        : '<span class="dl-soon">即将提供</span>';
      el.innerHTML = `<div class="dl-icon">${it.icon || '📦'}</div>
        <div class="dl-main">
          <div class="dl-name">${esc(it.label)}</div>
          <div class="dl-meta">${meta}${it.size ? ' · ' + fmtSize(it.size) : ''}</div>
          <div class="dl-note">${esc(it.note || '')}</div>
        </div>
        <div class="dl-act">${act}</div>`;
      box.appendChild(el);
    }
    $('modal-download').classList.remove('hidden');
  } catch (e) { toast('加载下载列表失败', true); }
}

function onGameChat(msg) {
  const box = $('game-chat'); if (!box) return;
  const el = document.createElement('div');
  el.className = 'gc-line' + (msg.seat && game.view && msg.seat === game.view.mySeat ? ' me' : '');
  el.innerHTML = `<span class="gc-name">${esc(msg.name)}</span><span class="gc-text">${esc(msg.text)}</span>`;
  box.appendChild(el); while (box.children.length > 60) box.removeChild(box.firstChild); box.scrollTop = box.scrollHeight;
}
async function sendGameChat() { const inp = $('gc-input'); if (!inp) return; const t = inp.value.trim(); if (!t) return; inp.value = ''; try { await API.post('/api/game/chat', { text: t }); } catch (e) { toast(e.message, true); } }
function showNarrator(msg) {
  const el = $('voice-caption'); if (!el) return; el.classList.remove('hidden');
  const line = document.createElement('div'); line.className = 'vc-line narrator';
  line.innerHTML = `<span class="vc-name">📢 ${esc(msg.name || '旁白')}</span><span class="vc-text">${esc(msg.text)}</span>`;
  el.appendChild(line); while (el.children.length > 7) el.removeChild(el.firstChild); el.scrollTop = el.scrollHeight;
  clearTimeout(el._t); el._t = setTimeout(() => { el.innerHTML = ''; }, 14000);
}

async function saveProfileInfo() { try {
    await API.post('/api/user/profile-info', { birthday: $('pf-birthday').value, location: $('pf-location').value.trim(), regLocation: $('pf-regloc').value.trim(), signature: $('pf-signature').value.trim() });
    await API.post('/api/user/voice-gender', { gender: $('pf-voicegender').value });
    toast('资料已保存');
  } catch (e) { toast(e.message, true); } }
function fmtDur(s) { s = s || 0; const h = Math.floor(s / 3600), m = Math.floor(s % 3600 / 60); return h ? `${h}时${m}分` : (m ? `${m}分` : `${s}秒`); }
async function loadRoleStats() { try { const rs = await API.get('/api/user/rolestats'); const box = $('pf-rolestats'); if (!box) return; if (!rs.length) { box.innerHTML = '<div class="pf-hint">还没有角色记录</div>'; return; } box.innerHTML = rs.map(r => `<div class="rs-row"><span class="rs-role">${esc(r.role)}</span><span class="rs-meta">${r.games}场 · 胜${r.wins} · ${fmtDur(r.seconds)}</span></div>`).join(''); } catch (e) {} }
async function doRedeem() { const c = $('redeem-code').value.trim(); if (!c) { toast('请输入兑换码', true); return; } try { const r = await API.post('/api/redeem', { code: c }); $('redeem-msg').textContent = '✅ 兑换成功，当前金币 ' + r.gold; $('redeem-code').value = ''; renderUser(); } catch (e) { $('redeem-msg').textContent = '❌ ' + e.message; } }

let ticketKind = 'FEEDBACK';
function openTicket(kind) { ticketKind = kind; $('ticket-title').textContent = kind === 'REPORT' ? '🚩 举报玩家' : '📝 提交反馈'; $('ticket-target-row').classList.toggle('hidden', kind !== 'REPORT'); $('ticket-content').value = ''; $('modal-ticket').classList.remove('hidden'); }
async function submitTicket() {
  const content = $('ticket-content').value.trim(); if (!content) { toast('请填写内容', true); return; }
  const body = { category: ticketKind === 'REPORT' ? '举报' : '问题', title: ticketKind === 'REPORT' ? '举报玩家' : '反馈建议', content };
  if (ticketKind === 'REPORT') { const t = $('ticket-target').value.trim(); if (t) body.targetUserId = +t; else { toast('请填写被举报玩家ID', true); return; } }
  try { await API.post('/api/tickets', body); toast('已提交，感谢反馈'); $('modal-ticket').classList.add('hidden'); } catch (e) { toast(e.message, true); }
}

function bindExtrasUI() {
  const SLOGANS = ['🌙 友谊第一，比赛第二', '🐺 谎言是这门游戏的艺术', '🔮 逻辑与直觉，缺一不可',
    '☕ 边玩边聊，开心最重要', '🎭 演技即正义', '🌕 天黑请闭眼，天亮请睁眼', '🤝 输赢一时，朋友一世'];
  let si = 0; const sl = $('topbar-slogan');
  // 标语轮播：句柄存入全局变量，避免重复调用 bindExtrasUI 时创建多个定时器；并提供 window.stopSloganCarousel 清理。
  if (sl) {
    sl.textContent = SLOGANS[0];
    if (!window.__sloganTimer) {
      window.__sloganTimer = setInterval(() => {
        si = (si + 1) % SLOGANS.length; sl.style.opacity = 0;
        setTimeout(() => { sl.textContent = SLOGANS[si]; sl.style.opacity = 1; }, 400);
      }, 7000);
    }
  }
  if (!window.stopSloganCarousel) {
    window.stopSloganCarousel = () => { if (window.__sloganTimer) { clearInterval(window.__sloganTimer); window.__sloganTimer = null; } };
  }
  $('btn-qq-group').addEventListener('click', () => copyText(QQ_GROUP));
  $('btn-qq').addEventListener('click', () => copyText(QQ_NUM));
  $('btn-checkin').addEventListener('click', doCheckin);
  $('btn-news').addEventListener('click', openNews);
  $('btn-news-close').addEventListener('click', () => $('modal-news').classList.add('hidden'));
  $('btn-download-app').addEventListener('click', openDownloads);
  $('btn-download-close').addEventListener('click', () => $('modal-download').classList.add('hidden'));
  const gs = $('gc-send'); if (gs) gs.addEventListener('click', sendGameChat);
  const gi = $('gc-input'); if (gi) gi.addEventListener('keydown', e => { if (e.key === 'Enter') sendGameChat(); });
  $('btn-save-profile-info').addEventListener('click', saveProfileInfo);
  $('btn-redeem').addEventListener('click', doRedeem);
  $('btn-feedback').addEventListener('click', () => openTicket('FEEDBACK'));
  $('btn-report').addEventListener('click', () => openTicket('REPORT'));
  $('btn-ticket-close').addEventListener('click', () => $('modal-ticket').classList.add('hidden'));
  $('btn-ticket-submit').addEventListener('click', submitTicket);
  THEME.init();
  // 外观面板（主题/强调色/字号/密度/动效/背景）整体交给 appearance.js，
  // 它只把状态写回 THEME，应用逻辑仍集中在 THEME.apply()。
  if (window.WW_APPEARANCE) WW_APPEARANCE.mount();
  $('btn-install-guide').addEventListener('click', openInstallGuide);
  const brk = $('btn-ranking'); if (brk) brk.addEventListener('click', openRanking);
  const brkc = $('btn-ranking-close'); if (brkc) brkc.addEventListener('click', () => $('modal-ranking').classList.add('hidden'));
  document.querySelectorAll('#modal-ranking .tab').forEach(t => t.addEventListener('click', () => loadRanking(t.dataset.rk)));
  const bf = $('btn-forum'); if (bf) bf.addEventListener('click', openForum);
  const bfc = $('btn-forum-close'); if (bfc) bfc.addEventListener('click', () => $('modal-forum').classList.add('hidden'));
  const fbk = $('forum-back'); if (fbk) fbk.addEventListener('click', forumList);
  const fnb = $('forum-new-btn'); if (fnb) fnb.addEventListener('click', forumNew);
  $('btn-install-close').addEventListener('click', () => $('modal-install').classList.add('hidden'));
}

/* ================= 外观状态：主题 / 强调色 / 字号 / 密度 / 动效 / 自定义背景 =================
   状态与“应用到 DOM”集中在这里；面板 UI 在 appearance.js（它渲染注册表、写回状态后调用 apply）。 */
const THEME = {
  mode: localStorage.getItem('ww_theme') || 'night',
  accent: localStorage.getItem('ww_accent') || 'auto',      // 'auto' = 跟随主题
  font: localStorage.getItem('ww_font') || 'md',            // sm | md | lg
  dense: localStorage.getItem('ww_density') === 'dense',
  motion: localStorage.getItem('ww_motion') !== 'off',
  bg: localStorage.getItem('ww_bg') || null,     // dataURL 或 css 颜色
  bright: parseFloat(localStorage.getItem('ww_bright') || '1') || 1,   // 背景亮度 0.4~1.6

  /** 已注册主题 id（appearance.js 提供；缺失时退化为旧三主题，保证不白屏）。 */
  ids() { return (window.WW_THEMES || []).map(t => t.id); },

  apply() {
    const b = document.body;
    // 1) 主题类：先清掉所有注册过的 theme-*，再挂当前一个（night 是 :root 默认，无类）
    const known = this.ids().length ? this.ids() : ['day', 'glass'];
    known.forEach(id => b.classList.remove('theme-' + id));
    if (this.mode && this.mode !== 'night') b.classList.add('theme-' + this.mode);
    const meta = (window.WW_THEMES || []).find(t => t.id === this.mode);
    b.classList.toggle('theme-light', !!(meta && meta.light));

    // 2) 字号 / 密度 / 动效档位
    b.classList.toggle('fs-sm', this.font === 'sm');
    b.classList.toggle('fs-lg', this.font === 'lg');
    b.classList.toggle('dense', this.dense);
    b.classList.toggle('no-motion', !this.motion);

    // 3) 强调色覆盖（auto 时交回主题令牌）
    ['--accent', '--accent-dim', '--accent-soft'].forEach(k => b.style.removeProperty(k));
    const acc = (window.WW_ACCENTS || []).find(a => a.id === this.accent);
    if (acc && acc.main) {
      b.style.setProperty('--accent', acc.main);
      b.style.setProperty('--accent-dim', acc.dim || acc.main);
      b.style.setProperty('--accent-soft', acc.soft || 'rgba(232,163,61,.14)');
    }

    // 4) 顶栏按钮图标 + 主题色 meta（PWA 状态栏）
    const t = $('btn-theme');
    if (t) t.textContent = (meta && meta.icon) || '🌙';
    const mc = document.querySelector('meta[name="theme-color"]');
    if (mc) mc.setAttribute('content', getComputedStyle(b).getPropertyValue('--bg-deep').trim() || '#090d18');

    // 5) 背景亮度与自定义背景图
    b.style.setProperty('--bg-bright', String(this.bright));
    if (this.bg) {
      b.style.setProperty('--custom-bg', `url("${this.bg}")`);
      b.classList.add('has-custom-bg');
    } else {
      b.classList.remove('has-custom-bg');
      b.style.removeProperty('--custom-bg');
    }
    if (window.WW_APPEARANCE) WW_APPEARANCE.syncControls();
  },

  setMode(m) { this.mode = m; localStorage.setItem('ww_theme', m); this.apply(); },
  setAccent(a) { this.accent = a; localStorage.setItem('ww_accent', a); this.apply(); },
  setFont(f) { this.font = f; localStorage.setItem('ww_font', f); this.apply(); },
  setDensity(d) { this.dense = !!d; localStorage.setItem('ww_density', d ? 'dense' : 'cozy'); this.apply(); },
  setMotion(on) { this.motion = !!on; localStorage.setItem('ww_motion', on ? 'on' : 'off'); this.apply(); },
  setBright(v) { this.bright = Math.max(0.4, Math.min(1.6, v)); localStorage.setItem('ww_bright', String(this.bright)); this.apply(); },
  clearBg() { this.bg = null; localStorage.removeItem('ww_bg'); this.apply(); toast('已恢复默认背景'); },
  setBgFile(file) {
    if (!file) return;
    if (file.size > 3 * 1024 * 1024) { toast('图片过大（≤3MB）', true); return; }
    const r = new FileReader();
    r.onload = () => { this.bg = r.result; try { localStorage.setItem('ww_bg', this.bg); } catch (_) { toast('背景过大，未能保存', true); } this.apply(); toast('背景已更新'); };
    r.readAsDataURL(file);
  },
  /** 随机换一个主题（面板上的 🎲 与快捷键 T 都走这里）。 */
  random() {
    const pool = this.ids();
    if (pool.length < 2) return;
    let m = this.mode;
    while (m === this.mode) m = pool[Math.floor(Math.random() * pool.length)];
    this.setMode(m);
    if (typeof toast === 'function') {
      const meta = (window.WW_THEMES || []).find(x => x.id === m);
      toast((meta ? meta.icon + ' ' + meta.name : m) + ' 主题已应用');
    }
  },
  init() {
    const tp = new URLSearchParams(location.search).get('theme');
    if (tp && (tp === 'night' || tp === 'day' || tp === 'glass' || this.ids().includes(tp))) {
      this.mode = tp; localStorage.setItem('ww_theme', tp);
    }
    this.apply();
  },
};

/* ================= 安装指导 ================= */
async function openInstallGuide() {
  const box = $('install-body');
  $('modal-install').classList.remove('hidden');
  box.innerHTML = '加载中…';
  try {
    const r = await fetch('/data/install.md');
    if (!r.ok) throw new Error('未找到安装指导');
    const md = await r.text();
    box.innerHTML = renderSimpleMarkdown(md);
  } catch (e) { box.innerHTML = `<p class="pf-hint">安装指导加载失败。</p>`; }
}

/** 轻量 Markdown 渲染（标题/列表/代码块/粗体/行内代码/链接），避免引入外部依赖。 */
function renderSimpleMarkdown(md) {
  const esc0 = s => escapeHtml(s);
  const lines = (md || '').replace(/\r\n/g, '\n').split('\n');
  let html = '', inCode = false, inList = false;
  const closeList = () => { if (inList) { html += '</ul>'; inList = false; } };
  for (let raw of lines) {
    if (/^```/.test(raw.trim())) {
      closeList();
      html += inCode ? '</code></pre>' : '<pre class="md-pre"><code>';
      inCode = !inCode; continue;
    }
    if (inCode) { html += esc0(raw) + '\n'; continue; }
    const line = raw.trimEnd();
    if (!line.trim()) { closeList(); continue; }
    let m;
    if ((m = line.match(/^(#{1,4})\s+(.*)$/))) {
      closeList();
      const lvl = Math.min(4, m[1].length) + 2;
      html += `<h${lvl} class="md-h">${inline(m[2])}</h${lvl}>`;
    } else if (/^\s*([-*+]|\d+\.)\s+/.test(line)) {
      if (!inList) { html += '<ul class="md-ul">'; inList = true; }
      html += `<li>${inline(line.replace(/^\s*([-*+]|\d+\.)\s+/, ''))}</li>`;
    } else if (/^>\s?/.test(line)) {
      closeList(); html += `<blockquote class="md-quote">${inline(line.replace(/^>\s?/, ''))}</blockquote>`;
    } else {
      closeList(); html += `<p class="md-p">${inline(line)}</p>`;
    }
  }
  closeList();
  if (inCode) html += '</code></pre>';
  function inline(s) {
    return esc0(s)
      .replace(/`([^`]+)`/g, '<code>$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
      .replace(/\*([^*]+)\*/g, '<em>$1</em>')
      .replace(/\[([^\]]+)\]\(([^)]+)\)/g, '<a href="$2" target="_blank" rel="noopener">$1</a>');
  }
  return html || '<p class="pf-hint">暂无内容</p>';
}

/* ================= 工单 ================= */
function statusZh(s) { return ({ OPEN: '待处理', PROCESSING: '处理中', REPLIED: '已回复', CLOSED: '已关闭' })[s] || s; }
const TK = {
  open() { showView('tickets'); $('tk-detail').classList.add('hidden'); $('tk-mine').classList.remove('hidden'); this.loadMine(); },
  async loadMine() {
    try {
      const list = await API.get('/api/tickets'); const box = $('tk-mine');
      if (!list.length) { box.innerHTML = '<div class="fr-empty">还没有工单，右侧可新建</div>'; return; }
      box.innerHTML = '';
      for (const t of list) {
        const el = document.createElement('div'); el.className = 'fr-item';
        el.innerHTML = `<div class="fr-main"><div class="fr-name">${esc(t.title || t.category)} <span class="tk-st st-${t.status}">${statusZh(t.status)}</span></div>
          <div class="fr-sub">${esc(t.category)} · ${(t.at || '').replace('T', ' ').slice(0, 16)} · ${t.replies || 0} 条回复</div></div>
          <div class="fr-acts"><button class="btn btn-sm">查看</button></div>`;
        el.querySelector('button').onclick = () => this.view(t.id);
        box.appendChild(el);
      }
    } catch (e) { toast(e.message, true); }
  },
  async view(id) {
    try {
      const t = await API.get('/api/tickets/' + id);
      let h = `<div class="tk-d-head"><b>${esc(t.title)}</b> <span class="tk-st st-${t.status}">${statusZh(t.status)}</span> <span class="fr-sub">${esc(t.category)} · ${esc(t.reporter || '')}</span></div>
        <div class="tk-d-body">${esc(t.content)}</div><div class="tk-thread">`;
      for (const r of (t.thread || [])) h += `<div class="tk-rep ${r.admin ? 'adm' : ''}"><span class="tk-rep-who">${r.admin ? '👑 官方' : esc(r.author)}</span><div>${esc(r.content)}</div><span class="tk-rep-at">${(r.at || '').replace('T', ' ').slice(5, 16)}</span></div>`;
      h += `</div>`;
      if (t.status !== 'CLOSED') h += `<div class="tk-reply"><input type="text" id="tk-reply-in" placeholder="追问或补充…" maxlength="1000"><button class="btn btn-sm btn-primary" id="tk-reply-btn">回复</button></div>`;
      h += `<button class="btn btn-sm btn-ghost" id="tk-back-list">← 返回列表</button>`;
      const d = $('tk-detail'); d.innerHTML = h; d.classList.remove('hidden'); $('tk-mine').classList.add('hidden');
      const rb = $('tk-reply-btn'); if (rb) rb.onclick = async () => { const v = $('tk-reply-in').value.trim(); if (!v) return; try { await API.post('/api/tickets/' + id + '/reply', { content: v }); this.view(id); } catch (e) { toast(e.message, true); } };
      $('tk-back-list').onclick = () => { d.classList.add('hidden'); $('tk-mine').classList.remove('hidden'); this.loadMine(); };
    } catch (e) { toast(e.message, true); }
  },
  async create() {
    const cat = $('tk-cat').value, title = $('tk-title').value.trim(), content = $('tk-content').value.trim();
    if (!content) { $('tk-create-msg').textContent = '请填写内容'; return; }
    const body = { category: cat, title, content };
    if (cat === '举报') { const tg = $('tk-target').value.trim(); if (tg) body.targetUserId = +tg; }
    try { await API.post('/api/tickets', body); $('tk-create-msg').textContent = '✅ 已提交'; $('tk-title').value = ''; $('tk-content').value = ''; $('tk-target').value = ''; this.loadMine(); }
    catch (e) { $('tk-create-msg').textContent = '❌ ' + e.message; }
  },
};
function bindTicketsUI() {
  $('btn-open-tickets').addEventListener('click', () => TK.open());
  $('btn-tickets-back').addEventListener('click', () => showView('lobby'));
  $('tk-create').addEventListener('click', () => TK.create());
  $('tk-cat').addEventListener('change', () => { const f = document.querySelector('.tk-target-row'); if (f) f.classList.toggle('hidden', $('tk-cat').value !== '举报'); });
}

/* ================= VIP 会员 ================= */
const VIP_NAMES = { 1: '白银会员', 2: '黄金会员', 3: '钻石会员' };
function vipBadge(lv) { return lv > 0 ? `<span class="vip-badge v${lv}">👑 ${VIP_NAMES[lv] || 'VIP'}</span>` : ''; }

let _seenVip = new Set(), _vipInit = false;
function playVipEntry(name, lv) {
  const ov = $('vip-entry'); if (!ov) return;
  $('ve-card').className = 've-card v' + lv;
  $('ve-name').textContent = name;
  $('ve-tag').textContent = (VIP_NAMES[lv] || 'VIP') + ' · 荣耀入场';
  ov.classList.remove('hidden'); void ov.offsetWidth;
  clearTimeout(ov._t); ov._t = setTimeout(() => ov.classList.add('hidden'), 2800);
}
function detectVipEntry(room) {
  if (!room || !room.players) { _vipInit = false; _seenVip = new Set(); return; }
  const cur = new Set(room.players.filter(p => p.vip > 0).map(p => p.userId));
  if (!_vipInit) { _seenVip = cur; _vipInit = true; return; }   // 首次进入不播放，避免给已在场的人放特效
  for (const id of cur) if (!_seenVip.has(id)) { const p = room.players.find(x => x.userId === id); if (p) playVipEntry(p.nickname, p.vip); }
  _seenVip = cur;
}

async function loadVip() {
  try {
    const v = await API.get('/api/vip');
    const box = $('vip-box'); if (!box) return;
    let h = `<div class="vip-cur ${v.active ? 'on' : ''}">${v.active ? '👑 ' + esc(v.name) + '（到期 ' + (v.expireAt || '长期').slice(0, 10) + '）' : '普通玩家 · 尚未开通会员'}</div>`;
    if (v.active) h += `<div class="vip-perks">当前权益：商店 ${(v.discount * 10).toFixed(1)} 折 · 称号「${esc(v.title)}」 · 入场特效 Lv${v.effect} · 签到 +${v.checkinBonusPct}%</div>`;
    h += `<div class="vip-tiers">`;
    for (const t of v.tiers) {
      const owned = v.level >= t.level;
      h += `<div class="vip-tier v${t.level} ${owned ? 'cur' : ''}">
        <div class="vt-name">👑 ${esc(t.name)}</div>
        <div class="vt-perk">商店 ${(t.discount * 10).toFixed(1)}折 · 签到+${t.checkinBonusPct}% · 入场特效Lv${t.effect} · 称号「${esc(t.title)}」</div>
        <div class="vt-price">💰 ${t.price} / ${t.days}天</div>
        ${owned
          ? `<span class="vt-owned">已拥有</span><button class="btn btn-sm" data-buy="${t.level}">续费（+${t.days}天）</button>`
          : `<button class="btn btn-sm btn-primary" data-buy="${t.level}">开通/续费</button>`}
      </div>`;
    }
    h += `</div><div class="pf-hint">当前金币：${v.gold}</div>`;
    box.innerHTML = h;
    box.querySelectorAll('[data-buy]').forEach(b => b.onclick = () => buyVip(+b.dataset.buy));
  } catch (e) { const b = $('vip-box'); if (b) b.textContent = '加载失败：' + e.message; }
}
async function buyVip(level) {
  const t = { 1: '白银会员 800金币/30天', 2: '黄金会员 2000金币/30天', 3: '钻石会员 5000金币/30天' }[level];
  if (!confirm(`确认用金币开通「${t}」？`)) return;
  try { const v = await API.post('/api/vip/purchase', { level }); toast(' 已开通 ' + v.name); loadVip(); renderUser(); }
  catch (e) { toast(e.message, true); }
}

/* ---------- 维护弹幕：仅当系统下发"维护通知"时于屏幕最底部展示（不再随机漂浮倒计时/提示语） ---------- */
const DM = {
  lastNotice: null,   // { text, expiry }
  _pt: null,
  esc(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); },
  // 收到（或恢复）一条维护通知：仅更新最底部滚动航道
  notice(text, expiry) {
    if (!text) { this.lastNotice = null; this.renderTicker(); return; }
    this.lastNotice = { text: String(text), expiry: expiry || (Date.now() + 6 * 3600 * 1000) };
    this.renderTicker();
    if (!this._pt) this._pt = setInterval(() => this.prune(), 5000);
  },
  ensureBox() {
    const box = $('danmaku'); if (!box) return null;
    box.classList.remove('hidden');
    return box;
  },
  renderTicker() {
    const box = this.ensureBox(); if (!box) return;
    let t = box.querySelector('.dm-ticker');
    const active = this.lastNotice && Date.now() <= this.lastNotice.expiry;
    if (!active) { if (t) t.remove(); this.checkHide(); return; }
    if (!t) { t = document.createElement('div'); t.className = 'dm-ticker'; box.appendChild(t); }
    const unit = '🛠 维护通知：' + this.esc(this.lastNotice.text) + '　　　🌙　　　';
    const half = unit.repeat(3);   // 每半段重复 3 次，文字更满；两半段等长 → translateX(-50%) 无缝一直左移
    t.innerHTML = `<div class="dm-track"><span>${half}</span><span>${half}</span></div>`;
    const track = t.querySelector('.dm-track');
    const dur = Math.min(60, Math.max(16, half.length * 0.42));  // 按内容长度给时长，滚动速度稳定
    track.style.animationDuration = dur.toFixed(1) + 's';
  },
  prune() {
    if (this.lastNotice && Date.now() > this.lastNotice.expiry) this.lastNotice = null;
    this.renderTicker();
    if (!this.lastNotice && this._pt) { clearInterval(this._pt); this._pt = null; }
  },
  checkHide() {
    const box = $('danmaku'); if (!box) return;
    if (!box.querySelector('.dm-ticker')) box.classList.add('hidden');
  },
  // 兼容旧调用
  stop() { const box = $('danmaku'); if (box) box.querySelectorAll('.dm-item').forEach(e => e.remove()); }
};

function initLang() {
  const opts = (window.LANGS || []).map(([v, l]) => `<option value="${v}">${l}</option>`).join('');
  ['lang-select', 'lang-select-auth'].forEach((id) => {
    const el = $(id); if (!el) return;
    el.innerHTML = opts; el.value = window.getLang();
    el.onchange = () => window.setLang(el.value);
  });
  window.applyI18n();
  document.addEventListener('ww:lang', () => {
    ['lang-select', 'lang-select-auth'].forEach((id) => { const el = $(id); if (el) el.value = window.getLang(); });
    rerenderCurrent();
  });
}
function rerenderCurrent() {
  try { renderUser(); } catch (_) {}
  try { if (currentView === 'room' && state.room) renderRoom(); } catch (_) {}
  try { if (currentView === 'game' && game.view) renderGame(game.view); } catch (_) {}
}

/* ---------- 玩家标记栏（本地按房间保存：疑/好/狼） ---------- */
const MARK = {
  room: null, data: {},
  load(roomNo) {
    if (this.room !== roomNo) {
      this.room = roomNo;
      try { this.data = JSON.parse(localStorage.getItem('ww_marks_' + roomNo) || '{}'); } catch (_) { this.data = {}; }
    }
  },
  save() { try { localStorage.setItem('ww_marks_' + this.room, JSON.stringify(this.data)); } catch (_) {} },
  cycle(seat) { this.data[seat] = ((this.data[seat] || 0) + 1) % 4; this.save(); },
  clear() { this.data = {}; this.save(); },
};
function renderMarkers(v) {
  const box = $('marker-list'); if (!box || !v) return;
  MARK.load(v.roomNo);
  const labels = ['未标', '可疑', '好人', '狼人'];
  const cls = ['', 'm-sus', 'm-good', 'm-wolf'];
  let h = '';
  for (const s of (v.seats || [])) {
    const lvl = MARK.data[s.seat] || 0;
    h += `<div class="mk-row"><span class="mk-name">${s.seat}号 ${esc(s.nickname)}${s.alive ? '' : '（出局）'}</span>`
       + `<button class="mk-btn ${cls[lvl]}" data-seat="${s.seat}">${labels[lvl]}</button></div>`;
  }
  box.innerHTML = h;
  box.querySelectorAll('.mk-btn').forEach(b => b.onclick = () => { MARK.cycle(+b.dataset.seat); renderMarkers(game.view); });
}

/* ---------- 排行榜 + 对局数据 PK ---------- */
async function openRanking() {
  $('modal-ranking').classList.remove('hidden');
  const pk = $('ranking-pk'); if (pk) pk.classList.add('hidden');
  await loadRanking('win');
}
async function loadRanking(by) {
  document.querySelectorAll('#modal-ranking .tab').forEach(t => t.classList.toggle('active', t.dataset.rk === by));
  const box = $('ranking-list'); box.innerHTML = '<div class="fr-empty">加载中…</div>';
  try {
    const d = await API.get('/api/ranking?by=' + by + '&limit=50');
    const list = d.list || [];
    let h = '';
    for (const r of list) {
      const medal = r.rank <= 3 ? ['🥇', '🥈', '🥉'][r.rank - 1] : ('#' + r.rank);
      const metric = by === 'gold' ? (r.gold + ' 金') : by === 'level' ? ('Lv.' + r.level) : (r.wins + ' 胜 · ' + r.winRate + '%');
      h += `<div class="rk-row"><span class="rk-rank">${medal}</span><span class="rk-name">${esc(r.nickname)}</span>`
         + `<span class="rk-metric">${metric}</span><button class="btn btn-sm rk-pk-btn" data-uid="${r.userId}">PK</button></div>`;
    }
    box.innerHTML = h || '<div class="fr-empty">暂无数据</div>';
    box.querySelectorAll('.rk-pk-btn').forEach(b => b.onclick = () => doPK(+b.dataset.uid));
  } catch (e) { box.innerHTML = '<div class="fr-empty">' + esc(e.message) + '</div>'; }
}
async function doPK(uid) {
  const panel = $('ranking-pk'); panel.classList.remove('hidden'); panel.innerHTML = '对比中…';
  try {
    const me = await API.get('/api/user/stats');
    const other = uid === state.user.id ? me : await API.get('/api/friends/profile/' + uid);
    const A = { name: state.user.nickname, games: me.total ?? 0, win: me.winRate ?? 0, wolf: me.wolfWinRate ?? 0, good: me.goodWinRate ?? 0 };
    const B = { name: other.nickname || '对方', games: other.games ?? other.total ?? 0, win: other.winRate ?? 0, wolf: other.wolfWinRate ?? 0, good: other.goodWinRate ?? 0 };
    const row = (label, k) => `<div class="pk-row"><span class="pk-a">${A[k]}</span><span class="pk-label">${label}</span><span class="pk-b">${B[k]}</span></div>`;
    panel.innerHTML = `<div class="pk-title">⚔️ ${esc(A.name)} vs ${esc(B.name)}</div>`
      + row('总场次', 'games') + row('总胜率%', 'win') + row('狼人胜率%', 'wolf') + row('好人胜率%', 'good');
    panel.scrollIntoView({ behavior: 'smooth', block: 'nearest' });
  } catch (e) { panel.textContent = '对比失败：' + e.message; }
}

/* ---------- 论坛 ---------- */
const FORUM = { cur: null };
function openForum() { $('modal-forum').classList.remove('hidden'); forumList(); }
function forumShowBack(on) { $('forum-back').classList.toggle('hidden', !on); $('forum-new-btn').classList.toggle('hidden', on); }
async function forumList() {
  FORUM.cur = null; forumShowBack(false); $('forum-title').textContent = '💬 论坛';
  const box = $('forum-body'); box.innerHTML = '<div class="fr-empty">加载中…</div>';
  try {
    const list = await API.getList('/api/forum');
    if (!list.length) { box.innerHTML = '<div class="fr-empty">还没有帖子，点右上「发帖」开个头吧～</div>'; return; }
    let h = '';
    for (const p of list) {
      h += `<div class="fm-post" data-id="${p.id}">
        <div class="fm-row1">${p.pinned ? '<span class="fm-pin">置顶</span>' : ''}<b>${esc(p.title)}</b><span class="fm-cat">${esc(p.category || '综合')}</span></div>
        <div class="fm-meta">${esc(p.author)} · ${(p.at || '').replace('T', ' ').slice(5, 16)} · 💬 ${p.replyCount || 0}</div></div>`;
    }
    box.innerHTML = h;
    box.querySelectorAll('.fm-post').forEach(el => el.onclick = () => forumView(+el.dataset.id));
  } catch (e) { box.innerHTML = '<div class="fr-empty">' + esc(e.message) + '</div>'; }
}
async function forumView(id) {
  FORUM.cur = id; forumShowBack(true); $('forum-title').textContent = '💬 帖子详情';
  const box = $('forum-body'); box.innerHTML = '<div class="fr-empty">加载中…</div>';
  try {
    const p = await API.get('/api/forum/' + id);
    let h = `<div class="fm-detail"><h3>${p.pinned ? '📌 ' : ''}${esc(p.title)} <span class="fm-cat">${esc(p.category || '')}</span></h3>
      <div class="fm-meta">${esc(p.author)} · ${(p.at || '').replace('T', ' ').slice(5, 16)}</div>
      <div class="fm-content">${esc(p.content)}</div>`;
    if (p.userId === state.user.id || state.user.admin) h += `<div class="fm-acts"><button class="btn btn-sm btn-ghost" id="fm-del">🗑 删除</button></div>`;
    h += `<div class="fm-replies"><div class="fm-rt">全部回复（${(p.replies || []).length}）</div>`;
    for (const r of (p.replies || [])) h += `<div class="fm-reply"><div class="fm-meta"><b>${esc(r.author)}</b> · ${(r.at || '').replace('T', ' ').slice(5, 16)}</div><div>${esc(r.content)}</div></div>`;
    h += `</div><div class="fm-replybox"><input type="text" id="fm-reply-input" placeholder="写下你的回复…" maxlength="500"><button class="btn btn-sm btn-primary" id="fm-reply-send">回复</button></div></div>`;
    box.innerHTML = h;
    const del = $('fm-del'); if (del) del.onclick = () => { if (confirm('删除该帖？')) API.post('/api/forum/' + id + '/delete').then(forumList).catch(e => toast(e.message, true)); };
    $('fm-reply-send').onclick = () => {
      const v = $('fm-reply-input').value.trim(); if (!v) return;
      API.post('/api/forum/' + id + '/reply', { content: v }).then(() => forumView(id)).catch(e => toast(e.message, true));
    };
  } catch (e) { box.innerHTML = '<div class="fr-empty">' + esc(e.message) + '</div>'; }
}
function forumNew() {
  forumShowBack(true); $('forum-title').textContent = '✍️ 发帖';
  const box = $('forum-body');
  box.innerHTML = `<div class="fm-form">
    <input type="text" id="fm-f-title" placeholder="标题" maxlength="80">
    <select id="fm-f-cat"><option>综合</option><option>攻略</option><option>吐槽</option><option>举报</option><option>招募</option></select>
    <textarea id="fm-f-content" placeholder="正文…" maxlength="4000" rows="6"></textarea>
    <button class="btn btn-primary" id="fm-f-submit">发布</button></div>`;
  $('fm-f-submit').onclick = async () => {
    const title = $('fm-f-title').value.trim(), content = $('fm-f-content').value.trim(), category = $('fm-f-cat').value;
    if (!title || !content) return toast('标题和正文都要填', true);
    try { await API.post('/api/forum', { title, content, category }); toast('已发布'); forumList(); }
    catch (e) { toast(e.message, true); }
  };
}

async function boot() {
  initLang();
  bindAuthUI();
  bindLobbyUI();
  bindRoomUI();
  bindGameUI();
  bindProfileShopUI();
  bindFriendsUI();
  bindReplayUI();
  bindBoardUI();
  bindSfxUI();
  initVoiceHooks();
  fetchVoiceStatus();
  loadMaintenance();
  bindExtrasUI();
  bindTicketsUI();
  const q0 = new URLSearchParams(location.search);
  const urlTok = q0.get('t');
  const joinNo = q0.get('join');
  if (urlTok) {
    localStorage.setItem('ww_token', urlTok);
    // 仅移除 token 参数，保留 join 等其它查询参数（否则邀请链接里的房间号会被一起清掉）
    q0.delete('t');
    const qs = q0.toString();
    history.replaceState(null, '', location.pathname + (qs ? '?' + qs : '') + location.hash);
  }
  if (joinNo) state.pendingJoin = joinNo;
  const token = localStorage.getItem('ww_token');
  if (token) {
    try {
      state.user = await API.get('/api/auth/me');
      await enterLobby();
      toast('欢迎回来，' + state.user.nickname);
      if (state.pendingJoin) { confirmPendingJoin(); return; }
      // 开发/验收便利：#profile / #shop 直达对应页
      if (location.hash === '#profile') openProfile();
      else if (location.hash === '#shop') openShop();
      else if (location.hash === '#friends') FR.open();
      else if (location.hash === '#tickets') TK.open();
      else if (location.hash === '#download') openDownloads();
      else if (location.hash.startsWith('#replay')) {
        const gid = +(location.hash.split('=')[1] || 0);
        if (gid) openReplay(gid);
      }
      return;
    } catch (_) {
      localStorage.removeItem('ww_token');
    }
  }
  showView('auth');
}

boot();

// PWA：注册 Service Worker（https 下），支持安装为应用 + 离线打开
if ('serviceWorker' in navigator && location.protocol === 'https:') {
  window.addEventListener('load', () => {
    navigator.serviceWorker.register('/sw.js').catch(() => {});
  });
}
