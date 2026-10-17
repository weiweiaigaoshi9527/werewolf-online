/// 3D 视角组件（P5）：把 [Scene3dScene] 渲染到画布，并叠上名牌 / 气泡 / 光环 / 假阴影。
///
/// 与网页端 `static/js/scene3d.js` + `index.html` 的 `canvas-3d` 对应：
/// 同一套拖拽旋转 / 滚轮与双指缩放 / 重置·远景·近景快捷视角，开关持久化键同为 `ww_mode_3d`。
library;

import 'dart:math' as math;

import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/scheduler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../i18n.dart';
import '../scene3d.dart';
import '../scene3d_avatar.dart';
import '../scene3d_scene.dart';
import '../theme.dart';

/// 3D 视角开关（持久化键 `ww_mode_3d`，与网页端 `LS_MODE_3D` 一致）。
class Scene3dMode extends ChangeNotifier {
  static const _key = 'ww_mode_3d';

  bool _on = false;
  bool get on => _on;

  /// 从本机读回开关（网页端在页面加载时恢复）。
  Future<void> load() async {
    try {
      final p = await SharedPreferences.getInstance();
      _on = p.getBool(_key) ?? false;
      notifyListeners();
    } catch (_) {}
  }

  Future<void> setOn(bool v) async {
    if (v == _on) return;
    _on = v;
    notifyListeners();
    try {
      final p = await SharedPreferences.getInstance();
      await p.setBool(_key, v);
    } catch (_) {}
  }

  Future<void> toggle() => setOn(!_on);

  /// 供测试重置（不落盘）。
  @visibleForTesting
  void debugReset() {
    _on = false;
    notifyListeners();
  }
}

/// 全局 3D 开关单例。
final Scene3dMode scene3dMode = Scene3dMode();

/// 3D 视角面板：填满父容器，内部自带手势与快捷视角按钮。
class Scene3dView extends StatefulWidget {
  const Scene3dView({super.key, required this.view, this.showControls = true});

  /// 对局视图（`game.state` 的 payload），与 2D 座位圈同源。
  final Map<String, dynamic> view;

  /// 是否显示右下角的快捷视角按钮。
  final bool showControls;

  @override
  State<Scene3dView> createState() => _Scene3dViewState();
}

class _Scene3dViewState extends State<Scene3dView> with SingleTickerProviderStateMixin {
  final Scene3dScene _scene = Scene3dScene();
  final Scene3dRenderer _renderer = Scene3dRenderer();

  /// 只驱动重绘，不重建 widget 树（每帧 setState 会连累整页）。
  final ValueNotifier<int> _repaint = ValueNotifier<int>(0);

  Ticker? _ticker;
  Duration _last = Duration.zero;
  double _acc = 0;

  /// 帧率上限。实测 16 人场景（约 1.8 万面、实绘约 6000 面）单帧 CPU 约 6ms，
  /// 桌面端跑满 60fps 仍有余量；压到 30fps 是给手机留余量并省一半 CPU/电量。
  static const double _frameBudget = 1 / 30;

  Offset _lastFocal = Offset.zero;
  double _lastScale = 1;
  bool _dragging = false;

  @override
  void initState() {
    super.initState();
    _scene.update(widget.view);
    _startTicker();
  }

  @override
  void didUpdateWidget(covariant Scene3dView old) {
    super.didUpdateWidget(old);
    if (!identical(old.view, widget.view)) {
      _scene.update(widget.view);
      _repaint.value++;
    }
  }

  @override
  void dispose() {
    _ticker?.dispose();
    _repaint.dispose();
    super.dispose();
  }

  /// 动效关闭时不启动 Ticker：场景静止渲染，也省电。
  void _startTicker() {
    if (!appearance.motion) return;
    _ticker = createTicker(_onTick)..start();
  }

  void _onTick(Duration elapsed) {
    final raw = _last == Duration.zero
        ? 1 / 60
        : (elapsed - _last).inMicroseconds / 1e6;
    _last = elapsed;
    _acc += raw.clamp(0.0, 0.1);
    if (_acc < _frameBudget) return;
    _scene.tick(_acc);
    _acc = 0;
    _repaint.value++;
  }

  void _onScaleStart(ScaleStartDetails d) {
    _lastFocal = d.focalPoint;
    _lastScale = 1;
    _dragging = d.pointerCount < 2;
  }

  void _onScaleUpdate(ScaleUpdateDetails d) {
    final cam = _scene.camera;
    // 双指张合缩放：张开（scale > 1）→ 拉近。
    if (d.pointerCount >= 2) {
      _dragging = false;
      final s = d.scale <= 0 ? 1.0 : d.scale / _lastScale;
      if (s > 0) cam.zoomScale(1 / s);
      _lastScale = d.scale;
      _snapIfStatic();
      _repaint.value++;
      return;
    }
    if (!_dragging) {
      _dragging = true;
      _lastFocal = d.focalPoint;
      return;
    }
    final dx = d.focalPoint.dx - _lastFocal.dx;
    final dy = d.focalPoint.dy - _lastFocal.dy;
    _lastFocal = d.focalPoint;
    if (dx == 0 && dy == 0) return;
    cam.dragBy(dx, dy);
    _snapIfStatic();
    _repaint.value++;
  }

  /// 动效关闭时没有阻尼动画，直接把当前值对齐目标值，否则拖拽看起来「没反应」。
  void _snapIfStatic() {
    if (appearance.motion) return;
    final c = _scene.camera;
    c.caz = c.az;
    c.cel = c.el;
    c.cdist = c.dist;
  }

  void _onSignal(PointerSignalEvent e) {
    if (e is! PointerScrollEvent) return;
    _scene.camera.zoomBy(e.scrollDelta.dy * 0.012);
    _snapIfStatic();
    _repaint.value++;
  }

  void _preset(String name) {
    final c = _scene.camera;
    switch (name) {
      case 'reset':
        c.reset();
      case 'far':
        c.set(a: 0, e: 0.62, d: 16.5);
      case 'near':
        c.set(a: 0, e: 0.34, d: 7.4);
    }
    _snapIfStatic();
    _repaint.value++;
  }

  @override
  Widget build(BuildContext context) {
    return ClipRect(
      child: Stack(
        fit: StackFit.expand,
        children: [
          Listener(
            onPointerSignal: _onSignal,
            child: GestureDetector(
              behavior: HitTestBehavior.opaque,
              onScaleStart: _onScaleStart,
              onScaleUpdate: _onScaleUpdate,
              child: RepaintBoundary(
                child: CustomPaint(
                  painter: _Scene3dPainter(
                    scene: _scene,
                    renderer: _renderer,
                    repaint: _repaint,
                  ),
                  size: Size.infinite,
                ),
              ),
            ),
          ),
          if (widget.showControls)
            Positioned(
              right: 8,
              bottom: 8,
              child: _controls(),
            ),
        ],
      ),
    );
  }

  Widget _controls() {
    return Column(
      mainAxisSize: MainAxisSize.min,
      crossAxisAlignment: CrossAxisAlignment.end,
      children: [
        _ctrlButton(Icons.center_focus_strong, tr('重置视角'), () => _preset('reset')),
        const SizedBox(height: 6),
        _ctrlButton(Icons.zoom_out_map, tr('远景'), () => _preset('far')),
        const SizedBox(height: 6),
        _ctrlButton(Icons.person_search, tr('近景'), () => _preset('near')),
      ],
    );
  }

  Widget _ctrlButton(IconData icon, String tip, VoidCallback onTap) {
    return Tooltip(
      message: tip,
      child: Material(
        color: kPanel.withOpacity(.82),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
          side: BorderSide(color: kBorder),
        ),
        child: InkWell(
          borderRadius: BorderRadius.circular(10),
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.all(7),
            child: Icon(icon, size: 17, color: kDim),
          ),
        ),
      ),
    );
  }
}

/// 把场景画到画布：天空 → 星空 → 地面 → 假阴影 / 光环 → 主体 → 碑文 / 名牌 / 气泡。
class _Scene3dPainter extends CustomPainter {
  _Scene3dPainter({
    required this.scene,
    required this.renderer,
    required this.repaint,
  }) : super(repaint: repaint);

  final Scene3dScene scene;
  final Scene3dRenderer renderer;
  final ValueNotifier<int> repaint;

  /// 文本布局缓存：键含语言，切语言后自动失效。
  final Map<String, TextPainter> _textCache = {};

  @override
  void paint(Canvas canvas, Size size) {
    if (size.width <= 1 || size.height <= 1) return;

    final cam = scene.camera;
    final frame = Scene3dFrame(
      size: size,
      eye: cam.eye,
      target: cam.look,
      fovY: Scene3dCamera.fovY,
    );

    canvas.save();
    canvas.clipRect(Offset.zero & size);

    _paintSky(canvas, size);
    if (scene.night) _paintStars(canvas, frame);

    // 地面单独一批
    renderer.begin();
    for (final p in scene.groundParts) {
      renderer.add(p.mesh, p.local);
    }
    renderer.setEye(frame.eye);
    renderer.paint(canvas, frame, scene.light);

    _paintShadows(canvas, frame);
    _paintRings(canvas, frame);

    // 主体
    renderer.begin();
    scene.collect(renderer);
    renderer.setEye(frame.eye);
    renderer.paint(canvas, frame, scene.light);

    _paintTombText(canvas, frame);
    _paintLabels(canvas, frame);
    _paintBubble(canvas, frame);

    canvas.restore();
  }

  void _paintSky(Canvas canvas, Size size) {
    final top = scene.night ? const Color(0xFF05070F) : const Color(0xFF0D1526);
    final bottom = scene.night ? const Color(0xFF0D1526) : const Color(0xFF1B2740);
    canvas.drawRect(
      Offset.zero & size,
      Paint()
        ..shader = LinearGradient(
          begin: Alignment.topCenter,
          end: Alignment.bottomCenter,
          colors: [top, bottom],
        ).createShader(Offset.zero & size),
    );
  }

  /// 星空：投影成点，按距离衰减大小（对齐网页端 `sizeAttenuation`）。
  void _paintStars(Canvas canvas, Scene3dFrame frame) {
    final paint = Paint()..color = const Color(0xFFCFE0FF).withOpacity(.85);
    for (final s in scene.stars) {
      final p = frame.project(s);
      if (p == null) continue;
      if (p.dx < -4 || p.dy < -4 || p.dx > frame.size.width + 4 || p.dy > frame.size.height + 4) {
        continue;
      }
      final d = frame.depthOf(s);
      if (d <= 0) continue;
      final r = (0.34 * 26 / d).clamp(0.4, 2.2);
      canvas.drawCircle(p, r, paint);
    }
  }

  /// 贴地椭圆假阴影：软件渲染没有实时阴影，用压暗的地面椭圆替代。
  void _paintShadows(Canvas canvas, Scene3dFrame frame) {
    // 三层同心椭圆、由外向内递增透明度，叠出柔和边缘——
    // 单块实心黑饼太生硬；叠加后中心约 0.39，与旧版观感亮度接近。
    const layers = [(1.45, 0.10), (1.20, 0.13), (1.00, 0.16)];
    for (final n in scene.seats) {
      if (!n.avatarVisible) continue;
      for (final (scale, op) in layers) {
        final e = _groundEllipse(frame, V3(n.x, 0.015, n.z), 0.62 * scale);
        if (e == null) continue;
        canvas.drawOval(
          Rect.fromCenter(center: e.c, width: e.rx * 2, height: e.ry * 2),
          Paint()..color = const Color(0xFF04060C).withOpacity(op),
        );
      }
    }
    // 圆桌下方也压一圈，让桌子「落」在地上
    final t = _groundEllipse(frame, const V3(0, 0.012, 0), kRTable * 0.95);
    if (t != null) {
      canvas.drawOval(
          Rect.fromCenter(center: t.c, width: t.rx * 2, height: t.ry * 2),
          Paint()..color = const Color(0xFF04060C).withOpacity(.38));
    }
  }

  /// 座位地面光环：发言者脉动青色、自己淡蓝、其他人不画。
  void _paintRings(Canvas canvas, Scene3dFrame frame) {
    for (final n in scene.seats) {
      if (n.ringOpacity <= 0.01) continue;
      final e = _groundEllipse(frame, V3(n.x, 0.02, n.z), 0.85);
      if (e == null) continue;
      final w = math.max(1.0, e.rx * 0.22);
      canvas.drawOval(
        Rect.fromCenter(
            center: e.c, width: e.rx * 2 - w, height: e.ry * 2 - w),
        Paint()
          ..style = PaintingStyle.stroke
          ..strokeWidth = w
          ..color = Color(n.ringColor).withOpacity(n.ringOpacity.clamp(0.0, 1.0)),
      );
    }
  }

  /// 碑文：网页端是贴在碑面的贴图，这里改成把同样三行画在碑面投影位置。
  void _paintTombText(Canvas canvas, Scene3dFrame frame) {
    for (final n in scene.seats) {
      if (!n.tombVisible) continue;
      final p = frame.project(V3(n.x, 0.66, n.z));
      if (p == null) continue;
      final scale = _distScale(frame, V3(n.x, 0.66, n.z));
      if (scale < 0.45) continue; // 太小了看不清，省掉
      final nm = n.nickname.length > 7 ? '${n.nickname.substring(0, 6)}…' : n.nickname;
      _drawText(canvas, p, ['R.I.P', '#${n.seat}', nm],
          fontSize: 9 * scale,
          color: const Color(0xFF3B4152),
          bold: true,
          lineGap: 1.05);
    }
  }

  /// 名牌：`#座位 昵称`、皮肤名、称号、已出局。
  void _paintLabels(Canvas canvas, Scene3dFrame frame) {
    for (final n in scene.seats) {
      final anchor = n.labelAnchor;
      final p = frame.project(anchor);
      if (p == null) continue;
      final scale = _distScale(frame, anchor);
      if (scale < 0.3) continue;

      final lines = <String>['#${n.seat} ${n.nickname}${n.isMe ? tr('（我）') : ''}'];
      final skin = tr(skinLabel(n.skinId));
      if (skin.isNotEmpty) lines.add(skin);
      if (n.title.isNotEmpty) lines.add(tr(n.title));
      if (n.tombVisible) lines.add(tr('已出局'));

      final border = n.tombVisible
          ? const Color(0x8C788096)
          : (n.isSheriff ? const Color(0xF2FFCE54) : const Color(0x7380AAFF));
      final fg = n.tombVisible
          ? const Color(0xFF8B93A8)
          : (n.isMe ? const Color(0xFF8EF0FF) : const Color(0xFFE8EEFC));

      _drawText(
        canvas,
        p,
        lines,
        fontSize: 11 * scale,
        color: fg,
        bold: true,
        bg: n.tombVisible ? const Color(0xE0181C28) : const Color(0xD60C1222),
        border: border,
        radius: 5 * scale,
        padX: 5 * scale,
        padY: 3 * scale,
      );
    }
  }

  /// 最新一条公开发言的气泡（网页端 `refreshBubble`：取最后一条 SPEECH / LAST_WORDS）。
  void _paintBubble(Canvas canvas, Scene3dFrame frame) {
    final feed = (scene.lastSpeechDetail, scene.lastSpeechActor);
    final detail = feed.$1;
    final actor = feed.$2;
    if (detail == null || actor == null) return;
    Scene3dSeatNode? node;
    for (final n in scene.seats) {
      if (n.seat == actor) node = n;
    }
    if (node == null) return;
    final anchor = node.bubbleAnchor;
    final p = frame.project(anchor);
    if (p == null) return;
    final scale = _distScale(frame, anchor);
    if (scale < 0.35) return;
    _drawText(
      canvas,
      p,
      wrapSpeech(detail, 14, 4),
      fontSize: 11 * scale,
      color: const Color(0xFFEAF3FF),
      bg: const Color(0xEB141C30),
      border: const Color(0x8C8CC8FF),
      radius: 6 * scale,
      padX: 6 * scale,
      padY: 4 * scale,
    );
  }

  /// 世界坐标处的「距离缩放」：模拟网页端世界空间精灵随距离变小。
  double _distScale(Scene3dFrame frame, V3 world) {
    final d = frame.depthOf(world);
    if (d <= 0.2) return 0;
    return (14 / d).clamp(0.3, 1.8);
  }

  /// 把水平圆投影成屏幕椭圆（相机无滚转，椭圆轴与屏幕轴对齐）。
  ({Offset c, double rx, double ry})? _groundEllipse(
      Scene3dFrame frame, V3 center, double radius) {
    double minX = double.infinity, maxX = -double.infinity;
    double minY = double.infinity, maxY = -double.infinity;
    for (var i = 0; i < 8; i++) {
      final a = i / 8 * kTau;
      final p = frame.project(V3(
        center.x + math.cos(a) * radius,
        center.y,
        center.z + math.sin(a) * radius,
      ));
      if (p == null) return null;
      minX = math.min(minX, p.dx);
      maxX = math.max(maxX, p.dx);
      minY = math.min(minY, p.dy);
      maxY = math.max(maxY, p.dy);
    }
    return (
      c: Offset((minX + maxX) / 2, (minY + maxY) / 2),
      rx: (maxX - minX) / 2,
      ry: (maxY - minY) / 2,
    );
  }

  /// 画一段居中多行文本，可选圆角底与描边（对齐网页端 `makeTextSprite`）。
  void _drawText(
    Canvas canvas,
    Offset center,
    List<String> lines, {
    required double fontSize,
    required Color color,
    bool bold = false,
    Color? bg,
    Color? border,
    double radius = 6,
    double padX = 6,
    double padY = 4,
    double lineGap = 1.32,
  }) {
    if (lines.isEmpty || fontSize <= 1) return;
    final key = '${i18n.lang}|$fontSize|$bold|${color.value}|${bg?.value}|'
        '${border?.value}|${lines.join('\u0001')}';
    final tp = _textCache.putIfAbsent(key, () {
      final t = TextPainter(
        text: TextSpan(
          text: lines.join('\n'),
          style: TextStyle(
            color: color,
            fontSize: fontSize,
            fontWeight: bold ? FontWeight.w700 : FontWeight.w400,
            height: lineGap,
            shadows: bg == null
                ? null
                : const [Shadow(color: Color(0xCC000000), blurRadius: 3)],
          ),
        ),
        textAlign: TextAlign.center,
        textDirection: TextDirection.ltr,
      )..layout();
      return t;
    });
    // 缓存可能因为缩放变化而无限增长，这里做个温和的上限保护。
    if (_textCache.length > 400) _textCache.clear();

    final rect = Rect.fromCenter(
      center: center,
      width: tp.width + padX * 2,
      height: tp.height + padY * 2,
    );
    if (bg != null) {
      final rr = RRect.fromRectAndRadius(rect, Radius.circular(radius));
      canvas.drawRRect(rr, Paint()..color = bg);
      if (border != null) {
        canvas.drawRRect(
          rr,
          Paint()
            ..style = PaintingStyle.stroke
            ..strokeWidth = math.max(1, fontSize / 9)
            ..color = border,
        );
      }
    }
    tp.paint(canvas, Offset(rect.left + padX, rect.top + padY));
  }

  @override
  bool shouldRepaint(covariant _Scene3dPainter old) => true;
}
