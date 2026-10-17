/// 外观子系统：主题 / 强调色 / 字号 / 密度 / 动效 / 背景亮度 / 自定义背景。
///
/// 与网页端完全对齐（见 static/js/appearance.js 的 `WW_THEMES` / `WW_ACCENTS`、
/// static/css/themes.css 与 style.css 的 CSS 变量令牌、static/js/app.js 的 `THEME`），
/// 连持久化键名都沿用同一套 `ww_*`：
///   ww_theme / ww_accent / ww_font / ww_density / ww_motion / ww_bg / ww_bright。
/// 这样同一台设备上网页端与客户端的外观选择可以互相“继承”（同一 origin 的
/// localStorage 与客户端 SharedPreferences 并不共享，键名一致只为语义对照）。
library;

import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// 一套主题的颜色令牌，字段与网页端 CSS 变量一一对应：
/// bg=--bg-deep / bgVoid=--bg-void / panel=--bg-panel / panel2=--bg-panel-2 /
/// raise=--bg-raise / border=--border-dim / borderSoft=--border-soft /
/// text=--text-main / dim=--text-dim / faint=--text-faint /
/// accent=--accent / accentDim=--accent-dim / accentSoft=--accent-soft /
/// accent2=--accent-2 / blood=--blood / moon=--moon / green=--green。
class WwPalette {
  final Color bg;
  final Color bgVoid;
  final Color panel;
  final Color panel2;
  final Color raise;
  final Color border;
  final Color borderSoft;
  final Color text;
  final Color dim;
  final Color faint;
  final Color accent;
  final Color accentDim;
  final Color accentSoft;
  final Color accent2;
  final Color blood;
  final Color moon;
  final Color green;

  const WwPalette({
    required this.bg,
    required this.bgVoid,
    required this.panel,
    required this.panel2,
    required this.raise,
    required this.border,
    required this.borderSoft,
    required this.text,
    required this.dim,
    required this.faint,
    required this.accent,
    required this.accentDim,
    required this.accentSoft,
    required this.accent2,
    required this.blood,
    required this.moon,
    required this.green,
  });

  /// 只替换强调色三项（网页端强调色与主题正交，`auto` 时不覆盖）。
  WwPalette withAccent(Color main, Color dim, Color soft) => WwPalette(
        bg: bg,
        bgVoid: bgVoid,
        panel: panel,
        panel2: panel2,
        raise: raise,
        border: border,
        borderSoft: borderSoft,
        text: text,
        dim: dim,
        faint: faint,
        accent: main,
        accentDim: dim,
        accentSoft: soft,
        accent2: accent2,
        blood: blood,
        moon: moon,
        green: green,
      );
}

/// 一套主题：注册表条目 + 它的颜色令牌。
class WwThemeDef {
  final String id;
  final String name;
  final String icon;

  /// 浅色主题（供遮罩/边框等“按明暗而非按主题”处理的场景使用）。
  final bool light;

  /// 无障碍高对比主题。
  final bool a11y;

  /// 面板上的三色预览（背景 / 强调 / 次强调）。
  final List<Color> swatch;
  final WwPalette palette;

  const WwThemeDef({
    required this.id,
    required this.name,
    required this.icon,
    this.light = false,
    this.a11y = false,
    required this.swatch,
    required this.palette,
  });
}

/// 夜间（默认）令牌，取自 style.css 的 `:root`。
const _night = WwPalette(
  bg: Color(0xFF090D18),
  bgVoid: Color(0xFF05080F),
  panel: Color(0xFF141A2E),
  panel2: Color(0xFF1A2238),
  raise: Color(0xFF202A45),
  border: Color(0xFF2A3350),
  borderSoft: Color(0xFF232C47),
  text: Color(0xFFCDD6F4),
  dim: Color(0xFF8B96B8),
  faint: Color(0xFF5B6684),
  accent: Color(0xFFE8A33D),
  accentDim: Color(0xFFA9782C),
  accentSoft: Color(0x24E8A33D),
  accent2: Color(0xFF8B93FF),
  blood: Color(0xFFA4243B),
  moon: Color(0xFFE3E8FF),
  green: Color(0xFF4F9D69),
);

/// 13 套主题，顺序与网页端 `WW_THEMES` 完全一致（面板按同序铺格子）。
const List<WwThemeDef> kWwThemes = [
  WwThemeDef(
    id: 'night', name: '月夜', icon: '🌙', palette: _night,
    swatch: [Color(0xFF090D18), Color(0xFFE8A33D), Color(0xFF8B93FF)],
  ),
  WwThemeDef(
    id: 'day', name: '白昼', icon: '☀️', light: true,
    swatch: [Color(0xFFEEF1F8), Color(0xFFB6741A), Color(0xFF5560C8)],
    palette: WwPalette(
      bg: Color(0xFFEEF1F8), bgVoid: Color(0xFFE3E8F4),
      panel: Color(0xFFFFFFFF), panel2: Color(0xFFF6F8FD), raise: Color(0xFFEAEEFB),
      border: Color(0xFFC7D0E4), borderSoft: Color(0xFFDBE2F0),
      text: Color(0xFF1D2437), dim: Color(0xFF5A6480), faint: Color(0xFF8B95AE),
      accent: Color(0xFFB6741A), accentDim: Color(0xFFC98A2E), accentSoft: Color(0x1FB6741A),
      accent2: Color(0xFF5560C8), blood: Color(0xFFB3213A), moon: Color(0xFF33406E),
      green: Color(0xFF2F7F4D),
    ),
  ),
  WwThemeDef(
    id: 'glass', name: '液态玻璃', icon: '🔮',
    swatch: [Color(0xFF070B16), Color(0xFFE8A33D), Color(0xFFA0B4EB)],
    // 网页端玻璃面板是半透明 rgba，这里按其叠在 bg 上的合成结果取等效实色。
    palette: WwPalette(
      bg: Color(0xFF070B16), bgVoid: Color(0xFF05080F),
      panel: Color(0xFF101628), panel2: Color(0xFF131A2D), raise: Color(0xFF1B243B),
      border: Color(0xFF293045), borderSoft: Color(0xFF1E2436),
      text: Color(0xFFCDD6F4), dim: Color(0xFF8B96B8), faint: Color(0xFF5B6684),
      accent: Color(0xFFE8A33D), accentDim: Color(0xFFA9782C), accentSoft: Color(0x24E8A33D),
      accent2: Color(0xFFA0B4EB), blood: Color(0xFFA4243B), moon: Color(0xFFE3E8FF),
      green: Color(0xFF4F9D69),
    ),
  ),
  WwThemeDef(
    id: 'bloodmoon', name: '血月', icon: '🩸',
    swatch: [Color(0xFF150A10), Color(0xFFE0526A), Color(0xFFF0B06B)],
    palette: WwPalette(
      bg: Color(0xFF150A10), bgVoid: Color(0xFF0C0509),
      panel: Color(0xFF24131C), panel2: Color(0xFF2D1822), raise: Color(0xFF3A202B),
      border: Color(0xFF4A2733), borderSoft: Color(0xFF3A1F29),
      text: Color(0xFFF2DFE4), dim: Color(0xFFB98D99), faint: Color(0xFF7D5A64),
      accent: Color(0xFFE0526A), accentDim: Color(0xFFA83A4C), accentSoft: Color(0x29E0526A),
      accent2: Color(0xFFF0B06B), blood: Color(0xFFFF4D6D), moon: Color(0xFFFFD9DF),
      green: Color(0xFF5FAE7C),
    ),
  ),
  WwThemeDef(
    id: 'deepsea', name: '深海', icon: '🌊',
    swatch: [Color(0xFF04121C), Color(0xFF34C3D9), Color(0xFF7AA8FF)],
    palette: WwPalette(
      bg: Color(0xFF04121C), bgVoid: Color(0xFF020A11),
      panel: Color(0xFF0B2434), panel2: Color(0xFF0F2D40), raise: Color(0xFF163A51),
      border: Color(0xFF1D4A64), borderSoft: Color(0xFF173C52),
      text: Color(0xFFD5ECF5), dim: Color(0xFF7FA9BD), faint: Color(0xFF4F7A8F),
      accent: Color(0xFF34C3D9), accentDim: Color(0xFF2295A8), accentSoft: Color(0x2634C3D9),
      accent2: Color(0xFF7AA8FF), blood: Color(0xFFE05A6B), moon: Color(0xFFDFF6FF),
      green: Color(0xFF45B98A),
    ),
  ),
  WwThemeDef(
    id: 'moss', name: '苔原', icon: '🌿',
    swatch: [Color(0xFF0C1410), Color(0xFFD8B25C), Color(0xFF7FD0A8)],
    palette: WwPalette(
      bg: Color(0xFF0C1410), bgVoid: Color(0xFF060C09),
      panel: Color(0xFF17251D), panel2: Color(0xFF1C2D24), raise: Color(0xFF253A2E),
      border: Color(0xFF2C4739), borderSoft: Color(0xFF23392E),
      text: Color(0xFFDBEADD), dim: Color(0xFF8BAB96), faint: Color(0xFF5B7A68),
      accent: Color(0xFFD8B25C), accentDim: Color(0xFFA98A3F), accentSoft: Color(0x26D8B25C),
      accent2: Color(0xFF7FD0A8), blood: Color(0xFFC9524F), moon: Color(0xFFEAF5E6),
      green: Color(0xFF56B87D),
    ),
  ),
  WwThemeDef(
    id: 'neon', name: '赛博霓虹', icon: '🛰️',
    swatch: [Color(0xFF07060F), Color(0xFFFF3FA4), Color(0xFF22E0FF)],
    palette: WwPalette(
      bg: Color(0xFF07060F), bgVoid: Color(0xFF030209),
      panel: Color(0xFF140F26), panel2: Color(0xFF1A1330), raise: Color(0xFF241A42),
      border: Color(0xFF37275F), borderSoft: Color(0xFF2A1E4B),
      text: Color(0xFFE6E2FF), dim: Color(0xFF9C8FD0), faint: Color(0xFF6A5F95),
      accent: Color(0xFFFF3FA4), accentDim: Color(0xFFC62C7D), accentSoft: Color(0x29FF3FA4),
      accent2: Color(0xFF22E0FF), blood: Color(0xFFFF2D55), moon: Color(0xFFF2E9FF),
      green: Color(0xFF2EE6A8),
    ),
  ),
  WwThemeDef(
    id: 'parchment', name: '羊皮纸', icon: '📜', light: true,
    swatch: [Color(0xFFF2E7D0), Color(0xFF9A5B1E), Color(0xFF4A6B8A)],
    palette: WwPalette(
      bg: Color(0xFFF2E7D0), bgVoid: Color(0xFFE6D8BA),
      panel: Color(0xFFFBF5E6), panel2: Color(0xFFF6EDD8), raise: Color(0xFFEFE2C6),
      border: Color(0xFFD3BF95), borderSoft: Color(0xFFE0D0AC),
      text: Color(0xFF3B2F1C), dim: Color(0xFF6D5B3E), faint: Color(0xFF96835F),
      accent: Color(0xFF9A5B1E), accentDim: Color(0xFFB07334), accentSoft: Color(0x1F9A5B1E),
      accent2: Color(0xFF4A6B8A), blood: Color(0xFF9E2B2B), moon: Color(0xFF4A3C25),
      green: Color(0xFF3F7A4A),
    ),
  ),
  WwThemeDef(
    id: 'slate', name: '石墨', icon: '🪨',
    swatch: [Color(0xFF14161A), Color(0xFF5AA9FF), Color(0xFFA4B6CF)],
    palette: WwPalette(
      bg: Color(0xFF14161A), bgVoid: Color(0xFF0D0E11),
      panel: Color(0xFF1F2329), panel2: Color(0xFF262B32), raise: Color(0xFF313741),
      border: Color(0xFF3A424E), borderSoft: Color(0xFF2E353F),
      text: Color(0xFFDFE4EC), dim: Color(0xFF939DAB), faint: Color(0xFF646D7A),
      accent: Color(0xFF5AA9FF), accentDim: Color(0xFF3F82C9), accentSoft: Color(0x265AA9FF),
      accent2: Color(0xFFA4B6CF), blood: Color(0xFFE05A6B), moon: Color(0xFFEAF1FB),
      green: Color(0xFF4FB98A),
    ),
  ),
  WwThemeDef(
    id: 'sakura', name: '樱粉', icon: '🌸', light: true,
    swatch: [Color(0xFFFBF1F4), Color(0xFFD9527F), Color(0xFF8A6FD0)],
    palette: WwPalette(
      bg: Color(0xFFFBF1F4), bgVoid: Color(0xFFF4E2E8),
      panel: Color(0xFFFFFFFF), panel2: Color(0xFFFDF3F6), raise: Color(0xFFF8E4EA),
      border: Color(0xFFECCDD6), borderSoft: Color(0xFFF3DEE4),
      text: Color(0xFF3C2A30), dim: Color(0xFF7C5F68), faint: Color(0xFFA88D95),
      accent: Color(0xFFD9527F), accentDim: Color(0xFFE0709A), accentSoft: Color(0x1FD9527F),
      accent2: Color(0xFF8A6FD0), blood: Color(0xFFC33A5E), moon: Color(0xFF5A3F48),
      green: Color(0xFF46996A),
    ),
  ),
  WwThemeDef(
    id: 'aurora', name: '极光', icon: '🌌',
    swatch: [Color(0xFF060D18), Color(0xFF45E0B8), Color(0xFF9B7DFF)],
    palette: WwPalette(
      bg: Color(0xFF060D18), bgVoid: Color(0xFF03070F),
      panel: Color(0xFF0F1C30), panel2: Color(0xFF13243C), raise: Color(0xFF1B3050),
      border: Color(0xFF24405F), borderSoft: Color(0xFF1C3350),
      text: Color(0xFFDCEAF6), dim: Color(0xFF86A5C0), faint: Color(0xFF557494),
      accent: Color(0xFF45E0B8), accentDim: Color(0xFF2FB192), accentSoft: Color(0x2445E0B8),
      accent2: Color(0xFF9B7DFF), blood: Color(0xFFE05A7A), moon: Color(0xFFE6F6FF),
      green: Color(0xFF45E0B8),
    ),
  ),
  WwThemeDef(
    id: 'ember', name: '炭火', icon: '🔥',
    swatch: [Color(0xFF120C08), Color(0xFFFF8B3D), Color(0xFFFFD166)],
    palette: WwPalette(
      bg: Color(0xFF120C08), bgVoid: Color(0xFF0A0604),
      panel: Color(0xFF1E140E), panel2: Color(0xFF261912), raise: Color(0xFF33231A),
      border: Color(0xFF40291D), borderSoft: Color(0xFF322017),
      text: Color(0xFFF0E2D5), dim: Color(0xFFAD9280), faint: Color(0xFF7A6252),
      accent: Color(0xFFFF8B3D), accentDim: Color(0xFFCC6A28), accentSoft: Color(0x26FF8B3D),
      accent2: Color(0xFFFFD166), blood: Color(0xFFE04A3D), moon: Color(0xFFFFE9D5),
      green: Color(0xFF6FAE6A),
    ),
  ),
  WwThemeDef(
    id: 'mono', name: '高对比', icon: '◐', a11y: true,
    swatch: [Color(0xFF000000), Color(0xFFFFD400), Color(0xFF66E0FF)],
    palette: WwPalette(
      bg: Color(0xFF000000), bgVoid: Color(0xFF000000),
      panel: Color(0xFF0A0A0A), panel2: Color(0xFF141414), raise: Color(0xFF1F1F1F),
      border: Color(0xFF6E6E6E), borderSoft: Color(0xFF4A4A4A),
      text: Color(0xFFFFFFFF), dim: Color(0xFFD4D4D4), faint: Color(0xFFABABAB),
      accent: Color(0xFFFFD400), accentDim: Color(0xFFFFE066), accentSoft: Color(0x2EFFD400),
      accent2: Color(0xFF66E0FF), blood: Color(0xFFFF6B6B), moon: Color(0xFFFFFFFF),
      green: Color(0xFF5CFF9D),
    ),
  ),
];

/// 强调色预设（与主题正交）。`auto` 表示跟随主题自身令牌。
class WwAccent {
  final String id;
  final String name;
  final Color? main;
  final Color? dim;
  final Color? soft;

  const WwAccent(this.id, this.name, {this.main, this.dim, this.soft});
}

const List<WwAccent> kWwAccents = [
  WwAccent('auto', '自动'),
  WwAccent('amber', '琥珀', main: Color(0xFFE8A33D), dim: Color(0xFFA9782C), soft: Color(0x24E8A33D)),
  WwAccent('coral', '珊瑚', main: Color(0xFFFF6B57), dim: Color(0xFFC9463A), soft: Color(0x26FF6B57)),
  WwAccent('rose', '玫瑰', main: Color(0xFFE35D8A), dim: Color(0xFFB23F68), soft: Color(0x26E35D8A)),
  WwAccent('violet', '紫罗兰', main: Color(0xFF9B7DFF), dim: Color(0xFF6F52C9), soft: Color(0x299B7DFF)),
  WwAccent('azure', '天青', main: Color(0xFF4AA3FF), dim: Color(0xFF2F77C9), soft: Color(0x264AA3FF)),
  WwAccent('mint', '薄荷', main: Color(0xFF45E0B8), dim: Color(0xFF2FB192), soft: Color(0x2645E0B8)),
];

/// 外观状态（全局单例，见文件末尾 [appearance]）。
///
/// 读写与网页端 THEME 一致：任何一处改动都立即落盘（SharedPreferences，键名 `ww_*`）
/// 并通知监听者重建界面。颜色令牌由 [colors] 计算得出（主题令牌 + 可选强调色覆盖）。
class Appearance extends ChangeNotifier {
  static const _kTheme = 'ww_theme';
  static const _kAccent = 'ww_accent';
  static const _kFont = 'ww_font';
  static const _kDensity = 'ww_density';
  static const _kMotion = 'ww_motion';
  static const _kBg = 'ww_bg';
  static const _kBright = 'ww_bright';

  String _themeId = 'night';
  String _accentId = 'auto';
  String _font = 'md'; // sm | md | lg
  bool _dense = false;
  bool _motion = true;
  double _bright = 1; // 0.4 ~ 1.6
  String? _bgPath; // 自定义背景（桌面/移动：本地图片绝对路径）
  Uint8List? _bgBytes; // 自定义背景（Web：file_picker 回传的图片字节，浏览器不给本地路径）

  String get themeId => _themeId;
  String get accentId => _accentId;
  String get font => _font;
  bool get dense => _dense;
  bool get motion => _motion;
  double get bright => _bright;
  String? get bgPath => _bgPath;
  Uint8List? get bgBytes => _bgBytes;

  WwThemeDef get themeDef =>
      kWwThemes.firstWhere((t) => t.id == _themeId, orElse: () => kWwThemes.first);

  bool get light => themeDef.light;

  /// 生效颜色 = 主题令牌（强调色非 auto 时覆盖 accent / accentDim / accentSoft）。
  WwPalette get colors {
    final base = themeDef.palette;
    final a = kWwAccents.firstWhere((x) => x.id == _accentId, orElse: () => kWwAccents.first);
    if (a.main == null) return base;
    return base.withAccent(a.main!, a.dim ?? a.main!, a.soft ?? base.accentSoft);
  }

  /// 字号档位换算成文本缩放（网页端 fs-sm / fs-lg 覆盖 --fs-* 令牌）。
  double get textScale => switch (_font) {
        'sm' => 0.9,
        'lg' => 1.15,
        _ => 1.0,
      };

  /// 密度档位（网页端 body.dense 收窄间距与圆角）。
  VisualDensity get visualDensity => _dense ? VisualDensity.compact : VisualDensity.standard;

  /// 外观指纹：用来在切换时强制整棵界面重建（颜色令牌是全局单例，不重挂会取到旧值）。
  String get fingerprint => '$_themeId|$_accentId|$_font|$_dense|$_motion|${_bright.toStringAsFixed(3)}|${_bgPath ?? ''}';

  /// 从 SharedPreferences 读取（键名与网页端一致）。任何异常都退回默认外观。
  Future<void> load() async {
    try {
      final p = await SharedPreferences.getInstance();
      _themeId = p.getString(_kTheme) ?? 'night';
      if (!kWwThemes.any((t) => t.id == _themeId)) _themeId = 'night';
      _accentId = p.getString(_kAccent) ?? 'auto';
      if (!kWwAccents.any((a) => a.id == _accentId)) _accentId = 'auto';
      final f = p.getString(_kFont);
      _font = (f == 'sm' || f == 'lg') ? f! : 'md';
      _dense = p.getString(_kDensity) == 'dense';
      _motion = p.getString(_kMotion) != 'off';
      _bright = double.tryParse(p.getString(_kBright) ?? '') ?? 1;
      _bright = _bright.clamp(0.4, 1.6);
      // 空串与缺失一律视作「没有自定义背景」，与 setBgPath 的归一化保持一致。
      final bg = p.getString(_kBg);
      _bgPath = (bg == null || bg.isEmpty) ? null : bg;
      notifyListeners();
    } catch (_) {}
  }

  Future<void> _save(String key, String? value) async {
    try {
      final p = await SharedPreferences.getInstance();
      if (value == null) {
        await p.remove(key);
      } else {
        await p.setString(key, value);
      }
    } catch (_) {}
  }

  Future<void> setTheme(String id) async {
    if (!kWwThemes.any((t) => t.id == id) || id == _themeId) return;
    _themeId = id;
    notifyListeners();
    await _save(_kTheme, id);
  }

  Future<void> setAccent(String id) async {
    if (!kWwAccents.any((a) => a.id == id) || id == _accentId) return;
    _accentId = id;
    notifyListeners();
    await _save(_kAccent, id);
  }

  Future<void> setFont(String f) async {
    if (f != 'sm' && f != 'md' && f != 'lg') return;
    _font = f;
    notifyListeners();
    await _save(_kFont, f);
  }

  Future<void> setDense(bool v) async {
    _dense = v;
    notifyListeners();
    await _save(_kDensity, v ? 'dense' : 'cozy');
  }

  Future<void> toggleDensity() => setDense(!_dense);

  Future<void> setMotion(bool v) async {
    _motion = v;
    notifyListeners();
    await _save(_kMotion, v ? 'on' : 'off');
  }

  Future<void> setBright(double v) async {
    _bright = v.clamp(0.4, 1.6);
    notifyListeners();
    await _save(_kBright, _bright.toString());
  }

  Future<void> setBgPath(String? path) async {
    _bgPath = (path == null || path.isEmpty) ? null : path;
    _bgBytes = null;
    notifyListeners();
    await _save(_kBg, _bgPath);
  }

  /// Web 专用：浏览器不暴露本地文件路径，file_picker 只回图片字节，因此把字节留在内存里渲染
  /// （见 platform/bg_image.dart）。字节不落盘：base64 进 localStorage 会顶爆浏览器配额，
  /// 所以 Web 上的自定义背景不跨会话保留。桌面/移动走 [setBgPath]。
  Future<void> setBgBytes(Uint8List? bytes) async {
    _bgBytes = (bytes == null || bytes.isEmpty) ? null : bytes;
    _bgPath = null;
    notifyListeners();
    await _save(_kBg, null);
  }

  Future<void> clearBg() => setBgPath(null);

  /// 随机换一个主题（网页端 THEME.random：不重复当前主题），返回新主题供提示。
  Future<WwThemeDef> randomTheme() async {
    if (kWwThemes.length < 2) return themeDef;
    var pick = _themeId;
    while (pick == _themeId) {
      pick = kWwThemes[(_randomSeed() % kWwThemes.length)].id;
    }
    await setTheme(pick);
    return themeDef;
  }

  /// 用时间做种，避免引入 dart:math 只为一次随机（外观面板不需要密码学强度）。
  int _randomSeed() => DateTime.now().microsecondsSinceEpoch;

  /// 供测试重置（不落盘）。
  @visibleForTesting
  void debugReset() {
    _themeId = 'night';
    _accentId = 'auto';
    _font = 'md';
    _dense = false;
    _motion = true;
    _bright = 1;
    _bgPath = null;
    _bgBytes = null;
    notifyListeners();
  }
}

/// 全局外观单例：界面直接读 `appearance.colors.xxx`，切换时由 main.dart 重建整棵树。
final Appearance appearance = Appearance();
