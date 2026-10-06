/* ========== nav.js · 应用外壳导航层 ==========
   职责：hash 路由（浏览器/系统返回键可用）、覆盖层开关、分段标签、浮出菜单、壳层状态类。
   设计约束：app.js 仍按老办法用 classList.add/remove('hidden') 控制视图与弹窗，
            本文件只做"增强"（同步 URL、历史回退、遮罩点击、Esc、滚动锁），
            任一环节失败时界面依旧可用，不会出现白屏。 */
(function () {
  'use strict';

  /* ---------- 路由表：route → 视图 id ---------- */
  const VIEWS = {
    auth: 'view-auth', lobby: 'view-lobby', room: 'view-room', game: 'view-game',
    profile: 'view-profile', shop: 'view-shop', friends: 'view-friends',
    tickets: 'view-tickets', replay: 'view-replay'
  };
  /* 覆盖层（由旧弹窗升级为整页/抽屉）：route → 元素 id */
  const LAYERS = {
    ranking: 'modal-ranking', forum: 'modal-forum', news: 'modal-news',
    download: 'modal-download', guide: 'modal-install',
    join: 'modal-join', settle: 'modal-settle', invite: 'modal-invite',
    card: 'modal-friend-profile', ticket: 'modal-ticket', theme: 'modal-theme'
  };
  const viewOfLayer = {}; Object.keys(LAYERS).forEach(r => { viewOfLayer[LAYERS[r]] = r; });

  /* 当前底页：直接看 DOM（app.js 用 let 声明 currentView，不挂在 window 上，不可依赖） */
  function currentRoute() {
    for (const r of Object.keys(VIEWS)) {
      const el = document.getElementById(VIEWS[r]);
      if (el && !el.classList.contains('hidden')) return r;
    }
    return 'auth';
  }

  /* 无覆盖层时应回到的底页 */
  function baseRoute() { return currentRoute(); }

  let guard = false;                       // 防 hash ↔ class 互相触发形成死循环
  /* 启动瞬间让路：app.js 的 boot() 用 #profile / #shop / #replay=ID 这类深链做直达，
     地址栏归本路由接管之前，先不改写 hash，避免把深链吃掉。 */
  let armed = false;
  const hidden = (el) => !el || el.classList.contains('hidden');

  function hashFor(route) { return '#' + route; }   // 与既有深链约定保持一致（无斜杠），同时兼容 #/route

  /* 当前可见的覆盖层：注册层 + 运行时动态创建的层（如快捷键帮助、新手引导），
     否则这些层既不受 Esc 关闭，也不会锁背景滚动。 */
  function openLayers() {
    const seen = [], ids = {};
    document.querySelectorAll('.layer').forEach(el => {
      if (el.classList.contains('hidden')) return;
      ids[el.id] = 1;
      seen.push(el);
    });
    return seen;
  }

  function topLayerRoute() {
    const list = openLayers();
    return list.length ? viewOfLayer[list[list.length - 1].id] : null;
  }

  /* ---------- hash 同步 ---------- */
  function syncHash(push) {
    if (!armed) return;
    const layer = topLayerRoute();
    const want = hashFor(layer || baseRoute());
    if (location.hash === want) return;
    guard = true;
    if (push) location.hash = want;                 // 产生一条历史，返回键可逐层关窗
    else location.replace(location.pathname + location.search + want);
    setTimeout(() => { guard = false; }, 0);
  }

  /* 按 hash 呈现对应界面 */
  function applyHash() {
    const route = (location.hash || '').replace(/^#\/?/, '').split('=')[0].split('?')[0];
    if (VIEWS[route]) {
      closeAllLayers();
      if (currentRoute() !== route && typeof window.showView === 'function') window.showView(route);
    } else if (LAYERS[route]) {
      const el = document.getElementById(LAYERS[route]);
      if (el && hidden(el)) el.classList.remove('hidden');
    }
    lockScroll();
  }

  function closeLayer(id) {
    const el = document.getElementById(id);
    if (!el || hidden(el)) return;
    el.classList.add('hidden');
    document.dispatchEvent(new CustomEvent('ww:layer-close', { detail: id }));
  }

  function closeAllLayers() {
    openLayers().forEach(el => closeLayer(el.id));
  }

  function lockScroll() {
    const any = openLayers().length > 0;
    document.body.classList.toggle('layer-open', any);
  }

  /* ---------- 对外接口 ---------- */
  const Nav = {
    version: '1.0.0',
    routes: VIEWS,
    get current() { return currentRoute(); },
    go(route) {
      if (!VIEWS[route]) return;
      /* “房间/对局”在没有对应状态时不该是空白页：退回大厅并说明原因 */
      const inRoom = (typeof state !== 'undefined') && state && state.room && state.room.roomNo;
      const inGame = (typeof game !== 'undefined') && game && game.view && game.view.phase && game.view.phase !== 'GAME_OVER';
      if (route === 'room' && !inRoom) { if (typeof toast === 'function') toast('你还没有加入房间，先去大厅建房或加入'); route = 'lobby'; }
      if (route === 'game' && !inGame) { if (typeof toast === 'function') toast('当前没有进行中的对局'); route = inRoom ? 'room' : 'lobby'; }
      armed = true;
      closeAllLayers();
      location.hash = hashFor(route);
      if (typeof window.showView === 'function') window.showView(route);
    },
    open(routeOrId) {
      const id = LAYERS[routeOrId] || routeOrId;
      const el = document.getElementById(id);
      if (!el) return;
      armed = true;
      el.classList.remove('hidden');
      lockScroll();
      syncHash(true);
    },
    close(routeOrId) { const id = LAYERS[routeOrId] || routeOrId; closeLayer(id); lockScroll(); syncHash(false); },
    toggle(routeOrId) {
      const id = LAYERS[routeOrId] || routeOrId;
      const el = document.getElementById(id);
      if (!el) return;
      if (hidden(el)) Nav.open(id); else Nav.close(id);
    },
    /* app.js 的 showView 末尾调用：把壳层状态、导航高亮、URL 同步好 */
    sync(name) {
      const shell = document.getElementById('app-shell');
      if (shell) shell.classList.toggle('hidden', name === 'auth');
      document.body.classList.toggle('state-auth', name === 'auth');
      document.body.classList.toggle('state-immersive', name === 'game');
      document.querySelectorAll('.nav-item[data-route]').forEach(it =>
        it.classList.toggle('active', it.dataset.route === name));
      const g = document.querySelector('.nav-item.game-only');
      if (g) g.classList.toggle('hidden', name !== 'game');
      /* 在房间里但看着别的页 → “房间”标签点亮一个提示点，方便找回桌子 */
      const dot = document.getElementById('nav-dot-room');
      if (dot) {
        const inRoom = (typeof state !== 'undefined') && state && state.room && state.room.roomNo;
        dot.classList.toggle('hidden', !(inRoom && name !== 'room' && name !== 'game'));
      }
      syncHash(false);
    }
  };
  window.Nav = Nav;

  /* ---------- 监听 ---------- */
  function boot() {
    /* 1) 覆盖层开关由 app.js 直接改 class；这里观察并同步 URL / 滚动锁 */
    new MutationObserver((muts) => {
      if (guard) return;
      for (const m of muts) {
        const t = m.target;
        if (t.nodeType !== 1 || !t.classList || !t.classList.contains('layer')) continue;
        lockScroll();
        syncHash(!t.classList.contains('hidden'));   // 打开→压历史；关闭→改写当前项
        return;
      }
    }).observe(document.body, { subtree: true, attributes: true, attributeFilter: ['class'] });

    /* 2) 返回键 / 手改地址栏 */
    window.addEventListener('hashchange', () => { if (guard) return; applyHash(); });
    /* app.js 的 boot() 是异步的（要拉 /api/auth/me），故延后接管地址栏，
       先让它的 #profile / #replay=ID 深链跑完，再统一规范化成路由格式。 */
    setTimeout(() => { armed = true; syncHash(false); lockScroll(); }, 1800);

    /* 3) 分段标签（新结构专用，不与旧 .tab 逻辑冲突） */
    document.querySelectorAll('[data-tabset]').forEach(root => {
      const btns = Array.prototype.slice.call(root.querySelectorAll('[data-panel]'));
      const body = root.nextElementSibling;
      if (!body || !btns.length) return;
      const panels = Array.prototype.slice.call(body.querySelectorAll('[id]'));
      btns.forEach(b => b.addEventListener('click', () => {
        btns.forEach(x => x.classList.toggle('active', x === b));
        const want = (b.dataset.panel || '').replace(/^#/, '');
        panels.forEach(p => p.classList.toggle('active', p.id === want));
        body.scrollTop = 0;
      }));
    });

    /* 4) 浮出菜单（更多 / 用户卡） */
    document.querySelectorAll('[data-popover]').forEach(btn => {
      const pop = document.getElementById(btn.dataset.popover);
      if (!pop) return;
      btn.addEventListener('click', (e) => {
        e.stopPropagation();
        const willOpen = pop.hidden;
        document.querySelectorAll('.pop').forEach(p => { p.hidden = true; p.classList.remove('open'); });
        pop.hidden = !willOpen;
        pop.classList.toggle('open', willOpen);
      });
      pop.addEventListener('click', (e) => e.stopPropagation());
    });
    document.addEventListener('click', () => {
      document.querySelectorAll('.pop.open').forEach(p => { p.hidden = true; p.classList.remove('open'); });
    });

    /* 5) 通用打开器：data-open="modal-xxx"（仅揭开覆盖层）
          / data-click="btn-xxx"（转发给既有触发按钮，保留它自带的数据加载逻辑）
          / data-route="lobby" */
    document.addEventListener('click', (e) => {
      const fwd = e.target.closest && e.target.closest('[data-click]');
      if (fwd) { const t = document.getElementById(fwd.dataset.click); if (t) { t.click(); return; } }
      const op = e.target.closest && e.target.closest('[data-open]');
      if (op) { Nav.open(op.dataset.open); return; }
      const rt = e.target.closest && e.target.closest('[data-route]');
      if (rt) { Nav.go(rt.dataset.route); return; }
      const layerBg = e.target.closest && e.target.closest('.layer');
      if (layerBg && e.target === layerBg) Nav.close(layerBg.id);      // 点遮罩关闭
    });

    /* 6) Esc 关闭最上层覆盖层 */
    document.addEventListener('keydown', (e) => {
      if (e.key !== 'Escape') return;
      const list = openLayers();
      if (list.length) { Nav.close(list[list.length - 1].id); return; }
      document.querySelectorAll('.pop.open').forEach(p => { p.hidden = true; p.classList.remove('open'); });
    });

    /* 7) 复制房间号（新加的便捷操作，不打断原有外链邀请） */
    const copyNo = document.getElementById('btn-copy-roomno');
    if (copyNo) copyNo.addEventListener('click', () => {
      const no = (document.getElementById('room-no') || {}).textContent || '';
      if (!no || no === '------') return;
      try { navigator.clipboard.writeText(no.trim()).then(() => { if (typeof window.toast === 'function') toast('已复制房间号 ' + no.trim()); }, () => { }); } catch (_) { }
    });

    /* 8) 大厅「AI 陪玩房」：建房 + 自动补位（复用 app.js 的两个动作） */
    const quick = document.getElementById('btn-quick-ai');
    if (quick) quick.addEventListener('click', () => {
      if (typeof window.quickAiRoom === 'function') window.quickAiRoom();
    });

    /* 9) 去掉启动遮罩 */
    document.body.classList.remove('boot');

    /* 10) 管理员设置的“网页最大高度”：匿名可读，失败静默（保持默认满屏） */
    fetch('/api/config/display').then(r => r.json()).then(d => {
      const h = parseInt(d && d.webMaxHeight, 10) || 0;
      if (h > 0) {
        document.documentElement.style.setProperty('--app-max-h', h + 'vh');   // 百分比按视口高度换算为 vh，避开网格布局内百分比解析不确定的问题
        document.body.classList.add('hmax');
      }
    }).catch(() => {});

    /* 11) 管理员功能开关：被关闭的功能在大厅/房间隐藏 */
    fetch('/api/config/features').then(r => r.json()).then(d => {
      const off = (d && d.disabled) || [];
      off.forEach(id => document.querySelectorAll('[data-feat="' + id + '"]').forEach(el => el.classList.add('hidden')));
      window.__FEATS_OFF = off;
    }).catch(() => {});
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', boot);
  else boot();
})();
