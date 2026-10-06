import 'dart:async';
import 'dart:convert';
import 'package:http/http.dart' as http;
import 'config.dart';

class AppError implements Exception {
  final String message;
  AppError(this.message);
  @override
  String toString() => message;
}

/// 轻量 REST 客户端：与网页版走完全相同的 /api 接口与 Bearer 鉴权。
class ApiClient {
  final ServerConfig config;
  String? token;
  final http.Client _client;

  /// client 可注入：生产用默认实现，页面级测试用 MockClient 喂固定响应，
  /// 这样"进页面即发请求"的页面也能离线做渲染回归。
  ApiClient(this.config, {http.Client? client}) : _client = client ?? http.Client();

  Map<String, String> get _headers => {
        'Content-Type': 'application/json',
        if (token != null) 'Authorization': 'Bearer $token',
      };

  Future<Map<String, dynamic>> get(String path) async =>
      (await _send('GET', path)) as Map<String, dynamic>? ?? const {};

  /// 返回数组型接口（如商店目录、好友列表、金币流水）。
  Future<List<dynamic>> getList(String path) async {
    final d = await _send('GET', path);
    return d is List ? d : const [];
  }

  Future<Map<String, dynamic>> post(String path, [Object? body]) async =>
      (await _send('POST', path, body)) as Map<String, dynamic>? ?? const {};

  /// multipart 上传（目前用于自定义头像：服务端要求字段名必须是 file，≤2MB，
  /// 且总是用 ImageIO 重新编码成 128×128 PNG）。返回服务端给的视图。
  Future<Map<String, dynamic>> postFile(String path,
      {required String field, required String filename, required List<int> bytes}) async {
    final req = http.MultipartRequest('POST', config.api(path))
      ..headers.addAll(Map.of(_headers)..remove('Content-Type'))
      ..files.add(http.MultipartFile.fromBytes(field, bytes, filename: filename));
    http.StreamedResponse res;
    try {
      res = await _client.send(req).timeout(const Duration(seconds: 30));
    } on TimeoutException {
      throw AppError('上传超时，请检查网络');
    } catch (e) {
      throw AppError('上传失败：$e');
    }
    final body = await res.stream.bytesToString();
    dynamic decoded;
    try {
      decoded = body.isEmpty ? null : jsonDecode(body);
    } catch (_) {}
    if (res.statusCode >= 400) {
      final msg = (decoded is Map && decoded['error'] != null) ? decoded['error'].toString() : '上传失败 (${res.statusCode})';
      throw AppError(msg);
    }
    return decoded is Map<String, dynamic> ? decoded : const {};
  }

  /// 读取匿名静态资源（如更新动态 /data/changelog.json——它不是 API，是随前端发布的静态文件）。
  Future<Map<String, dynamic>> getStatic(String path) async {
    http.Response res;
    try {
      res = await _client.get(config.api(path)).timeout(const Duration(seconds: 15));
    } on TimeoutException {
      throw AppError('加载超时');
    } catch (e) {
      throw AppError('无法加载：$e');
    }
    if (res.statusCode >= 400) throw AppError('加载失败 (${res.statusCode})');
    try {
      final d = jsonDecode(res.body);
      return d is Map<String, dynamic> ? d : const {};
    } catch (_) {
      throw AppError('内容不是合法的 JSON');
    }
  }

  /// 读取原始字节（如后台的用户 CSV 导出：GET /api/admin/ops/export/users）。
  /// 与 JSON 接口不同，这里不做 jsonDecode——成功直接回传 bodyBytes；
  /// 只有失败时才尝试解析 JSON 里的 error 字段，好把服务端的中文原因透出给用户。
  Future<List<int>> getBytes(String path) async {
    final uri = config.api(path);
    http.Response res;
    try {
      res = await _client.get(uri, headers: _headers).timeout(const Duration(seconds: 15));
    } on TimeoutException {
      throw AppError('连接服务器超时，请检查地址与网络');
    } catch (e) {
      throw AppError('无法连接服务器：$e');
    }
    if (res.statusCode >= 400) {
      String msg = '请求失败 (${res.statusCode})';
      try {
        final decoded = jsonDecode(res.body);
        if (decoded is Map && decoded['error'] != null) msg = decoded['error'].toString();
      } catch (_) {}
      throw AppError(msg);
    }
    return res.bodyBytes;
  }

  Future<dynamic> _send(String method, String path, [Object? body]) async {
    final uri = config.api(path);
    http.Response res;
    try {
      if (method == 'GET') {
        res = await _client.get(uri, headers: _headers).timeout(const Duration(seconds: 15));
      } else {
        res = await _client
            .post(uri, headers: _headers, body: body == null ? '{}' : jsonEncode(body))
            .timeout(const Duration(seconds: 25));
      }
    } on TimeoutException {
      throw AppError('连接服务器超时，请检查地址与网络');
    } catch (e) {
      throw AppError('无法连接服务器：$e');
    }
    dynamic decoded;
    if (res.body.isNotEmpty) {
      try {
        decoded = jsonDecode(res.body);
      } catch (_) {}
    }
    if (res.statusCode >= 400) {
      final msg = (decoded is Map && decoded['error'] != null) ? decoded['error'].toString() : '请求失败 (${res.statusCode})';
      throw AppError(msg);
    }
    return decoded;
  }

  Future<Map<String, dynamic>?> health() async {
    try {
      return await get('/api/health');
    } catch (_) {
      return null;
    }
  }
}
