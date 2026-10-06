/* ===== 狼人杀 · 语音通道客户端（SenseVoice 上行 + Kokoro 下行播放） ===== */
/* 全局 VOICE：连接 /ws/voice，采集麦克风 16k PCM16 持续上行；顺序播放服务端逐句下发的合成语音。
   仅语音模式对局启用。所有浏览器 API 均 try/catch 包裹，异常时静默降级为文字模式，绝不崩。 */
const VOICE = (() => {
  let ws = null;
  let enabled = false;      // 服务端语音是否可用
  let connected = false;
  let recording = false;
  let capCtx = null, srcNode = null, procNode = null, micStream = null;
  let playCtx = null;
  let playQueue = [];       // 待播 AudioBuffer 队列
  let playing = false;
  let currentSpeechName = null; // 正在发声的展示名（用于光环/字幕）
  let wantConnect = false;      // 是否期望保持连接（区分主动 disconnect 与意外断线，用于自动重连）
  let reconnectTimer = null;    // 自动重连定时器句柄
  const TARGET_SR = 16000;

  function pctx() {
    if (!playCtx) playCtx = new (window.AudioContext || window.webkitAudioContext)();
    if (playCtx.state === 'suspended') playCtx.resume();
    return playCtx;
  }

  function connect() {
    wantConnect = true;
    if (connected && ws && ws.readyState <= 1) return;
    const token = localStorage.getItem('ww_token');
    if (!token) return;
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    try { ws = new WebSocket(`${proto}://${location.host}/ws/voice?token=${token}`); }
    catch (e) { return; }
    ws.binaryType = 'arraybuffer';
    ws.onopen = () => { connected = true; };
    ws.onmessage = (ev) => {
      if (typeof ev.data === 'string') { handleControl(JSON.parse(ev.data)); }
      else { handleTtsBinary(ev.data); }
    };
    ws.onclose = () => {
      connected = false;
      // 连接断开：释放麦克风与音频节点，避免录音句柄悬挂（不发送 voice.stop，因通道已断开）
      stopMic(true);
      recording = false;
      ws = null;
      // 退避自动重连：3 秒后若仍处于应连接状态（未主动 disconnect）则重连
      if (wantConnect) {
        if (reconnectTimer) clearTimeout(reconnectTimer);
        reconnectTimer = setTimeout(() => { reconnectTimer = null; if (wantConnect) connect(); }, 3000);
      }
    };
    ws.onerror = () => { connected = false; };
  }

  function disconnect() {
    wantConnect = false;
    if (reconnectTimer) { clearTimeout(reconnectTimer); reconnectTimer = null; }
    stopMic(true);
    if (ws && ws.readyState <= 1) { try { ws.close(); } catch (_) {} }
    ws = null; connected = false;
    if (capCtx) { try { capCtx.close(); } catch (_) {} capCtx = null; }
    srcNode = procNode = null;
  }

  function handleControl(m) {
    switch (m.type) {
      case 'voice.hello':
        enabled = !!m.enabled;
        if (window.__onVoiceReady) window.__onVoiceReady(m);
        break;
      case 'voice.caption':
        currentSpeechName = m.name;
        if (window.__onVoiceCaption) window.__onVoiceCaption(m);
        scheduleClearName();
        break;
      case 'voice.speaking':
        if (window.__onVoiceSpeaking) window.__onVoiceSpeaking(m);
        break;
      case 'voice.denied':
      case 'voice.error':
        recording = false;
        if (window.toast) window.toast(m.reason || m.message || '语音错误', true);
        if (window.__onVoiceDenied) window.__onVoiceDenied(m);
        break;
    }
  }

  let nameTimer = null;
  function scheduleClearName() {
    if (nameTimer) clearTimeout(nameTimer);
    nameTimer = setTimeout(() => { currentSpeechName = null; if (window.__onSpeakingChanged) window.__onSpeakingChanged(); }, 1200);
  }

  /* ---- 下行音频：解析 [metaLen][json][payload] → 解码 → 顺序播放 ----
     payload 有两种：
       k="tts"  → 合成语音（WAV 字节，decodeAudioData 直接解码）；
       k="raw"  → 真人原声（16k PCM16 裸数据，需手动包 PCM → AudioBuffer）。 */
  function handleTtsBinary(buf) {
    try {
      const dv = new DataView(buf);
      const metaLen = dv.getInt32(0, false);
      const metaJson = new TextDecoder('utf-8').decode(new Uint8Array(buf, 4, metaLen));
      const payload = buf.slice(4 + metaLen);
      const meta = JSON.parse(metaJson);
      if (meta.name) { currentSpeechName = meta.name; scheduleClearName(); }
      const ctx = pctx();
      if (meta.k === 'raw') {
        // 真人原声：PCM16 单声道裸数据，按 meta.sr 采样率包成 AudioBuffer
        const sr = meta.sr || 16000;
        const n = payload.byteLength >> 1;
        if (n <= 0) return;
        const audio = ctx.createBuffer(1, n, sr);
        const ch = audio.getChannelData(0);
        const dv2 = new DataView(payload);
        for (let i = 0; i < n; i++) ch[i] = dv2.getInt16(i * 2, true) / 32768;
        playQueue.push(audio);
        pumpPlay();
      } else {
        ctx.decodeAudioData(payload.slice(0), (audioBuf) => {
          playQueue.push(audioBuf);
          pumpPlay();
        }, () => {});
      }
    } catch (e) {}
  }

  function pumpPlay() {
    if (playing) return;
    const b = playQueue.shift();
    if (!b) return;
    playing = true;
    const c = pctx();
    const src = c.createBufferSource();
    src.buffer = b; src.connect(c.destination);
    src.onended = () => { playing = false; pumpPlay(); };
    try { src.start(); } catch (e) { playing = false; }
  }

  /* ---- 上行麦克风：16k PCM16 持续推流 ---- */
  async function startMic() {
    if (!window.isSecureContext) { if (window.toast) window.toast('请用 https:// 访问才能使用麦克风', true); return false; }
    if (!enabled) { if (window.toast) window.toast('语音服务未启用', true); return false; }
    // 未连上就补连一次并稍等
    if (!connected) { connect(); for (let i = 0; i < 15 && !connected; i++) await new Promise(r => setTimeout(r, 100)); }
    if (!connected) { if (window.toast) window.toast('语音服务未就绪（ASR/TTS 未运行？）', true); return false; }
    if (recording) return true;
    try {
      micStream = await navigator.mediaDevices.getUserMedia({
        audio: { channelCount: 1, echoCancellation: true, noiseSuppression: true, autoGainControl: true }
      });
    } catch (e) {
      if (window.toast) window.toast('无法使用麦克风：请检查权限/是否被占用', true);
      return false;
    }
    try {
      capCtx = new (window.AudioContext || window.webkitAudioContext)({ sampleRate: TARGET_SR });
    } catch (e) {
      capCtx = new (window.AudioContext || window.webkitAudioContext)();
    }
    const sr = capCtx.sampleRate;
    const ratio = sr / TARGET_SR;
    srcNode = capCtx.createMediaStreamSource(micStream);
    procNode = capCtx.createScriptProcessor(2048, 1, 1);
    procNode.onaudioprocess = (e) => {
      if (!recording || !ws || ws.readyState !== 1) return;
      const inp = e.inputBuffer.getChannelData(0);
      const out = downsampleToInt16(inp, ratio);
      if (out) ws.send(out.buffer);
    };
    srcNode.connect(procNode); procNode.connect(capCtx.destination);
    sendCtl({ type: 'voice.start' });
    recording = true;
    return true;
  }

  function stopMic(silent) {
    if (recording) sendCtl({ type: 'voice.stop' });
    recording = false;
    try { if (procNode) { procNode.disconnect(); } } catch (_) {}
    try { if (srcNode) { srcNode.disconnect(); } } catch (_) {}
    try { if (micStream) micStream.getTracks().forEach(t => t.stop()); } catch (_) {}
    procNode = srcNode = micStream = null;
    if (capCtx) { try { capCtx.close(); } catch (_) {} capCtx = null; }
    if (!silent && window.__onMicState) window.__onMicState(false);
  }

  function downsampleToInt16(floats, ratio) {
    const n = Math.floor(floats.length / ratio);
    if (n <= 0) return null;
    const pcm = new Int16Array(n);
    for (let i = 0; i < n; i++) {
      const idx = Math.floor(i * ratio);
      let s = floats[Math.min(idx, floats.length - 1)];
      s = Math.max(-1, Math.min(1, s));
      pcm[i] = s < 0 ? s * 0x8000 : s * 0x7fff;
    }
    return pcm;
  }

  function sendCtl(obj) { try { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); } catch (_) {} }

  return {
    connect, disconnect,
    startMic() { return startMic(); },
    stopMic() { stopMic(false); },
    isRecording() { return recording; },
    isEnabled() { return enabled; },
    isConnected() { return connected; },
    hasSpeakingName(name) { return currentSpeechName === name; },
    currentName() { return currentSpeechName; },
    setEnabled(v) { enabled = v; },
  };
})();
window.VOICE = VOICE;
