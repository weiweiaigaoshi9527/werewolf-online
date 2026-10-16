import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/bgm.dart';
import 'package:werewolf_app/screens/bgm_panel.dart';

/// 背景音乐（P4）回归：下标推进规则、存档容错解析、列表增删与落盘、
/// 音量/循环默认值。真值来源是网页端 `static/js/extras.js` 的 BGM 模块。
///
/// 测试里一律 debugDisableAudio()，避免 AudioPlayer 触达 EventChannel
/// （测试绑定下会异步抛错，见 sfx 相关测试的同类处理）。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    bgm.debugReset();
    bgm.debugDisableAudio();
  });

  group('下标推进（纯函数）', () {
    test('next/prev 绕环，空列表返回 -1', () {
      expect(bgmNextIndex(0, 3), 1);
      expect(bgmNextIndex(2, 3), 0);
      expect(bgmPrevIndex(0, 3), 2);
      expect(bgmPrevIndex(2, 3), 1);
      expect(bgmNextIndex(0, 0), -1);
      expect(bgmPrevIndex(-1, 0), -1);
    });

    test('播完一曲：循环绕回 0，不循环则停在 -1', () {
      expect(bgmAdvanceAfterEnd(0, 3, true), 1);
      expect(bgmAdvanceAfterEnd(2, 3, true), 0);
      expect(bgmAdvanceAfterEnd(2, 3, false), -1);
      expect(bgmAdvanceAfterEnd(0, 1, true), 0);
      expect(bgmAdvanceAfterEnd(0, 0, true), -1);
    });
  });

  group('存档解析', () {
    test('字段齐全才收，缺字段/类型不符/空 path 一律丢弃', () {
      final ok = BgmTrack.fromJson({'id': 'a', 'name': 'x.mp3', 'path': r'C:\x.mp3'});
      expect(ok, isNotNull);
      expect(ok!.name, 'x.mp3');
      expect(BgmTrack.fromJson({'id': 'a', 'name': 'x.mp3'}), isNull); // 缺 path
      expect(BgmTrack.fromJson({'id': 1, 'name': 'x', 'path': 'y'}), isNull); // 类型不符
      expect(BgmTrack.fromJson({'id': 'a', 'name': 'x', 'path': ''}), isNull); // 空 path
      expect(BgmTrack.fromJson('nope'), isNull);
    });
  });

  group('播放列表', () {
    test('默认音量 0.6、循环勾选、无当前曲目', () {
      expect(bgm.volume, 0.6);
      expect(bgm.loop, isTrue);
      expect(bgm.cur, -1);
      expect(bgm.playing, isFalse);
      expect(bgm.isEmpty, isTrue);
    });

    test('添加文件后落盘到 ww_bgm，并把首曲设为当前项', () async {
      final n = await bgm.addFiles([r'C:\a.mp3', r'C:\b.mp3'], ['a.mp3', 'b.mp3']);
      expect(n, 2);
      expect(bgm.tracks.length, 2);
      expect(bgm.cur, 0); // 此前什么都没在播 → 自动从第 1 首开始

      final p = await SharedPreferences.getInstance();
      final raw = p.getString('ww_bgm');
      expect(raw, isNotNull);
      final list = jsonDecode(raw!) as List;
      expect(list.length, 2);
      expect((list[0] as Map)['name'], 'a.mp3');
      expect((list[0] as Map)['path'], r'C:\a.mp3');
    });

    test('空路径列表不加任何东西', () async {
      expect(await bgm.addFiles(const [], const []), 0);
      expect(bgm.isEmpty, isTrue);
      expect(bgm.cur, -1);
    });

    test('移除当前曲目会停播并清空下标，其他曲目只减一条', () async {
      await bgm.addFiles([r'C:\a.mp3', r'C:\b.mp3'], ['a.mp3', 'b.mp3']);
      final first = bgm.tracks.first.id;
      await bgm.remove(first);
      expect(bgm.tracks.length, 1);
      expect(bgm.cur, -1);
      expect(bgm.playing, isFalse);

      final last = bgm.tracks.first.id;
      await bgm.remove(last);
      expect(bgm.isEmpty, isTrue);
      expect(bgm.cur, -1);
    });

    test('load 读回已保存列表（不自动播放）', () async {
      SharedPreferences.setMockInitialValues({
        'ww_bgm': jsonEncode([
          {'id': '1', 'name': 'a.mp3', 'path': r'C:\a.mp3'},
          {'bad': true},
        ]),
      });
      final b = Bgm()..debugDisableAudio();
      await b.load();
      expect(b.tracks.length, 1); // 坏记录被丢掉
      expect(b.tracks.first.name, 'a.mp3');
      expect(b.cur, -1);
      expect(b.playing, isFalse);
    });

    test('音量 clamp 到 0..1；循环开关幂等', () async {
      await bgm.setVolume(1.7);
      expect(bgm.volume, 1.0);
      await bgm.setVolume(-0.3);
      expect(bgm.volume, 0.0);

      var notified = 0;
      bgm.addListener(() => notified++);
      bgm.setLoop(false);
      expect(bgm.loop, isFalse);
      bgm.setLoop(false); // 相同值不再通知
      expect(notified, 1);
    });

    test('列表为空时 toggle 返回 false（调用方据此提示先添加音乐）', () async {
      expect(await bgm.toggle(), isFalse);
      expect(bgm.playing, isFalse);
    });
  });

  group('面板', () {
    testWidgets('空列表显示引导文案', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BgmPanel())));
      expect(find.text('还没有添加音乐，选几首喜欢的 MP3 吧'), findsOneWidget);
    });

    testWidgets('有曲目时列出文件名，当前项带 ▶ 前缀', (tester) async {
      await bgm.addFiles([r'C:\a.mp3', r'C:\b.mp3'], ['a.mp3', 'b.mp3']);
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BgmPanel())));
      expect(find.text('▶ a.mp3'), findsOneWidget);
      expect(find.text('b.mp3'), findsOneWidget);
      expect(find.text('还没有添加音乐，选几首喜欢的 MP3 吧'), findsNothing);
    });

    testWidgets('空列表点播放会提示先添加音乐', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: BgmPanel())));
      await tester.tap(find.byIcon(Icons.play_circle));
      await tester.pump();
      expect(find.text('先添加几首本地 MP3 吧'), findsOneWidget);
    });
  });
}
