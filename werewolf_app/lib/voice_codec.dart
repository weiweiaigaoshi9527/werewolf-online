import 'dart:convert';
import 'dart:typed_data';

/// 语音协议编解码（与服务端 `AudioEnvelope.java` / 网页 `voice.js` 逐字节一致）。
///
/// 二进制帧格式：`[4 字节大端 metaLen][metaLen 字节 UTF-8 JSON][WAV 负载]`。
/// 这里把编解码与重采样从 [VoiceClient] 中剥离为纯函数，方便单测覆盖边界，
/// 也保证三端（Windows / Linux / Android）走的是同一份实现。

/// 一帧解码结果。
class VoiceFrame {
  /// 帧头元信息，通常含 `k:"tts"`、`room`、`seat`、`name`、`seq`、`kind`、`durMs`。
  final Map<String, dynamic> meta;

  /// WAV 负载（可能为空，表示只有元信息没有音频）。
  final Uint8List wav;

  const VoiceFrame(this.meta, this.wav);

  String get kind => '${meta['kind'] ?? ''}';
  String get name => '${meta['name'] ?? ''}';
  int get seat => (meta['seat'] as num?)?.toInt() ?? -1;
  int get seq => (meta['seq'] as num?)?.toInt() ?? 0;
}

/// 把元信息与 WAV 负载打成一条下行二进制帧（仅在测试与自检中用到）。
Uint8List encodeVoiceFrame(Map<String, dynamic> meta, Uint8List wav) {
  final metaBytes = utf8.encode(jsonEncode(meta));
  final out = Uint8List(4 + metaBytes.length + wav.length);
  final bd = ByteData.sublistView(out);
  bd.setInt32(0, metaBytes.length); // 大端，与 Java ByteBuffer 默认一致
  out.setRange(4, 4 + metaBytes.length, metaBytes);
  out.setRange(4 + metaBytes.length, out.length, wav);
  return out;
}

/// 容错解码：任何不合法的帧都返回 null，调用方直接丢弃（与网页行为一致）。
VoiceFrame? decodeVoiceFrame(Uint8List buf) {
  if (buf.length < 4) return null;
  final bd = ByteData.sublistView(buf);
  final metaLen = bd.getInt32(0); // 大端
  if (metaLen <= 0 || 4 + metaLen > buf.length) return null;
  Map<String, dynamic> meta;
  try {
    final raw = utf8.decode(Uint8List.sublistView(buf, 4, 4 + metaLen));
    final decoded = jsonDecode(raw);
    if (decoded is! Map) return null;
    meta = decoded.cast<String, dynamic>();
  } catch (_) {
    return null;
  }
  return VoiceFrame(meta, Uint8List.sublistView(buf, 4 + metaLen));
}

/// 流式 PCM16 线性重采样器：把麦克风采集率（如 48k）转换到服务端期望的 16k。
/// 跨音频块保持读位置与窗口，保证连续无跳变；自动处理奇数字节对齐。
class PcmResampler {
  final double step; // 每个输出样本消耗多少输入样本 = inRate / outRate
  final List<int> _win = []; // 当前输入样本窗口（Int16 值）
  double _pos = 0; // 窗口内的浮点读位置
  int _carry = 0;
  bool _hasCarry = false;

  PcmResampler({required int inRate, required int outRate}) : step = inRate / outRate;

  Uint8List process(Uint8List bytes) {
    // 1) 与上一次的半个样本字节拼接，保证 16bit 对齐
    Uint8List all;
    if (_hasCarry) {
      all = Uint8List(1 + bytes.length);
      all[0] = _carry;
      all.setRange(1, all.length, bytes);
    } else {
      all = bytes;
    }
    final nSamples = all.length >> 1;
    _hasCarry = (all.length & 1) == 1;
    if (_hasCarry) _carry = all[all.length - 1];
    if (nSamples == 0) return Uint8List(0);

    // 2) 解码为 Int16 小端样本，追加到窗口
    final bd = ByteData.sublistView(all, 0, nSamples * 2);
    for (var i = 0; i < nSamples; i++) {
      _win.add(bd.getInt16(i * 2, Endian.little));
    }

    // 3) 线性插值输出，直到窗口尾部样本不足
    final out = <int>[];
    while (_pos.floor() + 1 < _win.length) {
      final i = _pos.floor();
      final f = _pos - i;
      final a = _win[i];
      final b = _win[i + 1];
      var v = (a + (b - a) * f).round();
      if (v > 32767) {
        v = 32767;
      } else if (v < -32768) {
        v = -32768;
      }
      out.add(v);
      _pos += step;
    }

    // 4) 丢弃已消费的前缀，保持窗口小且读位置连续。
    //    注意：读位置按 step（48k→16k 时为 3.0）步进，循环退出时 _pos.floor() 完全可能
    //    越过窗口末尾（落在 [L, L+step) 之间）。此时必须夹到 L，否则 removeRange 抛
    //    RangeError 打断麦克风上行——采集块长度不是 step 整倍数时必现。
    final drop = _pos.floor() > _win.length ? _win.length : _pos.floor();
    if (drop > 0) {
      _win.removeRange(0, drop);
      _pos -= drop;
    }

    // 5) 编码回 Int16 小端字节
    final ob = ByteData(out.length * 2);
    for (var i = 0; i < out.length; i++) {
      ob.setInt16(i * 2, out[i], Endian.little);
    }
    return ob.buffer.asUint8List();
  }
}
