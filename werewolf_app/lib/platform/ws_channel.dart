/// WebSocket 连接的平台适配门面。
///
/// 桌面 / 移动用 IO 侧的实现（可设协议级 pingInterval，并且沿用 HttpOverrides 的
/// 自签证书放行）；Web 用浏览器原生 WebSocket（TLS 由浏览器处理，也没有 pingInterval
/// 这回事，心跳由应用层 25s 的 JSON ping 承担）。
library;

export 'ws_channel_io.dart' if (dart.library.js_interop) 'ws_channel_web.dart';
