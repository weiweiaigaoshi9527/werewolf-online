import 'dart:async';
import 'dart:convert';
import 'dart:math';

import 'package:audioplayers/audioplayers.dart';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 对局音效：与网页版 app.js 的 SFX 一一对应（同样的频率/时长/波形/包络），
/// 但不依赖任何音频素材——运行时直接合成 16bit PCM WAV 再交给 audioplayers 播放。
///
/// 合成逻辑做成纯函数（[buildWav] / [toneSamples]），这样包络、削波、时长都能离线单测；
/// 只有真正发声的部分需要平台通道。
class Sfx {
  static const _key = 'ww_sfx'; // 与网页版 localStorage 同名语义

  bool enabled = true;
  final Random _rng;
  final List<AudioPlayer> _pool = [];
  int _next = 0;

  Sfx({Random? rng}) : _rng = rng ?? Random(0x5EED);

  Future<void> load() async {
    final p = await SharedPreferences.getInstance();
    enabled = p.getBool(_key) ?? true;
  }

  /// 返回切换后的状态（与网页版 SFX.toggle() 一致）。
  Future<bool> toggle() async {
    enabled = !enabled;
    final p = await SharedPreferences.getInstance();
    await p.setBool(_key, enabled);
    return enabled;
  }

  /// 播一个具名音效；未知名字、关闭状态、平台异常都静默忽略——
  /// 音效永远不该把对局带崩（网页版也是整段 try/catch）。
  ///
  /// 注意必须用 runZonedGuarded：`AudioPlayer()` 构造时会去订阅一个 EventChannel，
  /// 那个错误是在 stream 监听里抛的，普通 try/catch 抓不到（实测会逃逸成未处理异常）。
  Future<void> play(String name) async {
    if (!enabled) return;
    final spec = sfxSpecs[name];
    if (spec == null) return;
    var failure = Object();
    StackTrace? trace;
    await runZonedGuarded(() async {
      try {
        final wav = buildWav(spec, _rng);
        final player = _borrow();
        await player.stop();
        await player.setSource(BytesSource(wav));
        await player.resume();
      } catch (e, s) {
        failure = e;
        trace = s;
      }
    }, (e, s) {
      failure = e;
      trace = s;
    });
    if (trace != null) {
      // 只留痕不抛出：没有音频设备/绑定未就绪时游戏照常进行
      debugPrint('Sfx.play($name) 忽略异常: $failure');
    }
  }

  /// 复用一小池 player：重叠发声（例如 win 的四个音）不能互相打断，
  /// 但也不该每响一次就新建一个泄漏掉。
  AudioPlayer _borrow() {
    while (_pool.length < 4) {
      _pool.add(AudioPlayer());
    }
    final p = _pool[_next % _pool.length];
    _next++;
    return p;
  }

  void dispose() {
    for (final p in _pool) {
      try {
        p.dispose();
      } catch (_) {}
    }
    _pool.clear();
  }
}

/// 一个振荡器音：频率 Hz、时长秒、波形、峰值音量、起始延迟秒。
class Tone {
  const Tone(this.freq, this.dur, this.type, this.vol, [this.delay = 0]);
  final double freq;
  final double dur;
  final Wave type;
  final double vol;
  final double delay;
}

enum Wave { sine, square, triangle, sawtooth }

/// 一段噪声：时长秒 + 音量，按 (1-i/n)^2 衰减（网页版 noise() 同形状）。
class Noise {
  const Noise(this.dur, this.vol);
  final double dur;
  final double vol;
}

class SfxSpec {
  const SfxSpec({this.tones = const [], this.noises = const []});
  final List<Tone> tones;
  final List<Noise> noises;

  double get seconds {
    var end = 0.0;
    for (final t in tones) {
      final e = t.delay + t.dur + 0.03; // o.stop(t + dur + 0.03)
      if (e > end) end = e;
    }
    for (final n in noises) {
      if (n.dur > end) end = n.dur;
    }
    return end;
  }
}

/// 与网页版 sounds{} 逐项对应的参数表。
const Map<String, SfxSpec> sfxSpecs = {
  'click': SfxSpec(tones: [Tone(620, 0.05, Wave.square, 0.04)]),
  'night': SfxSpec(tones: [
    Tone(233, 0.5, Wave.sine, 0.12),
    Tone(155, 0.8, Wave.sine, 0.10, 0.12),
  ]),
  'day': SfxSpec(tones: [
    Tone(523, 0.18, Wave.sine, 0.14),
    Tone(659, 0.18, Wave.sine, 0.12, 0.12),
    Tone(784, 0.30, Wave.sine, 0.12, 0.24),
  ]),
  'turn': SfxSpec(tones: [
    Tone(880, 0.12, Wave.triangle, 0.13),
    Tone(1175, 0.14, Wave.triangle, 0.11, 0.10),
  ]),
  'vote': SfxSpec(tones: [Tone(320, 0.1, Wave.square, 0.08)]),
  'shot': SfxSpec(noises: [Noise(0.22, 0.32)]),
  'death': SfxSpec(tones: [
    Tone(210, 0.3, Wave.sawtooth, 0.10),
    Tone(120, 0.5, Wave.sawtooth, 0.10, 0.12),
  ]),
  'msg': SfxSpec(tones: [Tone(720, 0.05, Wave.sine, 0.05)]),
  'win': SfxSpec(tones: [
    Tone(523, 0.28, Wave.sine, 0.13, 0.00),
    Tone(659, 0.28, Wave.sine, 0.13, 0.13),
    Tone(784, 0.28, Wave.sine, 0.13, 0.26),
    Tone(1047, 0.28, Wave.sine, 0.13, 0.39),
  ]),
  'lose': SfxSpec(tones: [
    Tone(420, 0.32, Wave.sine, 0.11, 0.00),
    Tone(360, 0.32, Wave.sine, 0.11, 0.16),
    Tone(300, 0.32, Wave.sine, 0.11, 0.32),
    Tone(220, 0.32, Wave.sine, 0.11, 0.48),
  ]),
};

const int kSampleRate = 44100;
const double _attack = 0.012; // gain.linearRampToValueAtTime(vol, t + 0.012)

/// 单个音的采样（长度 = (dur+0.03) 秒），包络复刻 Web Audio 的
/// "0.012s 线性起振 + 指数衰减到 0.0001"。
List<double> toneSamples(Tone t, {int sampleRate = kSampleRate}) {
  final total = t.dur + 0.03;
  final n = (total * sampleRate).round();
  final out = List<double>.filled(n, 0);
  if (n == 0 || t.dur <= 0) return out;
  final attackN = (_attack * sampleRate).round();
  for (var i = 0; i < n; i++) {
    final sec = i / sampleRate;
    double env;
    if (sec < _attack) {
      env = t.vol * (attackN == 0 ? 1 : (i / attackN));
    } else if (sec <= t.dur) {
      // exponentialRamp: v(τ) = vol * (0.0001/vol)^((τ-attack)/(dur-attack))
      final frac = (t.dur - _attack) <= 0 ? 1.0 : (sec - _attack) / (t.dur - _attack);
      env = t.vol * pow(0.0001 / t.vol, frac);
    } else {
      env = 0; // 已经 o.stop()
    }
    out[i] = _waveAt(t.type, t.freq, sec) * env;
  }
  return out;
}

/// 噪声段：白噪按 (1-i/n)^2 衰减。
List<double> noiseSamples(Noise nz, Random rng, {int sampleRate = kSampleRate}) {
  final n = (nz.dur * sampleRate).round();
  if (n <= 0) return const [];
  final out = List<double>.filled(n, 0);
  for (var i = 0; i < n; i++) {
    final k = 1 - i / n;
    out[i] = (rng.nextDouble() * 2 - 1) * k * k * nz.vol;
  }
  return out;
}

double _waveAt(Wave w, double freq, double sec) {
  final phase = (freq * sec) % 1.0;
  switch (w) {
    case Wave.sine:
      return sin(2 * pi * phase);
    case Wave.square:
      return phase < 0.5 ? 1 : -1;
    case Wave.triangle:
      return 4 * (phase < 0.5 ? phase : 1 - phase) - 1;
    case Wave.sawtooth:
      return 2 * phase - 1;
  }
}

/// 把一段 spec 混音并编码成 16bit 单声道 WAV（RIFF/PCM）。
Uint8List buildWav(SfxSpec spec, Random rng, {int sampleRate = kSampleRate}) {
  final seconds = spec.seconds;
  final n = (seconds * sampleRate).round();
  final mix = List<double>.filled(max(n, 1), 0);
  for (final t in spec.tones) {
    final s = toneSamples(t, sampleRate: sampleRate);
    final off = (t.delay * sampleRate).round();
    for (var i = 0; i < s.length && off + i < mix.length; i++) {
      mix[off + i] += s[i];
    }
  }
  for (final nz in spec.noises) {
    final s = noiseSamples(nz, rng, sampleRate: sampleRate);
    for (var i = 0; i < s.length && i < mix.length; i++) {
      mix[i] += s[i];
    }
  }
  final bytes = Uint8List(44 + mix.length * 2);
  final bd = ByteData.sublistView(bytes);
  void ascii(int at, String s) {
    for (var i = 0; i < s.length; i++) {
      bytes[at + i] = s.codeUnitAt(i);
    }
  }

  ascii(0, 'RIFF');
  bd.setUint32(4, 36 + mix.length * 2, Endian.little);
  ascii(8, 'WAVE');
  ascii(12, 'fmt ');
  bd.setUint32(16, 16, Endian.little);
  bd.setUint16(20, 1, Endian.little); // PCM
  bd.setUint16(22, 1, Endian.little); // mono
  bd.setUint32(24, sampleRate, Endian.little);
  bd.setUint32(28, sampleRate * 2, Endian.little); // byteRate
  bd.setUint16(32, 2, Endian.little); // blockAlign
  bd.setUint16(34, 16, Endian.little); // bits
  ascii(36, 'data');
  bd.setUint32(40, mix.length * 2, Endian.little);
  for (var i = 0; i < mix.length; i++) {
    var v = mix[i];
    if (v > 1) v = 1;
    if (v < -1) v = -1; // 混音叠加必须削波，否则 int16 会溢出翻转出爆音
    bd.setInt16(44 + i * 2, (v * 32767).round(), Endian.little);
  }
  return bytes;
}

/// 供测试与调试：从 WAV 里取第 i 个样本（-1..1）。
double wavSample(Uint8List wav, int i) {
  final bd = ByteData.sublistView(wav);
  return bd.getInt16(44 + i * 2, Endian.little) / 32767.0;
}

/// 把 14 个 GamePhase 归成"夜 / 昼 / 终局 / 其它"四个粗周期。
/// 夜晚有 GUARD→WOLF→WITCH→SEER→CROW→SILENCER 六个子阶段，
/// 若按 phase 逐次判断，一晚会响 4~6 次夜声——必须按周期变化来响。
String _periodOf(String phase) {
  if (phase.startsWith('NIGHT')) return 'night';
  if (phase == 'GAME_OVER') return 'over';
  if (phase == 'SETUP') return 'setup';
  // DAWN / SHERIFF_ELECTION / LAST_WORDS / DAY_SPEAK / DAY_VOTE / DAY_VOTE_TIEBREAK / SHOOT
  return 'day';
}

/// 两次 game.state 之间该响哪些音效。
///
/// 抽成纯函数是因为"在 build 里放声音"既难测又容易在每次重建时重复响；
/// 这里只描述规则，播放由界面层在状态监听里做。
List<String> sfxEventsFor(Map<String, dynamic>? prev, Map<String, dynamic>? now) {
  if (now == null) return const [];
  if (prev == null) return const []; // 首次拿到视图不发声：避免重连/切后台时一串音效轰炸
  final out = <String>[];
  final pPhase = '${prev['phase'] ?? ''}';
  final nPhase = '${now['phase'] ?? ''}';
  final pPeriod = _periodOf(pPhase);
  final nPeriod = _periodOf(nPhase);
  if (pPeriod != nPeriod) {
    switch (nPeriod) {
      case 'night':
        out.add('night');
        break;
      case 'day':
        out.add('day');
        break;
      case 'over':
        // 结算音由胜负决定：winner 是"狼人阵营/好人阵营"，我的阵营在 myInfo.faction
        final winner = '${now['winner'] ?? ''}';
        final mine = '${(now['myInfo'] as Map?)?['faction'] ?? ''}';
        final iAmWolf = mine == 'WOLF';
        final wolfWon = winner.contains('狼');
        out.add(iAmWolf == wolfWon ? 'win' : 'lose');
        break;
    }
  }
  // 轮到我了：currentActor 变成我的座位
  final mySeat = now['mySeat'];
  if (now['myTurn'] == true && prev['currentActor'] != now['currentActor'] && mySeat == now['currentActor']) {
    out.add('turn');
  }
  // 事件流新增条目：开枪、放逐、出局各给一个短音（旁白/发言不配音，避免和 TTS 打架）
  final pf = (prev['feed'] as List?) ?? const [];
  final nf = (now['feed'] as List?) ?? const [];
  if (nf.length > pf.length) {
    for (final e in nf.sublist(pf.length)) {
      if (e is! Map) continue;
      switch (e['type']) {
        case 'SHOOT':
        case 'WHITE_WOLF_BLOWUP':
          out.add('shot');
          break;
        case 'VOTE_RESULT':
          out.add('vote');
          break;
        case 'PLAYER_DIED':
          out.add('death');
          break;
      }
    }
  }
  return out;
}

int wavSampleCount(Uint8List wav) => (wav.length - 44) ~/ 2;

/// 校验 WAV 头（防止哪天偏移量改动把音频静默变成噪音）。
bool wavLooksValid(Uint8List wav) {
  if (wav.length < 44) return false;
  String at(int o, int len) => ascii.decode(wav.sublist(o, o + len));
  final bd = ByteData.sublistView(wav);
  return at(0, 4) == 'RIFF' &&
      at(8, 4) == 'WAVE' &&
      at(12, 4) == 'fmt ' &&
      bd.getUint16(20, Endian.little) == 1 &&
      bd.getUint16(22, Endian.little) == 1 &&
      bd.getUint32(40, Endian.little) == wav.length - 44;
}
