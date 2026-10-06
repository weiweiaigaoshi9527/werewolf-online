import 'package:shared_preferences/shared_preferences.dart';

/// 服务器地址与安全设置，跨启动持久化。所有端共用同一份后端即可互通。
class ServerConfig {
  static const kUrl = 'server_base_url';
  static const kInsecure = 'allow_insecure_tls';
  static const kToken = 'auth_token';

  /// 服务端默认端口（HTTPS 自签证书），必须与 application.yml 的 https 配置档一致。
  static const int kDefaultPort = 11111;

  // 注意：客户端不再内置任何「预设服务器」。原先这里写死了维护者的公网域名并做成
  // “一键填入”的快捷项，属于私人基础设施，不该随客户端分发；所有端统一改由用户在
  // 「连接服务器」页手工填写地址。需要批量分发预置地址时用下面的 bakedServer。

  /// 打包时可用 `--dart-define=WW_SERVER=https://你的域名:11111` 预置服务器地址，
  /// 装好即进登录页，省去首次「服务器设置」。未预置则仍走手工设置页。
  static const String bakedServer = String.fromEnvironment('WW_SERVER');

  String baseUrl; // 例如 https://game.example.com  或  https://192.168.1.169:11111
  bool allowInsecure; // 局域网自签证书调试用；公网正式证书请关闭

  ServerConfig({this.baseUrl = '', this.allowInsecure = true});

  String get _root => baseUrl.endsWith('/') ? baseUrl.substring(0, baseUrl.length - 1) : baseUrl;

  Uri api(String path) => Uri.parse('$_root$path');

  Uri wsUri(String token) {
    final u = Uri.parse(_root);
    final scheme = u.scheme == 'http' ? 'ws' : 'wss';
    return Uri(scheme: scheme, host: u.host, port: u.port, path: '/ws', queryParameters: {'token': token});
  }

  /// 语音通道 /ws/voice?token=（与网页 voice.js 一致）。
  Uri wsUriVoice(String token) {
    final u = Uri.parse(_root);
    final scheme = u.scheme == 'http' ? 'ws' : 'wss';
    return Uri(scheme: scheme, host: u.host, port: u.port, path: '/ws/voice', queryParameters: {'token': token});
  }

  bool get configured => baseUrl.trim().isNotEmpty;

  Future<void> save() async {
    final p = await SharedPreferences.getInstance();
    await p.setString(kUrl, baseUrl.trim());
    await p.setBool(kInsecure, allowInsecure);
  }

  static Future<ServerConfig> load() async {
    final p = await SharedPreferences.getInstance();
    final saved = (p.getString(kUrl) ?? '').trim();
    return ServerConfig(
      // 用户手工填过的地址优先；从未填过时用打包预置地址（若有）
      baseUrl: saved.isNotEmpty ? saved : bakedServer,
      allowInsecure: p.getBool(kInsecure) ?? true,
    );
  }

  static Future<String?> loadToken() async {
    final p = await SharedPreferences.getInstance();
    return p.getString(kToken);
  }

  static Future<void> saveToken(String? token) async {
    final p = await SharedPreferences.getInstance();
    if (token == null) {
      await p.remove(kToken);
    } else {
      await p.setString(kToken, token);
    }
  }
}
