import 'package:flutter/material.dart';

import 'appearance.dart';
import 'platform/bg_image.dart';

/// 外观令牌层：把 [appearance] 的当前选择暴露成一组**顶层 getter**，界面直接引用
/// `kBg` / `kAccent` 等即可，切换主题时由 [WerewolfApp] 重建整棵树取到新值。
///
/// 之所以用 getter 而不是原来的 `const Color`：颜色要能随主题实时变化，而 const 是
/// 编译期常量。代价是这些表达式不能再出现在 `const` 上下文里（见各屏 `const` 的减法）。
export 'appearance.dart';

/// 当前生效的完整调色板。
WwPalette get kPalette => appearance.colors;

Color get kBg => kPalette.bg;
Color get kBgVoid => kPalette.bgVoid;
Color get kPanel => kPalette.panel;
Color get kPanel2 => kPalette.panel2;
Color get kRaise => kPalette.raise;
Color get kBorder => kPalette.border;
Color get kBorderSoft => kPalette.borderSoft;
Color get kText => kPalette.text;
Color get kDim => kPalette.dim;
Color get kFaint => kPalette.faint;
Color get kAccent => kPalette.accent;
Color get kAccentDim => kPalette.accentDim;
Color get kAccentSoft => kPalette.accentSoft;
Color get kAccent2 => kPalette.accent2;
Color get kBlood => kPalette.blood;
Color get kMoon => kPalette.moon;
Color get kGreen => kPalette.green;

/// 强调色底上的前景墨色：网页端默认 #1a1206，浅色主题（羊皮纸 / 樱粉）改用亮墨。
Color get kOnAccent => appearance.light ? const Color(0xFFFFF5F8) : const Color(0xFF1A1206);

/// 座位标记（本地、仅自己可见）的配色，对齐网页版 `.mk-btn.m-sus / .m-good / .m-wolf`。
/// 等级 0（未标）返回 null，表示不显示任何标记。
Color? markColor(int level) => switch (level) {
      1 => kAccent, // 可疑
      2 => kGreen, // 好人
      3 => kBlood, // 狼人
      _ => null,
    };

/// 关闭动效时的页面过场（网页端 body.no-motion 会把所有动画压到 0.01ms）。
class _NoMotionTransitions extends PageTransitionsBuilder {
  const _NoMotionTransitions();
  @override
  Widget buildTransitions<T>(PageRoute<T> route, BuildContext context, Animation<double> animation,
          Animation<double> secondaryAnimation, Widget child) =>
      child;
}

ThemeData werewolfTheme() {
  final c = kPalette;
  final dark = !appearance.light;
  final onAccent = kOnAccent;

  final scheme = (dark
          ? const ColorScheme.dark()
          : const ColorScheme.light())
      .copyWith(
    primary: c.accent,
    onPrimary: onAccent,
    secondary: c.accent2,
    surface: c.panel,
    onSurface: c.text,
    error: c.blood,
    onError: Colors.white,
    outline: c.border,
  );

  return ThemeData(
    useMaterial3: true,
    brightness: dark ? Brightness.dark : Brightness.light,
    colorScheme: scheme,
    // 页面底色与背景图统一交给 WwStageBackground 绘制（对应网页端 .stage-bg），
    // 所以 Scaffold 自身保持透明，否则会把背景整块盖住。
    scaffoldBackgroundColor: Colors.transparent,
    canvasColor: c.panel,
    visualDensity: appearance.visualDensity,
    pageTransitionsTheme: appearance.motion
        ? null
        : const PageTransitionsTheme(builders: {
            TargetPlatform.android: _NoMotionTransitions(),
            TargetPlatform.iOS: _NoMotionTransitions(),
            TargetPlatform.windows: _NoMotionTransitions(),
            TargetPlatform.macOS: _NoMotionTransitions(),
            TargetPlatform.linux: _NoMotionTransitions(),
          }),
    appBarTheme: AppBarTheme(backgroundColor: c.panel, foregroundColor: c.moon, elevation: 0, centerTitle: false),
    cardTheme: CardTheme(
      color: c.panel2,
      elevation: 2,
      shape: RoundedRectangleBorder(
        side: BorderSide(color: c.border),
        borderRadius: BorderRadius.circular(appearance.dense ? 11 : 14),
      ),
    ),
    dividerColor: c.borderSoft,
    dialogTheme: DialogTheme(backgroundColor: c.panel, shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(16))),
    bottomSheetTheme: BottomSheetThemeData(backgroundColor: c.panel, modalBackgroundColor: c.panel),
    popupMenuTheme: PopupMenuThemeData(color: c.panel2, surfaceTintColor: Colors.transparent),
    navigationBarTheme: NavigationBarThemeData(backgroundColor: c.panel, indicatorColor: c.accent.withOpacity(.18)),
    inputDecorationTheme: InputDecorationTheme(
      filled: true,
      fillColor: c.bg,
      contentPadding: EdgeInsets.symmetric(horizontal: 14, vertical: appearance.dense ? 9 : 12),
      border: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide(color: c.border)),
      enabledBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide(color: c.border)),
      focusedBorder: OutlineInputBorder(borderRadius: BorderRadius.circular(10), borderSide: BorderSide(color: c.accent, width: 1.5)),
      hintStyle: TextStyle(color: c.faint),
    ),
    textTheme: TextTheme(
      bodyMedium: TextStyle(color: c.text),
      bodyLarge: TextStyle(color: c.text),
      titleLarge: TextStyle(color: c.moon),
    ),
    elevatedButtonTheme: ElevatedButtonThemeData(
      style: ElevatedButton.styleFrom(
        backgroundColor: c.accent,
        foregroundColor: onAccent,
        textStyle: const TextStyle(fontWeight: FontWeight.bold),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
      ),
    ),
    outlinedButtonTheme: OutlinedButtonThemeData(
      style: OutlinedButton.styleFrom(foregroundColor: c.text, side: BorderSide(color: c.border), shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10))),
    ),
    textButtonTheme: TextButtonThemeData(style: TextButton.styleFrom(foregroundColor: c.accent)),
    snackBarTheme: SnackBarThemeData(backgroundColor: c.panel2, contentTextStyle: TextStyle(color: c.text)),
    sliderTheme: SliderThemeData(activeTrackColor: c.accent, thumbColor: c.accent, inactiveTrackColor: c.borderSoft),
    switchTheme: SwitchThemeData(thumbColor: WidgetStateProperty.resolveWith((s) => s.contains(WidgetState.selected) ? c.accent : c.dim)),
    progressIndicatorTheme: ProgressIndicatorThemeData(color: c.accent),
  );
}

/// 页面背景舞台：主题渐变 + 自定义背景图 + 背景亮度（对应网页端 `.stage-bg` / `--bg-bright`）。
///
/// 亮度只作用于自定义背景图与月光晕（与网页端一致：基础渐变不受 --bg-bright 影响）。
class WwStageBackground extends StatelessWidget {
  const WwStageBackground({super.key});

  @override
  Widget build(BuildContext context) {
    final c = kPalette;
    final a = appearance;
    final bright = a.bright;
    // 自定义背景：桌面/移动是本地文件路径，Web 是 file_picker 回传的字节
    // （见 platform/bg_image.dart）。拿不到图源就当作「没有自定义背景」。
    final bgImage = customBgImage(path: a.bgPath, bytes: a.bgBytes);
    final hasCustom = bgImage != null;
    return Stack(
      fit: StackFit.expand,
      children: [
        // 基础底色：有自定义背景时压成 --bg-void（网页端 has-custom-bg 的写法）。
        ColoredBox(color: hasCustom ? c.bgVoid : c.bg),
        if (!hasCustom) _pageGradient(c),
        // 右上角月光晕。
        Positioned(
          top: -60,
          right: -60,
          child: IgnorePointer(
            child: Container(
              width: 420,
              height: 420,
              decoration: BoxDecoration(
                shape: BoxShape.circle,
                gradient: RadialGradient(colors: [
                  c.moon.withOpacity(a.light ? .10 : .16),
                  c.moon.withOpacity(.04),
                  Colors.transparent,
                ], stops: const [0, .45, .7]),
              ),
            ),
          ),
        ),
        if (bgImage != null) _customImage(bgImage, bright),
      ],
    );
  }

  Widget _customImage(ImageProvider image, double bright) {
    return IgnorePointer(
      child: Opacity(
        opacity: (0.5 * bright).clamp(0.0, 1.0),
        child: ColorFiltered(
          colorFilter: ColorFilter.matrix(_brightnessMatrix(bright)),
          child: Image(
            image: image,
            fit: BoxFit.cover,
            errorBuilder: (_, __, ___) => const SizedBox.shrink(),
          ),
        ),
      ),
    );
  }

  /// 网页端 .stage-bg 的 --grad-page：两处径向高光叠在底色上。
  Widget _pageGradient(WwPalette c) {
    return DecoratedBox(
      decoration: BoxDecoration(
        gradient: RadialGradient(
          center: const Alignment(-0.76, -1.16),
          radius: 1.1,
          colors: [c.accent.withOpacity(.16), c.bg.withOpacity(0)],
        ),
      ),
      child: DecoratedBox(
        decoration: BoxDecoration(
          gradient: RadialGradient(
            center: const Alignment(0.92, -0.92),
            radius: 0.95,
            colors: [c.accent2.withOpacity(.12), c.bg.withOpacity(0)],
          ),
        ),
      ),
    );
  }

  /// filter: brightness(b) 的 4x5 色彩矩阵。
  static List<double> _brightnessMatrix(double b) => <double>[
        b, 0, 0, 0, 0,
        0, b, 0, 0, 0,
        0, 0, b, 0, 0,
        0, 0, 0, 1, 0,
      ];
}
