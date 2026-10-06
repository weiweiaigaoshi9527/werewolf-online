import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart';
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/screens/replay.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

/// 回放播放器的界面交互回归：首屏=全量、播放靠定时器推进、后退/拖动/末帧停表。
void main() {
  Map<String, dynamic> replayFixture() => {
        'gameId': 80,
        'roomNo': '384687',
        'winner': '狼人阵营',
        // 服务端这里是**字符串**，不是对象
        'seatRoles': '1:预言家 2:狼人 3:村民 4:猎人',
        'startedAt': '2026-10-01T19:39:41.915248',
        'endedAt': '2026-10-01T19:39:42.1',
        'events': [
          {'seq': 0, 'day': 0, 'phase': 'SETUP', 'type': 'GAME_START', 'actor': 0, 'target': 0, 'detail': 'board={狼人x1 村民x2 猎人x1}'},
          {'seq': 1, 'day': 1, 'phase': 'SHERIFF_ELECTION', 'type': 'SHERIFF_WIN', 'actor': 1, 'target': 0, 'detail': '当选警长'},
          {'seq': 2, 'day': 1, 'phase': 'DAY_SPEAK', 'type': 'SPEECH', 'actor': 1, 'target': 0, 'detail': '昨晚验了2号，是狼人'},
          {'seq': 3, 'day': 1, 'phase': 'DAY_VOTE', 'type': 'VOTE_RESULT', 'actor': 2, 'target': 0, 'detail': '放逐出局'},
          {'seq': 4, 'day': 1, 'phase': 'DAY_VOTE', 'type': 'PLAYER_DIED', 'actor': 2, 'target': 0, 'detail': 'EXILE'},
          {'seq': 5, 'day': 1, 'phase': 'SHOOT', 'type': 'SHOOT', 'actor': 2, 'target': 0, 'detail': '放弃开枪'},
          {'seq': 6, 'day': 2, 'phase': 'GAME_OVER', 'type': 'GAME_OVER', 'actor': 0, 'target': 0, 'detail': 'winner=狼人'},
        ],
      };

  Future<AppStore> pump(WidgetTester tester, {Map<String, dynamic>? body, int status = 200}) async {
    SharedPreferences.setMockInitialValues({});
    final store = AppStore(ApiClient(
      ServerConfig(baseUrl: 'https://unit.test.invalid:11111'),
      client: MockClient((req) async => Response(
            jsonEncode(body ?? replayFixture()),
            status,
            headers: {'content-type': 'application/json'},
          )),
    ))
      ..user = {'id': 2180, 'username': 'winclient01', 'nickname': '三端自测'};
    tester.view.physicalSize = const Size(1200, 1600);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      child: MaterialApp(theme: werewolfTheme(), home: const ReplayScreen(gameId: 80)),
    ));
    await tester.pump(const Duration(milliseconds: 300));
    return store;
  }

  testWidgets('首屏直接把全部事件铺满（与网页版 replayStepAll 一致）', (tester) async {
    await pump(tester);
    expect(find.text('复盘 · 局 #80'), findsOneWidget);
    expect(find.textContaining('房间 384687 · 狼人阵营 胜'), findsOneWidget);
    expect(find.textContaining('身份：1:预言家'), findsOneWidget);
    // 7 帧全部可见，进度显示 7 / 7（行文案带座位前缀，所以只能 textContaining）
    expect(find.textContaining('1号：昨晚验了2号，是狼人'), findsOneWidget);
    expect(find.textContaining('本局结束：狼人胜'), findsOneWidget);
    expect(find.text('7 / 7'), findsOneWidget);
    expect(find.text('重播'), findsOneWidget, reason: '停在末帧时按钮文案应是「重播」');
  });

  testWidgets('座位条按帧重建：出局划掉并标死因、警长带冠', (tester) async {
    await pump(tester);
    // 2 号已出局（投票放逐），1 号是警长
    expect(find.text('👑1'), findsOneWidget);
    expect(find.textContaining('投票放逐'), findsWidgets);
    // 终局只剩 3 人在场：1/3/4 显示为阵营，2 显示死因
    expect(find.text('好人'), findsWidgets);
  });

  testWidgets('点播放会从头开始并由定时器逐帧推进', (tester) async {
    await pump(tester);
    await tester.tap(find.text('重播'));
    await tester.pump();
    // 刚按下：指针被重置，第一帧要等一个间隔
    expect(find.text('0 / 7'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.text('1 / 7'), findsOneWidget);
    await tester.pump(const Duration(milliseconds: 600));
    expect(find.text('2 / 7'), findsOneWidget);
    expect(find.text('暂停'), findsOneWidget);

    // 暂停后必须冻结在当前帧，不再被定时器推进
    await tester.tap(find.text('暂停'));
    await tester.pump();
    await tester.pump(const Duration(seconds: 3));
    expect(find.text('2 / 7'), findsOneWidget, reason: '暂停后定时器必须真的停下来');
  });

  testWidgets('下一帧 / 后退一帧 手动推进', (tester) async {
    await pump(tester);
    await tester.tap(find.text('重播'));
    await tester.pump(); // 不 pump 的话按钮文案还没重建
    // 播放键是 FilledButton.icon，只有 label 没有 tooltip（其余图标键才有）
    await tester.tap(find.text('暂停'));
    await tester.pump();
    await tester.tap(find.byTooltip('下一帧'));
    await tester.pump();
    expect(find.text('1 / 7'), findsOneWidget);
    await tester.tap(find.byTooltip('下一帧'));
    await tester.pump();
    expect(find.text('2 / 7'), findsOneWidget);
    await tester.tap(find.byTooltip('后退一帧'));
    await tester.pump();
    expect(find.text('1 / 7'), findsOneWidget);
  });

  testWidgets('拖动进度条可任意跳转', (tester) async {
    await pump(tester);
    final slider = find.byType(Slider);
    final box = tester.getRect(slider);
    // 拖到约 30% 处（7 帧 → 第 2 帧，显示 3 / 7）
    await tester.drag(slider, Offset(box.width * 0.30 - box.width * 0.5, 0));
    await tester.pump();
    final label = find.textContaining('/ 7');
    expect(label, findsOneWidget);
    expect(tester.widget<Text>(label).data, isNot('7 / 7'), reason: '拖动后不应还停在末帧');
  });

  testWidgets('走到最后一帧自动停表，不空转定时器', (tester) async {
    await pump(tester);
    await tester.tap(find.text('重播'));
    await tester.pump();
    for (var i = 0; i < 10; i++) {
      await tester.pump(const Duration(milliseconds: 600));
    }
    expect(find.text('7 / 7'), findsOneWidget);
    expect(find.text('重播'), findsOneWidget, reason: '到末尾后自动停表，按钮回到「重播」（再点则从头）');
    expect(find.text('暂停'), findsNothing);
  });

  testWidgets('切换速度档位立刻生效于后续间隔', (tester) async {
    await pump(tester);
    await tester.tap(find.text('中'));
    await tester.pump();
    await tester.tap(find.text('快'));
    await tester.pump();
    await tester.tap(find.text('重播'));
    await tester.pump();
    await tester.pump(const Duration(milliseconds: 300)); // 250ms 间隔应已推进一帧
    expect(find.text('1 / 7'), findsOneWidget);
  });

  testWidgets('服务端报错时显示中文错误而不是崩溃', (tester) async {
    await pump(tester, body: {'error': '无权查看该对局回放'}, status: 403);
    expect(find.textContaining('无权查看该对局回放'), findsOneWidget);
    expect(find.byType(Slider), findsNothing);
  });
}
