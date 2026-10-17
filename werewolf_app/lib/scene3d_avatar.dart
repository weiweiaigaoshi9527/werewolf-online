/// 3D 视角的皮肤与角色造型（P5）：11 套皮肤、坐姿人形、凳子与墓碑。
///
/// 逐条对齐网页端 `static/js/scene3d.js` 的 `SKINS` 表与 `buildAvatar()`：
/// 同一套皮肤 id、同一套配色、同一套身体比例（凳面高 0.46、头顶约 1.47、坐姿腿型）。
/// 差异只在**分段数与贴图**——这是 CPU 软件渲染，没有纹理采样，
/// 因此网页端 `clothTexture()` 的程序化布纹改为「逐面配色」近似（见 [clothFaceColors]）。
library;

import 'dart:math' as math;
import 'dart:typed_data';

import 'scene3d.dart';

/* ============================== 皮肤 ============================== */

/// 布纹样式（对应网页端 `SKINS[].tex`）。
enum ClothTex { plain, stripe, fur, rune, metal }

/// 头部装饰（对应网页端 `SKINS[].hat`）。
enum HatKind { none, ears, helm, witchhat, jester, crown, hood, crystals, collar }

/// 一套 3D 皮肤。与后端 `ShopCatalog` 的 SKIN3D 资源、网页端 `SKINS` 一一对应。
class WwSkin {
  const WwSkin({
    required this.id,
    required this.name,
    required this.body,
    required this.head,
    required this.accent,
    required this.hat,
    this.hair,
    this.cape,
    this.glow,
    this.tex = ClothTex.plain,
    this.ghost = false,
    this.wings = false,
    this.scythe = false,
  });

  final String id;

  /// 中文名（网页端名牌上会显示非村民的皮肤名）。
  final String name;
  final int body;
  final int head;
  final int accent;
  final HatKind hat;
  final int? hair;
  final int? cape;
  final int? glow;
  final ClothTex tex;

  /// 游魂：半透明质感，软件渲染下改用更亮的配色近似。
  final bool ghost;
  final bool wings;
  final bool scythe;
}

/// 11 套皮肤，顺序与配色照搬网页端（未知 id 一律回落村民）。
const Map<String, WwSkin> kWwSkins = {
  'villager': WwSkin(
      id: 'villager',
      name: '村民',
      body: 0xFF6B7A99,
      head: 0xFFF0D9C0,
      accent: 0xFF8FA6C8,
      hat: HatKind.none,
      hair: 0xFF3A2A1C),
  'jester': WwSkin(
      id: 'jester',
      name: '弄臣',
      body: 0xFFA8323F,
      head: 0xFFF5E6D0,
      accent: 0xFFF0C04A,
      hat: HatKind.jester,
      hair: 0xFF2B2118,
      cape: 0xFF7A1F2A,
      tex: ClothTex.stripe),
  'ghost': WwSkin(
      id: 'ghost',
      name: '游魂',
      body: 0xFF9FD8E8,
      head: 0xFFE4F7FF,
      accent: 0xFF7FE6FF,
      hat: HatKind.none,
      ghost: true),
  'wolf': WwSkin(
      id: 'wolf',
      name: '狼影',
      body: 0xFF565C68,
      head: 0xFF8B909C,
      accent: 0xFF2F3440,
      hat: HatKind.ears,
      cape: 0xFF2B3038,
      tex: ClothTex.fur),
  'knight': WwSkin(
      id: 'knight',
      name: '骑士',
      body: 0xFFB9C3D4,
      head: 0xFFD8DEE9,
      accent: 0xFF8FA2C0,
      hat: HatKind.helm,
      cape: 0xFF2B3A5C,
      tex: ClothTex.metal),
  'witch': WwSkin(
      id: 'witch',
      name: '女巫',
      body: 0xFF5B3A8C,
      head: 0xFFF0D9C0,
      accent: 0xFFC9A3FF,
      hat: HatKind.witchhat,
      hair: 0xFF1E1430,
      cape: 0xFF3A2560,
      tex: ClothTex.rune),
  'frost': WwSkin(
      id: 'frost',
      name: '霜灵',
      body: 0xFF8FD8FF,
      head: 0xFFE6F7FF,
      accent: 0xFF4FB8FF,
      hat: HatKind.crystals,
      glow: 0xFF2A6F9C,
      tex: ClothTex.rune),
  'vampire': WwSkin(
      id: 'vampire',
      name: '血族',
      body: 0xFF2A2030,
      head: 0xFFE8DFE6,
      accent: 0xFFD03050,
      hat: HatKind.collar,
      hair: 0xFF120C14,
      cape: 0xFF8C1A2B),
  'shadow': WwSkin(
      id: 'shadow',
      name: '影舞者',
      body: 0xFF3A2A55,
      head: 0xFF6A5A85,
      accent: 0xFF9A6CFF,
      hat: HatKind.hood,
      cape: 0xFF1A1230,
      tex: ClothTex.rune,
      wings: true),
  'royal': WwSkin(
      id: 'royal',
      name: '王族',
      body: 0xFF8C1A2B,
      head: 0xFFF0D9C0,
      accent: 0xFFFFD76A,
      hat: HatKind.crown,
      hair: 0xFF2B2118,
      cape: 0xFFC8A03A),
  'reaper': WwSkin(
      id: 'reaper',
      name: '死神',
      body: 0xFF1A1A22,
      head: 0xFF26262F,
      accent: 0xFF7F8CFF,
      hat: HatKind.hood,
      cape: 0xFF101018,
      tex: ClothTex.rune,
      scythe: true),
};

/// 取皮肤；未知 id（含 null 语义的 'villager'）回退村民。
WwSkin skinOf(String? id) => kWwSkins[id] ?? kWwSkins['villager']!;

/// 皮肤名（村民不显示，与网页端名牌逻辑一致）。
String skinLabel(String? id) {
  final s = skinOf(id);
  return s.name == '村民' ? '' : s.name;
}

/* ============================== 程序化布纹 ============================== */

int _clamp8(int v) => v < 0 ? 0 : (v > 255 ? 255 : v);

/// 按比例缩放亮度（f<1 变暗、f>1 变亮）。
int shadeColor(int c, double f) {
  final r = _clamp8((((c >> 16) & 0xFF) * f).round());
  final g = _clamp8((((c >> 8) & 0xFF) * f).round());
  final b = _clamp8(((c & 0xFF) * f).round());
  return 0xFF000000 | (r << 16) | (g << 8) | b;
}

/// 两色线性插值。
int mixColor(int a, int b, double t) {
  int ch(int shift) {
    final av = (a >> shift) & 0xFF, bv = (b >> shift) & 0xFF;
    return _clamp8((av + (bv - av) * t).round());
  }

  return 0xFF000000 | (ch(16) << 16) | (ch(8) << 8) | ch(0);
}

/// 确定性伪随机（同一个面每次构建都得到同样的扰动，避免造型逐帧抖动）。
double _noise(int i, int j) {
  var h = (i * 73856093) ^ (j * 19349663);
  h = (h ^ (h >> 13)) * 1274126177;
  return ((h ^ (h >> 16)) & 0xFFFF) / 65535.0;
}

/// 生成车削体/圆柱侧壁的逐面配色，近似网页端 `clothTexture()` 的四种花纹。
///
/// 面序约定见 [Geo.lathe]：先按圈（段）推进，每圈内再按轮廓段推进，
/// 每段两个三角形。所以 `面序号 ~/ (rings * 2)` 就是圈号。
Int32List clothFaceColors(
    ClothTex tex, int base, int accent, int seg, int rings) {
  final perSeg = rings * 2;
  final out = Int32List(seg * perSeg);
  for (var f = 0; f < out.length; f++) {
    final i = f ~/ perSeg; // 圈号
    final j = (f ~/ 2) % rings; // 轮廓段号
    switch (tex) {
      case ClothTex.stripe:
        // 竖条纹：隔一圈换点缀色（弄臣）。
        out[f] = (i % 2 == 0) ? base : accent;
      case ClothTex.fur:
        // 毛皮：逐面细碎明暗（狼影）。
        out[f] = shadeColor(base, 0.86 + _noise(i, j) * 0.30);
      case ClothTex.rune:
        // 符文：大部分素色，零星几处提亮（女巫 / 霜灵 / 影舞者 / 死神）。
        out[f] = _noise(i, j) > 0.82 ? mixColor(base, accent, 0.45) : base;
      case ClothTex.metal:
        // 甲片：横向明暗带（骑士）。
        out[f] = shadeColor(base, (i % 2 == 0) ? 1.07 : 0.94);
      case ClothTex.plain:
        // 素色：极轻微的明暗交替，避免整块塑料感。
        out[f] = shadeColor(base, (j % 2 == 0) ? 1.0 : 0.955);
    }
  }
  return out;
}

/* ============================== 可动部件 ============================== */

/// 会被逐帧动画改写的部件（连同它的静止变换）。
class AnimPart {
  AnimPart(this.part, this.base);

  final Part part;

  /// 静止变换（含位置/朝向），动画只在它之上叠加。
  final M4 base;

  /// 叠加纵向缩放（呼吸 / 眨眼）。
  void scaleY(double s) => part.local = base.mul(M4.scale(1, s, 1));

  /// 叠加绕 Y 的旋转（转头）。
  void rotY(double r) => part.local = base.mul(M4.rotY(r));

  /// 叠加整体缩放（提灯火焰的跳动）。
  void scaleAll(double s) => part.local = base.mul(M4.scale(s, s, s));
}

/// 一个角色：全部部件 + 需要动画的引用。
class Avatar {
  Avatar({
    required this.parts,
    required this.torso,
    required this.head,
    required this.eyes,
  });

  final List<Part> parts;
  final AnimPart torso;
  final AnimPart head;

  /// 眼白 + 瞳孔（眨眼时纵向压扁）。
  final List<AnimPart> eyes;
}

/* ============================== 造型构建 ============================== */

/// 坐姿人形的关键高度（与网页端同名常量一致）。
const double kSeatTop = 0.46;

/// 构建一套皮肤对应的坐姿人形。
///
/// 局部坐标：`y = 0` 为地面，凳面在 [kSeatTop]，角色坐在凳面上、面朝 +Z。
Avatar buildAvatar(String? skinId) {
  final sk = skinOf(skinId);
  final parts = <Part>[];

  final cloth = sk.body;
  final skinC = sk.head;
  final accent = sk.accent;
  const dark = 0xFF2A2A33;

  /// 放一个部件：位置 / 三轴旋转 / 三轴缩放，应用顺序与 Three.js 一致（缩放→旋转→平移）。
  Part put(
    Mesh m, {
    double x = 0,
    double y = 0,
    double z = 0,
    double rx = 0,
    double ry = 0,
    double rz = 0,
    double sx = 1,
    double sy = 1,
    double sz = 1,
  }) {
    var t = M4.translate(x, y, z);
    if (rz != 0) t = t.mul(M4.rotZ(rz));
    if (ry != 0) t = t.mul(M4.rotY(ry));
    if (rx != 0) t = t.mul(M4.rotX(rx));
    if (sx != 1 || sy != 1 || sz != 1) t = t.mul(M4.scale(sx, sy, sz));
    final p = Part(m, t);
    parts.add(p);
    return p;
  }

  /// 布料的逐面配色（素色皮肤用 plain，同样能得到一点织物明暗）。
  Int32List clothFaces(int seg, int rings) =>
      clothFaceColors(sk.tex, cloth, accent, seg, rings);

  // ---- 腿：大腿前伸、小腿垂下、脚踩地（坐姿）----
  for (var side = -1; side <= 1; side += 2) {
    put(Geo.capsule(0.072, 0.20, 5, cloth, MatKind.cloth),
        x: 0.105 * side, y: 0.50, z: 0.15, rx: math.pi / 2 + 0.10);
    put(Geo.capsule(0.062, 0.24, 5, cloth, MatKind.cloth),
        x: 0.105 * side, y: 0.28, z: 0.34);
    put(Geo.box(0.13, 0.07, 0.22, dark, MatKind.dark),
        x: 0.105 * side, y: 0.045, z: 0.40);
  }

  // ---- 骨盆 + 躯干（车削出腰细胸宽的轮廓）----
  put(Geo.sphere(0.19, 10, 6, cloth, MatKind.cloth), y: 0.55, sy: 0.72, sz: 0.86);

  final torsoProfile = <List<double>>[
    [0.150, 0.00], [0.180, 0.06], [0.210, 0.14], [0.232, 0.24],
    [0.240, 0.32], [0.230, 0.40], [0.185, 0.47], [0.120, 0.52], [0.075, 0.53],
  ];
  final torsoBase = M4.translate(0, 0.56, 0);
  final torsoPart = Part(
    Geo.lathe(torsoProfile, 12, cloth, MatKind.cloth,
        faceColors: clothFaces(12, torsoProfile.length - 1)),
    torsoBase,
  );
  parts.add(torsoPart);

  // ---- 肩 / 臂 / 手 ----
  for (var s = -1; s <= 1; s += 2) {
    put(Geo.sphere(0.085, 6, 4, cloth, MatKind.cloth), x: 0.20 * s, y: 1.02);
    put(Geo.capsule(0.058, 0.17, 4, cloth, MatKind.cloth),
        x: 0.245 * s, y: 0.895, z: 0.015, rz: -0.20 * s);
    put(Geo.sphere(0.056, 4, 3, cloth, MatKind.cloth), x: 0.272 * s, y: 0.765, z: 0.03);
    put(Geo.capsule(0.05, 0.17, 4, cloth, MatKind.cloth),
        x: 0.272 * s, y: 0.70, z: 0.125, rx: 0.95);
    put(Geo.sphere(0.055, 5, 3, skinC, MatKind.skin),
        x: 0.272 * s, y: 0.632, z: 0.215, sy: 0.8, sz: 1.15);
  }

  // ---- 脖子 + 头 ----
  put(Geo.cylinder(0.058, 0.07, 0.11, 6, skinC, MatKind.skin, capTop: false, capBot: false),
      y: 1.115);
  final headBase = M4.translate(0, 1.30, 0);
  final headPart = Part(Geo.sphere(0.17, 12, 8, skinC, MatKind.skin), headBase);
  parts.add(headPart);

  // ---- 五官 ----
  final eyes = <AnimPart>[];
  for (var e = -1; e <= 1; e += 2) {
    const white = 0xFFF6F7FB, pupil = 0xFF1B1A22;
    final wBase = M4.translate(0.063 * e, 1.325, 0.134).mul(M4.scale(1, 0.86, 0.8));
    final wp = Part(Geo.sphere(0.033, 5, 3, white, MatKind.skin), wBase);
    parts.add(wp);
    final pBase = M4.translate(0.063 * e, 1.325, 0.152).mul(M4.scale(1, 1, 0.7));
    final pp = Part(Geo.sphere(0.019, 4, 3, pupil, MatKind.dark), pBase);
    parts.add(pp);
    eyes.add(AnimPart(wp, wBase));
    eyes.add(AnimPart(pp, pBase));
    // 眉毛
    put(Geo.box(0.064, 0.013, 0.02, dark, MatKind.dark),
        x: 0.063 * e, y: 1.368, z: 0.146, rz: 0.13 * e);
  }
  put(Geo.cylinder(0, 0.022, 0.06, 5, skinC, MatKind.skin), y: 1.303, z: 0.156, rx: math.pi / 2);
  put(Geo.box(0.058, 0.012, 0.016, 0xFF7C3A44, MatKind.dark), y: 1.262, z: 0.150);

  // ---- 头发（兜帽 / 头盔会盖住，跳过）----
  if (sk.hat != HatKind.hood && sk.hat != HatKind.helm && sk.hair != null) {
    put(Geo.sphere(0.178, 12, 3, sk.hair!, MatKind.cloth, thetaLen: 1.05),
        y: 1.30, sy: 1.06, sz: 1.05);
  }

  // ---- 披风（开口锥壳，绕 Y 转一点让开口朝前）----
  if (sk.cape != null) {
    put(
      Geo.cylinder(0, 0.40, 0.92, 12, sk.cape!, MatKind.cloth,
          capTop: false, capBot: false, thetaLen: math.pi * 1.3, twoSided: true),
      y: 0.80,
      z: -0.05,
      ry: math.pi * 0.35,
    );
  }

  // ---- 头饰 / 身体装饰 ----
  switch (sk.hat) {
    case HatKind.ears:
      for (var q = -1; q <= 1; q += 2) {
        put(Geo.cylinder(0, 0.072, 0.20, 5, cloth, MatKind.cloth),
            x: 0.115 * q, y: 1.465, z: -0.01, rz: -0.20 * q);
      }
      put(Geo.cylinder(0, 0.085, 0.19, 6, cloth, MatKind.cloth),
          y: 1.278, z: 0.20, rx: math.pi / 2);
    case HatKind.helm:
      put(Geo.sphere(0.198, 8, 4, sk.body, MatKind.metal, thetaLen: math.pi * 0.66), y: 1.31);
      put(Geo.box(0.24, 0.05, 0.05, dark, MatKind.dark), y: 1.30, z: 0.155);
      put(Geo.cylinder(0, 0.05, 0.30, 5, 0xFFD24B5A, MatKind.cloth), y: 1.60);
    case HatKind.witchhat:
      put(Geo.cylinder(0.30, 0.30, 0.035, 10, cloth, MatKind.cloth), y: 1.435);
      put(Geo.cylinder(0, 0.195, 0.48, 8, cloth, MatKind.cloth), y: 1.66, rz: 0.10);
    case HatKind.jester:
      put(Geo.sphere(0.196, 8, 4, accent, MatKind.cloth, thetaLen: math.pi * 0.5), y: 1.40);
      for (var k = 0; k < 3; k++) {
        final ang = (k - 1) * 0.72;
        put(Geo.cylinder(0, 0.055, 0.26, 5, k == 1 ? 0xFF2F8F6F : sk.body, MatKind.cloth),
            x: math.sin(ang) * 0.13, y: 1.60, z: math.cos(ang) * 0.05, rz: -ang * 0.6);
      }
    case HatKind.crown:
      put(
          Geo.cylinder(0.185, 0.185, 0.13, 8, accent, MatKind.accent,
              capTop: false, capBot: false, twoSided: true),
          y: 1.50);
      for (var c = 0; c < 5; c++) {
        final a = c / 5 * kTau;
        put(Geo.cylinder(0, 0.045, 0.15, 5, accent, MatKind.accent),
            x: math.cos(a) * 0.165, y: 1.61, z: math.sin(a) * 0.165);
      }
    case HatKind.hood:
      final hoodC = sk.cape ?? sk.body;
      put(Geo.sphere(0.238, 8, 5, hoodC, MatKind.cloth, thetaLen: math.pi * 0.76), y: 1.305);
      put(
          Geo.cylinder(0, 0.30, 0.44, 8, hoodC, MatKind.cloth,
              capTop: false, capBot: false, twoSided: true),
          y: 1.14,
          rx: math.pi);
    case HatKind.crystals:
      for (var c = 0; c < 5; c++) {
        final a = c / 5 * kTau;
        put(Geo.octahedron(0.055 + (c % 3) * 0.012, accent, MatKind.accent),
            x: math.cos(a) * 0.22, y: 1.42 + (c % 2) * 0.14, z: math.sin(a) * 0.22);
      }
    case HatKind.collar:
      put(
          Geo.cylinder(0, 0.26, 0.30, 8, accent, MatKind.accent,
              capTop: false, capBot: false, twoSided: true),
          y: 1.13,
          rx: math.pi);
    case HatKind.none:
      break;
  }

  // ---- 影翼 ----
  if (sk.wings) {
    for (var w = -1; w <= 1; w += 2) {
      put(
          Geo.cylinder(0, 0.18, 0.80, 4, sk.accent, MatKind.accent,
              capTop: false, capBot: false, twoSided: true),
          x: 0.34 * w,
          y: 0.95,
          z: -0.20,
          rx: 0.32,
          rz: w * 0.9);
    }
  }

  // ---- 镰刀 ----
  if (sk.scythe) {
    put(Geo.cylinder(0.03, 0.03, 1.6, 5, 0xFF3A3A44, MatKind.dark), x: 0.40, y: 0.82, z: 0.10, rz: 0.13);
    put(
        Geo.torus(0.30, 0.04, 4, 8, 0xFFC9D2E6, MatKind.metal, arc: math.pi * 0.9),
        x: 0.52,
        y: 1.52,
        z: 0.10,
        rx: math.pi / 2,
        rz: -0.5);
  }

  return Avatar(
    parts: parts,
    torso: AnimPart(torsoPart, torsoBase),
    head: AnimPart(headPart, headBase),
    eyes: eyes,
  );
}

/* ============================== 凳子与墓碑 ============================== */

/// 凳子：凳面 + 靠背 + 两条前腿（网页端 `buildSeats` 里的椅子部分）。
List<Part> buildStool() {
  const chair = 0xFF2A3350;
  final out = <Part>[];
  out.add(Part(
      Geo.cylinder(0.46, 0.50, kSeatTop, 10, chair, MatKind.cloth),
      M4.translate(0, kSeatTop / 2, 0)));
  out.add(Part(Geo.box(0.86, 0.70, 0.11, chair, MatKind.cloth),
      M4.translate(0, 0.92, -0.46)));
  for (var lg = -1; lg <= 1; lg += 2) {
    out.add(Part(
        Geo.cylinder(0.05, 0.06, kSeatTop, 5, chair, MatKind.cloth),
        M4.translate(0.32 * lg, kSeatTop / 2, 0)));
  }
  return out;
}

/// 墓碑：碑身 + 圆顶 + 底座（网页端 `buildTomb`）。
///
/// 碑面上的 R.I.P / 座位号 / 昵称在网页端是**贴图**；软件渲染没有纹理采样，
/// 改为由 2D 叠加层把同样三行字画在碑面投影位置（见 `scene3d_view.dart`）。
List<Part> buildTomb() {
  const stone = 0xFF8D93A3;
  const plinth = 0xFF6D7383;
  final out = <Part>[];
  out.add(Part(Geo.box(0.78, 1.0, 0.16, stone, MatKind.cloth), M4.translate(0, 0.6, 0)));
  out.add(Part(
      Geo.cylinder(0.39, 0.39, 0.16, 10, stone, MatKind.cloth),
      M4.translate(0, 1.1, 0).mul(M4.rotX(math.pi / 2))));
  out.add(Part(Geo.box(0.96, 0.14, 0.34, plinth, MatKind.cloth), M4.translate(0, 0.07, 0)));
  // 碑面：比碑身略暗，给 2D 碑文一个可读的底
  out.add(Part(
      Geo.plane(0.66, 0.66, shadeColor(stone, 0.92), MatKind.cloth),
      M4.translate(0, 0.66, 0.086)));
  return out;
}
