import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/edit_profile.dart';
import 'package:werewolf_app/screens/forum.dart';
import 'package:werewolf_app/screens/inventory.dart';
import 'package:werewolf_app/screens/lobby.dart';
import 'package:werewolf_app/screens/ranking.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

import 'fixtures/server_views.dart';

/// P3 新页面的离线渲染回归：用 MockClient 按路径喂真实形状的响应，
/// 让「进页面即发请求」的页面也能在没有网络的情况下做结构与门控断言。
void main() {
  /// 按请求路径路由到 fixtures；未列出的路径一律 404，好让漏配立刻暴露。
  http.Client fakeClient({List<String> disabled = const []}) => MockClient((req) async {
        final path = req.url.path;
        String body() {
          if (path == '/api/forum') return jsonEncode(forumList());
          if (path == '/api/forum/65') {
            return jsonEncode({...forumList().first, 'replies': [
              {'id': 1, 'content': '收到！', 'at': '2026-10-03T10:00:00.1', 'userId': 3021, 'author': '阿尔法'}
            ]});
          }
          if (path == '/api/ranking') return jsonEncode(rankingView(by: req.url.queryParameters['by'] ?? 'win'));
          if (path == '/api/shop/inventory') return jsonEncode(shopInventory());
          if (path == '/api/user/profile') {
            return jsonEncode({
              'id': 2180, 'username': 'winclient01', 'nickname': '三端自测', 'level': 12, 'gold': 880,
              'signature': '夜里别刀我', 'location': '上海', 'regLocation': '成都',
              'birthday': '2000-05-06', 'voiceGender': 'M', 'avatarId': 3, 'avatarUrl': null,
            });
          }
          if (path == '/api/user/rolestats') {
            return jsonEncode([
              {'role': '预言家', 'games': 30, 'wins': 18, 'seconds': 7200},
              {'role': '狼人', 'games': 22, 'wins': 11, 'seconds': 5400},
            ]);
          }
          if (path == '/api/config/features') return jsonEncode(featuresView(disabled: disabled));
          return '{"error":"not mocked: $path"}';
        }

        final ok = path != '/api/__missing__';
        return http.Response(body(), ok ? 200 : 404, headers: {'content-type': 'application/json'});
      });

  Future<AppStore> pump(WidgetTester tester, Widget page, {List<String> disabled = const []}) async {
    SharedPreferences.setMockInitialValues({});
    final config = ServerConfig(baseUrl: 'https://unit.test.invalid:11111');
    final store = AppStore(ApiClient(config, client: fakeClient(disabled: disabled)))
      ..user = {'id': 2180, 'username': 'winclient01', 'nickname': '三端自测', 'level': 12, 'gold': 880};
    store.disabledFeatures = disabled;
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      child: MaterialApp(theme: werewolfTheme(), home: page),
    ));
    await tester.pump(const Duration(milliseconds: 300));
    return store;
  }

  group('论坛', () {
    testWidgets('列表渲染标题、分类、作者与回复数，置顶排在前', (tester) async {
      await pump(tester, const ForumScreen());
      expect(find.text('论坛'), findsOneWidget);
      expect(find.text('语音功能上线啦'), findsOneWidget);
      expect(find.text('预言家首夜该验谁'), findsOneWidget);
      expect(find.textContaining('攻略 · QA'), findsOneWidget);
      expect(find.textContaining('3 回复'), findsOneWidget);
      // 置顶帖排第一（WidgetTester 没有 getTop，取矩形上边）
      expect(
        tester.getRect(find.text('预言家首夜该验谁')).top < tester.getRect(find.text('语音功能上线啦')).top,
        isTrue,
      );
    });

    testWidgets('分类筛选真的过滤列表', (tester) async {
      await pump(tester, const ForumScreen());
      await tester.tap(find.text('综合'));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('语音功能上线啦'), findsOneWidget);
      expect(find.text('预言家首夜该验谁'), findsNothing);
    });

    testWidgets('管理员关闭论坛后页面直接提示，不发请求', (tester) async {
      await pump(tester, const ForumScreen(), disabled: ['forum']);
      expect(find.textContaining('论坛已被管理员关闭'), findsOneWidget);
      expect(find.text('语音功能上线啦'), findsNothing);
    });
  });

  group('排行榜', () {
    testWidgets('渲染名次、昵称与指标，前三名出奖牌', (tester) async {
      await pump(tester, const RankingScreen());
      expect(find.text('排行榜'), findsOneWidget);
      expect(find.text('胜率榜'), findsOneWidget);
      expect(find.text('🥇'), findsOneWidget);
      expect(find.text('weiwei'), findsOneWidget);
      expect(find.text('夜枭'), findsOneWidget);
      expect(find.textContaining('34.0% · 18/53'), findsOneWidget);
      expect(find.textContaining('MVP 9'), findsOneWidget);
    });

    testWidgets('切到财富榜后指标换成金币', (tester) async {
      await pump(tester, const RankingScreen());
      await tester.tap(find.text('财富榜'));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.textContaining('💰99966463'), findsOneWidget);
      expect(find.textContaining('34.0% · 18/53'), findsNothing);
    });
  });

  testWidgets('背包按类型分组，功能道具给「带入」而装扮给「装备」', (tester) async {
    await pump(tester, const InventoryScreen());
    expect(find.text('我的背包'), findsOneWidget);
    expect(find.text('功能道具 · 1'), findsOneWidget);
    expect(find.text('头像 · 1'), findsOneWidget);
    expect(find.text('双倍经验卡'), findsOneWidget);
    expect(find.text('带入'), findsOneWidget);
    expect(find.text('装备'), findsOneWidget);
  });

  testWidgets('资料编辑页回填服务端现值并算出角色胜率', (tester) async {
    await pump(tester, const EditProfileScreen());
    expect(find.text('夜里别刀我'), findsOneWidget);
    expect(find.text('上海'), findsOneWidget);
    expect(find.text('成都'), findsOneWidget);
    expect(find.text('2000-05-06'), findsOneWidget);
    expect(find.text('男声'), findsOneWidget);
    expect(find.textContaining('30 场 · 胜 18 · 胜率 60.0%'), findsOneWidget);
    expect(find.textContaining('2.0h'), findsOneWidget);
  });

  testWidgets('大厅入口随能力门控增减', (tester) async {
    await pump(tester, const LobbyScreen());
    expect(find.text('论坛'), findsOneWidget);
    expect(find.text('排行榜'), findsOneWidget);
    expect(find.text('兑换码'), findsOneWidget);

    await pump(tester, const LobbyScreen(), disabled: ['forum', 'ranking', 'friends']);
    expect(find.text('论坛'), findsNothing);
    expect(find.text('排行榜'), findsNothing);
    // 建房等核心入口不受影响
    expect(find.text('创建房间'), findsOneWidget);
  });
}
