import 'package:web_socket_channel/web_socket_channel.dart';

/// Web：浏览器原生 WebSocket。浏览器 API 不支持协议级 pingInterval，故只连接；
/// 断线重连与 25s 心跳由 [WsClient] / [VoiceClient] 自己的定时器负责。
WebSocketChannel connectWebSocket(Uri uri) => WebSocketChannel.connect(uri);
