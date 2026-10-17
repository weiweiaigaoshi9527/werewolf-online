import 'dart:io' show HttpClient, HttpOverrides, SecurityContext, X509Certificate;

/// 局域网自签证书调试：忽略证书校验。公网正式证书下无需（可在设置里关掉“允许不安全连接”）。
class _InsecureOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? ctx) =>
      super.createHttpClient(ctx)
        ..badCertificateCallback = (X509Certificate c, String h, int p) => true;
}

/// 桌面 / 移动：把全局 HttpClient 换成不校验证书的实现（行为与改造前完全一致）。
void allowInsecureHttp() => HttpOverrides.global = _InsecureOverrides();
