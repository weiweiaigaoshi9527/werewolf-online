import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/i18n.dart';

/// 多语言子系统（P3）纯逻辑回归：语言注册表、精确整串翻译、正则兜底、
/// 词典合并（主表 + 增量 + 骨架 + 客户端补充）、持久化键名与非法值回落。
/// 真值来源是网页端 `static/js/i18n.js` 的 `I18N_TEXT` / `I18N_PATTERNS`。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    i18n.debugReset();
  });

  group('语言注册表', () {
    test('5 种语言，顺序与网页端一致，代码唯一', () {
      expect(kLangMeta.map((m) => m[0]).toList(), ['zh', 'en', 'ja', 'ko', 'ru']);
      expect(kLangMeta.map((m) => m[0]).toSet().length, 5);
      for (final m in kLangMeta) {
        expect(m.length, 3, reason: '${m[0]} 应有 代码/母语名/英文名');
      }
    });

    test('每种语言都有完整词典（含客户端补充表）', () {
      for (final code in ['en', 'ja', 'ko', 'ru']) {
        final d = kI18nDict[code];
        expect(d, isNotNull, reason: '$code 缺词典');
        expect(d!.length, greaterThan(300), reason: '$code 词条过少');
      }
      // zh 走原文直出，词典表为空（tr 在 lang=='zh' 时提前返回）
      expect(kI18nDict['zh'], isEmpty);
    });
  });

  group('翻译', () {
    test('中文直出，不做任何改动', () {
      expect(tr('房间号 '), '房间号 ');
      expect(tr('后台管理'), '后台管理');
    });

    test('英文：精确整串命中主表', () async {
      await i18n.setLang('en');
      expect(tr('房间号 '), 'Room code ');
      expect(tr('等待中'), 'Waiting');
    });

    test('英文：命中客户端补充表（网页端 i18n.js 没有的词）', () async {
      await i18n.setLang('en');
      expect(tr('后台管理'), 'Admin');
      expect(tr('删除'), 'Delete');
      expect(tr('创建'), 'Create');
    });

    test('四种语言都能命中同一词条', () async {
      const expectMap = {'en': 'Waiting', 'ja': '待機中', 'ko': '대기 중', 'ru': 'Ожидание'};
      for (final e in expectMap.entries) {
        await i18n.setLang(e.key);
        expect(tr('等待中'), e.value, reason: e.key);
      }
    });

    test('正则兜底：词典外但符合模式的中文也能翻', () async {
      await i18n.setLang('en');
      expect(tr('第 3 天'), 'Day 3');
      expect(tr('Lv.5 · 在线'), 'Lv.5 · Online');
      expect(tr('Lv.7 · 离线'), 'Lv.7 · Offline');
      expect(tr('已连续12天'), 'Streak 12 days');
      expect(tr('· 更新于 2026-10-16'), '· Updated 2026-10-16');
    });

    test('词典与模式都不命中时原样返回中文（对局内容天然不受影响）', () async {
      await i18n.setLang('en');
      expect(tr('狼人请睁眼'), '狼人请睁眼'); // 旁白/身份名不进词典
      expect(tr('第3天'), '第3天'); // 无空格，不匹配 ^第 (\d+) 天$
      expect(tr('Alice'), 'Alice'); // 昵称
      expect(tr(''), '');
    });
  });

  group('状态与持久化', () {
    test('setLang 落盘到 ww_lang 并通知监听者', () async {
      SharedPreferences.setMockInitialValues({});
      var notified = 0;
      i18n.addListener(() => notified++);
      await i18n.setLang('ja');
      expect(i18n.lang, 'ja');
      expect(notified, 1);
      final p = await SharedPreferences.getInstance();
      expect(p.getString('ww_lang'), 'ja');
    });

    test('setLang 相同语言或非法代码不生效', () async {
      var notified = 0;
      i18n.addListener(() => notified++);
      await i18n.setLang('zh'); // 已是 zh
      await i18n.setLang('xx'); // 非法
      expect(i18n.lang, 'zh');
      expect(notified, 0);
    });

    test('load 读回已保存语言', () async {
      SharedPreferences.setMockInitialValues({'ww_lang': 'ru'});
      await i18n.load();
      expect(i18n.lang, 'ru');
      expect(i18n.nativeName, 'Русский');
    });

    test('load 遇到非法值时回落中文', () async {
      SharedPreferences.setMockInitialValues({'ww_lang': 'klingon'});
      await i18n.load();
      expect(i18n.lang, 'zh');
      expect(i18n.isZh, isTrue);
    });
  });

  group('Tx 组件', () {
    testWidgets('Tx 渲染译文，切语言后重挂即换语言', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Tx('等待中'))));
      expect(find.text('等待中'), findsOneWidget);

      await i18n.setLang('en');
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: Tx('等待中'))));
      expect(find.text('Waiting'), findsOneWidget);
    });

    testWidgets('Tx 支持 Text 的常用参数（style/maxLines/overflow）', (tester) async {
      await tester.pumpWidget(const MaterialApp(
        home: Scaffold(
          body: Tx('房间号 ',
              maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 20)),
        ),
      ));
      final t = tester.widget<Text>(find.byType(Text));
      expect(t.maxLines, 1);
      expect(t.overflow, TextOverflow.ellipsis);
      expect(t.style?.fontSize, 20);
    });

    testWidgets('LangButton 打开弹层并可切换语言', (tester) async {
      await tester.pumpWidget(const MaterialApp(home: Scaffold(body: LangButton())));
      await tester.tap(find.byIcon(Icons.language));
      await tester.pumpAndSettle();
      // 弹层列出 5 种语言的母语名
      expect(find.text('中文'), findsOneWidget);
      expect(find.text('日本語'), findsOneWidget);
      expect(find.text('Русский'), findsOneWidget);

      // 'English' 作为标题与副标题各出现一次（候选歧义），改点唯一项 Русский
      await tester.tap(find.text('Русский'));
      await tester.pumpAndSettle();
      expect(i18n.lang, 'ru');
    });
  });
}
