/* ===== 狼人杀 · 后台管理前端（独立页，仅管理员） ===== */
const TOKEN_KEY = 'ww_admin_token';
const $ = (id) => document.getElementById(id);
const esc = (s) => String(s == null ? '' : s).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));

async function api(path, method = 'GET', body = null) {
  const headers = { 'Content-Type': 'application/json' };
  const tok = localStorage.getItem(TOKEN_KEY);
  if (tok) headers['Authorization'] = 'Bearer ' + tok;
  const res = await fetch(path, { method, headers, body: body ? JSON.stringify(body) : undefined });
  const data = await res.json().catch(() => ({}));
  if (res.status === 401 || res.status === 403) {
    // 登录失效 / 无权限：清理陈旧的本地 token，避免残留导致反复弹登录页
    if (res.status === 401) { try { localStorage.removeItem(TOKEN_KEY); } catch (_) {} }
    showLogin(data.error || (res.status === 403 ? '需要管理员权限' : '登录已失效'));
    throw new Error(data.error || '无权限');
  }
  if (!res.ok) throw new Error(data.error || ('请求失败 ' + res.status));
  return data;
}

function showLogin(msg) {
  $('adm-app').classList.add('hidden');
  $('adm-login').classList.remove('hidden');
  if (msg) $('lg-err').textContent = msg;
}
function showApp(user) {
  $('adm-login').classList.add('hidden');
  $('adm-app').classList.remove('hidden');
  $('adm-who').textContent = user.nickname + '（管理员）';
}

async function doLogin() {
  $('lg-err').textContent = '';
  try {
    const r = await fetch('/api/auth/login', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ username: $('lg-user').value.trim(), password: $('lg-pass').value })
    });
    const d = await r.json();
    if (!r.ok) { $('lg-err').textContent = d.error || '登录失败'; return; }
    if (!d.user.admin) { $('lg-err').textContent = '该账号不是管理员，无法进入后台'; return; }
    localStorage.setItem(TOKEN_KEY, d.token);
    showApp(d.user);
    loadSystem(); loadAi(); loadUsers('');
  } catch (e) { $('lg-err').textContent = '网络错误：' + e.message; }
}

function logout() { localStorage.removeItem(TOKEN_KEY); showLogin(''); }

/* ---------- 页签 ---------- */
function activateTab(name) {
  document.querySelectorAll('.adm-tab').forEach(x => x.classList.toggle('active', x.dataset.tab === name));
  document.querySelectorAll('.adm-panel').forEach(x => x.classList.toggle('active', x.id === 'p-' + name));
  if (name === 'system') loadSystem();
  if (name === 'voice') loadVoice();
  if (name === 'users') loadUsers($('u-kw').value.trim());
  if (name === 'rooms') loadRooms();
  if (name === 'redeem') loadRedeem();
  if (name === 'tickets') loadTickets();
  if (name === 'ops') loadOps();
  if (name === 'forum') loadForum();
}
function bindTabs() {
  document.querySelectorAll('.adm-tab').forEach(t => t.onclick = () => activateTab(t.dataset.tab));
}

/* ---------- 系统状态 ---------- */
async function loadSystem() {
  try {
    const s = await api('/api/admin/system');
    const ok = (b) => b ? '<span class="pill ok">在线</span>' : '<span class="pill bad">离线</span>';
    const v = s.voice || {}, ai = s.ai || {};
    $('sys-box').innerHTML =
      `<div class="grid2">
        <div class="stat"><b>${esc(s.version)}</b><span>版本</span></div>
        <div class="stat"><b>${s.online}</b><span>在线人数</span></div>
        <div class="stat"><b>${s.rooms}</b><span>房间数</span></div>
        <div class="stat"><b>${s.activeGames}</b><span>进行中对局</span></div>
        <div class="stat"><b>${s.users}</b><span>注册用户</span></div>
        <div class="stat"><b>${ai.enabled ? '已启用' : '未启用'}</b><span>AI · ${esc(ai.model || '—')}</span></div>
        <div class="stat"><b>${ok(v.asr && v.asr.ok)}</b><span>ASR (SenseVoice)</span></div>
        <div class="stat"><b>${ok(v.tts && v.tts.ok)}</b><span>TTS (Kokoro)</span></div>
      </div>
      <div class="hint" style="margin-top:12px">服务器时间：${esc(s.serverTime)} · AI Base：${esc(ai.baseUrl || '未配置')}${ai.mock ? '（mock 离线）' : ''}</div>`;
  } catch (e) { $('sys-box').textContent = '加载失败：' + e.message; }
}

/* ---------- AI 配置 ---------- */
async function loadAi() {
  try {
    const c = await api('/api/admin/ai/config');
    $('ai-base').value = c.baseUrl || '';
    $('ai-key').value = '';
    $('ai-model').value = c.model || '';
    $('ai-depth').value = c.depth || 'medium';
    $('ai-temp').value = c.temperature != null ? c.temperature : 0.8;
    $('ai-timeout').value = c.timeoutSeconds || 30;
    $('ai-retry').value = c.maxRetries != null ? c.maxRetries : 2;
    $('ai-conc').value = c.concurrency || 4;
    $('ai-enabled').checked = !!c.enabled;
    $('ai-status').textContent = c.mock ? '（当前 mock 离线）' : (c.hasKey ? '（已存密钥）' : '');
  } catch (e) { $('ai-status').textContent = '加载失败：' + e.message; }
}
async function saveAi() {
  $('ai-status').textContent = '保存中…';
  const body = {
    baseUrl: $('ai-base').value.trim(), model: $('ai-model').value.trim(), depth: $('ai-depth').value,
    temperature: parseFloat($('ai-temp').value), timeoutSeconds: parseInt($('ai-timeout').value),
    maxRetries: parseInt($('ai-retry').value), concurrency: parseInt($('ai-conc').value),
    enabled: $('ai-enabled').checked,
  };
  const key = $('ai-key').value.trim(); if (key) body.apiKey = key;
  try { await api('/api/admin/ai/config', 'POST', body); $('ai-status').textContent = '✅ 已保存'; $('ai-key').value = ''; loadAi(); }
  catch (e) { $('ai-status').textContent = '❌ ' + e.message; }
}
async function testAi() {
  $('ai-status').textContent = '测试中…';
  try { const r = await api('/api/admin/ai/test', 'POST', {}); $('ai-status').textContent = '✅ 连通：' + (r.reply || ''); }
  catch (e) { $('ai-status').textContent = '❌ ' + e.message; }
}

/* ---------- 语音配置 ---------- */
const VF = [
  ['enabled', '启用语音', 'bool'], ['asrUrl', 'ASR 地址', 'text'], ['ttsUrl', 'TTS 地址', 'text'],
  ['sampleRate', '采样率', 'int'], ['serviceTimeoutSeconds', '服务超时(s)', 'int'],
  ['voices', '音色列表(每行一个)', 'lines'],
  ['msPerChar', '每字时长(ms)', 'int'], ['gapMinMs', '句间停顿下界(ms)', 'int'], ['gapMaxMs', '句间停顿上界(ms)', 'int'],
  ['leadInMinMs', '起手延迟下界(ms)', 'int'], ['leadInMaxMs', '起手延迟上界(ms)', 'int'],
  ['maxSentencesPerTurn', '每回合最多句数', 'int'], ['maxSpeechChars', '每回合最多字数', 'int'],
  ['vadRmsThreshold', 'VAD 阈值', 'float'], ['vadSilenceMs', 'VAD 静音(ms)', 'int'],
  ['vadMaxSpeechMs', 'VAD 最长语音(ms)', 'int'], ['vadMinSpeechMs', 'VAD 最短语音(ms)', 'int'],
  ['anonNames', '匿名昵称(逗号)', 'csv'], ['anonAvatars', '匿名头像id(逗号)', 'csv'],
  ['anonNickColor', '匿名昵称色', 'text'], ['anonFrameColor', '匿名头像框色', 'text'],
];
async function loadVoice() {
  try {
    const d = await api('/api/admin/voice');
    const c = d.config || {}, h = d.health || {};
    let html = `<div class="hint">ASR ${h.asr && h.asr.ok ? '<span class="pill ok">在线</span>' : '<span class="pill bad">离线</span>'}
      · TTS ${h.tts && h.tts.ok ? '<span class="pill ok">在线</span>' : '<span class="pill bad">离线</span>'}
      · 保存后即时生效并持久化（重启保留）。</div>`;
    for (const [k, label, type] of VF) {
      let val = c[k];
      if (type === 'lines') val = (val || []).join('\n');
      else if (type === 'csv') val = (val || []).join(', ');
      else if (type === 'bool') { html += `<div class="field"><label>${label}</label><input type="checkbox" id="v_${k}" ${val ? 'checked' : ''} style="width:auto"></div>`; continue; }
      const inputType = (type === 'int' || type === 'float') ? 'number' : 'text';
      const step = type === 'float' ? 'step="0.001"' : '';
      const box = type === 'lines' ? 'textarea' : 'input';
      if (box === 'textarea') html += `<div class="field"><label>${label}</label><textarea id="v_${k}">${esc(val)}</textarea></div>`;
      else html += `<div class="field"><label>${label}</label><input type="${inputType}" ${step} id="v_${k}" value="${esc(val == null ? '' : val)}"></div>`;
    }
    html += `<div class="field"><label></label><button class="btn primary" id="voice-save">保存语音配置</button><span class="hint" id="voice-status"></span></div>`;
    $('voice-box').innerHTML = html;
    $('voice-save').onclick = saveVoice;
  } catch (e) { $('voice-box').textContent = '加载失败：' + e.message; }
}
async function saveVoice() {
  const obj = {};
  for (const [k, label, type] of VF) {
    const el = $('v_' + k); if (!el) continue;
    if (type === 'bool') obj[k] = el.checked;
    else if (type === 'int') obj[k] = parseInt(el.value);
    else if (type === 'float') obj[k] = parseFloat(el.value);
    else if (type === 'lines') obj[k] = el.value.split('\n').map(s => s.trim()).filter(Boolean);
    else if (type === 'csv') obj[k] = el.value.split(',').map(s => s.trim()).filter(Boolean).map(k2 => k2.match(/^\d+$/) ? parseInt(k2) : k2);
    else obj[k] = el.value;
  }
  $('voice-status').textContent = '保存中…';
  try { await api('/api/admin/voice', 'POST', obj); $('voice-status').textContent = '✅ 已保存并生效'; }
  catch (e) { $('voice-status').textContent = '❌ ' + e.message; }
}

/* ---------- 用户管理 ---------- */
function selectedIds() {
  return Array.from(document.querySelectorAll('#u-table .u-sel:checked')).map(c => +c.dataset.id);
}
function updateSelCount() {
  const n = selectedIds().length;
  const box = document.querySelectorAll('#u-table .u-sel');
  const all = $('u-selall');
  if (all) { all.checked = box.length > 0 && n === box.length; all.indeterminate = n > 0 && n < box.length; }
  const c = $('u-selcount'); if (c) c.textContent = '已选 ' + n + ' 人';
}
async function loadUsers(kw) {
  try {
    const list = await api('/api/admin/users' + (kw ? '?kw=' + encodeURIComponent(kw) : ''));
    let h = `<tr><th></th><th>ID</th><th>用户名</th><th>昵称</th><th>等级</th><th>金币</th><th>状态</th><th>操作</th></tr>`;
    for (const u of list) {
      h += `<tr>
        <td><input type="checkbox" class="u-sel" data-id="${u.id}" style="width:auto"></td>
        <td>${u.id}</td><td>${esc(u.username)}</td><td>${esc(u.nickname)}</td>
        <td>Lv.${u.level}</td><td>${u.gold}</td>
        <td>${u.online ? '<span class="pill ok">在线</span>' : '<span class="pill bad">离线</span>'}
            ${u.admin ? ' <span class="pill ok">管理员</span>' : ''}${u.banned ? ' <span class="pill bad">封禁</span>' : ''}${u.vip ? ' <span class="pill ok">VIP' + u.vip + '</span>' : ''}</td>
        <td><div class="row-actions">
          <button class="btn sm" data-a="edit" data-id="${u.id}" data-nick="${esc(u.nickname)}" data-lv="${u.level}" data-exp="${u.exp ?? 0}" data-gold="${u.gold}">编辑</button>
          <button class="btn sm" data-a="login" data-id="${u.id}" data-nick="${esc(u.nickname)}">进入账号</button>
          <button class="btn sm" data-a="admin" data-id="${u.id}" data-v="${u.admin ? 0 : 1}">${u.admin ? '取消管理' : '设为管理'}</button>
          <button class="btn sm" data-a="vip" data-id="${u.id}" data-v="${u.vip || 0}">VIP</button>
          <button class="btn sm ${u.banned ? '' : 'danger'}" data-a="ban" data-id="${u.id}" data-v="${u.banned ? 0 : 1}">${u.banned ? '解封' : '封禁'}</button>
          <button class="btn sm" data-a="gold" data-id="${u.id}">金币</button>
          <button class="btn sm" data-a="pw" data-id="${u.id}">重置密码</button>
          <button class="btn sm danger" data-a="del" data-id="${u.id}">删除</button>
        </div></td></tr>`;
    }
    $('u-table').innerHTML = h;
    $('u-table').querySelectorAll('button').forEach(b => b.onclick = () => userAction(b));
    $('u-table').querySelectorAll('.u-sel').forEach(cb => cb.onchange = updateSelCount);
    if ($('u-selall')) $('u-selall').checked = false;
    updateSelCount();
  } catch (e) { $('u-table').innerHTML = '<tr><td>加载失败：' + esc(e.message) + '</td></tr>'; }
}
async function userAction(b) {
  const id = b.dataset.id, a = b.dataset.a;
  try {
    if (a === 'admin') await api(`/api/admin/users/${id}/admin`, 'POST', { value: b.dataset.v === '1' });
    else if (a === 'vip') {
      const lv = prompt(`设置 VIP 等级（0取消 / 1白银 / 2黄金 / 3钻石），当前 ${b.dataset.v}`, b.dataset.v);
      if (lv === null) return;
      const days = prompt('有效天数（0=永久）', '30');
      if (days === null) return;
      await api(`/api/admin/users/${id}/vip`, 'POST', { level: parseInt(lv) || 0, days: parseInt(days) || 0 });
    } else if (a === 'ban') await api(`/api/admin/users/${id}/ban`, 'POST', { value: b.dataset.v === '1' });
    else if (a === 'gold') {
      const g = prompt('增减金币（正数增加，负数扣除）', '100'); if (g === null) return;
      await api(`/api/admin/users/${id}/gold`, 'POST', { delta: parseInt(g) || 0 });
    } else if (a === 'del') {
      if (!confirm('确定删除该账号？将清除其座位/好友/私聊，不可恢复。')) return;
      await api(`/api/admin/users/${id}/delete`, 'POST', {});
    } else if (a === 'pw') {
      const p = prompt('输入新密码（至少 6 位）'); if (!p) return;
      await api(`/api/admin/users/${id}/password`, 'POST', { password: p });
      alert('密码已重置'); return;
    } else if (a === 'edit') {
      const nickname = prompt('昵称（留空不改）', b.dataset.nick); if (nickname === null) return;
      const level = prompt('等级', b.dataset.lv); if (level === null) return;
      const exp = prompt('经验', b.dataset.exp); if (exp === null) return;
      const gold = prompt('金币', b.dataset.gold); if (gold === null) return;
      await api(`/api/admin/users/${id}/edit`, 'POST', {
        nickname: nickname.trim() || null,
        level: level === '' ? null : parseInt(level),
        exp: exp === '' ? null : parseInt(exp),
        gold: gold === '' ? null : parseInt(gold),
      });
    } else if (a === 'login') {
      if (!confirm(`以「${b.dataset.nick}」的账号进入？会在新标签页以该用户身份登录（用于排查问题）。`)) return;
      const r = await api(`/api/admin/users/${id}/impersonate`, 'POST', {});
      window.open('/?t=' + encodeURIComponent(r.token), '_blank');
      return;
    }
    loadUsers($('u-kw').value.trim());
  } catch (e) { alert(e.message); }
}

/* ---------- 批量用户操作 ---------- */
async function batchOp(btn) {
  const ids = selectedIds();
  const msg = $('u-batch-msg'); msg.textContent = '';
  if (!ids.length) { msg.textContent = '请先勾选用户'; return; }
  const op = btn.dataset.b, v = btn.dataset.v;
  let body = { ids, op };
  if (op === 'ban' || op === 'admin') body.value = +v;
  else if (op === 'vip') {
    const lv = prompt('批量设置 VIP 等级（0取消 / 1白银 / 2黄金 / 3钻石）', '1'); if (lv === null) return;
    const days = prompt('有效天数（0=永久）', '30'); if (days === null) return;
    body.value = parseInt(lv) || 0; body.days = parseInt(days) || 0;
  } else if (op === 'gold') {
    const g = prompt('对每位选中用户增减金币（正加负扣）', '100'); if (g === null) return;
    body.value = parseInt(g) || 0;
  } else if (op === 'del') {
    if (!confirm(`确定删除选中的 ${ids.length} 个账号？将级联清理且不可恢复。`)) return;
  }
  btn.disabled = true; msg.textContent = '处理中…';
  try {
    const r = await api('/api/admin/users/batch', 'POST', body);
    msg.textContent = `✅ 成功 ${r.done} 个` + (r.skipped ? `，跳过 ${r.skipped} 个（自身/不存在）` : '');
    loadUsers($('u-kw').value.trim());
  } catch (e) { msg.textContent = '❌ ' + e.message; }
  finally { btn.disabled = false; }
}

/* ---------- 维护通知（底部弹幕） ---------- */
async function sendNotice(clear) {
  const msg = $('mn-status'); msg.textContent = '';
  const text = clear ? '' : $('mn-text').value.trim();
  if (!clear && !text) { msg.textContent = '请输入通知内容'; return; }
  if (!clear && !confirm('确认向所有在线用户发送该维护通知？')) return;
  try {
    const r = await api('/api/room/notice', 'POST', { message: text });
    msg.textContent = clear ? '✅ 已清除通知' : `✅ 已发送给 ${r.delivered} 个在线连接`;
    if (!clear) $('mn-text').value = '';
  } catch (e) { msg.textContent = '❌ ' + e.message; }
}

/* ---------- 启动 ---------- */
/* ---------- 兑换码管理 ---------- */
async function loadRedeem() {
  try {
    const list = await api('/api/admin/redeem');
    let h = '<tr><th>码</th><th>金币</th><th>经验</th><th>道具</th><th>用/限</th><th>状态</th><th>操作</th></tr>';
    for (const c of list) {
      h += `<tr><td><b>${esc(c.code)}</b></td><td>${c.gold}</td><td>${c.exp}</td><td>${c.itemDefId}</td>
        <td>${c.usedCount}/${c.maxUses}</td>
        <td>${c.active ? '<span class="pill ok">启用</span>' : '<span class="pill bad">停用</span>'}${c.expireAt ? '<div class="hint">' + esc(c.expireAt.slice(0, 16)) + '</div>' : ''}</td>
        <td><button class="btn sm" data-a="act" data-id="${c.id}" data-v="${c.active ? 0 : 1}">${c.active ? '停用' : '启用'}</button>
            <button class="btn sm danger" data-a="del" data-id="${c.id}">删除</button></td></tr>`;
    }
    $('rc-table').innerHTML = h;
    $('rc-table').querySelectorAll('button').forEach(b => b.onclick = () => {
      if (b.dataset.a === 'del') { if (!confirm('删除该兑换码？')) return; api('/api/admin/redeem/' + b.dataset.id + '/delete', 'POST', {}).then(loadRedeem, e => alert('操作失败：' + e.message)); }
      else api('/api/admin/redeem/' + b.dataset.id + '/active', 'POST', { value: b.dataset.v === '1' }).then(loadRedeem, e => alert('操作失败：' + e.message));
    });
  } catch (e) { $('rc-table').innerHTML = '<tr><td>' + esc(e.message) + '</td></tr>'; }
}
async function createRedeem() {
  $('rc-msg').textContent = '生成中…';
  const body = {
    code: $('rc-code').value.trim() || null, gold: +$('rc-gold').value || 0, exp: +$('rc-exp').value || 0,
    itemDefId: +$('rc-item').value || 0, maxUses: +$('rc-max').value || 1,
    expireAt: $('rc-expire').value.trim() || null, note: $('rc-note').value.trim() || null,
  };
  try { const r = await api('/api/admin/redeem', 'POST', body); $('rc-msg').textContent = '✅ 已生成 ' + r.code; $('rc-code').value = ''; loadRedeem(); }
  catch (e) { $('rc-msg').textContent = '❌ ' + e.message; }
}

/* ---------- 工单 ---------- */
const STATUS_ZH = { OPEN: '待处理', PROCESSING: '处理中', REPLIED: '已回复', CLOSED: '已关闭' };
async function loadTickets() {
  try {
    const st = $('tk-filter').value, cat = ($('tk-catf') ? $('tk-catf').value : '');
    const list = await api('/api/admin/tickets?status=' + st + (cat ? '&category=' + encodeURIComponent(cat) : ''));
    const box = $('tk-list');
    if (!list.length) { box.innerHTML = '<div class="hint">暂无工单</div>'; return; }
    box.innerHTML = '';
    for (const t of list) {
      const el = document.createElement('div'); el.className = 'adm-card'; el.style.marginBottom = '10px';
      el.innerHTML = `<div class="field"><span class="pill ${t.category === '举报' ? 'bad' : 'ok'}">${esc(t.category)}</span>
        <b style="margin-left:8px">${esc(t.title)}</b>
        <span class="hint" style="margin-left:8px">${esc(t.reporter || '')}${t.target ? ' → 举报 ' + esc(t.target) : ''} · ${esc((t.at || '').replace('T', ' ').slice(0, 16))} · <b>${esc(STATUS_ZH[t.status] || t.status)}</b></span></div>
        <div class="hint" style="color:var(--text-main);margin:6px 0">${esc(t.content)}</div>
        <div class="row-actions" style="flex-wrap:wrap">
          <input type="text" id="tk-r-${t.id}" placeholder="回复内容…" style="flex:1;min-width:200px;background:var(--bg-deep);border:1px solid var(--border-dim);border-radius:8px;padding:6px 8px;color:var(--text-main)">
          <select id="tk-s-${t.id}" style="background:var(--bg-deep);border:1px solid var(--border-dim);border-radius:8px;padding:6px;color:var(--text-main)">
            <option value="PROCESSING" ${t.status==='PROCESSING'?'selected':''}>处理中</option>
            <option value="REPLIED" ${t.status==='REPLIED'?'selected':''}>已回复</option>
            <option value="CLOSED" ${t.status==='CLOSED'?'selected':''}>已关闭</option>
          </select>
          <button class="btn sm primary" data-a="reply" data-id="${t.id}">回复</button>
          <button class="btn sm" data-a="status" data-id="${t.id}">仅改状态</button>
        </div>`;
      el.querySelector('[data-a=reply]').onclick = () => {
        const c = el.querySelector('#tk-r-' + t.id).value.trim();
        if (!c) { alert('请输入回复'); return; }
        api('/api/admin/tickets/' + t.id + '/reply', 'POST', { content: c, status: el.querySelector('#tk-s-' + t.id).value }).then(loadTickets, e => alert('回复失败：' + e.message));
      };
      el.querySelector('[data-a=status]').onclick = () => {
        api('/api/admin/tickets/' + t.id + '/status', 'POST', { status: el.querySelector('#tk-s-' + t.id).value }).then(loadTickets, e => alert('修改失败：' + e.message));
      };
      box.appendChild(el);
    }
  } catch (e) { $('tk-list').innerHTML = '<div class="hint">' + esc(e.message) + '</div>'; }
}

/* ---------- 服务器控制 ---------- */
async function srvRestart() { if (!confirm('确定重启服务器？需 start.bat 守护循环在运行才会重新拉起。')) return; try { await api('/api/admin/server/restart', 'POST', {}); $('srv-status').textContent = '已发送重启指令…'; } catch (e) { $('srv-status').textContent = e.message; } }
async function srvShutdown() { if (!confirm('确定关闭服务器？')) return; try { await api('/api/admin/server/shutdown', 'POST', {}); $('srv-status').textContent = '已发送关闭指令…'; } catch (e) { $('srv-status').textContent = e.message; } }

/* ---------- 运维工具 ---------- */
async function loadOps() { await refreshOnline(); }
async function refreshOnline() {
  const box = $('ops-online-box'); if (!box) return;
  box.innerHTML = '加载中…';
  try {
    const d = await api('/api/admin/ops/online');
    const users = d.users || [];
    if (!users.length) { box.innerHTML = '当前无人在线'; return; }
    let h = `当前在线 <b style="color:var(--moon)">${d.count}</b> 人：<table class="adm" style="margin-top:6px"><tr><th>用户</th><th>等级</th><th>现在在</th></tr>`;
    for (const u of users) {
      h += `<tr><td>${esc(u.nickname)}${u.admin ? ' <span class="pill ok">管理</span>' : ''}</td><td>Lv.${u.level}</td>`
         + `<td>${esc(u.activity || '大厅')}${u.roomNo ? ` <span class="hint">(${esc(u.roomNo)})</span>` : ''}</td></tr>`;
    }
    h += '</table>';
    box.innerHTML = h;
  } catch (e) { box.textContent = '加载失败：' + e.message; }
}
async function opsExportUsers() {
  try {
    const tok = localStorage.getItem('ww_admin_token');
    const res = await fetch('/api/admin/ops/export/users', { headers: { 'Authorization': 'Bearer ' + tok } });
    if (!res.ok) throw new Error('导出失败 ' + res.status);
    const blob = await res.blob();
    const a = document.createElement('a'); a.href = URL.createObjectURL(blob); a.download = 'users.csv'; a.click();
    URL.revokeObjectURL(a.href); $('ops-msg').textContent = '✅ 已导出 users.csv';
  } catch (e) { $('ops-msg').textContent = '❌ ' + e.message; }
}
async function opsEndAll() {
  if (!confirm('确定强制结束【所有】进行中的对局？这会中断全部 AI 调用并复位所有房间。')) return;
  try { const r = await api('/api/admin/ops/end-all', 'POST', {}); $('ops-msg').textContent = `✅ 已结束 ${r.ended} 局`; }
  catch (e) { $('ops-msg').textContent = '❌ ' + e.message; }
}

/* ---------- 论坛管理 ---------- */
async function loadForum() {
  const t = $('forum-table'); if (!t) return;
  t.innerHTML = '<tr><td>加载中…</td></tr>';
  try {
    const list = await api('/api/admin/ops/forum');
    let h = '<tr><th>ID</th><th>标题</th><th>作者</th><th>分类</th><th>回复</th><th>状态</th><th>操作</th></tr>';
    for (const p of list) {
      h += `<tr><td>${p.id}</td><td>${esc(p.title)}</td><td>${esc(p.author)}</td><td>${esc(p.category || '')}</td><td>${p.replyCount || 0}</td>
        <td>${p.pinned ? '<span class="pill ok">置顶</span>' : ''}${p.hidden ? ' <span class="pill bad">隐藏</span>' : ''}</td>
        <td><div class="row-actions">
          <button class="btn sm" data-a="pin" data-id="${p.id}" data-v="${p.pinned ? 0 : 1}">${p.pinned ? '取消置顶' : '置顶'}</button>
          <button class="btn sm" data-a="hide" data-id="${p.id}" data-v="${p.hidden ? 0 : 1}">${p.hidden ? '恢复' : '隐藏'}</button>
          <button class="btn sm danger" data-a="del" data-id="${p.id}">删除</button>
        </div></td></tr>`;
    }
    t.innerHTML = h;
    t.querySelectorAll('button').forEach(b => b.onclick = async () => {
      const id = b.dataset.id, a = b.dataset.a;
      try {
        if (a === 'del') { if (!confirm('删除该帖及其回复？')) return; await api(`/api/forum/${id}/delete`, 'POST', {}); }
        else await api(`/api/forum/${id}/${a}`, 'POST', { value: b.dataset.v === '1' });
        loadForum();
      } catch (e) { alert(e.message); }
    });
  } catch (e) { t.innerHTML = `<tr><td>加载失败：${esc(e.message)}</td></tr>`; }
}

async function loadRooms() {
  const t = $('rooms-table'); if (!t) return;
  t.innerHTML = '<tr><td>加载中…</td></tr>';
  try {
    const list = await api('/api/admin/rooms');
    if (!list.length) { t.innerHTML = '<tr><td>暂无房间</td></tr>'; return; }
    let h = '<tr><th>ID</th><th>房号</th><th>房主</th><th>状态</th><th>人数</th><th>板子</th><th>模式</th><th>创建</th><th>操作</th></tr>';
    for (const r of list) {
      h += `<tr><td>${r.id}</td><td><b>${esc(r.roomNo)}</b></td><td>${esc(r.hostName)}</td>
        <td>${r.status === 'PLAYING' ? '<span class="pill bad">游戏中</span>' : '<span class="pill ok">等待中</span>'}</td>
        <td>${r.playerCount}/${r.maxSeats} <span class="hint">在线${r.onlineCount || 0}</span></td><td>${esc(r.boardName || '')}</td>
        <td>${r.voiceMode ? '🎙 ' : ''}${r.anonymous ? '🕶 ' : ''}${r.itemMatch ? '🎒' : ''}</td>
        <td class="hint">${(r.createdAt || '').replace('T', ' ').slice(0, 16)}</td>
        <td><div class="row-actions">
          <button class="btn sm" data-a="end" data-id="${r.id}">结束对局</button>
          <button class="btn sm danger" data-a="dis" data-id="${r.id}">解散</button>
        </div></td></tr>`;
    }
    t.innerHTML = h;
    t.querySelectorAll('button').forEach(b => b.onclick = async () => {
      const id = b.dataset.id;
      try {
        if (b.dataset.a === 'end') { if (!confirm('强制结束该房间对局？')) return; await api(`/api/admin/rooms/${id}/end`, 'POST', {}); $('rooms-msg').textContent = '已结束对局'; }
        else { if (!confirm('解散该房间？将清理成员并删除房间。')) return; await api(`/api/admin/rooms/${id}/dissolve`, 'POST', {}); $('rooms-msg').textContent = '已解散'; }
        loadRooms();
      } catch (e) { alert(e.message); }
    });
  } catch (e) { t.innerHTML = `<tr><td>加载失败：${esc(e.message)}</td></tr>`; }
}

async function boot() {
  bindTabs();
  $('lg-btn').onclick = doLogin;
  $('adm-logout').onclick = logout;
  $('ai-save').onclick = saveAi; $('ai-test').onclick = testAi;
  $('u-search').onclick = () => loadUsers($('u-kw').value.trim());
  if ($('u-selall')) $('u-selall').onchange = (e) => {
    document.querySelectorAll('#u-table .u-sel').forEach(cb => cb.checked = e.target.checked);
    updateSelCount();
  };
  document.querySelectorAll('[data-b]').forEach(b => b.onclick = () => batchOp(b));
  // 显示设置：读取 + 保存
  // 功能开关
  const FEAT_LIST = [
    ['quickai', 'AI 陪玩房'], ['spectate', '观战加入'], ['shop', '商店'], ['friends', '好友'],
    ['forum', '论坛'], ['ranking', '排行榜'], ['checkin', '每日签到'], ['news', '更新动态'],
    ['tickets', '工单'], ['download', '下载应用'], ['guide', '安装指导'],
    ['voiceMode', '房间·语音同传'], ['duo', '房间·双人组队'], ['items', '房间·功能道具赛'],
    ['invite', '房间·邀请好友'], ['addai', '房间·补位 AI']
  ];
  if ($('feat-grid')) {
    const flags = {};
    const grid = $('feat-grid');
    grid.innerHTML = FEAT_LIST.map(([id, label]) =>
      `<label style="display:flex;align-items:center;gap:6px"><input type="checkbox" data-flag="${id}" checked> ${label}</label>`).join('');
    api('/api/admin/system/features').then(d => {
      const f = d.flags || {};
      grid.querySelectorAll('[data-flag]').forEach(cb => { if (f[cb.dataset.flag] === false) cb.checked = false; });
    }).catch(() => {});
    $('feat-save').onclick = async () => {
      grid.querySelectorAll('[data-flag]').forEach(cb => { flags[cb.dataset.flag] = cb.checked; });
      try {
        await api('/api/admin/system/features', 'POST', { flags });
        $('feat-status').textContent = '✅ 已保存，客户端刷新后生效';
      } catch (e) { $('feat-status').textContent = '❌ ' + e.message; }
      setTimeout(() => { const el = $('feat-status'); if (el) el.textContent = ''; }, 4000);
    };
  }
  if ($('dp-maxh')) {
    api('/api/admin/system/display').then(d => { $('dp-maxh').value = d.webMaxHeight || 0; }).catch(() => {});
    $('dp-save').onclick = async () => {
      const h = parseInt($('dp-maxh').value || '0', 10) || 0;
      try {
        await api('/api/admin/system/display', 'POST', { webMaxHeight: h });
        $('dp-status').textContent = '✅ 已保存，客户端刷新后生效';
      } catch (e) { $('dp-status').textContent = '❌ ' + e.message; }
      setTimeout(() => { const el = $('dp-status'); if (el) el.textContent = ''; }, 4000);
    };
  }
  if ($('mn-send')) $('mn-send').onclick = () => sendNotice(false);
  if ($('mn-clear')) $('mn-clear').onclick = () => sendNotice(true);
  $('rc-create').onclick = createRedeem;
  $('tk-refresh').onclick = loadTickets;
  $('tk-filter').onchange = loadTickets;
  if ($('tk-catf')) $('tk-catf').onchange = loadTickets;
  $('srv-restart').onclick = srvRestart;
  $('srv-shutdown').onclick = srvShutdown;
  const oo = $('ops-online'); if (oo) oo.onclick = refreshOnline;
  const oe = $('ops-export-users'); if (oe) oe.onclick = opsExportUsers;
  const oa = $('ops-end-all'); if (oa) oa.onclick = opsEndAll;
  const fr = $('forum-refresh'); if (fr) fr.onclick = loadForum;
  const rr = $('rooms-refresh'); if (rr) rr.onclick = loadRooms;
  $('lg-pass').addEventListener('keydown', e => { if (e.key === 'Enter') doLogin(); });
  const up = new URLSearchParams(location.search).get('t');
  if (up) {
    localStorage.setItem(TOKEN_KEY, up);
    // 安全：token 已从 URL 读入 localStorage，立即用 replaceState 从地址栏抹掉 t 参数，避免泄漏到历史/日志/Referer
    const u = new URL(location.href);
    u.searchParams.delete('t');
    history.replaceState(null, '', u.pathname + (u.search ? u.search : '') + u.hash);
  }
  const tok = localStorage.getItem(TOKEN_KEY);
  if (!tok) { showLogin(''); return; }
  try {
    const me = await api('/api/auth/me');
    if (!me.admin) { showLogin('该账号不是管理员'); return; }
    showApp(me); loadSystem(); loadAi(); loadUsers('');
    const tab = new URLSearchParams(location.search).get('tab');
    if (tab) activateTab(tab);
  } catch (_) { /* api() 已处理 401/403 */ }
}
boot();
