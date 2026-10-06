import 'dart:math';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/sfx.dart';

void main() {
  group('WAV 封装', () {
    test('每个具名音效都能合成出结构合法的 WAV', () {
      for (final entry in sfxSpecs.entries) {
        final wav = buildWav(entry.value, Random(1));
        expect(wavLooksValid(wav), isTrue, reason: '${entry.key} 的 WAV 头不对');
        final expectSamples = (entry.value.seconds * kSampleRate).round();
        expect(wavSampleCount(wav), closeTo(expectSamples, 2), reason: '${entry.key} 时长不符');
      }
    });

    test('空 spec 不崩，且 data 段长度为 0', () {
      final wav = buildWav(const SfxSpec(), Random(1));
      expect(wavLooksValid(wav), isTrue);
      expect(wavSampleCount(wav), greaterThan(0), reason: 'mix 至少留 1 个样本，避免 0 长度 data 段');
    });

    test('data 段长度与实际字节严格一致（防止偏移量改动静默变噪音）', () {
      final wav = buildWav(sfxSpecs['win']!, Random(1));
      final bd = ByteData.sublistView(wav);
      expect(bd.getUint32(4, Endian.little), wav.length - 8);
      expect(bd.getUint32(40, Endian.little), wav.length - 44);
    });
  });

  group('包络与波形', () {
    test('正弦音的主频与标称频率一致（过零计数法）', () {
      const t = Tone(440, 0.2, Wave.sine, 1.0);
      final s = toneSamples(t, sampleRate: 44100);
      var crossings = 0;
      for (var i = 1; i < s.length; i++) {
        if (s[i - 1] >= 0 && s[i] < 0) crossings++;
      }
      // 每个周期一次下穿零点 → 周期数 ≈ crossings
      expect(crossings / 0.2, closeTo(440, 12));
    });

    test('起振在 12ms 内到峰值，之后指数衰减，尾段归零', () {
      const t = Tone(300, 0.4, Wave.sine, 0.5);
      final s = toneSamples(t);
      final attackN = (0.012 * kSampleRate).round();
      var peak = 0.0, peakAt = 0;
      for (var i = 0; i < s.length; i++) {
        final a = s[i].abs();
        if (a > peak) {
          peak = a;
          peakAt = i;
        }
      }
      expect(peakAt, closeTo(attackN, attackN), reason: '峰值应出现在起振末端附近');
      expect(peak, closeTo(0.5, 0.06));
      // 包络单调衰减：中段峰值应大于尾段峰值
      double maxIn(int a, int b) {
        var m = 0.0;
        for (var i = a; i < b && i < s.length; i++) {
          m = max(m, s[i].abs());
        }
        return m;
      }

      expect(maxIn((0.05 * kSampleRate).round(), (0.1 * kSampleRate).round()),
          greaterThan(maxIn((0.3 * kSampleRate).round(), (0.36 * kSampleRate).round())));
      expect(s[s.length - 1], 0.0, reason: 'dur 之后是 stop 尾音，应为静音');
    });

    test('四种波形在周期内的形状可区分（按包络归一化后取相位 1/4 与 3/4）', () {
      // freq=100、dur=0.01 → 一个周期 441 样本，两点落在相位 .25 / .75。
      // 这两点都还在 12ms 起振段内，值被包络缩小了，所以必须先除以包络再看形状。
      const dur = 0.01;
      double envAt(int i) {
        const attackN = 0.012 * kSampleRate;
        final sec = i / kSampleRate;
        if (sec < 0.012) return i / attackN;
        return pow(0.0001, (sec - 0.012) / (dur - 0.012)).toDouble();
      }

      double at(Wave w, int idx) => toneSamples(Tone(100, dur, w, 1.0))[idx] / envAt(idx);
      final q1 = (kSampleRate * 0.25 / 100).round();
      final q3 = (kSampleRate * 0.75 / 100).round();

      expect(at(Wave.sine, q1), greaterThan(0.9), reason: 'sin(2π·0.25)=1');
      expect(at(Wave.sine, q3), lessThan(-0.9));
      expect(at(Wave.square, q1), greaterThan(0.9));
      expect(at(Wave.square, q3), lessThan(-0.9));
      // 锯齿在 .25 为负、.75 为正，与正弦/方波正好相反
      expect(at(Wave.sawtooth, q1), closeTo(-0.5, 0.06));
      expect(at(Wave.sawtooth, q3), closeTo(0.5, 0.06));
      // 三角在两个四分点上过零
      expect(at(Wave.triangle, q1).abs(), lessThan(0.06));
      expect(at(Wave.triangle, q3).abs(), lessThan(0.06));
    });

    test('延迟音的前导静音长度对得上（延迟由 buildWav 混音时按偏移施加）', () {
      const spec = SfxSpec(tones: [Tone(600, 0.1, Wave.sine, 0.3, 0.13)]);
      final wav = buildWav(spec, Random(1));
      final lead = (0.13 * kSampleRate).round();
      for (var i = 0; i < lead; i++) {
        expect(wavSample(wav, i), 0.0, reason: '第 $i 个样本应还在延迟区间内');
      }
      expect(wavSample(wav, lead + 100).abs(), greaterThan(0));
    });
  });

  group('混音与削波', () {
    test('多音叠加绝不溢出 int16（削波而非翻转）', () {
      // 故意用 4 个 vol=1 的重叠音制造过冲
      const hot = SfxSpec(tones: [
        Tone(400, 0.1, Wave.sine, 1.0),
        Tone(401, 0.1, Wave.sine, 1.0),
        Tone(402, 0.1, Wave.sine, 1.0),
        Tone(403, 0.1, Wave.sine, 1.0),
      ]);
      final wav = buildWav(hot, Random(1));
      var maxAbs = 0.0;
      for (var i = 0; i < wavSampleCount(wav); i++) {
        maxAbs = max(maxAbs, wavSample(wav, i).abs());
      }
      expect(maxAbs, lessThanOrEqualTo(1.0));
      expect(maxAbs, greaterThan(0.9), reason: '应该被顶到满幅而不是被整体缩小');
    });

    test('噪声：同种子可复现、能量随时间衰减、幅度不超 vol', () {
      const nz = Noise(0.2, 0.32);
      final a = noiseSamples(nz, Random(7));
      final b = noiseSamples(nz, Random(7));
      expect(a, equals(b), reason: '噪声必须可复现，否则测试与音效都会飘');
      expect(a.every((e) => e.abs() <= 0.32 + 1e-9), isTrue);
      double rms(List<double> s, int from, int to) {
        var sum = 0.0;
        for (var i = from; i < to; i++) {
          sum += s[i] * s[i];
        }
        return sqrt(sum / (to - from));
      }

      final head = rms(a, 0, 1000);
      final tail = rms(a, a.length - 1000, a.length);
      expect(head, greaterThan(tail * 3), reason: '(1-i/n)^2 衰减应让尾部明显更轻');
    });

    test('shot 是噪声不是纯音：波形无稳定周期', () {
      final wav = buildWav(sfxSpecs['shot']!, Random(3));
      var signFlips = 0;
      for (var i = 1; i < wavSampleCount(wav); i++) {
        if (wavSample(wav, i - 1) * wavSample(wav, i) < 0) signFlips++;
      }
      final rate = signFlips / (wavSampleCount(wav) / kSampleRate);
      expect(rate, greaterThan(3000), reason: '白噪过零率应远高于任何可听音高');
    });
  });

  group('什么时候该响（状态转移规则）', () {
    Map<String, dynamic> st({
      String phase = 'NIGHT_WOLF',
      int? currentActor,
      bool myTurn = false,
      int mySeat = 1,
      String winner = '',
      String faction = 'WOLF',
      List feed = const [],
    }) =>
        {
          'phase': phase,
          'currentActor': currentActor,
          'myTurn': myTurn,
          'mySeat': mySeat,
          'winner': winner,
          'myInfo': {'faction': faction},
          'feed': feed,
        };

    test('首次拿到视图不发声（重连/切后台回来不该被一串音效轰炸）', () {
      expect(sfxEventsFor(null, st()), isEmpty);
      expect(sfxEventsFor(st(), null), isEmpty);
    });

    test('阶段切换映射到夜/昼音效，同阶段重复推送不响', () {
      expect(sfxEventsFor(st(phase: 'DAY_SPEAK'), st(phase: 'NIGHT_WOLF')), ['night']);
      expect(sfxEventsFor(st(phase: 'NIGHT_WOLF'), st(phase: 'DAWN')), ['day']);
      expect(sfxEventsFor(st(phase: 'NIGHT_WOLF'), st(phase: 'DAY_VOTE')), ['day']);
      expect(sfxEventsFor(st(phase: 'NIGHT_WOLF'), st(phase: 'NIGHT_WITCH')), isEmpty,
          reason: '夜晚内部换子阶段不该再响一次夜声');
    });

    test('结算音按"我的阵营是否获胜"选 win / lose', () {
      expect(sfxEventsFor(st(phase: 'DAY_VOTE'), st(phase: 'GAME_OVER', winner: '狼人阵营', faction: 'WOLF')), ['win']);
      expect(sfxEventsFor(st(phase: 'DAY_VOTE'), st(phase: 'GAME_OVER', winner: '狼人阵营', faction: 'GOD')), ['lose']);
      expect(sfxEventsFor(st(phase: 'DAY_VOTE'), st(phase: 'GAME_OVER', winner: '好人阵营', faction: 'GOD')), ['win']);
      expect(sfxEventsFor(st(phase: 'DAY_VOTE'), st(phase: 'GAME_OVER', winner: '好人阵营', faction: 'WOLF')), ['lose']);
    });

    test('轮到我才响提示音；轮到别人不响', () {
      expect(
        sfxEventsFor(st(phase: 'DAY_SPEAK'), st(phase: 'DAY_SPEAK', currentActor: 1, myTurn: true)),
        contains('turn'),
      );
      expect(
        sfxEventsFor(st(phase: 'DAY_SPEAK'), st(phase: 'DAY_SPEAK', currentActor: 4, myTurn: false)),
        isNot(contains('turn')),
      );
    });

    test('事件流新增条目按类型配音，发言与旁白不配（避免和 TTS 打架）', () {
      final prev = st(phase: 'DAY_VOTE');
      final now = st(phase: 'DAY_VOTE', feed: [
        {'type': 'SPEECH', 'actor': 2},
        {'type': 'VOTE_RESULT', 'actor': 3},
        {'type': 'PLAYER_DIED', 'actor': 3},
        {'type': 'SHOOT', 'actor': 4},
      ]);
      expect(sfxEventsFor(prev, now), ['vote', 'death', 'shot']);
    });

    test('feed 变短（服务端换局重置）不越界、不抛', () {
      final prev = st(feed: [
        {'type': 'SPEECH'},
        {'type': 'SPEECH'},
        {'type': 'SPEECH'},
      ]);
      expect(sfxEventsFor(prev, st(feed: [{'type': 'GAME_START'}])), isEmpty);
    });
  });

  group('开关与容错', () {
    test('开关状态持久化，且默认开启', () async {
      SharedPreferences.setMockInitialValues({});
      final s = Sfx();
      await s.load();
      expect(s.enabled, isTrue);
      expect(await s.toggle(), isFalse);
      final again = Sfx();
      await again.load();
      expect(again.enabled, isFalse, reason: '关掉后重启仍应是关的');
      expect(await again.toggle(), isTrue);
    });

    test('未知音效名静默忽略，不抛异常', () async {
      SharedPreferences.setMockInitialValues({});
      final s = Sfx();
      await s.load();
      expect(() => s.play('不存在的音效'), returnsNormally);
      s.dispose();
    });

    test('关闭时 play 直接返回，不合成不占平台通道', () async {
      SharedPreferences.setMockInitialValues({SfxTestKeys.enabled: false});
      final s = Sfx()..enabled = false;
      await s.play('win'); // 不该发声，也不该抛
      s.dispose();
    });
  });
}

class SfxTestKeys {
  static const enabled = 'ww_sfx';
}
