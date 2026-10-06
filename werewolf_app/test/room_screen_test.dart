import 'dart:io';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/room.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

import 'fixtures/server_views.dart';

/// 等待室页面的渲染回归：房主管理入口、四个模式开关、双人组队条。
/// 全部用 test/fixtures 里的真实视图快照驱动，不碰网络。
void main() {
  /// 视觉基线只在显式要求时参与断言/产图：
  /// Windows 与 Linux 的字体渲染像素级不同，默认跑会把 CI 弄脏。
  /// 产图：`set WW_GOLDEN=1 && flutter test test/room_screen_test.dart --update-goldens`
  Future<void> maybeGolden(WidgetTester tester, String name) async {
    if (Platform.environment['WW_GOLDEN'] != '1') return;
    await expectLater(find.byType(RoomScreen), matchesGoldenFile('goldens/$name.png'));
  }

  AppStore buildStore({required bool host, bool duoMode = false, List<List<int>>? duos}) {
    SharedPreferences.setMockInitialValues({});
    final store = AppStore(ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111')));
    store.user = {
      'id': host ? 2180 : 3021,
      'username': 'winclient01',
      'nickname': '三端自测',
    };
    // maxSeats 收到 8：GridView 只布局视口内的子项，24 座时 6 号座位压根没被 build 出来。
    store.room = roomView(duoMode: duoMode, duos: duos, maxSeats: 8);
    return store;
  }

  Future<void> pumpRoom(WidgetTester tester, AppStore store) async {
    // 撑高虚拟屏幕，保证 8 个座位卡（4 行）全部进入视口
    tester.view.physicalSize = const Size(1400, 2400);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      // 必须套上真实主题，否则 golden 拍出来的是 Flutter 默认浅色，与三端实际观感无关。
      // 注意：flutter test 环境不带 CJK 字体，中文在 golden 里会渲染成方块——
      // 这里只锁布局结构与开关/按钮的有无，文字正确性由语义断言 + 真机截图负责。
      child: MaterialApp(theme: werewolfTheme(), home: const RoomScreen()),
    ));
    // 用 pump 而不是 pumpAndSettle：duo 开启时页面挂着 2 秒轮询定时器，settle 不会收敛。
    await tester.pump(const Duration(milliseconds: 200));
  }

  testWidgets('房主看到板子与四个模式开关（含猎城、双人组队）', (tester) async {
    final store = buildStore(host: true);
    await pumpRoom(tester, store);

    expect(find.text('房间 200728'), findsOneWidget);
    expect(find.text('入门9人局 · 6 人'), findsOneWidget);
    expect(find.text('语音同传（匿名伪装以藏 AI）'), findsOneWidget);
    expect(find.text('匿名模式（隐藏真实昵称）'), findsOneWidget);
    expect(find.text('猎城规则（狼人数量达标直接判胜）'), findsOneWidget);
    expect(find.text('双人组队（结队者同阵营）'), findsOneWidget);
    expect(find.text('功能道具赛（携带道具本局生效）'), findsOneWidget);
    expect(find.text('配置板子'), findsOneWidget);
    expect(find.text('开始游戏'), findsOneWidget);
    expect(find.text('6/8 人'), findsOneWidget);
    await maybeGolden(tester, 'room_host');
  });

  testWidgets('非房主看不到管理面板，也看不到 AI 按钮', (tester) async {
    final store = buildStore(host: false);
    await pumpRoom(tester, store);

    expect(find.text('配置板子'), findsNothing);
    expect(find.text('猎城规则（狼人数量达标直接判胜）'), findsNothing);
    expect(find.text('开始游戏'), findsNothing);
    expect(find.byTooltip('添加 AI（一次补一个空位）'), findsNothing);
    // 普通成员仍可准备；fixture 里 6 号（即本账号）已准备，所以按钮显示「取消准备」
    expect(find.text('取消准备'), findsOneWidget);
  });

  testWidgets('房主点座位管理菜单能看到「移出房间 / 转让房主」', (tester) async {
    final store = buildStore(host: true);
    await pumpRoom(tester, store);

    // 座位 6 是唯一的其他真人（阿尔法 / userId 3021）
    await tester.tap(find.byTooltip('房主管理').first);
    await tester.pumpAndSettle();
    expect(find.text('移出房间'), findsOneWidget);
    expect(find.text('转让房主'), findsOneWidget);

    await tester.tap(find.text('移出房间'));
    // 踢人必须先过确认框，不能一点就踢
    await tester.pumpAndSettle();
    expect(find.text('把 阿尔法（6号）移出房间？'), findsOneWidget);
    expect(find.text('取消'), findsOneWidget);
  });

  testWidgets('开启双人组队后出现状态条与座位上的组队入口', (tester) async {
    final store = buildStore(host: true, duoMode: true, duos: const []);
    await pumpRoom(tester, store);

    expect(find.textContaining('双人组队已开启'), findsOneWidget);
    // 其他真人座位（6 号）应有「组队」按钮；AI 座位不给组队入口
    expect(find.text('组队'), findsOneWidget);
    expect(find.text('解除我的队伍'), findsNothing);
  });

  testWidgets('已在队内时显示所在对子并可解除', (tester) async {
    final store = buildStore(host: true, duoMode: true);
    // duoMode 打开时 fixture 会把 [[1,6]] 作为已成的对子下发（1 号就是房主本人）
    await pumpRoom(tester, store);
    expect(store.mySeat, 1);
    expect(store.duoPairOfMine(), [1, 6]);
    expect(find.textContaining('已组队 1 对'), findsOneWidget);
    expect(find.text('解除我的队伍'), findsOneWidget);
    // 6 号就是我的队友：座位上给「解除」，不再给「组队」邀请
    expect(find.text('解除'), findsOneWidget);
    expect(find.text('组队'), findsNothing);
    await maybeGolden(tester, 'room_host_duo');
  });
}
