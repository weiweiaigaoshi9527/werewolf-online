// 测试里大量用运行时变量构造 widget，const 化不是这里的目标，定向豁免。
// ignore_for_file: prefer_const_constructors
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/animations.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/game.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

import 'fixtures/server_views.dart';

void main() {
  group('身份牌翻牌', () {
    testWidgets('先牌背、过半亮面、结束时回调；点击可提前收起', (tester) async {
      var done = 0;
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: RoleReveal(role: '预言家', faction: 'GOD', seat: 3, onDone: () => done++),
        ),
      ));
      await tester.pump();
      // 起始是牌背：只有月亮图案，看不到角色名
      expect(find.text('🌙'), findsOneWidget);
      expect(find.text('预言家'), findsNothing);

      // 过半进入亮面
      await tester.pump(const Duration(milliseconds: 600));
      expect(find.text('预言家'), findsOneWidget);
      expect(find.textContaining('你是 3 号'), findsOneWidget);
      expect(done, 0, reason: '动画没结束不该回调');

      await tester.pump(const Duration(milliseconds: 600));
      expect(done, 1, reason: '动画结束应回调一次');
    });

    testWidgets('狼人与神民用不同阵营文案', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: RoleReveal(role: '白狼王', faction: 'WOLF', seat: 2)),
      ));
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.text('狼人阵营'), findsOneWidget);
      expect(find.text('🐺'), findsOneWidget);

      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: RoleReveal(role: '女巫', faction: 'GOD', seat: 4)),
      ));
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.text('神民阵营'), findsOneWidget);
    });
  });

  group('结算揭示', () {
    testWidgets('胜负横幅 + 每个座位的身份牌，出局者标 dagger 并划掉', (tester) async {
      tester.view.physicalSize = const Size(1200, 900);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SettleReveal(winner: '好人阵营', seats: const [
            {'seat': 1, 'role': '预言家', 'alive': true},
            {'seat': 2, 'role': '狼人', 'alive': false},
          ]),
        ),
      ));
      await tester.pump(const Duration(milliseconds: 400));
      expect(find.text('🌅 好人阵营胜利'), findsOneWidget);
      expect(find.text('1·预言家'), findsOneWidget);
      expect(find.textContaining('2·狼人'), findsOneWidget);
      await tester.pump(const Duration(seconds: 2));
      expect(find.textContaining('†'), findsOneWidget, reason: '出局座位应带†');
    });

    testWidgets('狼人胜走狼人文案', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(body: SettleReveal(winner: '狼人阵营', seats: const [])),
      ));
      await tester.pump(const Duration(milliseconds: 300));
      expect(find.text('🐺 狼人阵营胜利'), findsOneWidget);
    });
  });

  group('接进对局页', () {
    AppStore storeWith(Map<String, dynamic> game) {
      SharedPreferences.setMockInitialValues({});
      final s = AppStore(ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111')))
        ..user = {'id': 2180, 'username': 'winclient01', 'nickname': '三端自测'}
        ..game = game;
      return s;
    }

    Future<void> pumpGame(WidgetTester tester, AppStore store) async {
      tester.view.physicalSize = const Size(1200, 1700);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ChangeNotifierProvider.value(
        value: store,
        child: MaterialApp(theme: werewolfTheme(), home: const GameScreen()),
      ));
      await tester.pump();
    }

    testWidgets('开局自动弹一次身份牌，收起后重建不再弹', (tester) async {
      final store = storeWith(gameStateDay());
      await pumpGame(tester, store);
      await tester.pump(const Duration(milliseconds: 700)); // postFrame 里弹对话框
      expect(find.textContaining('你是 1 号'), findsOneWidget);

      await tester.tap(find.textContaining('你是 1 号')); // 点击即收起
      await tester.pumpAndSettle();
      expect(find.textContaining('你是 1 号'), findsNothing);
      // 收起后动画的 whenComplete 仍会到点，但 onDone 只能触发一次，
      // 不能再 pop 掉对局页本身
      expect(find.text('村庄大厅'), findsNothing);
      expect(find.byType(GameScreen), findsOneWidget);

      store.notifyListeners(); // 模拟后续 WS 推送导致重建
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.textContaining('你是 1 号'), findsNothing, reason: '每局只该弹一次');
    });

    testWidgets('没有身份牌（观战）时不弹揭示', (tester) async {
      final g = gameStateDay()..remove('myInfo');
      await pumpGame(tester, storeWith(g));
      await tester.pump(const Duration(milliseconds: 700));
      expect(find.textContaining('点击继续'), findsNothing);
    });

    testWidgets('GAME_OVER 时结算揭示替掉原来那行纯文字', (tester) async {
      tester.view.physicalSize = const Size(1200, 1700);
      final store = storeWith(gameStateOver());
      await pumpGame(tester, store);
      await tester.pump(const Duration(milliseconds: 700));
      // 先收掉身份牌，再看结算
      if (find.textContaining('你是 1 号').evaluate().isNotEmpty) {
        await tester.tap(find.textContaining('你是 1 号'));
        await tester.pump(const Duration(milliseconds: 500));
      }
      expect(find.text('🌅 好人阵营胜利'), findsOneWidget);
      expect(find.textContaining('2·狼人'), findsOneWidget);
      expect(find.textContaining('🏁 本局结束'), findsNothing, reason: '旧的一行文字应已被动画取代');
    });
  });
}
