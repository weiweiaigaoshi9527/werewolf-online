/* ========== extras.js：背景音乐（本地 MP3）+ 安装应用 + 大厅快捷入口 ========== */
/* 依赖 app.js 的全局 $ / toast。所有音乐文件仅保存在玩家本机（IndexedDB），绝不上传。 */

/* ---------------- 安装为应用（PWA） ---------------- */
(function installApp() {
  let deferred = null;
  const btn = () => document.getElementById('btn-install');
  window.addEventListener('beforeinstallprompt', (e) => {
    e.preventDefault();
    deferred = e;
    if (btn()) btn().classList.remove('hidden');
  });
  window.addEventListener('appinstalled', () => {
    if (btn()) btn().classList.add('hidden');
    if (typeof toast === 'function') toast('🎉 已安装为桌面 / 主屏应用');
  });
  document.addEventListener('click', (e) => {
    if (e.target && e.target.id === 'btn-install') {
      if (!deferred) {
        if (typeof toast === 'function') toast('请用浏览器菜单里的「添加到主屏幕 / 安装应用」', true);
        return;
      }
      deferred.prompt();
      deferred.userChoice.then((c) => {
        if (c.outcome === 'accepted') { deferred = null; if (btn()) btn().classList.add('hidden'); }
      });
    }
  });
})();

/* ---------------- 大厅快捷入口 ---------------- */
document.addEventListener('DOMContentLoaded', () => {
  const hc = document.getElementById('hero-create');
  const hj = document.getElementById('hero-join');
  if (hc) hc.addEventListener('click', () => document.getElementById('btn-create-room').click());
  if (hj) hj.addEventListener('click', () => document.getElementById('btn-join-room').click());
});

/* ---------------- 背景音乐：本地 MP3 播放器 ---------------- */
const BGM = (() => {
  const DB_NAME = 'ww-bgm', STORE = 'tracks';
  let db = null;
  let tracks = [];            // [{id, name, blob}]
  let cur = -1;               // 当前播放索引
  let audio = null;
  let panel = null;

  function idb() {
    return new Promise((res, rej) => {
      if (db) return res(db);
      const rq = indexedDB.open(DB_NAME, 1);
      rq.onupgradeneeded = () => rq.result.createObjectStore(STORE, { autoIncrement: true });
      rq.onsuccess = () => { db = rq.result; res(db); };
      rq.onerror = () => rej(rq.error);
    });
  }
  function tx(mode) {
    return idb().then((d) => d.transaction(STORE, mode).objectStore(STORE));
  }
  function loadAll() {
    return new Promise((res, rej) => {
      tx('readonly').then((st) => {
        const vals = [], keys = [];
        const c1 = st.openCursor();
        c1.onsuccess = () => {
          const c = c1.result;
          if (!c) { res(keys.map((k, i) => ({ id: k, name: vals[i].name, blob: vals[i].blob }))); return; }
          keys.push(c.key); vals.push(c.value); c.continue();
        };
        c1.onerror = () => rej(c1.error);
      }).catch(rej);
    });
  }
  function add(name, blob) {
    return new Promise((res, rej) => {
      tx('readwrite').then((st) => {
        const rq = st.add({ name, blob });
        rq.onsuccess = () => res(rq.result);
        rq.onerror = () => rej(rq.error);
      }).catch(rej);
    });
  }
  function del(id) {
    return new Promise((res, rej) => {
      tx('readwrite').then((st) => {
        const rq = st.delete(id);
        rq.onsuccess = () => res(); rq.onerror = () => rej(rq.error);
      }).catch(rej);
    });
  }

  function initAudio() {
    if (audio) return;
    audio = new Audio();
    audio.volume = 0.6;
    audio.addEventListener('ended', () => {
      if (!tracks.length) return;
      const loop = document.getElementById('bgm-loop');
      let next = cur + 1;
      if (next >= tracks.length) {
        if (loop && loop.checked) next = 0;
        else { setPlayIcon(false); return; }
      }
      play(next);
    });
    const vol = document.getElementById('bgm-vol');
    if (vol) vol.addEventListener('input', () => { audio.volume = vol.value / 100; });
  }

  function setPlayIcon(playing) {
    const b = document.getElementById('bgm-play');
    if (b) b.textContent = playing ? '⏸' : '▶';
  }

  function play(idx) {
    if (!tracks.length) return;
    if (idx < 0) idx = tracks.length - 1;
    if (idx >= tracks.length) idx = 0;
    cur = idx;
    initAudio();
    if (audio._url) URL.revokeObjectURL(audio._url);
    audio._url = URL.createObjectURL(tracks[cur].blob);
    audio.src = audio._url;
    audio.play().catch(() => {});
    setPlayIcon(true);
    renderList();
  }
  function toggle() {
    initAudio();
    if (!tracks.length) { if (typeof toast === 'function') toast('先添加几首本地 MP3 吧', true); return; }
    if (cur < 0) return play(0);
    if (audio.paused) { audio.play().catch(() => {}); setPlayIcon(true); }
    else { audio.pause(); setPlayIcon(false); }
  }
  function next() { if (tracks.length) play((cur + 1) % tracks.length); }
  function prev() { if (tracks.length) play((cur - 1 + tracks.length) % tracks.length); }

  function renderList() {
    const box = document.getElementById('bgm-list');
    if (!box) return;
    if (!tracks.length) { box.innerHTML = '<div class="bgm-empty">还没有添加音乐，选几首喜欢的 MP3 吧</div>'; return; }
    box.innerHTML = '';
    tracks.forEach((t, i) => {
      const el = document.createElement('div');
      el.className = 'bgm-item' + (i === cur ? ' on' : '');
      el.innerHTML = `<span class="bgm-name">${(i === cur ? '▶ ' : '') + t.name}</span><button class="bgm-del" title="移除">✕</button>`;
      el.querySelector('.bgm-name').onclick = () => play(i);
      el.querySelector('.bgm-del').onclick = async (ev) => {
        ev.stopPropagation();
        if (i === cur) { initAudio(); audio.pause(); setPlayIcon(false); cur = -1; }
        await del(t.id);
        await refresh();
      };
      box.appendChild(el);
    });
  }
  async function refresh() {
    tracks = await loadAll().catch(() => []);
    if (cur >= tracks.length) { cur = -1; setPlayIcon(false); }
    renderList();
  }

  function togglePanel() {
    panel = document.getElementById('bgm-panel');
    if (!panel) return;
    panel.classList.toggle('hidden');
    if (!panel.classList.contains('hidden')) refresh();
  }

  function pickFiles(ev) {
    const files = Array.from(ev.target.files || []);
    files.forEach((f) => add(f.name, f));
    Promise.all([]).then(async () => {
      await refresh();
      if (typeof toast === 'function') toast('已添加 ' + files.length + ' 首到播放列表');
      if (cur < 0 && tracks.length) play(0);
    });
    ev.target.value = '';
  }

  function bind() {
    const bgmBtn = document.getElementById('btn-bgm');
    if (bgmBtn) bgmBtn.addEventListener('click', togglePanel);
    const pick = document.getElementById('bgm-pick');
    if (pick) pick.addEventListener('click', () => document.getElementById('bgm-files').click());
    const files = document.getElementById('bgm-files');
    if (files) files.addEventListener('change', pickFiles);
    const play = document.getElementById('bgm-play');
    if (play) play.addEventListener('click', toggle);
    const nx = document.getElementById('bgm-next');
    if (nx) nx.addEventListener('click', next);
    const pv = document.getElementById('bgm-prev');
    if (pv) pv.addEventListener('click', prev);
  }
  document.addEventListener('DOMContentLoaded', bind);

  return { refresh };
})();
