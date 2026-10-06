import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart';
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/admin.dart';
import 'package:werewolf_app/screens/common.dart';
import 'package:werewolf_app/screens/updates.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

/// 更新动态 / 下载中心 / 后台管理 的离线渲染与门控回归。
void main() {
  Client fake({bool admin = false}) => MockClient((req) async {
        final p = req.url.path;
        String body() {
          if (p == '/data/changelog.json') {
            return jsonEncode({
              'updated': '2026-10-03',
              'items': [
                {'date': '2026-10-03', 'tag': '新增', 'text': '三端客户端上线'},
                {'date': '2026-10-02', 'tag': '修复', 'text': '语音上行偶发中断'},
              ],
            });
          }
          if (p == '/api/downloads') {
            return jsonEncode({
              'updated': '2026-10-03',
              'banner': 'v2.0.0 全新界面',
              'items': [
                {'platform': 'windows', 'label': 'Windows 桌面版', 'icon': '🖥️', 'os': 'Windows 10 / 11', 'arch': 'x64', 'version': '2.0.0', 'note': '安装版', 'file': 'ww-win.exe', 'available': true, 'size': 80450359, 'url': '/download/ww-win.exe'},
                {'platform': 'pwa', 'label': '网页版', 'icon': '🌐', 'os': '任意', 'arch': '', 'version': '', 'note': '直接访问', 'file': '', 'available': true, 'size': 0, 'url': '/'},
                {'platform': 'android', 'label': '安卓版', 'icon': '🤖', 'os': 'Android', 'arch': '', 'version': '', 'note': '', 'file': '', 'available': false, 'size': 0, 'url': null},
              ],
            });
          }
          if (p == '/api/admin/system') {
            return jsonEncode({'version': '0.1.0', 'serverTime': '2026-10-04T10:00:00', 'online': 3, 'rooms': 12, 'activeGames': 2, 'users': 222});
          }
          if (p == '/api/admin/ops/online') {
            return jsonEncode([
              {'id': 1, 'username': 'weiwei', 'nickname': 'weiwei', 'level': 690, 'admin': true, 'activity': '对局中 · 3号', 'roomNo': '200728'},
            ]);
          }
          if (p == '/api/admin/users') {
            return jsonEncode([
              {'id': 2180, 'username': 'winclient01', 'nickname': '三端自测', 'level': 1, 'gold': 100, 'admin': false, 'banned': false, 'online': true, 'vip': 0, 'createdAt': '2026-10-03'},
            ]);
          }
          if (p == '/api/admin/system/features') {
            return jsonEncode({
              'flags': {for (final f in ['quickai', 'spectate', 'shop', 'friends', 'forum', 'ranking', 'checkin', 'news', 'tickets', 'download', 'guide', 'voiceMode', 'duo', 'items', 'invite', 'addai']) f: true}
            });
          }
          if (p == '/api/admin/system/display') return jsonEncode({'webMaxHeight': 0});
          if (p == '/api/room/notice') return jsonEncode({'message': ''});
          return '[]';
        }

        // 非管理员访问 /api/admin/** 一律 403（与后端 AdminInterceptor 一致）
        if (p.startsWith('/api/admin/') && !admin) {
          return Response('{"error":"需要管理员权限"}', 403, headers: {'content-type': 'application/json'});
        }
        return Response(body(), 200, headers: {'content-type': 'application/json'});
      });

  Future<AppStore> pump(WidgetTester tester, Widget page, {bool admin = false}) async {
    SharedPreferences.setMockInitialValues({});
    final store = AppStore(ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111'), client: fake(admin: admin)))
      ..user = {'id': 2180, 'username': 'winclient01', 'nickname': '三端自测', 'admin': admin};
    tester.view.physicalSize = const Size(1300, 2200);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      child: MaterialApp(theme: werewolfTheme(), home: page),
    ));
    await tester.pump(const Duration(milliseconds: 300));
    return store;
  }

  group('更新动态', () {
    testWidgets('渲染 tag 徽章、正文与更新日期', (tester) async {
      await pump(tester, const NewsScreen());
      expect(find.text('更新动态'), findsOneWidget);
      expect(find.text('更新于 2026-10-03'), findsOneWidget);
      expect(find.text('新增'), findsOneWidget);
      expect(find.text('三端客户端上线'), findsOneWidget);
      expect(find.text('修复'), findsOneWidget);
      expect(find.text('语音上行偶发中断'), findsOneWidget);
    });
  });

  group('下载中心', () {
    testWidgets('banner、体积人性化、三种 url 形态各自的操作', (tester) async {
      await pump(tester, const DownloadsScreen());
      expect(find.text('v2.0.0 全新界面'), findsOneWidget);
      expect(find.text('Windows 桌面版'), findsOneWidget);
      expect(find.textContaining('76.7 MB'), findsOneWidget, reason: '80450359 字节应显示为 76.7 MB');
      expect(find.text('复制链接'), findsOneWidget);
      expect(find.text('复制网址'), findsOneWidget, reason: 'url=="/" 的 PWA 条目应给网址而非文件下载');
      expect(find.text('即将提供'), findsOneWidget, reason: 'available=false 且 url 为 null 的条目要灰态占位');
    });

    testWidgets('点复制写入剪贴板（相对路径已拼上 baseUrl）', (tester) async {
      // flutter_test 里没有 SystemClipboard，剪贴板要拦平台通道
      // （SystemChannels.platform.setMockMethodCallHandler 已废弃，用 binding 的 messenger）
      final copied = <String>[];
      tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, (call) async {
        if (call.method == 'Clipboard.setData') {
          copied.add('${(call.arguments as Map)['text']}');
        }
        return null;
      });
      addTearDown(() => tester.binding.defaultBinaryMessenger.setMockMethodCallHandler(SystemChannels.platform, null));

      await pump(tester, const DownloadsScreen());
      await tester.tap(find.text('复制链接'));
      await tester.pump(const Duration(milliseconds: 200));
      expect(copied, ['https://unit.test.invalid:11111/download/ww-win.exe']);

      await tester.tap(find.text('复制网址'));
      await tester.pump(const Duration(milliseconds: 200));
      expect(copied.last, 'https://unit.test.invalid:11111', reason: 'url 为站点根时解析成 baseUrl，不额外补斜杠');
    });
  });

  group('后台管理', () {
    testWidgets('非管理员直接提示，不渲染任何管理操作', (tester) async {
      await pump(tester, const AdminScreen());
      expect(find.text('需要管理员权限'), findsOneWidget);
      expect(find.text('危险运维'), findsNothing);
      expect(find.text('关闭服务'), findsNothing);
    });

    testWidgets('管理员可见七个分节与在线统计', (tester) async {
      await pump(tester, const AdminScreen(), admin: true);
      for (final t in ['概览', '用户', '房间', '工单', '兑换码', '论坛', '系统']) {
        expect(find.text(t), findsWidgets, reason: '缺少分节 $t');
      }
      expect(find.textContaining('服务 v0.1.0'), findsOneWidget);
      expect(find.text('222'), findsOneWidget);
      // 在线玩家列表在长列表下方，ListView 懒加载，要先滚进视口
      // （副标题实际是「对局中 · 3号 · 房号 200728」，所以只能 textContaining）
      await tester.dragUntilVisible(
        find.textContaining('对局中 · 3号'),
        find.byKey(const Key('admin-overview')),
        const Offset(0, -220),
      );
      expect(find.textContaining('对局中 · 3号'), findsOneWidget);
    });

    testWidgets('危险操作必须先输入确认词，不输入就不发请求', (tester) async {
      await pump(tester, const AdminScreen(), admin: true);
      await tester.tap(find.text('结束全部对局'));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('服务端没有二次确认参数'), findsOneWidget);
      expect(find.text('确认执行'), findsWidgets);
      // 直接点「继续」而不填确认词 → 应被关闭且无请求
      await tester.tap(find.text('继续'));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.textContaining('服务端没有二次确认参数'), findsNothing);
    });

    testWidgets('系统分节渲染 16 个功能开关与 webMaxHeight 的真实语义', (tester) async {
      await pump(tester, const AdminScreen(initialTab: 6), admin: true);
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('快速 AI 房  (quickai)'), findsOneWidget);
      expect(find.text('房主补 AI  (addai)'), findsOneWidget);
      expect(find.text('当前 不限制'), findsOneWidget);
      expect(find.textContaining('占屏高百分比'), findsOneWidget);
    });
  });

  group('avatarUrl 解析', () {
    Future<String?> probe(WidgetTester tester, String? raw, {String base = 'https://h.test:11111'}) async {
      SharedPreferences.setMockInitialValues({});
      final store = AppStore(ApiClient(ServerConfig(baseUrl: base)))..user = {'id': 1};
      String? out;
      await tester.pumpWidget(ChangeNotifierProvider.value(
        value: store,
        child: MaterialApp(
          home: Builder(builder: (c) {
            out = resolveAvatarUrl(c, raw);
            return const SizedBox.shrink();
          }),
        ),
      ));
      return out;
    }

    testWidgets('相对路径拼上 baseUrl', (tester) async {
      expect(await probe(tester, '/uploads/avatars/u2180_1.png'), 'https://h.test:11111/uploads/avatars/u2180_1.png');
    });

    testWidgets('绝对 http(s) 与 data: 原样返回', (tester) async {
      expect(await probe(tester, 'https://cdn.test/a.png'), 'https://cdn.test/a.png');
      expect(await probe(tester, 'data:image/png;base64,AAA'), 'data:image/png;base64,AAA');
    });

    testWidgets('空值返回 null；baseUrl 未配置时相对路径也返回 null', (tester) async {
      expect(await probe(tester, ''), isNull);
      expect(await probe(tester, null), isNull);
      expect(await probe(tester, '/uploads/a.png', base: ''), isNull);
    });

    testWidgets('baseUrl 结尾多斜杠不会拼出双斜杠', (tester) async {
      expect(await probe(tester, '/uploads/a.png', base: 'https://h.test:11111/'), 'https://h.test:11111/uploads/a.png');
    });
  });
}
