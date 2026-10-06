import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/hotkeys.dart';
import 'package:werewolf_app/screens/game.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

import 'fixtures/server_views.dart';

/// 对局页桌面快捷键（工作流 B）回归。
///
/// 分两层：
/// 1) hotkeyFor 纯逻辑：把「按了什么键 + 当前上下文」翻成动作，逐分支覆盖（照搬 ux.js 的判定顺序）；
/// 2) GameScreen widget：真按键 → 真提交，验证门控、抑制与不误伤输入。
void main() {
  // ---------------- 1. hotkeyFor 纯逻辑 ----------------

  /// 便捷构造：默认「轮到自己、有 10 个目标、有放弃入口、未在输入、未按 Shift」。
  HotkeyHit? hit(
    String key, {
    bool shift = false,
    bool typing = false,
    bool canAct = true,
    int targetCount = 10,
    bool hasPass = true,
  }) =>
      hotkeyFor(
        key: key,
        shift: shift,
        typing: typing,
        canAct: canAct,
        targetCount: targetCount,
        hasPass: hasPass,
      );

  group('hotkeyFor 门控与顺序', () {
    test('Escape 一律交给框架（返回 null），其它条件不再判定', () {
      expect(hit('Escape'), isNull);
      expect(hit('escape'), isNull);
      // 即使满足帮助/过麦条件也不拦
      expect(hit('Escape', shift: true, canAct: true, hasPass: true), isNull);
    });

    test('正在输入时所有快捷键都不生效', () {
      for (final k in ['a', 'f', 'm', '1', '9', '0', '?', '/']) {
        expect(hit(k, typing: true), isNull, reason: 'typing 时应忽略 $k');
      }
    });

    test('? 或 Shift+/ 打开帮助', () {
      expect(hit('?'), const HotkeyHit(HotkeyAction.help));
      expect(hit('?', shift: true), const HotkeyHit(HotkeyAction.help));
      expect(hit('/', shift: true), const HotkeyHit(HotkeyAction.help));
    });

    test('单独的 / 不是快捷键', () {
      expect(hit('/'), isNull);
    });

    test('a 仅在 canAct && hasPass 时过麦', () {
      expect(hit('a'), const HotkeyHit(HotkeyAction.pass));
      expect(hit('A'), const HotkeyHit(HotkeyAction.pass)); // 大小写不敏感
      expect(hit('a', shift: true), const HotkeyHit(HotkeyAction.pass)); // Shift 对字母无意义
      expect(hit('a', hasPass: false), isNull);
      expect(hit('a', canAct: false), isNull);
      expect(hit('a', canAct: false, hasPass: false), isNull);
    });

    test('f 聚焦聊天、m 切麦克风：不受 canAct 门控', () {
      expect(hit('f'), const HotkeyHit(HotkeyAction.focusChat));
      expect(hit('F'), const HotkeyHit(HotkeyAction.focusChat));
      expect(hit('m'), const HotkeyHit(HotkeyAction.mic));
      expect(hit('M'), const HotkeyHit(HotkeyAction.mic));
      expect(hit('f', canAct: false), const HotkeyHit(HotkeyAction.focusChat));
      expect(hit('m', canAct: false), const HotkeyHit(HotkeyAction.mic));
    });

    test('1..9 映射到 0..8，且越界返回 null', () {
      expect(hit('1'), const HotkeyHit(HotkeyAction.target, index: 0));
      expect(hit('9'), const HotkeyHit(HotkeyAction.target, index: 8));
      expect(hit('3'), const HotkeyHit(HotkeyAction.target, index: 2));
      // 目标 4 个：'4' 命中下标 3，'5' 越界 → null
      expect(hit('4', targetCount: 4), const HotkeyHit(HotkeyAction.target, index: 3));
      expect(hit('5', targetCount: 4), isNull);
    });

    test('0 映射到下标 9，目标不足 10 个时返回 null', () {
      expect(hit('0', targetCount: 10), const HotkeyHit(HotkeyAction.target, index: 9));
      expect(hit('0', targetCount: 11), const HotkeyHit(HotkeyAction.target, index: 9));
      expect(hit('0', targetCount: 9), isNull);
      expect(hit('0', targetCount: 0), isNull);
    });

    test('canAct 为 false 时数字键全部失效', () {
      expect(hit('1', canAct: false), isNull);
      expect(hit('9', canAct: false), isNull);
      expect(hit('0', canAct: false), isNull);
    });

    test('未知键返回 null', () {
      for (final k in ['x', 'z', 'q', 'Enter', 'Tab', '', '12', 'a1']) {
        expect(hit(k), isNull, reason: '不应命中 $k');
      }
    });

    test('HotkeyHit 值相等与可读字符串', () {
      expect(const HotkeyHit(HotkeyAction.target, index: 2),
          const HotkeyHit(HotkeyAction.target, index: 2));
      expect(const HotkeyHit(HotkeyAction.target, index: 2),
          isNot(const HotkeyHit(HotkeyAction.target, index: 3)));
      expect(const HotkeyHit(HotkeyAction.pass).toString(), contains('pass'));
    });

    test('hotkeyHelp 非空且标注了网页独有的 T / D', () {
      expect(hotkeyHelp, isNotEmpty);
      expect(hotkeyHelp.map((e) => e.keys), contains('1 – 9 / 0'));
      // 必须显式说明哪些是网页独有、本端不适用
      expect(hotkeyHelp.any((e) => e.desc.contains('不适用')), isTrue);
      expect(hotkeyHelp.any((e) => e.keys.contains('T / D')), isTrue);
    });
  });

  // ---------------- 2. GameScreen widget ----------------

  /// 构造带 MockClient 的 store：捕获对 /api/game/action 的提交体，供断言。
  AppStore buildStore(List<Map<String, dynamic>> actions, {Map<String, dynamic>? game}) {
    SharedPreferences.setMockInitialValues({});
    final client = MockClient((req) async {
      if (req.url.path == '/api/game/action' && req.body.isNotEmpty) {
        actions.add(jsonDecode(req.body) as Map<String, dynamic>);
      }
      return http.Response('{}', 200);
    });
    final s = AppStore(ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111'), client: client));
    // 关掉音效，避免 play -> AudioPlayer() 触达 EventChannel（测试绑定下会异步抛错）。
    s.sfx.enabled = false;
    if (game != null) s.handleWsEvent(game);
    return s;
  }

  /// 白天发言态的对局快照；去掉身份牌以绕过翻牌揭示弹窗，让测试聚焦本页。
  Map<String, dynamic> dayGame({bool myTurn = true}) {
    final g = gameStateDay();
    g['myInfo'] = null;
    g['myTurn'] = myTurn;
    return g;
  }

  Future<void> pumpGame(WidgetTester tester, AppStore store) async {
    tester.view.physicalSize = const Size(1200, 2000);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(tester.view.reset);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      child: MaterialApp(theme: werewolfTheme(), home: const GameScreen()),
    ));
    // autofocus 的取焦在 post-frame 回调里完成，需要再 pump 一次。
    await tester.pump();
  }

  testWidgets('数字键按顺序选目标、A 过麦：提交与按钮一致的动作体', (tester) async {
    final actions = <Map<String, dynamic>>[];
    await pumpGame(tester, buildStore(actions, game: dayGame()));

    // 1 号是本人；存活且非本人的座位是 2 / 4 / 5 / 6 → 下标 0 对应 2 号
    await tester.sendKeyEvent(LogicalKeyboardKey.digit1);
    await tester.pump();
    expect(actions.last, {'target': 2});

    await tester.sendKeyEvent(LogicalKeyboardKey.digit2);
    await tester.pump();
    expect(actions.last, {'target': 4});

    // 发言阶段按 A = 过麦 → 发送「（过）」文本
    await tester.sendKeyEvent(LogicalKeyboardKey.keyA);
    await tester.pump();
    expect(actions.last, {'text': '（过）'});
  });

  testWidgets('Shift+/ 打开中文快捷键帮助弹窗', (tester) async {
    final actions = <Map<String, dynamic>>[];
    await pumpGame(tester, buildStore(actions, game: dayGame()));

    // 测试模拟器无法为 LogicalKeyboardKey.question 合成物理键，这里用 Shift+/ 触发；
    // 独立的 '?' 分支由 hotkeyFor 单测覆盖。
    await tester.sendKeyDownEvent(LogicalKeyboardKey.shiftLeft);
    await tester.sendKeyEvent(LogicalKeyboardKey.slash);
    await tester.sendKeyUpEvent(LogicalKeyboardKey.shiftLeft);
    await tester.pumpAndSettle();
    expect(find.text('⌨️ 快捷键'), findsOneWidget);
    expect(find.textContaining('过麦'), findsWidgets);

    await tester.tap(find.text('关闭'));
    await tester.pumpAndSettle();
    expect(find.text('⌨️ 快捷键'), findsNothing);
  });

  testWidgets('F 聚焦聊天框，聚焦后所有快捷键被抑制（不误伤打字）', (tester) async {
    final actions = <Map<String, dynamic>>[];
    await pumpGame(tester, buildStore(actions, game: dayGame()));

    // 初始焦点在整页 Focus 上，不在输入框内
    expect(
      FocusManager.instance.primaryFocus?.context
          ?.findAncestorWidgetOfExactType<EditableText>(),
      isNull,
    );

    await tester.sendKeyEvent(LogicalKeyboardKey.keyF);
    await tester.pump();
    // 焦点已进入某个输入框（EditableText 祖先存在）
    expect(
      FocusManager.instance.primaryFocus?.context
          ?.findAncestorWidgetOfExactType<EditableText>(),
      isNotNull,
    );

    // 聚焦输入框后：A / 数字键都不应再触发动作
    int before = actions.length;
    await tester.sendKeyEvent(LogicalKeyboardKey.keyA);
    await tester.sendKeyEvent(LogicalKeyboardKey.digit1);
    await tester.pump();
    expect(actions.length, before);
  });

  testWidgets('未轮到自己时数字键与 A 均不生效，但 F 仍可聚焦', (tester) async {
    final actions = <Map<String, dynamic>>[];
    await pumpGame(tester, buildStore(actions, game: dayGame(myTurn: false)));

    await tester.sendKeyEvent(LogicalKeyboardKey.digit1);
    await tester.sendKeyEvent(LogicalKeyboardKey.keyA);
    await tester.pump();
    expect(actions, isEmpty);

    await tester.sendKeyEvent(LogicalKeyboardKey.keyF);
    await tester.pump();
    expect(
      FocusManager.instance.primaryFocus?.context
          ?.findAncestorWidgetOfExactType<EditableText>(),
      isNotNull,
    );
  });
}
