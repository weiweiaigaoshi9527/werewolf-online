import 'package:flutter_test/flutter_test.dart';
import 'package:werewolf_app/config.dart';

void main() {
  group('默认端口', () {
    test('仍是迁移后的 11111（历史上曾长期写死 8080）', () {
      expect(ServerConfig.kDefaultPort, 11111);
    });
  });

  group('ServerConfig URL 拼装', () {
    test('REST 路径拼接与结尾斜杠归一', () {
      expect(ServerConfig(baseUrl: 'https://a.example.com:11111').api('/api/health').toString(),
          'https://a.example.com:11111/api/health');
      expect(ServerConfig(baseUrl: 'https://a.example.com:11111/').api('/api/health').toString(),
          'https://a.example.com:11111/api/health');
    });

    test('https → wss，http → ws，两个通道路径正确', () {
      final s = ServerConfig(baseUrl: 'https://192.168.1.169:11111');
      final ws = s.wsUri('TK');
      final voice = s.wsUriVoice('TK');
      expect(ws.scheme, 'wss');
      expect(ws.path, '/ws');
      expect(ws.port, 11111);
      expect(ws.queryParameters['token'], 'TK');
      expect(voice.scheme, 'wss');
      expect(voice.path, '/ws/voice');
      expect(voice.queryParameters['token'], 'TK');

      final plain = ServerConfig(baseUrl: 'http://localhost:11111');
      expect(plain.wsUri('T').scheme, 'ws');
      expect(plain.wsUriVoice('T').scheme, 'ws');
    });

    test('configured 只看是否填了地址', () {
      expect(ServerConfig().configured, isFalse);
      expect(ServerConfig(baseUrl: '   ').configured, isFalse);
      expect(ServerConfig(baseUrl: 'https://x:11111').configured, isTrue);
    });
  });
}
