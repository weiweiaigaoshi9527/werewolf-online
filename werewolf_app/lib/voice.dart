import 'dart:async';
import 'dart:convert';
import 'dart:typed_data';
import 'package:audioplayers/audioplayers.dart';
import 'package:record/record.dart';
import 'package:web_socket_channel/io.dart';
import 'config.dart';
import 'voice_codec.dart';

/// 一条语音字幕（服务端逐句下发）。
class VoiceCaption {
  final int seat;
  final String name;
  final String text;
  final String kind; // ai / relay / narrator
  final bool mine;
  VoiceCaption(this.seat, this.name, this.text, this.kind, this.mine);
}

/// 语音通道客户端：与网页 voice.js 对齐。
/// 下行：连 /ws/voice，接收逐句 TTS（二进制 [metaLen][json][wav]）顺序播放 + 字幕（JSON 文本帧）。
/// 上行：麦克风 16k 单声道 PCM16 流式上传，服务端 VAD/ASR 后转述。
class VoiceClient {
  final ServerConfig config;
  final String token;
  int mySeat; // 我的显示座位（用于把 relay 字幕回填到发言框、高亮）

  void Function(VoiceCaption)? onCaption;
  void Function(String? name)? onSpeaking;
  void Function(bool denied)? onDenied;

  IOWebSocketChannel? _ch;
  StreamSubscription<dynamic>? _sub;
  Timer? _ping;
  bool _closed = false;

  bool enabled = false;
  bool connected = false;
  bool recording = false;

  // 录音器/播放器一律懒建：它们的构造函数会立刻触碰平台通道，
  // 而语音通道在“连上但从不发言/纯观战”时也必须能安全建立（纯 Dart 环境同理）。
  AudioRecorder? _recField;
  AudioRecorder get _rec => _recField ??= AudioRecorder();
  StreamSubscription<Uint8List>? _micSub;
  PcmResampler? _resampler;
  static const int _captureRate = 48000; // 采集率（各平台都稳定支持），再重采样到 16k 上行
  static const int _targetRate = 16000;  // 服务端 SenseVoice 期望的采样率

  AudioPlayer? _playerField;
  AudioPlayer get _player => _playerField ??= AudioPlayer();
  final List<Uint8List> _queue = [];
  bool _playing = false;
  String? _currentName;

  VoiceClient(this.config, this.token, {this.mySeat = -1});

  void connect() {
    if (connected && _ch != null) return;
    try {
      _ch = IOWebSocketChannel.connect(config.wsUriVoice(token),
          pingInterval: const Duration(seconds: 25));
      connected = true;
      _sub = _ch!.stream.listen(_onData, onDone: _onLost, onError: (_) => _onLost());
      _ping = Timer.periodic(const Duration(seconds: 25), (_) => _sendCtl({'type': 'ping'}));
    } catch (_) {
      connected = false;
    }
  }

  void _onLost() {
    connected = false;
    if (_closed) return;
    _ping?.cancel();
    _ping = null;
    // 断线后不自动重连（对局视图会在下次推送时重新 connect）
  }

  void _onData(dynamic d) {
    if (d is String) {
      try {
        final m = jsonDecode(d);
        if (m is Map) _onControl(m.cast<String, dynamic>());
      } catch (_) {}
    } else if (d is List<int>) {
      _onBinary(Uint8List.fromList(d));
    }
  }

  void _onControl(Map<String, dynamic> m) {
    switch (m['type']) {
      case 'voice.hello':
        enabled = m['enabled'] == true;
        break;
      case 'voice.caption':
        final seat = (m['seat'] as num?)?.toInt() ?? 0;
        final cap = VoiceCaption(seat, '${m['name'] ?? ''}', '${m['text'] ?? ''}', '${m['kind'] ?? ''}', seat == mySeat);
        _currentName = cap.name;
        onCaption?.call(cap);
        _scheduleClear();
        break;
      case 'voice.speaking':
        onSpeaking?.call(m['speaking'] == true ? '${m['name'] ?? ''}' : null);
        break;
      case 'voice.denied':
      case 'voice.error':
        recording = false;
        onDenied?.call(true);
        break;
    }
  }

  void _onBinary(Uint8List buf) {
    final frame = decodeVoiceFrame(buf);
    if (frame == null) return;
    if (frame.name.isNotEmpty) {
      _currentName = frame.name;
      _scheduleClear();
    }
    if (frame.wav.isNotEmpty) {
      _queue.add(frame.wav);
      _pump();
    }
  }

  Timer? _nameTimer;
  void _scheduleClear() {
    _nameTimer?.cancel();
    _nameTimer = Timer(const Duration(milliseconds: 1400), () {
      _currentName = null;
      onSpeaking?.call(null);
    });
  }

  /// 顺序播放队列里的 WAV 片段（一段段来，模拟逐句发声）。
  Future<void> _pump() async {
    if (_playing) return;
    _playing = true;
    while (_queue.isNotEmpty && !_closed) {
      final wav = _queue.removeAt(0);
      final done = Completer<void>();
      late StreamSubscription sub;
      try {
        sub = _player.onPlayerComplete.listen((_) {
          if (!done.isCompleted) done.complete();
        });
        await _player.stop();
        await _player.setSource(BytesSource(wav));
        await _player.resume();
        await done.future.timeout(const Duration(seconds: 30), onTimeout: () {});
      } catch (_) {}
      try {
        await sub.cancel();
      } catch (_) {}
    }
    _playing = false;
  }

  void _sendCtl(Map<String, dynamic> m) {
    try {
      _ch?.sink.add(jsonEncode(m));
    } catch (_) {}
  }

  /// 开始麦克风上行；返回是否成功。
  Future<bool> startMic() async {
    if (!enabled) return false;
    if (recording) return true;
    if (!connected) {
      connect();
      for (var i = 0; i < 15 && !connected; i++) {
        await Future.delayed(const Duration(milliseconds: 100));
      }
    }
    if (!connected) return false;
    try {
      if (!await _rec.hasPermission()) return false;
      _resampler = PcmResampler(inRate: _captureRate, outRate: _targetRate);
      final stream = await _rec.startStream(const RecordConfig(
        encoder: AudioEncoder.pcm16bits,
        sampleRate: _captureRate,
        numChannels: 1,
        autoGain: true,
        echoCancel: true,
        noiseSuppress: true,
      ));
      _micSub = stream.listen((bytes) {
        final out = _resampler?.process(bytes);
        if (out == null || out.isEmpty) return;
        try {
          _ch?.sink.add(out);
        } catch (_) {}
      });
      _sendCtl({'type': 'voice.start'});
      recording = true;
      return true;
    } catch (_) {
      recording = false;
      return false;
    }
  }

  Future<void> stopMic() async {
    if (!recording) return;
    recording = false;
    _sendCtl({'type': 'voice.stop'});
    try {
      await _micSub?.cancel();
    } catch (_) {}
    _micSub = null;
    try {
      await _recField?.stop();
    } catch (_) {}
  }

  String? get currentName => _currentName;

  void close() {
    _closed = true;
    stopMic();
    _ping?.cancel();
    _ping = null;
    try {
      _sub?.cancel();
    } catch (_) {}
    _sub = null;
    try {
      _ch?.sink.close();
    } catch (_) {}
    _ch = null;
    connected = false;
    try {
      _playerField?.dispose();
    } catch (_) {}
    _playerField = null;
    _queue.clear();
  }
}

