/// 「允许不安全连接」的平台适配门面。
///
/// 桌面 / 移动靠 IO 侧的 `HttpOverrides` 全局放行自签证书；Web 的 TLS 校验完全由
/// 浏览器负责（Dart 侧改不了），所以 Web 版是空操作。条件导入让两边语义各自保持，
/// 且任何被 Web 编译的文件里都不会再出现 IO 专有库。
///
/// 判据用 `dart.library.js_interop`：Dart 3.5 的 Web 编译恒为真，比已废弃的
/// `dart.library.html` 更稳（两者在 3.5 下都可用，优先前者）。
library;

export 'insecure_http_io.dart' if (dart.library.js_interop) 'insecure_http_web.dart';
