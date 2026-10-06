import 'dart:async';
import 'dart:convert';
import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';
import 'config.dart';

/// 原始 WebSocket 客户端：连 /ws?token=，按 type 分发；断线指数退避重连；25s 心跳。
/// 协议与网页版 HallWebSocketHandler 完全一致。
class WsClient {
  final ServerConfig config;
  final String token;
  WebSocketChannel? _ch;
  StreamSubscription<dynamic>? _sub;
  Timer? _ping;
  int _retry = 0;
  bool _closed = false;

  final _controller = StreamController<Map<String, dynamic>>.broadcast();
  Stream<Map<String, dynamic>> get messages => _controller.stream;

  final void Function(bool connected) onStatus;

  WsClient(this.config, this.token, this.onStatus);

  void connect() {
    _closed = false;
    _open();
  }

  void _open() {
    if (_closed) return;
    try {
      final uri = config.wsUri(token);
      _ch = IOWebSocketChannel.connect(uri, pingInterval: const Duration(seconds: 25));
      _retry = 0;
      onStatus(true);
      _sub = _ch!.stream.listen(
        (data) {
          try {
            final m = jsonDecode(data as String);
            if (m is Map<String, dynamic>) _controller.add(m);
          } catch (_) {}
        },
        onDone: _onLost,
        onError: (_) => _onLost(),
        cancelOnError: true,
      );
      _ping = Timer.periodic(const Duration(seconds: 25), (_) => _send({'type': 'ping'}));
    } catch (_) {
      _onLost();
    }
  }

  void _onLost() {
    _ping?.cancel();
    _ping = null;
    try {
      _sub?.cancel();
    } catch (_) {}
    onStatus(false);
    if (_closed) return;
    final delay = Duration(milliseconds: 800 * (1 << (_retry.clamp(0, 5))));
    _retry++;
    Timer(delay, _open);
  }

  void _send(Map<String, dynamic> m) {
    try {
      _ch?.sink.add(jsonEncode(m));
    } catch (_) {}
  }

  void close() {
    _closed = true;
    _ping?.cancel();
    try {
      _sub?.cancel();
    } catch (_) {}
    try {
      _ch?.sink.close();
    } catch (_) {}
    try {
      _controller.close();
    } catch (_) {}
  }
}
