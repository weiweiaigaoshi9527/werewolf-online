/* ========== ux.js · 体验增强（快捷键 / 记录筛选与导出 / 投票条 / 结算大事记 / @补全 / 引导 / 计时环） ==========
   加载在 app.js 之后：只“包装”既有渲染函数与注入 DOM，不改对局逻辑。
   任何一步失败都不允许影响正常对局，因此全部包在 try/catch 里。 */
(function () {
  'use strict';

  const $ = (id) => document.getElementById(id);
  const view = () => (typeof game !== 'undefined' && game && game.view) ? game.view : null;
  const esc = (s) => String(s == null ? '' : s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));

  /* ==================== 1. 对局记录：分类、筛选、导出 ==================== */

  const CATS = [
    { id: 'all',   label: '全部' },
    { id: 'speech', label: '发言' },
    { id: 'vote',  label: '投票' },
    { id: 'death', label: '出局' },
    { id: 'night', label: '夜间' },
  ];
  const catOf = (type) => {
    if (type === 'SPEECH' || type === 'LAST_WORDS') return 'speech';
    if (type && type.indexOf('VOTE') === 0) return 'vote';
    if (type === 'PLAYER_DIED' || type === 'SHOOT' || type === 'WHITE_WOLF_BLOWUP' || type === 'IDIOT_REVEAL') return 'death';
    if (type === 'NIGHT_ACTION' || type === 'DAWN_ANNOUNCE') return 'night';
    return 'other';
  };
  const feedFilter = { cat: 'all', seat: 0 };   // seat>0：只看某个座位（点记录里的号徽章触发）

  function mountFeedTools() {
    const dock = $('dock-feed');
    if (!dock || dock.querySelector('.feed-tools')) return;
    const bar = document.createElement('div');
    bar.className = 'feed-tools';
    bar.innerHTML = `<div class="seg feed-filter">${CATS.map(c =>
      `<button class="seg-btn${c.id === 'all' ? ' active' : ''}" data-fc="${c.id}" type="button">${c.label}</button>`).join('')}</div>
      <button class="icon-btn feed-seat-clear hidden" id="feed-seat-clear" type="button" title="取消只看某人">✕</button>
      <span class="spacer"></span>
      <button class="btn btn-sm btn-ghost" id="btn-feed-copy" type="button" title="复制本局记录">⧉ 复制</button>`;
    dock.insertBefore(bar, dock.firstChild);

    bar.addEventListener('click', (e) => {
      const b = e.target.closest('[data-fc]');
      if (!b) return;
      feedFilter.cat = b.dataset.fc;
      bar.querySelectorAll('[data-fc]').forEach(x => x.classList.toggle('active', x === b));
      applyFeedFilter();
    });
    $('btn-feed-copy').addEventListener('click', copyFeed);
    $('feed-seat-clear').addEventListener('click', () => { feedFilter.seat = 0; applyFeedFilter(); });
  }

  /** 依据 game.view.feed 给每条记录打上分类与座位，再按当前条件显隐。 */
  function applyFeedFilter() {
    const box = $('game-feed'), v = view();
    if (!box) return;
    const items = ((v && v.feed) || []).slice().reverse();     // 与 renderGameFeed 的顺序一致
    const children = box.children;
    const clear = $('feed-seat-clear');
    if (clear) clear.classList.toggle('hidden', !feedFilter.seat);

    for (let i = 0; i < children.length; i++) {
      const el = children[i], it = items[i];
      const cat = it ? catOf(it.type) : 'other';
      el.dataset.cat = cat;
      el.dataset.seat = it && it.actor ? String(it.actor) : '';
      let ok = feedFilter.cat === 'all' || cat === feedFilter.cat || (feedFilter.cat === 'vote' && cat === 'other' && /投票/.test(el.textContent));
      if (ok && feedFilter.seat) ok = (String(it && it.actor) === String(feedFilter.seat)) || (String(it && it.target) === String(feedFilter.seat));
      el.classList.toggle('dim', !ok);
    }
    if (clear && feedFilter.seat) clear.title = '当前只看 ' + feedFilter.seat + ' 号相关，点击取消';
  }

  function copyFeed() {
    const v = view();
    const box = $('game-feed');
    const text = v && v.feed && v.feed.length
      ? `狼人杀 Online · 房间 ${v.roomNo || ''} 第 ${v.day} 天记录\n` + v.feed.map(e =>
          `[第${e.day}天][${e.type}]${e.actor ? ' ' + e.actor + '号' : ''}${e.target ? '→' + e.target + '号' : ''} ${e.detail || ''}`).join('\n')
      : (box ? box.innerText : '');
    if (!text.trim()) { if (window.toast) toast('还没有可复制的记录', true); return; }
    try {
      navigator.clipboard.writeText(text).then(
        () => { if (window.toast) toast('已复制本局记录（' + text.split('\n').length + ' 行）'); },
        () => fallbackCopy(text));
    } catch (_) { fallbackCopy(text); }
  }
  function fallbackCopy(text) {
    const ta = document.createElement('textarea');
    ta.value = text; document.body.appendChild(ta); ta.select();
    try { document.execCommand('copy'); if (window.toast) toast('已复制本局记录'); } catch (_) { if (window.toast) toast('复制失败，请手动选择记录', true); }
    ta.remove();
  }

  /* ==================== 2. 投票实时条 ==================== */

  function renderVoteBar() {
    const main = document.querySelector('.game-main');
    const v = view();
    let bar = $('vote-bar');
    if (!main) return;
    if (!bar) { bar = document.createElement('div'); bar.id = 'vote-bar'; bar.className = 'vote-bar hidden'; main.insertBefore(bar, main.children[1] || null); }

    const voting = v && (v.phase === 'DAY_VOTE' || v.phase === 'DAY_VOTE_TIEBREAK' || v.phase === 'SHERIFF_ELECTION');
    if (!voting || !v.feed) { bar.classList.add('hidden'); return; }
    // 必须按标签匹配当前这轮投票：服务端 VOTE_DETAIL 的标签有「警长投票 / 放逐投票 / PK 投票」三种，
    // 只取“当天最后一条”会把同日更早的警长票当成当前放逐票显示出来。
    const wantLabel = v.phase === 'SHERIFF_ELECTION' ? '警长投票'
      : v.phase === 'DAY_VOTE_TIEBREAK' ? 'PK 投票' : '放逐投票';
    const details = v.feed.filter(e => e.type === 'VOTE_DETAIL' && e.day === v.day
      && String(e.detail || '').indexOf(wantLabel) === 0);
    const last = details[details.length - 1];
    if (!last || !last.detail) { bar.classList.add('hidden'); return; }   // 本轮还没计票，不拿旧数据凑数

    const tally = new Map();
    let total = 0;
    for (const m of String(last.detail).matchAll(/(\d+)\s*→\s*(?:(\d+)号|弃)/g)) {
      total++;
      if (!m[2]) continue;
      const t = +m[2];
      const cur = tally.get(t) || { n: 0, from: [] };
      cur.n++; cur.from.push(m[1] + '号');
      tally.set(t, cur);
    }
    if (!tally.size && !total) { bar.classList.add('hidden'); return; }

    const rows = [...tally.entries()].sort((a, b) => b[1].n - a[1].n);
    const max = rows.length ? rows[0][1].n : 1;
    bar.classList.remove('hidden');
    bar.innerHTML = `<div class="vb-head">🗳️ 当前票型 <span class="vb-total">已投 ${total} 票</span></div>`
      + (rows.length ? rows.map(([seat, info]) => `
        <div class="vb-row">
          <span class="vb-seat">${seat}号</span>
          <span class="vb-track"><i style="width:${Math.max(6, Math.round(info.n / max * 100))}%"></i></span>
          <span class="vb-n">${info.n} 票</span>
          <span class="vb-from">${info.from.map(esc).join(' ')}</span>
        </div>`).join('') : '<div class="vb-row"><span class="vb-from">全部弃票</span></div>');
  }

  /* ==================== 3. 结算页“本局大事记” ==================== */

  function renderSettleDigest() {
    const box = $('settle-roles');
    const sheet = box && box.closest('.sheet');
    if (!sheet || sheet.querySelector('.settle-digest')) return;
    const v = view();
    if (!v || !v.feed) return;
    const marks = v.feed.filter(e => ['PLAYER_DIED', 'VOTE_RESULT', 'SHOOT', 'WHITE_WOLF_BLOWUP', 'IDIOT_REVEAL', 'SHERIFF_WIN'].includes(e.type));
    if (!marks.length) return;
    const wrap = document.createElement('div');
    wrap.className = 'settle-digest';
    wrap.innerHTML = `<div class="sd-title">本局大事记</div>` + marks.slice(-14).map(e => {
      const who = e.actor ? e.actor + '号 ' : '';
      const txt = e.type === 'PLAYER_DIED' ? '出局（' + ({ WOLF: '狼刀', POISON: '毒杀', EXILE: '放逐', SHOT: '枪杀', BLOWUP: '自爆' }[e.detail] || e.detail) + '）'
        : e.type === 'VOTE_RESULT' ? (e.actor ? '被放逐' : e.detail)
          : e.type === 'SHERIFF_WIN' ? '当选警长'
            : (e.detail || '');
      return `<div class="sd-item"><b>第${e.day}天</b><span>${who}${esc(txt)}</span></div>`;
    }).join('');
    box.parentNode.insertBefore(wrap, box.nextSibling);
  }

  /* ==================== 4. 发言计时环 ==================== */

  function mountTimerRing() {
    const el = $('game-timer');
    if (!el || el.dataset.ring) return;
    el.dataset.ring = '1';
    el.insertAdjacentHTML('beforebegin', `
      <svg class="timer-ring" id="timer-ring" viewBox="0 0 36 36" aria-hidden="true">
        <circle class="tr-bg" cx="18" cy="18" r="15"></circle>
        <circle class="tr-fg" id="timer-ring-fg" cx="18" cy="18" r="15"></circle>
      </svg>`);
    const C = 2 * Math.PI * 15;
    const obs = new MutationObserver(() => {
      const m = /(\d+)\s*s/.exec(el.textContent || '');
      const ring = $('timer-ring');
      if (!ring) return;
      if (!m) { ring.classList.add('hidden'); return; }
      ring.classList.remove('hidden');
      const left = +m[1], total = Math.max(left, parseInt(ring.dataset.total || '0', 10) || left);
      ring.dataset.total = String(total);
      if (left >= total) ring.dataset.total = String(left);
      const frac = Math.max(0, Math.min(1, left / Math.max(1, +ring.dataset.total)));
      const fg = $('timer-ring-fg');
      if (fg) { fg.style.strokeDasharray = C.toFixed(1); fg.style.strokeDashoffset = (C * (1 - frac)).toFixed(1); }
      ring.classList.toggle('urgent', left <= 10);
      if (left <= 0) ring.dataset.total = '0';
    });
    obs.observe(el, { childList: true, characterData: true, subtree: true });
  }

  /* ==================== 5. 聊天 @ 自动补全 ==================== */

  function mountAtComplete() {
    const input = $('gc-input');
    if (!input || input.dataset.at) return;
    input.dataset.at = '1';
    let pop = null;
    const close = () => { if (pop) { pop.remove(); pop = null; } };
    input.addEventListener('input', () => {
      const v = view();
      const val = input.value;
      const m = /@([^\s@]{0,8})$/.exec(val.slice(0, input.selectionStart || val.length));
      if (!m || !v || !v.seats) return close();
      const kw = m[1].toLowerCase();
      const hits = v.seats.filter(s => s.alive && String(s.nickname).toLowerCase().includes(kw)).slice(0, 6);
      if (!hits.length) return close();
      if (!pop) { pop = document.createElement('div'); pop.className = 'at-pop'; input.closest('.chat-input').appendChild(pop); }
      pop.innerHTML = hits.map(s => `<button type="button" data-seat="${esc(s.nickname)}">@${esc(s.nickname)} <i>${s.seat}号</i></button>`).join('');
      pop.querySelectorAll('button').forEach(b => b.addEventListener('mousedown', (e) => {
        e.preventDefault();
        const name = b.dataset.seat;
        input.value = val.replace(/@([^\s@]*)$/, '@' + name + ' ');
        close(); input.focus();
      }));
    });
    input.addEventListener('blur', () => setTimeout(close, 120));
    input.addEventListener('keydown', (e) => { if (e.key === 'Escape') close(); });
  }

  /* ==================== 6. 快捷键与帮助层 ==================== */

  const HOTKEYS = [
    ['1 – 9 / 0', '对局中按序号选择目标按钮（第 10 个用 0）'],
    ['Enter', '发言框内：结束发言并送出；聊天框内：发送消息'],
    ['A', '对局中快速过麦（放弃本次发言/投票）'],
    ['M', '循环切换当前座位的标记（疑 → 好 → 狼）'],
    ['F', '聚焦对局聊天输入框'],
    ['T', '随机换一个主题'],
    ['D', '切换紧凑 / 舒适密度'],
    ['?', '打开本帮助'],
    ['Esc', '关闭弹窗 / 抽屉 / 浮出菜单'],
  ];

  function mountHotkeys() {
    document.addEventListener('keydown', (e) => {
      try {
        const tag = (e.target.tagName || '').toLowerCase();
        const typing = tag === 'input' || tag === 'textarea' || tag === 'select';
        if (e.key === 'Escape') return;                        // Esc 由 nav.js 处理
        if ((e.key === '?' || (e.key === '/' && e.shiftKey)) && !typing) { e.preventDefault(); toggleHelp(); return; }
        if (typing) return;
        if (typeof THEME === 'undefined') return;   // THEME 是 app.js 顶层 const，不在 window 上
        const k = e.key.toLowerCase();
        if (k === 't') { e.preventDefault(); THEME.random(); return; }
        if (k === 'd') { e.preventDefault(); THEME.setDensity(!THEME.dense); toast(THEME.dense ? '紧凑密度' : '舒适密度'); return; }
        if (k === 'f') { const gi = $('gc-input'); if (gi) { e.preventDefault(); const tab = document.querySelector('[data-panel="#dock-chat"]'); if (tab) tab.click(); gi.focus(); } return; }
        if (k === 'a') {
          const pass = $('ga-speak-pass') || $('ga-pass') || $('ga-witch-skip') || $('ga-blowup-cancel');
          if (pass) { e.preventDefault(); pass.click(); }
          return;
        }
        if (k === 'm') {
          const v = view();
          if (v && v.seats) {
            const idx = (v.seats.findIndex(s => s.seat === v.mySeat) + 1) % v.seats.length;
            const btn = document.querySelectorAll('#marker-list .mk-btn')[idx];
            if (btn) { e.preventDefault(); btn.click(); }
          }
          return;
        }
        if (/^[0-9]$/.test(e.key)) {
          const targets = document.querySelectorAll('#game-action .ga-targets .ga-btn');
          const i = e.key === '0' ? 9 : (+e.key - 1);
          if (targets[i]) { e.preventDefault(); targets[i].click(); }
        }
      } catch (_) { /* 快捷键永远不能把对局带崩 */ }
    });

    const help = document.createElement('div');
    help.id = 'modal-hotkeys';
    help.className = 'layer hidden';
    help.setAttribute('aria-modal', 'true');
    help.innerHTML = `<div class="sheet">
      <div class="sheet-title">⌨️ 快捷键</div>
      <div class="sheet-body"><div class="hk-list">${HOTKEYS.map(([k, d]) =>
        `<div class="hk-row"><kbd>${esc(k)}</kbd><span>${esc(d)}</span></div>`).join('')}</div>
        <p class="hint">提示：外观（主题/字号/密度）在右上角 🌙 面板里；改完立即生效并保存在本机。</p>
      </div>
      <div class="sheet-foot"><button class="btn btn-ghost" id="btn-hotkeys-close">关闭</button></div>
    </div>`;
    document.body.appendChild(help);
    $('btn-hotkeys-close').addEventListener('click', () => toggleHelp(false));
  }

  function toggleHelp(force) {
    const m = $('modal-hotkeys'); if (!m) return;
    const show = force === undefined ? m.classList.contains('hidden') : force;
    m.classList.toggle('hidden', !show);
  }

  /* ==================== 7. 新手引导 ==================== */

  const GUIDE = [
    ['🌙', '欢迎来到月夜村庄', '左侧（手机上在底部）是主导航：大厅、房间、对局、好友、商店、我的。'],
    ['🏠', '开一局最快的方式', '大厅顶部「创建房间」立刻建房；人不够就点「AI 陪玩房」，建房并自动补位。'],
    ['🧩', '板子与模式', '房间右侧可换预设板子或自定义角色配比；房主还能开语音同传、匿名伪装、双人组队。'],
    ['🎙', '发言与标记', '轮到你时底部会出现操作条；右侧「标记」可以给玩家打疑/好/狼，只有你自己看得到。'],
    ['⌨️', '想更快一点', '按 ? 看快捷键；🌙 面板里有 13 套主题、字号与密度档位，全存在本机。'],
  ];

  function mountGuide() {
    if (localStorage.getItem('ww_guide') === 'done') return;
    let i = 0;
    const el = document.createElement('div');
    el.id = 'guide-overlay';
    el.className = 'layer';
    el.innerHTML = `<div class="sheet guide-card">
      <div class="gc-body"><div class="gc-icon" id="gc-icon"></div><div class="gc-h" id="gc-h"></div><div class="gc-p" id="gc-p"></div></div>
      <div class="gc-dots" id="gc-dots"></div>
      <div class="sheet-foot">
        <button class="btn btn-sm btn-ghost" id="gc-skip">不再显示</button>
        <span class="spacer"></span>
        <button class="btn btn-sm" id="gc-prev">上一步</button>
        <button class="btn btn-sm btn-primary" id="gc-next">下一步</button>
      </div></div>`;
    document.body.appendChild(el);
    const show = () => {
      const [ic, h, p] = GUIDE[i];
      $('gc-icon').textContent = ic; $('gc-h').textContent = h; $('gc-p').textContent = p;
      $('gc-dots').innerHTML = GUIDE.map((_, k) => `<i class="${k === i ? 'on' : ''}"></i>`).join('');
      $('gc-prev').classList.toggle('hidden', i === 0);
      $('gc-next').textContent = i === GUIDE.length - 1 ? '开始游戏' : '下一步';
    };
    const finish = () => { localStorage.setItem('ww_guide', 'done'); el.remove(); };
    $('gc-next').addEventListener('click', () => { if (i === GUIDE.length - 1) finish(); else { i++; show(); } });
    $('gc-prev').addEventListener('click', () => { if (i > 0) { i--; show(); } });
    $('gc-skip').addEventListener('click', finish);
    show();
  }

  /* ==================== 8. 记录里点座位号 → 只看他 ==================== */

  function mountSeatFocus() {
    const box = $('game-feed');
    if (!box || box.dataset.seatfocus) return;
    box.dataset.seatfocus = '1';
    box.addEventListener('click', (e) => {
      const a = e.target.closest('.fd-actor');
      if (!a) return;
      const n = parseInt(a.textContent, 10);
      if (!n) return;
      feedFilter.seat = feedFilter.seat === n ? 0 : n;
      applyFeedFilter();
      if (window.toast) toast(feedFilter.seat ? '只看 ' + n + ' 号相关记录' : '已显示全部记录');
    });
  }

  /* ==================== 9. 行动按钮防连点（避免过期点击换来一串 400 报错） ==================== */

  function mountSubmitGuard() {
    const box = $('game-action');
    if (!box || box.dataset.guard) return;
    box.dataset.guard = '1';
    box.addEventListener('click', (e) => {
      const b = e.target.closest('.ga-btn');
      if (!b || b.disabled) return;
      // 麦克风开关与“取消自爆”只是本地 UI 切换，不提交动作，锁住会让玩家无法停止录音
      if (b.id === 'ga-mic' || b.id === 'ga-blowup-cancel') return;
      // 提交后短暂锁住整个行动区：状态推送回来会重建面板，锁随之失效
      box.querySelectorAll('.ga-btn').forEach(x => { x.disabled = true; });
      b.classList.add('pending');
      setTimeout(() => { box.querySelectorAll('.ga-btn').forEach(x => { x.disabled = false; }); b.classList.remove('pending'); }, 1400);
    }, true);
  }

  /* ==================== 包装渲染入口 ==================== */

  function wrapRender() {
    if (typeof window.renderGame !== 'function') return;
    const raw = window.renderGame;
    window.renderGame = function (v) {
      raw(v);
      try {
        mountFeedTools();
        applyFeedFilter();
        renderVoteBar();
        if (v.phase === 'GAME_OVER') renderSettleDigest();
      } catch (_) { /* 增强失败不影响对局 */ }
    };
  }

  function boot() {
    wrapRender();
    mountHotkeys();
    mountTimerRing();
    mountAtComplete();
    mountSeatFocus();
    mountSubmitGuard();
    mountGuide();
    // 观战/刷新进入对局时，即使还没收到新的状态推送也要把工具条挂上
    setTimeout(() => { if (view()) { mountFeedTools(); applyFeedFilter(); renderVoteBar(); } }, 1200);
  }

  // 调试/验收入口：可在控制台或自动化里直接驱动这几个渲染函数
  window.__WW_UX = { renderVoteBar, applyFeedFilter, renderSettleDigest, feedFilter, toggleHelp, CATS };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
