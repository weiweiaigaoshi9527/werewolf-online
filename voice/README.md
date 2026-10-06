# 狼人杀 · 语音同传 / 匿名 AI 伪装

给 AI 对局增加「同语种声纹中继」语音链路，用来平衡游戏、把 AI 伪装成真人。
两套本地 Python 微服务（SenseVoice-Small 语音识别 + Kokoro 语音合成）+ Spring Boot 语音通道 + 前端麦克风采集。

## 两种模式
- **标准模式**：玩家自选「语音或文字」，AI 只发文字，不走声纹中继。
- **匿名 / 语音同传模式**（房主在房间开启）：
  - 全员头像、昵称固定为 `1号…16号` 伪装人格，服务器不下发 `bot` 标志 → 真人无法分辨谁是 AI。
  - 每个座位绑定一个固定的 Kokoro 中文音色；**真人和 AI 都用各自座位的同一套 TTS 音色发声**。
  - 真人发言：浏览器麦克风 → 上传到服务器 → 服务端能量 VAD 断句 → SenseVoice 识别（逗号/句号切句）→ 用该座位音色重新朗读并广播给全房间（同语种声纹中继，类似同传）。识别文本同时作为该玩家的发言提交，AI 下一回合即可「听见」。
  - AI 发言：LLM 一次性返回整段文案 → 服务器按逗号/句号切句 → **逐句**合成语音 + 字幕广播；句间停顿 = `字数 × 每字时长 + 随机(约0.9~2.6s)`，开口前另有随机起手延迟，模拟真人一句句说的节奏（不一次性连续念完）。发言播完才推进回合。

## 部署（本服务器）
语音运行时与模型放在**纯 ASCII 路径** `G:\werewolf-voice`（非 ASCII 目录会让部分原生库加载失败）：
```
G:\werewolf-voice\
  py311\            # Python 3.11.9
  venv-asr\         # FunASR + SenseVoice + 标点
  venv-tts\         # Kokoro + misaki[zh]
  models\SenseVoiceSmall\  models\punc_ct\  models\hf\ (Kokoro-82M)
  asr_service.py    tts_service.py     # 由 voice/ 复制而来
```
一键安装（国内镜像：pip 用腾讯云、模型用 ModelScope + hf-mirror）：双击 `voice\install_voice.bat`。

## 启动 / 停止
- `start.bat`：自动拉起 ASR(5001) + TTS(5002) 两服务并置 `VOICE_ENABLED=true`，再启动游戏(8080)。
- `stop.bat`：停止 Java 与两枚 Python 服务。
- 未安装语音时 `start.bat` 仍能以纯文字模式运行（`VOICE_ENABLED=false`）。

## 关键配置（application.yml 的 `voice.*`）
- `enabled` / `asr-url` / `tts-url` / `sample-rate`
- `voices`：8 个 Kokoro 真实中文音色（`zf_xiaobei/zm_yunjian/zf_xiaoxiao/zm_yunxi/zf_xiaoni/zm_yunyang/zf_xiaoyi/zm_yunxia`），按座位循环分配。
- 发声节奏：`ms-per-char` `gap-min-ms` `gap-max-ms` `lead-in-min-ms` `lead-in-max-ms` `max-sentences-per-turn` `max-speech-chars`
- VAD：`vad-rms-threshold` `vad-silence-ms` `vad-max-speech-ms` `vad-min-speech-ms`
- 匿名伪装：`anon-names` `anon-avatars` `anon-nick-color` `anon-frame-color`

## 技术要点
- 语音走独立的二进制 WebSocket `/ws/voice`（与游戏状态 `/ws` 分离）：上行裸 PCM16(16k)，下行 `[4字节metaLen][JSON元信息][WAV]` 封包；字幕走 JSON 文本帧。
- 全部逻辑服务器权威：信息隔离、动作合法性、发言提交均由 Java 侧强制；语音服务不可用时自动降级为纯文字/字幕，不卡局。
- AI 决策仍走 OpenAI 兼容接口（后台可配），语音只是「发声 + 听懂」的外壳。

## 验证（已实跑）
- ASR：识别官方中文样例音频 → `开饭时间早上9点至下午5点。` ✓
- TTS→ASR 回环：Kokoro 合成中文 → SenseVoice 还原，几乎逐字吻合 ✓
- 端到端（无头 harness，真人麦克风用 TTS 合成音频模拟）：匿名全员 `N号` 无 bot 泄漏、真人语音中继回读、AI 逐句发声带音频、游戏自然结束 ✓
- 浏览器截图：对局页显示「🕶 匿名 · 🎙 语音同传」徽标与固定伪装座位 ✓
