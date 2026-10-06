/* ========== appearance.js · 外观注册表与设置面板 ==========
   职责：声明有哪些主题/强调色（注册表），渲染外观面板，把用户选择写回 app.js 的 THEME。
   加载顺序：在 app.js 之前（THEME 在 app.js 里定义，面板交互发生在用户点击时，那时 THEME 已存在）。
   新增主题：在 css/themes.css 写 body.theme-<id> 令牌 + 在 WW_THEMES 注册一条即可。 */
(function () {
  'use strict';

  /* ---------- 主题注册表 ---------- */
  window.WW_THEMES = [
    { id: 'night',     name: '月夜',       icon: '🌙', swatch: ['#090d18', '#e8a33d', '#8b93ff'] },
    { id: 'day',       name: '白昼',       icon: '☀️', light: true, swatch: ['#eef1f8', '#b6741a', '#5560c8'] },
    { id: 'glass',     name: '液态玻璃',   icon: '🔮', swatch: ['#070b16', '#e8a33d', '#a0b4eb'] },
    { id: 'bloodmoon', name: '血月',       icon: '🩸', swatch: ['#150a10', '#e0526a', '#f0b06b'] },
    { id: 'deepsea',   name: '深海',       icon: '🌊', swatch: ['#04121c', '#34c3d9', '#7aa8ff'] },
    { id: 'moss',      name: '苔原',       icon: '🌿', swatch: ['#0c1410', '#d8b25c', '#7fd0a8'] },
    { id: 'neon',      name: '赛博霓虹',   icon: '🛰️', swatch: ['#07060f', '#ff3fa4', '#22e0ff'] },
    { id: 'parchment', name: '羊皮纸',     icon: '📜', light: true, swatch: ['#f2e7d0', '#9a5b1e', '#4a6b8a'] },
    { id: 'slate',     name: '石墨',       icon: '🪨', swatch: ['#14161a', '#5aa9ff', '#a4b6cf'] },
    { id: 'sakura',    name: '樱粉',       icon: '🌸', light: true, swatch: ['#fbf1f4', '#d9527f', '#8a6fd0'] },
    { id: 'aurora',    name: '极光',       icon: '🌌', swatch: ['#060d18', '#45e0b8', '#9b7dff'] },
    { id: 'ember',     name: '炭火',       icon: '🔥', swatch: ['#120c08', '#ff8b3d', '#ffd166'] },
    { id: 'mono',      name: '高对比',     icon: '◐', swatch: ['#000000', '#ffd400', '#66e0ff'], a11y: true },
  ];

  /* ---------- 强调色（覆盖 --accent，与主题正交） ---------- */
  window.WW_ACCENTS = [
    { id: 'auto' },
    { id: 'amber',  main: '#e8a33d', dim: '#a9782c', soft: 'rgba(232,163,61,.14)' },
    { id: 'coral',  main: '#ff6b57', dim: '#c9463a', soft: 'rgba(255,107,87,.15)' },
    { id: 'rose',   main: '#e35d8a', dim: '#b23f68', soft: 'rgba(227,93,138,.15)' },
    { id: 'violet', main: '#9b7dff', dim: '#6f52c9', soft: 'rgba(155,125,255,.16)' },
    { id: 'azure',  main: '#4aa3ff', dim: '#2f77c9', soft: 'rgba(74,163,255,.15)' },
    { id: 'mint',   main: '#45e0b8', dim: '#2fb192', soft: 'rgba(69,224,184,.15)' },
  ];

  const esc = (s) => String(s).replace(/[&<>"]/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
  // 注意：app.js 里 THEME 是顶层 const，属于全局词法绑定、不是 window 属性，
  // 所以必须按标识符取，不能写 window.THEME（那样永远拿到 undefined，面板会整体失效）。
  const T = () => (typeof THEME !== 'undefined' ? THEME : (window.THEME || null));

  function renderGrids() {
    const grid = document.getElementById('theme-grid');
    if (grid && !grid.dataset.rendered) {
      grid.innerHTML = window.WW_THEMES.map(t => `
        <button class="theme-cell" data-theme="${esc(t.id)}" type="button" title="${esc(t.name)}${t.light ? '（浅色）' : ''}${t.a11y ? '（无障碍高对比）' : ''}">
          <span class="tc-swatch">
            ${t.swatch.map(c => `<i style="background:${esc(c)}"></i>`).join('')}
          </span>
          <span class="tc-name">${t.icon} ${esc(t.name)}</span>
        </button>`).join('');
      grid.dataset.rendered = '1';
    }
    const acc = document.getElementById('accent-row');
    if (acc && !acc.dataset.rendered) {
      acc.innerHTML = window.WW_ACCENTS.map(a => a.id === 'auto'
        ? `<button class="accent-dot auto" data-accent="auto" type="button" title="跟随主题">自动</button>`
        : `<button class="accent-dot" data-accent="${esc(a.id)}" type="button" title="${esc(a.id)}" style="background:${esc(a.main)}"></button>`).join('');
      acc.dataset.rendered = '1';
    }
  }

  /** 把当前状态反映到面板控件上（只改类/value，绝不调用 THEME.apply，避免回环）。 */
  function syncControls() {
    const t = T(); if (!t) return;
    document.querySelectorAll('[data-theme]').forEach(b => b.classList.toggle('active', b.dataset.theme === t.mode));
    document.querySelectorAll('[data-accent]').forEach(b => b.classList.toggle('active', b.dataset.accent === t.accent));
    document.querySelectorAll('[data-font]').forEach(b => b.classList.toggle('active', b.dataset.font === t.font));
    document.querySelectorAll('[data-density]').forEach(b => b.classList.toggle('active', b.dataset.density === (t.dense ? 'dense' : 'cozy')));
    const mo = document.getElementById('motion-toggle'); if (mo) mo.checked = !!t.motion;
    const br = document.getElementById('bg-bright');
    if (br) { br.value = String(t.bright); const v = document.getElementById('bg-bright-val'); if (v) v.textContent = Math.round(t.bright * 100) + '%'; }
  }

  function mount() {
    renderGrids();
    const t = T(); if (!t) return;

    const on = (sel, ev, fn) => document.querySelectorAll(sel).forEach(el => el.addEventListener(ev, fn));

    // 面板开关
    const openBtn = document.getElementById('btn-theme');
    if (openBtn) openBtn.addEventListener('click', () => {
      const m = document.getElementById('modal-theme');
      if (!m) return;
      m.classList.toggle('hidden');
      if (!m.classList.contains('hidden')) renderGrids();
    });
    const closeBtn = document.getElementById('btn-theme-close');
    if (closeBtn) closeBtn.addEventListener('click', () => document.getElementById('modal-theme').classList.add('hidden'));

    // 主题 / 强调色 / 字号 / 密度 / 动效
    on('[data-theme]', 'click', e => t.setMode(e.currentTarget.dataset.theme));
    on('[data-accent]', 'click', e => t.setAccent(e.currentTarget.dataset.accent));
    on('[data-font]', 'click', e => t.setFont(e.currentTarget.dataset.font));
    on('[data-density]', 'click', e => t.setDensity(e.currentTarget.dataset.density === 'dense'));
    const mo = document.getElementById('motion-toggle');
    if (mo) mo.addEventListener('change', () => t.setMotion(mo.checked));
    const dice = document.getElementById('theme-random');
    if (dice) dice.addEventListener('click', () => t.random());

    // 背景亮度与自定义背景
    const br = document.getElementById('bg-bright');
    if (br) br.addEventListener('input', () => t.setBright(parseFloat(br.value)));
    const up = document.getElementById('bg-upload-btn');
    if (up) up.addEventListener('click', () => document.getElementById('bg-file').click());
    const bf = document.getElementById('bg-file');
    if (bf) bf.addEventListener('change', e => t.setBgFile(e.target.files[0]));
    ['bg-default', 'bg-clear'].forEach(id => {
      const el = document.getElementById(id);
      if (el) el.addEventListener('click', () => t.clearBg());
    });

    syncControls();
  }

  window.WW_APPEARANCE = { mount, syncControls, renderGrids };

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', renderGrids);
  else renderGrids();
})();
