/// 纯 Dart 软件 3D 渲染内核（P5）：数学、程序化图元、网格与光栅化。
///
/// 网页端 `static/js/scene3d.js` 走 Three.js + WebGL。本端当前工具链（Flutter 3.24.5）
/// 没有可用且离线安全的 3D 引擎（`flutter_scene` 需 ≥3.29 且依赖 Impeller，
/// `three_dart` / `flutter_gl` 已停更），因此用 Flutter 自带的 `Canvas.drawVertices`
/// 自研一个轻量软件渲染器：
///   - CPU 逐面做背面剔除 → 兰伯特 + 高光着色 → 按视深排序（画家算法）；
///   - 所有面合并成**一次** `drawVertices` 提交（逐顶点颜色），避免逐面绘制调用。
///
/// 代价是平片着色、没有实时阴影与 PBR；已用「贴地椭圆假阴影 + 平面高光 + 雾」近似。
/// 本文件只依赖 `dart:ui` 与 `dart:math`，可脱离 Widget 直接单测（含像素级校验）。
library;

import 'dart:math' as math;
import 'dart:typed_data';
import 'dart:ui' as ui;

/// 2π。
const double kTau = math.pi * 2;

/// 近平面距离：视深小于它的顶点视为在相机之后，所在面整体丢弃。
const double kNear = 0.1;

/// 远平面距离：超出即完全被雾吞掉（与网页端 `THREE.Fog(…, 24, 46)` 呼应）。
const double kFar = 240;

/// 亚像素面剔除阈值（平方像素）。软件渲染下这一条最关键：
/// 远景时眼睛、眉毛、帽饰等大量小面自然消失，省掉的是成倍的顶点变换与排序开销，
/// 而近景（放大看造型）时它们又自动回来。
const double kMinFaceArea = 0.9;

/* ============================== 数学 ============================== */

/// 三维向量（不可变）。与 Three.js 一致：**y 轴朝上**。
class V3 {
  const V3(this.x, this.y, this.z);

  final double x, y, z;

  static const V3 zero = V3(0, 0, 0);
  static const V3 up = V3(0, 1, 0);

  V3 operator +(V3 o) => V3(x + o.x, y + o.y, z + o.z);
  V3 operator -(V3 o) => V3(x - o.x, y - o.y, z - o.z);
  V3 operator *(double s) => V3(x * s, y * s, z * s);

  double dot(V3 o) => x * o.x + y * o.y + z * o.z;

  V3 cross(V3 o) => V3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x);

  double get length => math.sqrt(x * x + y * y + z * z);

  /// 单位化；零向量原样返回（避免除零）。
  V3 get unit {
    final l = length;
    return l < 1e-9 ? zero : V3(x / l, y / l, z / l);
  }

  @override
  String toString() =>
      'V3(${x.toStringAsFixed(2)}, ${y.toStringAsFixed(2)}, ${z.toStringAsFixed(2)})';
}

/// 4×4 变换矩阵，行主序存放（`m[row * 4 + col]`）。
///
/// 只覆盖本场景用得到的变换：平移 / 缩放 / 绕单轴旋转，以及它们的级联。
class M4 {
  M4._(this.m);

  final Float64List m;

  factory M4.identity() {
    final a = Float64List(16);
    a[0] = a[5] = a[10] = a[15] = 1;
    return M4._(a);
  }

  factory M4.translate(double x, double y, double z) {
    final a = M4.identity().m;
    a[3] = x;
    a[7] = y;
    a[11] = z;
    return M4._(a);
  }

  factory M4.scale(double x, double y, double z) {
    final a = Float64List(16);
    a[0] = x;
    a[5] = y;
    a[10] = z;
    a[15] = 1;
    return M4._(a);
  }

  factory M4.rotX(double r) {
    final c = math.cos(r), s = math.sin(r);
    final a = M4.identity().m;
    a[5] = c;
    a[6] = -s;
    a[9] = s;
    a[10] = c;
    return M4._(a);
  }

  factory M4.rotY(double r) {
    final c = math.cos(r), s = math.sin(r);
    final a = M4.identity().m;
    a[0] = c;
    a[2] = s;
    a[8] = -s;
    a[10] = c;
    return M4._(a);
  }

  factory M4.rotZ(double r) {
    final c = math.cos(r), s = math.sin(r);
    final a = M4.identity().m;
    a[0] = c;
    a[1] = -s;
    a[4] = s;
    a[5] = c;
    return M4._(a);
  }

  /// 级联：`a.mul(b)` 表示**先应用 b、再应用 a**（列向量约定下的 `a * b`）。
  /// 因此「先缩放、再旋转、后平移」要写成 `T.mul(R).mul(S)`。
  M4 mul(M4 o) {
    final a = m, b = o.m, r = Float64List(16);
    for (var row = 0; row < 4; row++) {
      final r0 = row * 4;
      for (var col = 0; col < 4; col++) {
        r[r0 + col] = a[r0] * b[col] +
            a[r0 + 1] * b[4 + col] +
            a[r0 + 2] * b[8 + col] +
            a[r0 + 3] * b[12 + col];
      }
    }
    return M4._(r);
  }

  /// 变换一个点（含平移）。
  V3 apply(V3 v) => V3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z + m[3],
        m[4] * v.x + m[5] * v.y + m[6] * v.z + m[7],
        m[8] * v.x + m[9] * v.y + m[10] * v.z + m[11],
      );

  /// 变换一个方向（忽略平移）。
  V3 applyDir(V3 v) => V3(
        m[0] * v.x + m[1] * v.y + m[2] * v.z,
        m[4] * v.x + m[5] * v.y + m[6] * v.z,
        m[8] * v.x + m[9] * v.y + m[10] * v.z,
      );
}

/// 视空间 → 屏幕空间的投影上下文（渲染器与 2D 叠加层共用同一份）。
///
/// 构造后即固定（一帧一份），[project] / [depthOf] 供名牌、气泡、光环、
/// 假阴影等 2D 叠加物把世界坐标落到画布上。
class Scene3dFrame {
  Scene3dFrame({
    required this.size,
    required this.eye,
    required this.target,
    required this.fovY,
  }) {
    _buildView();
  }

  final ui.Size size;
  final V3 eye;
  final V3 target;

  /// 竖直视场角（弧度），对应网页端 `PerspectiveCamera(45, …)`。
  final double fovY;

  final Float64List _view = Float64List(16);
  double _fy = 1;

  double get aspect => size.height <= 0 ? 1 : size.width / size.height;

  void _buildView() {
    var f = (target - eye).unit;
    if (f.length < 1e-9) f = const V3(0, 0, -1);
    var r = f.cross(V3.up);
    // 视线与 up 平行时叉积退化，退回到 +x 方向，避免整帧变黑。
    if (r.length < 1e-6) r = const V3(1, 0, 0);
    r = r.unit;
    final u = r.cross(f).unit;
    _view[0] = r.x;
    _view[1] = r.y;
    _view[2] = r.z;
    _view[3] = -r.dot(eye);
    _view[4] = u.x;
    _view[5] = u.y;
    _view[6] = u.z;
    _view[7] = -u.dot(eye);
    _view[8] = -f.x;
    _view[9] = -f.y;
    _view[10] = -f.z;
    _view[11] = f.dot(eye);
    _view[15] = 1;
    _fy = 1 / math.tan(fovY / 2);
  }

  /// 视深（相机前方为正）；<= 0 表示在相机之后。
  double depthOf(V3 w) => -(_view[8] * w.x + _view[9] * w.y + _view[10] * w.z + _view[11]);

  /// 世界坐标 → 屏幕坐标；在近平面之后返回 null（不可见）。
  ui.Offset? project(V3 w) {
    final vz = _view[8] * w.x + _view[9] * w.y + _view[10] * w.z + _view[11];
    final depth = -vz;
    if (depth < kNear) return null;
    final vx = _view[0] * w.x + _view[1] * w.y + _view[2] * w.z + _view[3];
    final vy = _view[4] * w.x + _view[5] * w.y + _view[6] * w.z + _view[7];
    final ndcX = (_fy / aspect) * vx / depth;
    final ndcY = _fy * vy / depth;
    return ui.Offset(
      (ndcX * 0.5 + 0.5) * size.width,
      (0.5 - ndcY * 0.5) * size.height,
    );
  }
}

/* ============================== 网格 ============================== */

/// 材质档位：只影响着色系数（无贴图，靠程序化配色还原网页端观感）。
enum MatKind {
  /// 布料 / 皮革：漫反射为主，几乎无高光。
  cloth,

  /// 甲片 / 金属：高光强、环境光略低。
  metal,

  /// 皮肤：偏亮的环境光，柔和高光。
  skin,

  /// 点缀（金属饰件、宝石）。
  accent,

  /// 深色部件（鞋、眉毛、护目镜、杖身）。
  dark,
}

/// 一个可渲染网格：顶点（局部空间）+ 三角面 + 基色 + 材质档位。
///
/// 每个网格自带逐帧暂存缓冲（世界坐标 / 屏幕坐标 / 视深），
/// 避免每帧为上万顶点反复分配。
class Mesh {
  Mesh({
    required this.pos,
    required this.tris,
    required this.color,
    this.kind = MatKind.cloth,
    this.twoSided = false,
    this.faceColors,
  });

  /// 顶点坐标（x,y,z 连续存放）。
  final Float64List pos;

  /// 三角面顶点下标（每 3 个一组）。
  final Int32List tris;

  /// 基色（0xAARRGGBB）。
  final int color;

  final MatKind kind;

  /// 双面渲染（车削体、环、平面等开口曲面）；闭合凸体置 false 以启用背面剔除。
  final bool twoSided;

  /// 逐面配色（长度 = 面数）。用于条纹 / 布纹等程序化花纹；
  /// 为 null 时整块网格统一用 [color]。
  final Int32List? faceColors;

  /// 顶点数。
  late final int nv = pos.length ~/ 3;

  /// 逐帧暂存：世界坐标 / 屏幕坐标（sx, sy, 视深）/ 是否在近平面之后。
  late final Float64List world = Float64List(nv * 3);
  late final Float64List screen = Float64List(nv * 3);
  late final Uint8List behind = Uint8List(nv);

  int get faceCount => tris.length ~/ 3;

  /// 把顶点变换到世界坐标并投影到屏幕（每帧、每个网格只做一次）。
  void prepare(M4 worldXform, Scene3dFrame frame) {
    final w = worldXform.m;
    for (var i = 0; i < nv; i++) {
      final i3 = i * 3;
      final x = pos[i3], y = pos[i3 + 1], z = pos[i3 + 2];
      final wx = w[0] * x + w[1] * y + w[2] * z + w[3];
      final wy = w[4] * x + w[5] * y + w[6] * z + w[7];
      final wz = w[8] * x + w[9] * y + w[10] * z + w[11];
      world[i3] = wx;
      world[i3 + 1] = wy;
      world[i3 + 2] = wz;

      // 视空间
      final f = frame._view;
      final vz = f[8] * wx + f[9] * wy + f[10] * wz + f[11];
      final depth = -vz;
      if (depth < kNear) {
        behind[i] = 1;
        screen[i3] = 0;
        screen[i3 + 1] = 0;
        screen[i3 + 2] = depth;
        continue;
      }
      behind[i] = 0;
      final vx = f[0] * wx + f[1] * wy + f[2] * wz + f[3];
      final vy = f[4] * wx + f[5] * wy + f[6] * wz + f[7];
      final ndcX = (frame._fy / frame.aspect) * vx / depth;
      final ndcY = frame._fy * vy / depth;
      screen[i3] = (ndcX * 0.5 + 0.5) * frame.size.width;
      screen[i3 + 1] = (0.5 - ndcY * 0.5) * frame.size.height;
      screen[i3 + 2] = depth;
    }
  }
}

/// 场景里的一个部件：网格 + 局部变换。
///
/// 局部变换是可变的——呼吸（躯干纵向缩放）、转头、眨眼都是逐帧改写它的结果，
/// 因此不能把变换烘进顶点里。
class Part {
  Part(this.mesh, this.local);

  final Mesh mesh;
  M4 local;
}

/* ============================== 图元 ============================== */

/// 程序化图元工厂。全部以原点为中心（与 Three.js 的几何体一致），
/// 尺寸口径也对齐 Three.js：`BoxGeometry(w,h,d)`、`CylinderGeometry(rTop,rBot,h)`、
/// `SphereGeometry(r)`、`CapsuleGeometry(r,len)` 等。
///
/// 分段数刻意压得很低——这是 CPU 软件渲染，段数直接决定帧率。
class Geo {
  Geo._();

  /// 闭合凸体的绕序修正：把朝内的面翻过来，之后就能开背面剔除。
  static Int32List _outward(Float64List pos, List<int> tris) {
    for (var f = 0; f < tris.length; f += 3) {
      final a = tris[f] * 3, b = tris[f + 1] * 3, c = tris[f + 2] * 3;
      final ax = pos[a], ay = pos[a + 1], az = pos[a + 2];
      final bx = pos[b], by = pos[b + 1], bz = pos[b + 2];
      final cx = pos[c], cy = pos[c + 1], cz = pos[c + 2];
      final ux = bx - ax, uy = by - ay, uz = bz - az;
      final vx = cx - ax, vy = cy - ay, vz = cz - az;
      final nx = uy * vz - uz * vy;
      final ny = uz * vx - ux * vz;
      final nz = ux * vy - uy * vx;
      // 面心（以原点为中心的凸体，面心方向即外法线方向）
      final mx = (ax + bx + cx) / 3, my = (ay + by + cy) / 3, mz = (az + bz + cz) / 3;
      if (nx * mx + ny * my + nz * mz < 0) {
        final t = tris[f + 1];
        tris[f + 1] = tris[f + 2];
        tris[f + 2] = t;
      }
    }
    return Int32List.fromList(tris);
  }

  static Mesh _make(List<double> pos, List<int> tris, int color, MatKind kind,
      {bool twoSided = false, bool orient = true, Int32List? faceColors}) {
    final p = Float64List.fromList(pos);

    // 丢掉退化三角形（极点处三个顶点重合）。它们既不可见，又会让面法线变成零向量，
    // 使背面剔除的「朝内/朝外」判定失效。
    final kept = <int>[];
    final keptFaces = <int>[];
    for (var f = 0; f < tris.length; f += 3) {
      final a = tris[f] * 3, b = tris[f + 1] * 3, c = tris[f + 2] * 3;
      final ux = p[b] - p[a], uy = p[b + 1] - p[a + 1], uz = p[b + 2] - p[a + 2];
      final vx = p[c] - p[a], vy = p[c + 1] - p[a + 1], vz = p[c + 2] - p[a + 2];
      final nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
      if (nx * nx + ny * ny + nz * nz < 1e-20) continue;
      kept.addAll([tris[f], tris[f + 1], tris[f + 2]]);
      keptFaces.add(f ~/ 3);
    }

    Int32List? fc;
    if (faceColors != null) {
      fc = Int32List(keptFaces.length);
      for (var i = 0; i < keptFaces.length; i++) {
        fc[i] = faceColors[keptFaces[i]];
      }
    }

    // 逐面配色时不做绕序修正：翻面会打乱「第 n 个面」与颜色的对应关系。
    final doOrient = orient && !twoSided && fc == null;
    return Mesh(
      pos: p,
      tris: doOrient ? _outward(p, kept) : Int32List.fromList(kept),
      color: color,
      kind: kind,
      twoSided: twoSided,
      faceColors: fc,
    );
  }

  /// 长方体。
  static Mesh box(double w, double h, double d, int color, MatKind kind) {
    final x = w / 2, y = h / 2, z = d / 2;
    final pos = <double>[
      -x, -y, -z, x, -y, -z, x, y, -z, -x, y, -z, // 后
      -x, -y, z, x, -y, z, x, y, z, -x, y, z, // 前
    ];
    final tris = <int>[
      0, 1, 2, 0, 2, 3, // 后
      4, 6, 5, 4, 7, 6, // 前
      0, 4, 5, 0, 5, 1, // 下
      3, 2, 6, 3, 6, 7, // 上
      0, 3, 7, 0, 7, 4, // 左
      1, 5, 6, 1, 6, 2, // 右
    ];
    return _make(pos, tris, color, kind);
  }

  /// 圆柱 / 圆锥 / 圆台。`thetaLen < 2π` 时是扇形柱（网页端披风、兜帽的开口锥）。
  ///
  /// 角度基准刻意对齐 Three.js 的 `CylinderGeometry`：`theta = 0` 指向 **+Z**、
  /// 朝 +X 方向增长（`x = r·sin θ`、`z = r·cos θ`）。差 90° 就会让「披风开口」
  /// 从背后转到侧面，直接盖住胸口。
  static Mesh cylinder(
    double rTop,
    double rBot,
    double h,
    int seg,
    int color,
    MatKind kind, {
    bool capTop = true,
    bool capBot = true,
    double thetaStart = 0,
    double thetaLen = kTau,
    bool twoSided = false,
  }) {
    final pos = <double>[];
    final tris = <int>[];
    final hy = h / 2;
    for (var i = 0; i <= seg; i++) {
      final a = thetaStart + thetaLen * i / seg;
      final sx = math.sin(a), cz = math.cos(a);
      pos.addAll([sx * rTop, hy, cz * rTop]);
      pos.addAll([sx * rBot, -hy, cz * rBot]);
    }
    for (var i = 0; i < seg; i++) {
      final t0 = i * 2, b0 = i * 2 + 1, t1 = (i + 1) * 2, b1 = (i + 1) * 2 + 1;
      tris.addAll([t0, b0, b1, t0, b1, t1]);
    }
    final closed = thetaLen >= kTau - 1e-6;
    if (capTop && rTop > 1e-6 && closed) {
      final c = pos.length ~/ 3;
      pos.addAll([0, hy, 0]);
      for (var i = 0; i < seg; i++) {
        tris.addAll([c, i * 2, (i + 1) * 2]);
      }
    }
    if (capBot && rBot > 1e-6 && closed) {
      final c = pos.length ~/ 3;
      pos.addAll([0, -hy, 0]);
      for (var i = 0; i < seg; i++) {
        tris.addAll([c, (i + 1) * 2 + 1, i * 2 + 1]);
      }
    }
    return _make(pos, tris, color, kind, twoSided: twoSided, orient: !twoSided);
  }

  /// 球 / 球冠（`thetaLen < π` 时是帽子，`phiLen < 2π` 时是半开口壳）。
  static Mesh sphere(
    double r,
    int segU,
    int segV,
    int color,
    MatKind kind, {
    double phiStart = 0,
    double phiLen = kTau,
    double thetaStart = 0,
    double thetaLen = math.pi,
    bool twoSided = false,
  }) {
    final pos = <double>[];
    final tris = <int>[];
    for (var v = 0; v <= segV; v++) {
      final theta = thetaStart + thetaLen * v / segV;
      final st = math.sin(theta), ct = math.cos(theta);
      for (var u = 0; u <= segU; u++) {
        final phi = phiStart + phiLen * u / segU;
        pos.addAll([r * st * math.cos(phi), r * ct, r * st * math.sin(phi)]);
      }
    }
    final row = segU + 1;
    for (var v = 0; v < segV; v++) {
      for (var u = 0; u < segU; u++) {
        final a = v * row + u, b = a + 1, c = a + row, d = c + 1;
        tris.addAll([a, c, b, b, c, d]);
      }
    }
    return _make(pos, tris, color, kind, twoSided: twoSided, orient: !twoSided);
  }

  /// 胶囊（Three.js `CapsuleGeometry(r, len)`）：圆柱 + 两端半球，总高 `len + 2r`。
  ///
  /// 两端从**极点**（半径 0）开始绕，极点那一圈会产生 seg 个退化三角形，
  /// 由 [_make] 统一丢掉——这样两端是真正封口的，不会留洞。
  static Mesh capsule(double r, double len, int seg, int color, MatKind kind) {
    final pos = <double>[];
    final tris = <int>[];
    final hy = len / 2;
    const capV = 3;

    // 侧壁
    for (var i = 0; i <= seg; i++) {
      final a = kTau * i / seg;
      final c = math.cos(a), s = math.sin(a);
      pos.addAll([c * r, hy, s * r]);
      pos.addAll([c * r, -hy, s * r]);
    }
    for (var i = 0; i < seg; i++) {
      final t0 = i * 2, b0 = i * 2 + 1, t1 = (i + 1) * 2, b1 = (i + 1) * 2 + 1;
      tris.addAll([t0, b0, b1, t0, b1, t1]);
    }

    // 两端半球：v=0 是极点，逐圈与上一圈连成四边形
    for (var cap = 0; cap < 2; cap++) {
      final up = cap == 0;
      var prevBase = -1;
      for (var v = 0; v <= capV; v++) {
        final theta = math.pi / 2 * v / capV;
        final st = math.sin(theta), ct = math.cos(theta);
        final base = pos.length ~/ 3;
        for (var i = 0; i <= seg; i++) {
          final a = kTau * i / seg;
          pos.addAll([
            r * st * math.cos(a),
            up ? hy + r * ct : -hy - r * ct,
            r * st * math.sin(a),
          ]);
        }
        if (prevBase >= 0) {
          for (var i = 0; i < seg; i++) {
            if (up) {
              tris.addAll([
                prevBase + i, base + i, prevBase + i + 1,
                prevBase + i + 1, base + i, base + i + 1,
              ]);
            } else {
              tris.addAll([
                prevBase + i + 1, base + i + 1, prevBase + i,
                prevBase + i, base + i + 1, base + i,
              ]);
            }
          }
        }
        prevBase = base;
      }
    }
    return _make(pos, tris, color, kind);
  }

  /// 圆环 / 环弧（Three.js `TorusGeometry(R, r, radialSeg, tubularSeg, arc)`）。
  /// 位于 XY 平面，法线朝 ±Z。
  static Mesh torus(double R, double r, int segR, int segT, int color, MatKind kind,
      {double arc = kTau}) {
    final pos = <double>[];
    final tris = <int>[];
    for (var i = 0; i <= segT; i++) {
      final u = arc * i / segT;
      final cu = math.cos(u), su = math.sin(u);
      for (var j = 0; j <= segR; j++) {
        final v = kTau * j / segR;
        final cv = math.cos(v), sv = math.sin(v);
        pos.addAll([(R + r * cv) * cu, (R + r * cv) * su, r * sv]);
      }
    }
    final row = segR + 1;
    for (var i = 0; i < segT; i++) {
      for (var j = 0; j < segR; j++) {
        final a = i * row + j, b = a + 1, c = a + row, d = c + 1;
        tris.addAll([a, b, c, b, d, c]);
      }
    }
    return _make(pos, tris, color, kind, twoSided: true);
  }

  /// 车削体（Three.js `LatheGeometry`）：把 `[半径, 高度]` 轮廓绕 Y 轴旋转。
  ///
  /// 面序为「先绕一圈、每圈内再按轮廓段」，因此调用方可以按 `面序号 / 轮廓段数`
  /// 推出「第几圈」，据此做竖条纹等程序化花纹。
  static Mesh lathe(List<List<double>> profile, int seg, int color, MatKind kind,
      {Int32List? faceColors}) {
    final pos = <double>[];
    final tris = <int>[];
    for (var i = 0; i <= seg; i++) {
      final a = kTau * i / seg;
      final c = math.cos(a), s = math.sin(a);
      for (final p in profile) {
        pos.addAll([p[0] * c, p[1], p[0] * s]);
      }
    }
    final row = profile.length;
    for (var i = 0; i < seg; i++) {
      for (var j = 0; j < row - 1; j++) {
        final a = i * row + j, b = a + 1, c = a + row, d = c + 1;
        tris.addAll([a, b, c, b, d, c]);
      }
    }
    return _make(pos, tris, color, kind,
        twoSided: true, faceColors: faceColors);
  }

  /// 圆面（XY 平面，法线朝 +Z）。
  static Mesh circle(double r, int seg, int color, MatKind kind) {
    final pos = <double>[0, 0, 0];
    for (var i = 0; i <= seg; i++) {
      final a = kTau * i / seg;
      pos.addAll([r * math.cos(a), r * math.sin(a), 0]);
    }
    final tris = <int>[];
    for (var i = 0; i < seg; i++) {
      tris.addAll([0, 1 + i, 2 + i]);
    }
    return _make(pos, tris, color, kind, twoSided: true);
  }

  /// 平面（XY 平面，法线朝 +Z）。
  static Mesh plane(double w, double h, int color, MatKind kind) {
    final x = w / 2, y = h / 2;
    final pos = <double>[-x, -y, 0, x, -y, 0, x, y, 0, -x, y, 0];
    return _make(pos, <int>[0, 1, 2, 0, 2, 3], color, kind, twoSided: true);
  }

  /// 八面体（网页端霜灵头冠的水晶）。
  static Mesh octahedron(double r, int color, MatKind kind) {
    final pos = <double>[
      0, r, 0, -r, 0, 0, 0, 0, r, r, 0, 0, 0, 0, -r, 0, -r, 0,
    ];
    const tris = <int>[
      0, 1, 2, 0, 2, 3, 0, 3, 4, 0, 4, 1, 5, 2, 1, 5, 3, 2, 5, 4, 3, 5, 1, 4,
    ];
    return _make(pos, tris, color, kind);
  }
}

/* ============================== 光照与渲染 ============================== */

/// 一帧的光照参数（夜晚 / 白天两套，数值对齐网页端 `update()` 里的调参）。
class Scene3dLight {
  Scene3dLight.night()
      : ambI = 0.32,
        hemiI = 0.28,
        dirI = 0.6,
        ambCol = 0x6d82b8,
        hemiCol = 0x8fb0ff,
        dirCol = 0xa8c0ff,
        fogCol = 0x05070f;

  Scene3dLight.day()
      : ambI = 0.7,
        hemiI = 0.6,
        dirI = 1.15,
        ambCol = 0x93a6cc,
        hemiCol = 0x8fb0ff,
        dirCol = 0xffe9c4,
        fogCol = 0x0d1526;

  final double ambI, hemiI, dirI;
  final int ambCol, hemiCol, dirCol, fogCol;

  /// 平行光方向（对齐网页端 `dir.position.set(7, 13, 9)`，取反得到入射方向）。
  static final V3 lightDir = const V3(7, 13, 9).unit;
}

/// 一帧的渲染统计（供调试与单测断言）。
class Scene3dStats {
  int faces = 0;
  int drawn = 0;
  int culled = 0;
  int clipped = 0;
  int meshes = 0;
}

/// 把一批网格画到画布上：剔除 → 着色 → 排序 → **一次** `drawVertices` 提交。
///
/// 之所以能只提交一次：所有面都是不透明的，按视深从远到近排列后顺序覆盖即可
/// 得到正确遮挡（画家算法），不需要深度缓冲。
class Scene3dRenderer {
  Scene3dRenderer();

  /// 待绘制的网格与其世界变换。
  final List<Mesh> _meshes = [];
  final List<M4> _xforms = [];

  /// 逐面数据（三个顶点的屏幕坐标 + 视深 + 颜色）。
  final List<double> _px = [];
  final List<double> _py = [];
  final List<double> _pd = [];
  final List<int> _pc = [];

  final Scene3dStats stats = Scene3dStats();

  /// 逐帧复用同一实例时先清空。
  void begin() {
    _meshes.clear();
    _xforms.clear();
    stats.faces = 0;
    stats.drawn = 0;
    stats.culled = 0;
    stats.clipped = 0;
    stats.meshes = 0;
  }

  /// 加入一个网格（可见性由调用方判断，不可见的不要加）。
  void add(Mesh mesh, M4 world) {
    _meshes.add(mesh);
    _xforms.add(world);
    stats.meshes++;
  }

  /// 渲染整帧。
  void paint(ui.Canvas canvas, Scene3dFrame frame, Scene3dLight light) {
    _px.clear();
    _py.clear();
    _pd.clear();
    _pc.clear();

    for (var i = 0; i < _meshes.length; i++) {
      final mesh = _meshes[i];
      mesh.prepare(_xforms[i], frame);
      _collect(mesh, light);
    }

    final n = _pd.length;
    if (n == 0) return;

    // 画家算法：远的先画。
    final order = List<int>.generate(n, (i) => i);
    order.sort((a, b) => _pd[b].compareTo(_pd[a]));

    final fpos = Float32List(n * 6);
    final fcol = Int32List(n * 3);
    for (var k = 0; k < n; k++) {
      final f = order[k];
      // 必须写在第 k 个槽位（绘制次序），而不是第 f 个槽位（原始面序号）——
      // 写成 f * 6 会让缓冲保持「加入顺序」，排序结果等于白算，
      // 表现就是「后加入的网格永远盖住先加入的」。
      final p = k * 6, c = k * 3;
      fpos[p] = _px[f * 3];
      fpos[p + 1] = _py[f * 3];
      fpos[p + 2] = _px[f * 3 + 1];
      fpos[p + 3] = _py[f * 3 + 1];
      fpos[p + 4] = _px[f * 3 + 2];
      fpos[p + 5] = _py[f * 3 + 2];
      fcol[c] = _pc[f];
      fcol[c + 1] = _pc[f];
      fcol[c + 2] = _pc[f];
    }

    final v = ui.Vertices.raw(ui.VertexMode.triangles, fpos, colors: fcol);
    // BlendMode.dst：顶点色即最终颜色（不加 shader）。
    canvas.drawVertices(v, ui.BlendMode.dst, ui.Paint());
  }

  /// 逐面：视锥/背面剔除 → 兰伯特着色 → 压入待排序队列。
  void _collect(Mesh mesh, Scene3dLight light) {
    final tris = mesh.tris;
    final n = mesh.faceCount;
    stats.faces += n;

    // 光照常量与相机位置提到循环外，逐面上万次，省下的乘法很可观。
    final ambR = light.ambI * _r(light.ambCol);
    final ambG = light.ambI * _g(light.ambCol);
    final ambB = light.ambI * _b(light.ambCol);
    final hemiR = light.hemiI * _r(light.hemiCol);
    final hemiG = light.hemiI * _g(light.hemiCol);
    final hemiB = light.hemiI * _b(light.hemiCol);
    final dirR = light.dirI * _r(light.dirCol);
    final dirG = light.dirI * _g(light.dirCol);
    final dirB = light.dirI * _b(light.dirCol);
    final fogR = _r(light.fogCol), fogG = _g(light.fogCol), fogB = _b(light.fogCol);
    final ambMul = _kindAmbient(mesh.kind);
    final difMul = _kindDiffuse(mesh.kind);
    final specMul = _kindSpecular(mesh.kind);
    final rimMul = _kindRim(mesh.kind);
    final faceColors = mesh.faceColors;
    final baseColor = mesh.color;
    final lx = Scene3dLight.lightDir.x;
    final ly = Scene3dLight.lightDir.y;
    final lz = Scene3dLight.lightDir.z;
    final ex0 = _eyeX, ey0 = _eyeY, ez0 = _eyeZ;

    for (var f = 0; f < n; f++) {
      final i0 = tris[f * 3], i1 = tris[f * 3 + 1], i2 = tris[f * 3 + 2];
      if (mesh.behind[i0] != 0 || mesh.behind[i1] != 0 || mesh.behind[i2] != 0) {
        stats.clipped++;
        continue;
      }

      final a = i0 * 3, b = i1 * 3, c = i2 * 3;
      final w = mesh.world;
      final ax = w[a], ay = w[a + 1], az = w[a + 2];
      final bx = w[b], by = w[b + 1], bz = w[b + 2];
      final cx = w[c], cy = w[c + 1], cz = w[c + 2];

      // 世界空间面法线
      final ux = bx - ax, uy = by - ay, uz = bz - az;
      final vx = cx - ax, vy = cy - ay, vz = cz - az;
      var nx = uy * vz - uz * vy;
      var ny = uz * vx - ux * vz;
      var nz = ux * vy - uy * vx;
      final nl = math.sqrt(nx * nx + ny * ny + nz * nz);
      if (nl < 1e-12) continue; // 退化面
      nx /= nl;
      ny /= nl;
      nz /= nl;

      final s = mesh.screen;
      final s0 = i0 * 3, s1 = i1 * 3, s2 = i2 * 3;
      final x0 = s[s0], y0 = s[s0 + 1];
      final x1 = s[s1], y1 = s[s1 + 1];
      final x2 = s[s2], y2 = s[s2 + 1];

      // 亚像素面剔除（放在最前面：省掉后面全部计算）
      final area = ((x1 - x0) * (y2 - y0) - (x2 - x0) * (y1 - y0)).abs() * 0.5;
      if (area < kMinFaceArea) {
        stats.culled++;
        continue;
      }

      final depth = (s[s0 + 2] + s[s1 + 2] + s[s2 + 2]) / 3;
      final mx = (ax + bx + cx) / 3, my = (ay + by + cy) / 3, mz = (az + bz + cz) / 3;

      if (!mesh.twoSided) {
        final ex = ex0 - mx, ey = ey0 - my, ez = ez0 - mz;
        if (nx * ex + ny * ey + nz * ez <= 0) {
          stats.culled++;
          continue;
        }
      }

      // 兰伯特 + 半球环境光
      final diff = math.max(0.0, nx * lx + ny * ly + nz * lz);
      final hemi = (ny * 0.5 + 0.5);
      final argb = faceColors == null ? baseColor : faceColors[f];
      final br = ((argb >> 16) & 0xFF) / 255.0;
      final bg = ((argb >> 8) & 0xFF) / 255.0;
      final bb = (argb & 0xFF) / 255.0;

      final lr = ambR * ambMul + hemiR * hemi + dirR * difMul * diff;
      final lg = ambG * ambMul + hemiG * hemi + dirG * difMul * diff;
      final lb = ambB * ambMul + hemiB * hemi + dirB * difMul * diff;
      var rr = br * lr;
      var gg = bg * lg;
      var bbv = bb * lb;

      // 视线向量：高光与边缘光都要用，逐面只算一次。
      final ex = ex0 - mx, ey = ey0 - my, ez = ez0 - mz;
      final el = math.sqrt(ex * ex + ey * ey + ez * ez);

      // 金属/点缀的高光（半程向量）
      if (specMul > 0 && el > 1e-6) {
        final hx = lx + ex / el, hy = ly + ey / el, hz = lz + ez / el;
        final hl = math.sqrt(hx * hx + hy * hy + hz * hz);
        if (hl > 1e-6) {
          final ndh = math.max(0.0, (nx * hx + ny * hy + nz * hz) / hl);
          final sp = specMul * math.pow(ndh, 26).toDouble();
          rr += sp;
          gg += sp;
          bbv += sp;
        }
      }

      // 边缘光（Fresnel 近似）：视线与法线越垂直越亮。
      // 软件渲染只有平片着色，这一项让角色轮廓从暗背景里「立」起来。
      if (rimMul > 0 && el > 1e-6) {
        final ndv = (nx * ex + ny * ey + nz * ez) / el;
        final rim = rimMul * math.pow(1.0 - ndv.abs(), 3).toDouble();
        rr += rim * 0.62;
        gg += rim * 0.76;
        bbv += rim;
      }

      // 雾：把远处压向背景色，制造纵深。
      final fog = ((depth - 24) / (46 - 24)).clamp(0.0, 1.0);
      if (fog > 0) {
        rr += (fogR - rr) * fog;
        gg += (fogG - gg) * fog;
        bbv += (fogB - bbv) * fog;
      }

      _px.addAll([s[s0], s[s1], s[s2]]);
      _py.addAll([s[s0 + 1], s[s1 + 1], s[s2 + 1]]);
      _pd.add(depth);
      _pc.add(_pack(rr, gg, bbv));
      stats.drawn++;
    }
  }

  double _eyeX = 0, _eyeY = 0, _eyeZ = 0;

  /// 每帧渲染前告知相机位置（背面剔除要用）。
  void setEye(V3 eye) {
    _eyeX = eye.x;
    _eyeY = eye.y;
    _eyeZ = eye.z;
  }

  static double _kindAmbient(MatKind k) => switch (k) {
        MatKind.cloth => 1.0,
        MatKind.metal => 0.88,
        MatKind.skin => 1.05,
        MatKind.accent => 1.0,
        MatKind.dark => 0.82,
      };

  static double _kindDiffuse(MatKind k) => switch (k) {
        MatKind.cloth => 0.95,
        MatKind.metal => 1.1,
        MatKind.skin => 0.85,
        MatKind.accent => 1.0,
        MatKind.dark => 0.62,
      };

  /// 边缘光强度：金属最亮（要反光感），布料最弱（避免像塑料）。
  static double _kindRim(MatKind k) => switch (k) {
        MatKind.metal => 0.20,
        MatKind.accent => 0.13,
        MatKind.skin => 0.15,
        MatKind.cloth => 0.09,
        MatKind.dark => 0.04,
      };

  static double _kindSpecular(MatKind k) => switch (k) {
        MatKind.metal => 0.42,
        MatKind.accent => 0.2,
        MatKind.skin => 0.05,
        MatKind.cloth => 0.0,
        MatKind.dark => 0.03,
      };

  static double _r(int c) => ((c >> 16) & 0xFF) / 255;
  static double _g(int c) => ((c >> 8) & 0xFF) / 255;
  static double _b(int c) => (c & 0xFF) / 255;

  static int _pack(double r, double g, double b) {
    final ri = (r * 255).round().clamp(0, 255);
    final gi = (g * 255).round().clamp(0, 255);
    final bi = (b * 255).round().clamp(0, 255);
    return 0xFF000000 | (ri << 16) | (gi << 8) | bi;
  }
}
