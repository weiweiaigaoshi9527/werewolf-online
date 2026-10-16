import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/appearance.dart';

/// 外观子系统（P2）纯逻辑回归：注册表、令牌合成、档位换算、持久化键名与非法值回落。
/// 真值来源是网页端 `static/js/appearance.js` 的 `WW_THEMES` / `WW_ACCENTS` 与 `static/js/app.js` 的 `THEME`。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
  });

  group('注册表', () {
    test('13 套主题，顺序与网页端 WW_THEMES 一致', () {
      expect(kWwThemes.length, 13);
      expect(kWwThemes.map((t) => t.id).toList(), [
        'night', 'day', 'glass', 'bloodmoon', 'deepsea', 'moss', 'neon',
        'parchment', 'slate', 'sakura', 'aurora', 'ember', 'mono',
      ]);
      expect(kWwThemes.map((t) => t.id).toSet().length, 13);
    });

    test('浅色主题只标 day / parchment / sakura，高对比只标 mono', () {
      expect(
        kWwThemes.where((t) => t.light).map((t) => t.id).toList(),
        ['day', 'parchment', 'sakura'],
      );
      expect(kWwThemes.where((t) => t.a11y).map((t) => t.id).toList(), ['mono']);
    });

    test('7 个强调色，auto 不带各自的主色（表示跟随主题）', () {
      expect(kWwAccents.length, 7);
      expect(kWwAccents.first.id, 'auto');
      expect(kWwAccents.first.main, isNull);
      for (final a in kWwAccents.skip(1)) {
        expect(a.main, isNotNull, reason: '${a.id} 应给出主色');
      }
    });
  });

  group('档位换算', () {
    test('字号 sm / md / lg → 0.9 / 1.0 / 1.15', () async {
      final app = Appearance();
      await app.setFont('sm');
      expect(app.textScale, 0.9);
      await app.setFont('md');
      expect(app.textScale, 1.0);
      await app.setFont('lg');
      expect(app.textScale, 1.15);
    });

    test('密度档位切换 compact / standard', () async {
      final app = Appearance();
      expect(app.dense, isFalse);
      expect(app.visualDensity, VisualDensity.standard);
      await app.toggleDensity();
      expect(app.dense, isTrue);
      expect(app.visualDensity, VisualDensity.compact);
    });

    test('非法字号忽略，保持原值', () async {
      final app = Appearance();
      await app.setFont('xxl');
      expect(app.textScale, 1.0);
    });
  });

  group('令牌合成', () {
    test('默认（night + auto）等于主题自身令牌', () {
      final c = Appearance().colors;
      expect(c.bg, const Color(0xFF090D18));
      expect(c.accent, const Color(0xFFE8A33D));
    });

    test('强调色只覆盖 accent 三项，其余令牌保持主题原值', () async {
      final app = Appearance();
      final base = app.colors;
      await app.setAccent('rose');
      final c = app.colors;
      expect(c.accent, const Color(0xFFE35D8A));
      expect(c.accentDim, const Color(0xFFB23F68));
      expect(c.accentSoft, const Color(0x26E35D8A));
      expect(c.bg, base.bg);
      expect(c.accent2, base.accent2);
      expect(c.blood, base.blood);
      expect(c.text, base.text);
    });

    test('切主题会带回其自身令牌与浅色标记', () async {
      final app = Appearance();
      await app.setTheme('day');
      expect(app.light, isTrue);
      expect(app.colors.panel, const Color(0xFFFFFFFF));
      await app.setTheme('bloodmoon');
      expect(app.light, isFalse);
      expect(app.colors.accent, const Color(0xFFE0526A));
    });

    test('非法主题 / 强调色 id 被忽略', () async {
      final app = Appearance();
      await app.setTheme('nope');
      expect(app.themeId, 'night');
      await app.setAccent('nope');
      expect(app.accentId, 'auto');
      await app.setTheme('night');
      expect(app.themeId, 'night');
    });
  });

  group('指纹与随机主题', () {
    test('任一档位变化都会改变指纹', () async {
      final app = Appearance();
      final f0 = app.fingerprint;
      await app.setTheme('neon');
      expect(app.fingerprint, isNot(f0));
      final f1 = app.fingerprint;
      await app.setAccent('mint');
      expect(app.fingerprint, isNot(f1));
      final f2 = app.fingerprint;
      await app.setFont('lg');
      expect(app.fingerprint, isNot(f2));
      final f3 = app.fingerprint;
      await app.toggleDensity();
      expect(app.fingerprint, isNot(f3));
      final f4 = app.fingerprint;
      await app.setMotion(false);
      expect(app.fingerprint, isNot(f4));
      final f5 = app.fingerprint;
      await app.setBright(0.7);
      expect(app.fingerprint, isNot(f5));
      final f6 = app.fingerprint;
      await app.setBgPath('/tmp/a.png');
      expect(app.fingerprint, isNot(f6));
    });

    test('randomTheme 不重复当前主题，并立即落盘', () async {
      final app = Appearance();
      final before = app.themeId;
      final t = await app.randomTheme();
      expect(t.id, isNot(before));
      expect(app.themeId, t.id);
      final p = await SharedPreferences.getInstance();
      expect(p.getString('ww_theme'), t.id);
    });
  });

  group('load 读取与回落', () {
    test('读到全部 ww_* 键', () async {
      SharedPreferences.setMockInitialValues({
        'ww_theme': 'bloodmoon',
        'ww_accent': 'mint',
        'ww_font': 'sm',
        'ww_density': 'dense',
        'ww_motion': 'off',
        'ww_bright': '1.4',
        'ww_bg': 'C:/bg/a.png',
      });
      final app = Appearance();
      await app.load();
      expect(app.themeId, 'bloodmoon');
      expect(app.accentId, 'mint');
      expect(app.font, 'sm');
      expect(app.dense, isTrue);
      expect(app.motion, isFalse);
      expect(app.bright, 1.4);
      expect(app.bgPath, 'C:/bg/a.png');
    });

    test('非法值一律回落默认', () async {
      SharedPreferences.setMockInitialValues({
        'ww_theme': 'nope',
        'ww_accent': 'nope',
        'ww_font': 'huge',
        'ww_bright': 'abc',
        'ww_bg': '',
      });
      final app = Appearance();
      await app.load();
      expect(app.themeId, 'night');
      expect(app.accentId, 'auto');
      expect(app.font, 'md');
      expect(app.bright, 1);
      expect(app.bgPath, isNull);
    });

    test('越界亮度被钳制到 0.4 ~ 1.6', () async {
      SharedPreferences.setMockInitialValues({'ww_bright': '9'});
      final app = Appearance();
      await app.load();
      expect(app.bright, 1.6);

      SharedPreferences.setMockInitialValues({'ww_bright': '0.05'});
      final app2 = Appearance();
      await app2.load();
      expect(app2.bright, 0.4);
    });

    test('setBright 也做钳制并落盘', () async {
      final app = Appearance();
      await app.setBright(5);
      expect(app.bright, 1.6);
      await app.setBright(0.1);
      expect(app.bright, 0.4);
    });
  });

  group('写盘编码与清背景', () {
    test('密度 / 动效沿用网页端的字符串枚举', () async {
      final app = Appearance();
      final p = await SharedPreferences.getInstance();
      await app.setDense(true);
      await app.setMotion(false);
      expect(p.getString('ww_density'), 'dense');
      expect(p.getString('ww_motion'), 'off');
      await app.toggleDensity();
      await app.setMotion(true);
      expect(p.getString('ww_density'), 'cozy');
      expect(p.getString('ww_motion'), 'on');
    });

    test('setBgPath 空值 / clearBg 清掉 ww_bg', () async {
      final app = Appearance();
      final p = await SharedPreferences.getInstance();
      await app.setBgPath('C:/bg/a.png');
      expect(p.getString('ww_bg'), 'C:/bg/a.png');
      await app.clearBg();
      expect(app.bgPath, isNull);
      expect(p.getString('ww_bg'), isNull);
      await app.setBgPath('');
      expect(app.bgPath, isNull);
      expect(p.getString('ww_bg'), isNull);
    });
  });

  test('debugReset 回到默认外观（指纹与全新实例一致）', () async {
    final app = Appearance();
    await app.setTheme('neon');
    await app.setAccent('rose');
    await app.setFont('sm');
    await app.toggleDensity();
    await app.setMotion(false);
    await app.setBright(1.5);
    await app.setBgPath('/tmp/a.png');
    app.debugReset();
    expect(app.fingerprint, Appearance().fingerprint);
  });
}
