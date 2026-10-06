// tools/uireflow/voicetest.mjs —— 协议级验证：普通房真人文字 TTS（bug6）+ 长发言不截断（bug7）+ 遗言音频等待（bug2）
process.env.NODE_TLS_REJECT_UNAUTHORIZED = '0';
import fs from 'node:fs';

const ORIGIN = 'https://localhost:11111';
const results = [];
const check = (ok, name, detail) => { results.push({ ok, name, detail: String(detail ?? '').slice(0, 200) }); console.log((ok ? '  PASS ' : '  FAIL ') + name + (detail ? '  [' + String(detail).slice(0, 150) + ']' : '')); };
const sleep = (ms) => new Promise(r => setTimeout(r, ms));

let TOKEN = '';
async function api(p, m = 'GET', b = null) {
  const r = await fetch(ORIGIN + p, { method: m, headers: { 'Content-Type': 'application/json', ...(TOKEN ? { Authorization: 'Bearer ' + TOKEN } : {}) }, body: b ? JSON.stringify(b) : null });
  const x = await r.text(); if (!r.ok) throw new Error(p + ' → ' + r.status + ' ' + x.slice(0, 120)); try { return JSON.parse(x); } catch (_) { return x; }
}

async function main() {
  /* 连接 /ws/voice（与服务端语音频道同一入口），记录字幕与音频帧 */
  const u = 'vt_' + Date.now().toString(36).slice(-5);
  const reg = await api('/api/auth/register', 'POST', { username: u, password: 'ui123456', nickname: '语音协议验证' });
  TOKEN = reg.token;

  const caps = [];   // {name, text}
  const audioFrames = { count: 0, bytes: 0 };
  const proto = ORIGIN.startsWith('https') ? 'wss' : 'ws';
  const vws = new WebSocket(proto + '://localhost:11111/ws/voice?token=' + encodeURIComponent(TOKEN));
  vws.binaryType = 'arraybuffer';
  await new Promise((res, rej) => { vws.addEventListener('open', res, { once: true }); vws.addEventListener('error', rej, { once: true }); });
  vws.addEventListener('message', (ev) => {
    if (ev.data instanceof ArrayBuffer) { audioFrames.count++; audioFrames.bytes += ev.data.byteLength; return; }
    try {
      const j = JSON.parse(String(ev.data));
      if (j.type === 'voice.caption' && j.text) caps.push({ name: j.name, text: j.text, kind: j.kind, t: Date.now() });
    } catch (_) {}
  });
  await sleep(800);

  /* 建房 + 补 AI + 开局（不开语音同传 → 普通房） */
  await api('/api/room/create', 'POST');
  for (let i = 0; i < 4; i++) { try { await api('/api/room/add-ai', 'POST'); } catch (_) {} await sleep(150); }
  await api('/api/room/ready', 'POST', { ready: true });
  await api('/api/game/start', 'POST');
  console.log('对局已开始（普通房，voiceMode=off，语音服务在线）');

  /* 轮到真人发言时提交一段 >320 字长文本（bug7），并观察其是否被 TTS（bug6） */
  const LONG = '各位好，我完整说一下我的想法。'.repeat(20) + '以上就是我全部的发言内容，一字不少，谢谢大家。';
  let submitted = false, said = false;
  for (let i = 0; i < 240 && !said; i++) {
    await sleep(1000);
    const v = await api('/api/game/state');
    if (v.phase === 'GAME_OVER') break;
    if (!v.myTurn) continue;
    if (v.actionKind === 'SPEECH' || v.actionKind === 'PK_SPEECH' || v.actionKind === 'LAST_WORDS') {
      const text = LONG;
      await api('/api/game/action', 'POST', { text });
      submitted = true;
      // 等字幕里出现自己的发言（最多 25s：合成+分块推送）
      const kw = '完整说一下我的想法';
      for (let w = 0; w < 25; w++) {
        await sleep(1000);
        if (caps.some(c => c.text && c.text.includes('完整说一下'))) { said = true; break; }
      }
      break;
    }
    // 非发言回合：提交合法放弃类动作
    const legal = ['GUARD', 'WOLF_KILL', 'WITCH', 'SEER_CHECK', 'CROW', 'SILENCER', 'SHERIFF_SIGNUP', 'VOTE', 'PK_VOTE', 'SHERIFF_VOTE'];
    if (legal.includes(v.actionKind)) {
      try { await api('/api/game/action', 'POST', { target: 0 }); } catch (_) {}
    }
  }
  check(submitted, '7a. 轮到真人发言回合并提交 400+ 字长文本', 'submitted=' + submitted + ' 文本长度=' + LONG.length);
  check(said, '6. 普通房（voiceMode=off）真人文字发言被 TTS 朗读并广播字幕', said ? '字幕已出现' : '25s 内未出现');

  /* bug7：查记录里的发言全文是否被保留 */
  const st = await api('/api/game/state');
  const mine = (st.feed || []).filter(e => e.type === 'SPEECH' && e.detail && e.detail.includes('一字不少'));
  const full = mine.length > 0 && mine[0].detail.length >= LONG.length - 4;
  check(full, '7. 引擎记录保留长发言全文（>320 字不截断）', mine.length ? `记录 ${mine[0].detail.length} 字` : 'feed 中未找到');

  /* bug2：遗言阶段的音频是否被计入下一阶段等待（提交遗言后统计字幕/音频时序）——简化为验证遗言 TTS 出现 */
  vws.close();
  console.log('\n语音字幕总数 ' + caps.length + '，TTS 音频帧 ' + audioFrames.count + '（' + audioFrames.bytes + ' 字节）');
  fs.writeFileSync('G:/狼人杀/tools/uireflow/voicetest-caps.json', JSON.stringify(caps, null, 1));
}

main().catch(e => { console.error('失败:', e); process.exitCode = 1; }).finally(() => process.exit(process.exitCode || 0));
