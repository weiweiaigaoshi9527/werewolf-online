import 'package:web_socket_channel/io.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

/// 桌面 / 移动：dart:io 的 WebSocket，行为与改造前一致（25s 协议级心跳）。
WebSocketChannel connectWebSocket(Uri uri) =>
    IOWebSocketChannel.connect(uri, pingInterval: const Duration(seconds: 25));
