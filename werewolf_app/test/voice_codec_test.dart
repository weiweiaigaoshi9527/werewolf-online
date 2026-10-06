import 'dart:convert';
import 'dart:typed_data';

import 'package:flutter_test/flutter_test.dart';
import 'package:werewolf_app/voice_codec.dart';

Uint8List _u8(List<int> v) => Uint8List.fromList(v);

void main() {
  group('语音下行帧编解码（对齐服务端 AudioEnvelope.java）', () {
    test('编解码往返一致', () {
      final wav = _u8(List.generate(160, (i) => i % 251));
      final meta = {'k': 'tts', 'room': 7, 'seat': 3, 'name': '3号', 'seq': 12, 'kind': 'ai', 'durMs': 1800};
      final frame = decodeVoiceFrame(encodeVoiceFrame(meta, wav))!;
      expect(frame.meta['k'], 'tts');
      expect(frame.name, '3号');
      expect(frame.seat, 3);
      expect(frame.seq, 12);
      expect(frame.kind, 'ai');
      expect(frame.wav, wav);
    });

    test('帧头长度必须是大端 int32', () {
      final metaBytes = utf8.encode(jsonEncode({'k': 'tts'}));
      final buf = Uint8List(4 + metaBytes.length);
      ByteData.sublistView(buf).setInt32(0, metaBytes.length); // 大端（默认）
      buf.setRange(4, buf.length, metaBytes);
      expect(decodeVoiceFrame(buf)!.meta['k'], 'tts');
      // 反向验证：若误用小端，多字节长度会被解成完全不同的值
      final wrong = Uint8List(4 + metaBytes.length);
      ByteData.sublistView(wrong).setInt32(0, metaBytes.length, Endian.little);
      wrong.setRange(4, wrong.length, metaBytes);
      if (metaBytes.length > 127) {
        expect(decodeVoiceFrame(wrong), isNull, reason: '小端帧头不应被当作合法帧');
      }
    });

    test('空 WAV 负载（只有元信息）仍算合法帧', () {
      final frame = decodeVoiceFrame(encodeVoiceFrame({'name': '旁白'}, Uint8List(0)))!;
      expect(frame.wav, isEmpty);
      expect(frame.name, '旁白');
    });

    test('畸形帧一律容错丢弃，绝不抛异常', () {
      expect(decodeVoiceFrame(Uint8List(0)), isNull); // 空
      expect(decodeVoiceFrame(_u8([0, 0, 1])), isNull); // 不足 4 字节
      expect(decodeVoiceFrame(encodeVoiceFrame({'a': 1}, _u8([1, 2, 3])).sublist(0, 3)), isNull); // 截断
      // metaLen 越过缓冲区尾部
      final forged = Uint8List(8);
      ByteData.sublistView(forged).setInt32(0, 9999);
      expect(decodeVoiceFrame(forged), isNull);
      // metaLen 为 0 或负
      final zero = Uint8List(8);
      expect(decodeVoiceFrame(zero), isNull);
      // 帧头是 JSON 但不是对象
      final arr = encodeVoiceFrame({'x': 1}, _u8([9]));
      final arrBytes = utf8.encode('[1,2]');
      final bad = Uint8List(4 + arrBytes.length)..setRange(4, 4 + arrBytes.length, arrBytes);
      ByteData.sublistView(bad).setInt32(0, arrBytes.length);
      expect(decodeVoiceFrame(bad), isNull);
      expect(arr.isNotEmpty, isTrue);
    });
  });

  group('流式 PCM 重采样 48k → 16k', () {
    Uint8List pcm(List<int> samples) {
      final bd = ByteData(samples.length * 2);
      for (var i = 0; i < samples.length; i++) {
        bd.setInt16(i * 2, samples[i], Endian.little);
      }
      return bd.buffer.asUint8List();
    }

    List<int> decode(Uint8List bytes) {
      final bd = ByteData.sublistView(bytes);
      return List.generate(bytes.length ~/ 2, (i) => bd.getInt16(i * 2, Endian.little));
    }

    test('输出长度约为输入的 1/3', () {
      final r = PcmResampler(inRate: 48000, outRate: 16000);
      final out = r.process(pcm(List.filled(1440, 1000))); // 30ms @48k
      expect(out.lengthInBytes, closeTo(1440 * 2 / 3, 4));
    });

    test('恒定信号重采样后仍是恒定值（无跳变）', () {
      final r = PcmResampler(inRate: 48000, outRate: 16000);
      final out = decode(r.process(pcm(List.filled(960, 12345))));
      expect(out, isNotEmpty);
      expect(out.every((v) => v == 12345), isTrue);
    });

    test('跨块连续喂数据与一次性喂数据结果一致', () {
      final samples = List.generate(2000, (i) => (i * 37) % 6000 - 3000);
      final once = decode(PcmResampler(inRate: 48000, outRate: 16000).process(pcm(samples)));
      final chunked = <int>[];
      final r = PcmResampler(inRate: 48000, outRate: 16000);
      for (var i = 0; i < samples.length; i += 137) {
        chunked.addAll(decode(r.process(pcm(samples.sublist(i, (i + 137).clamp(0, samples.length))))));
      }
      expect(chunked.length, closeTo(once.length, 6));
      final n = [chunked.length, once.length].reduce((a, b) => a < b ? a : b);
      expect(chunked.sublist(0, n - 4), once.sublist(0, n - 4));
    });

    test('奇数字节输入不丢样、不崩溃（半个样本被带到下一块）', () {
      final bytes = pcm(List.filled(10, 500)); // 20 字节
      final odd = Uint8List(21)..setRange(0, 20, bytes); // 多一个字节
      final r = PcmResampler(inRate: 48000, outRate: 16000);
      expect(() => r.process(odd), returnsNormally);
      expect(() => r.process(Uint8List(1)), returnsNormally);
    });

    test('满幅与下溢样本被夹在 Int16 范围内', () {
      final r = PcmResampler(inRate: 48000, outRate: 16000);
      final out = decode(r.process(pcm([32767, -32768, 32767, -32768, 32767, -32768])));
      expect(out.every((v) => v >= -32768 && v <= 32767), isTrue);
    });
  });
}
