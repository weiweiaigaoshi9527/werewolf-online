/// 3D 视角的场景组装与逐帧更新（P5）。
///
/// 对照网页端 `static/js/scene3d.js` 的 `init()` / `buildSeats()` / `update()` / `loop()`：
/// 圆桌 + 提灯 + 地面 + 星空、座位圆环布局（1 号位在正前方）、相机阻尼与拖拽、
/// 近景自动跟随发言者、昼夜光照切换、呼吸/眨眼/转头/发言者光环。
///
/// 数据完全复用 `game.state` 推送（`seats` / `phase` / `currentActor` / `mySeat` / `feed`），
/// 与 2D 座位圈同源，不改动任何对局逻辑。
library;

import 'dart:math' as math;

import 'scene3d.dart';
import 'scene3d_avatar.dart';

/// 座位到圆心的距离（网页端 `R_SEAT`）。
const double kRSeat = 5.0;

/// 圆桌半径（网页端 `R_TABLE`）。
const double kRTable = 3.1;

/// 相机默认注视点高度（网页端 `FOCUS_Y`）。
const double kFocusY = 1.1;

/// 近景阈值：距离小于它就把注视点移到当前发言者身上（网页端 `cdist < 10.5`）。
const double kNearFocusDist = 10.5;

/// 3D 视角相机。目标值与当前值分开存，[tick] 里做阻尼，避免拖动时生硬跳变。
class Scene3dCamera {
  /// 目标方位角 / 俯仰 / 距离。
  double az = 0, el = 0.62, dist = 16.5;

  /// 当前值（阻尼后，真正用于成像）。
  double caz = 0, cel = 0.62, cdist = 16.5;

  /// 注视点（近景时跟随当前发言者）。
  V3 look = const V3(0, kFocusY, 0);

  /// 近景时把相机绕到发言者「外侧」的基准角，避免圆桌横在镜头与角色之间。
  double baseAngle = 0;

  /// 视场角与网页端 `PerspectiveCamera(45, …)` 一致。
  static const double fovY = 45 * math.pi / 180;

  void reset() {
    az = 0;
    el = 0.62;
    dist = 16.5;
  }

  /// 直接设定相机（供「远景 / 近景」快捷视角使用）。
  void set({double? a, double? e, double? d}) {
    if (a != null) az = a;
    if (e != null) el = e;
    if (d != null) dist = d.clamp(3.0, 30.0);
  }

  /// 拖拽旋转（网页端 `az -= dx * 0.006; el = clamp(0.18, 1.35, el + dy * 0.004)`）。
  void dragBy(double dx, double dy) {
    az -= dx * 0.006;
    el = (el + dy * 0.004).clamp(0.18, 1.35);
  }

  /// 滚轮 / 双指张合缩放。
  void zoomBy(double delta) => dist = (dist + delta).clamp(8.0, 30.0);

  /// 按比例缩放（双指张合：张开拉近）。
  void zoomScale(double factor) =>
      dist = (dist * factor).clamp(8.0, 30.0);

  /// 相机世界坐标。
  V3 get eye {
    final ca = baseAngle + caz;
    final cy = math.sin(cel) * cdist;
    final cr = math.cos(cel) * cdist;
    return V3(
      look.x + math.sin(ca) * cr,
      cy + look.y,
      look.z + math.cos(ca) * cr,
    );
  }
}

/// 一个座位节点：凳子 / 角色 / 墓碑 + 运行时状态。
class Scene3dSeatNode {
  Scene3dSeatNode({required this.seat, required this.angle, required this.skinId});

  final int seat;

  /// 座位在圆周上的角度（1 号位在正前方）。
  final double angle;

  String skinId;

  /// 静止朝向（面朝桌心）。
  late final double baseYaw = math.atan2(-x, -z);

  double get x => math.cos(angle) * kRSeat;
  double get z => math.sin(angle) * kRSeat;

  /// 当前朝向 / 当前高度偏移（发言时轻微起伏）。
  double yaw = 0;
  double y = 0;

  /// 转头注视发言者的当前角度（缓动）。
  double look = 0;

  late Avatar avatar;
  late List<Part> stool;
  late List<Part> tomb;

  bool avatarVisible = true;
  bool tombVisible = false;
  bool acting = false;

  /// 地面光环不透明度（发言者 0.9、自己 0.32、其他 0）。
  double ringOpacity = 0;
  int ringColor = 0x6CF0FF;

  // ---- 来自 game.state 的座位信息（供名牌 / 气泡 / 碑文使用）----
  String nickname = '';
  String title = '';
  bool isSheriff = false;
  bool silenced = false;
  bool isMe = false;
  bool bot = false;

  /// 座位根变换（位置 + 朝向）。
  M4 get group => M4.translate(x, y, z).mul(M4.rotY(yaw));

  /// 名牌锚点（出局时压低，避免与墓碑重叠）。
  V3 get labelAnchor => V3(x, tombVisible ? 1.72 : 2.28, z);

  /// 气泡锚点。
  V3 get bubbleAnchor => V3(x, tombVisible ? 3.0 : 3.35, z);
}

/// 3D 视角场景：静态陈设 + 座位节点 + 相机 + 逐帧动画。
class Scene3dScene {
  Scene3dScene() {
    _buildStatics();
    _buildStars();
  }

  final Scene3dCamera camera = Scene3dCamera();

  /// 静态陈设（圆桌 / 提灯）。
  final List<Part> statics = [];

  /// 地面单独成一批：先画地面、再叠 2D 假阴影与光环、最后画主体，
  /// 否则地面网格会把阴影整片盖住（全部面合并成一次 drawVertices，无法中途插入 2D）。
  final List<Part> groundParts = [];

  /// 提灯火焰（呼吸跳动）。
  AnimPart? flame;

  /// 星空（夜晚显示）。用 2D 叠加绘制，比 700 个网格便宜得多。
  final List<V3> stars = [];

  /// 座位节点，下标与 `seats` 顺序一致。
  final List<Scene3dSeatNode> seats = [];

  /// 累计时间，驱动所有周期性动画。
  double pulse = 0;

  /// 夜晚 / 白天，决定光照与星空。
  bool night = true;

  /// 当前发言者座位（0 = 无）。
  int actorSeat = 0;

  /// 对局是否已结束。
  bool over = false;

  /// 最新一条公开发言的座位号与文本（头顶气泡用）。
  /// 网页端 `refreshBubble` 取的是 feed 里最后一条 SPEECH / LAST_WORDS；
  /// 空串与「过」不弹气泡。
  int? lastSpeechActor;
  String? lastSpeechDetail;

  Scene3dLight get light => night ? _nightLight : _dayLight;
  final Scene3dLight _nightLight = Scene3dLight.night();
  final Scene3dLight _dayLight = Scene3dLight.day();

  /// 座位集合签名：变了才重建造型（皮肤可能因换装 / 匿名打乱而变）。
  String _seatSig = '';

  void _buildStatics() {
    // 地面（单独一批，见 groundParts 的说明）
    groundParts.add(Part(
      Geo.circle(15, 40, 0xFF2A3550, MatKind.cloth),
      M4.translate(0, -0.01, 0).mul(M4.rotX(-math.pi / 2)),
    ));
    // 桌面 + 桌沿 + 桌腿 + 中央符文盘
    // 桌面用「侧壁 + 整张毛毡顶面」，而不是「木顶 + 贴在木顶上的毡心」：
    // 后者是两块共面（仅差 0.01）的大面，画家算法按面片重心排序，
    // 会把毡心的近半块排到桌板后面，表现为桌面像缺了一半（实测出图确认）。
    statics.add(Part(
      Geo.cylinder(kRTable, kRTable, 0.32, 24, 0xFF5A3B22, MatKind.cloth,
          capTop: false),
      M4.translate(0, 1.0, 0),
    ));
    statics.add(Part(
      Geo.torus(kRTable, 0.08, 4, 24, 0xFF33210F, MatKind.cloth),
      M4.translate(0, 1.16, 0).mul(M4.rotX(math.pi / 2)),
    ));
    statics.add(Part(
      Geo.cylinder(0.55, 0.90, 0.95, 10, 0xFF33210F, MatKind.cloth),
      M4.translate(0, 0.47, 0),
    ));
    statics.add(Part(
      Geo.circle(kRTable, 24, 0xFF1B2650, MatKind.cloth),
      M4.translate(0, 1.16, 0).mul(M4.rotX(-math.pi / 2)),
    ));
    // 提灯：灯笼罩（开口双面）+ 火焰
    statics.add(Part(
      Geo.cylinder(0.16, 0.20, 0.36, 8, 0xFF2B2B33, MatKind.metal,
          capTop: false, capBot: false, twoSided: true),
      M4.translate(0, 1.34, 0),
    ));
    final flameBase = M4.translate(0, 1.36, 0);
    final flamePart = Part(Geo.sphere(0.11, 6, 4, 0xFFC879, MatKind.cloth), flameBase);
    statics.add(flamePart);
    flame = AnimPart(flamePart, flameBase);
  }

  /// 星空：700 颗，确定性分布（同一个场景每次重建位置一致，避免闪烁）。
  void _buildStars() {
    var seed = 0x5EED1234;
    double rnd() {
      // 线性同余，够用且不引入 Random 的状态依赖。
      seed = (seed * 1103515245 + 12345) & 0x7FFFFFFF;
      return seed / 0x7FFFFFFF;
    }

    for (var i = 0; i < 700; i++) {
      final a = rnd() * kTau;
      final h = 0.12 + rnd() * 0.85;
      final r = 30 + rnd() * 22;
      stars.add(V3(math.cos(a) * r, h * 34, math.sin(a) * r));
    }
  }

  /// 依据对局视图更新场景。`seats` 里每个座位携带
  /// `seat / nickname / alive / isSheriff / silenced / title / bot / skin3d`。
  void update(Map<String, dynamic> view) {
    final rawSeats = (view['seats'] as List?) ?? const [];
    final sig = rawSeats
        .map((s) => '${(s as Map)['seat']}:${s['skin3d'] ?? 'villager'}')
        .join(',');
    if (sig != _seatSig) {
      _seatSig = sig;
      _rebuildSeats(rawSeats);
    }

    final phase = '${view['phase'] ?? ''}';
    night = phase.startsWith('NIGHT');
    over = phase == 'GAME_OVER';
    actorSeat = over ? 0 : ((view['currentActor'] as num?)?.toInt() ?? 0);

    final mySeat = (view['mySeat'] as num?)?.toInt();
    for (final node in seats) {
      final s = _findSeat(rawSeats, node.seat);
      if (s == null) continue;
      node.nickname = '${s['nickname'] ?? ''}';
      node.title = '${s['title'] ?? ''}';
      node.isSheriff = s['isSheriff'] == true;
      node.silenced = s['silenced'] == true;
      node.bot = s['bot'] == true;
      node.isMe = node.seat == mySeat;

      final alive = s['alive'] != false;
      node.avatarVisible = alive;
      node.tombVisible = !alive;

      final isActor = !over && node.seat == actorSeat;
      node.acting = isActor;
      node.ringOpacity = isActor ? 0.9 : (node.isMe ? 0.32 : 0);
      node.ringColor = isActor ? 0x6CF0FF : 0x8EA8FF;
    }

    _updateBubble(view);
  }

  /// 从记录里挑出最后一条公开发言，作为头顶气泡。
  void _updateBubble(Map<String, dynamic> view) {
    final feed = (view['feed'] as List?) ?? const [];
    Map? last;
    for (var i = feed.length - 1; i >= 0; i--) {
      final e = feed[i];
      if (e is! Map) continue;
      final t = '${e['type'] ?? ''}';
      if (t == 'SPEECH' || t == 'LAST_WORDS') {
        last = e;
        break;
      }
    }
    if (last == null) {
      lastSpeechActor = null;
      lastSpeechDetail = null;
      return;
    }
    final txt = '${last['detail'] ?? ''}'.trim();
    lastSpeechActor = (last['actor'] as num?)?.toInt();
    lastSpeechDetail = (txt.isEmpty || txt == '过') ? null : txt;
  }

  Map? _findSeat(List raw, int seat) {
    for (final s in raw) {
      if (s is Map && s['seat'] == seat) return s;
    }
    return null;
  }

  /// 按圆周均匀布置座位；1 号位在正前方（+Z，正对默认相机）。
  void _rebuildSeats(List raw) {
    seats.clear();
    final n = raw.length;
    for (var k = 0; k < n; k++) {
      final s = raw[k] as Map;
      final seat = (s['seat'] as num?)?.toInt() ?? (k + 1);
      final angle = math.pi / 2 + k / n * kTau;
      final node = Scene3dSeatNode(
        seat: seat,
        angle: angle,
        skinId: '${s['skin3d'] ?? 'villager'}',
      );
      node.yaw = node.baseYaw;
      node.stool = buildStool();
      node.tomb = buildTomb();
      node.avatar = buildAvatar(node.skinId);
      seats.add(node);
    }
  }

  /// 推进一帧动画。[dt] 为秒。
  void tick(double dt) {
    pulse += dt;

    // ---- 相机阻尼 ----
    final k = 1 - math.pow(0.001, dt).toDouble();
    camera.caz += (camera.az - camera.caz) * k;
    camera.cel += (camera.el - camera.cel) * k;
    camera.cdist += (camera.dist - camera.cdist) * k;

    // ---- 当前行动者坐标（供近景跟随与「转头看」）----
    double ax = 0, az = 0;
    var hasActor = false;
    for (final n in seats) {
      if (n.seat == actorSeat && n.avatarVisible) {
        ax = n.x;
        az = n.z;
        hasActor = true;
        break;
      }
    }

    // 近景时注视点移到发言者身上，否则放大也看不清造型。
    final nearFocus = camera.cdist < kNearFocusDist && hasActor;
    final wantX = nearFocus ? ax : 0.0;
    final wantZ = nearFocus ? az : 0.0;
    final wantY = nearFocus ? 1.20 : kFocusY;
    final kk = 1 - math.pow(0.05, dt).toDouble();
    camera.look = V3(
      camera.look.x + (wantX - camera.look.x) * kk,
      camera.look.y + (wantY - camera.look.y) * kk,
      camera.look.z + (wantZ - camera.look.z) * kk,
    );

    // 近景时相机绕到发言者外侧。
    final wantBase = nearFocus ? math.atan2(ax, az) : 0.0;
    camera.baseAngle += _wrapAngle(wantBase - camera.baseAngle) * kk;

    final eye = camera.eye;

    // ---- 提灯呼吸 ----
    final f = 0.9 + 0.16 * math.sin(pulse * 6.1);
    flame?.scaleAll(0.94 + 0.08 * math.sin(pulse * 6.1));
    _lanternFlicker = f;

    // ---- 逐个座位 ----
    for (final n in seats) {
      // 朝向：平时面朝桌心；近景聚焦时被聚焦的发言者转身面向镜头。
      final showOff = nearFocus && n.seat == actorSeat && n.avatarVisible;
      final wantYaw = showOff
          ? math.atan2(eye.x - n.x, eye.z - n.z)
          : n.baseYaw;
      final d = _wrapAngle(wantYaw - n.yaw);
      if (d.abs() > 0.0005) {
        n.yaw += d * (1 - math.pow(showOff ? 0.02 : 0.06, dt).toDouble());
      }

      final av = n.avatar;
      // 呼吸：躯干沿自身底部轻微伸缩
      av.torso.scaleY(1 + 0.014 * math.sin(pulse * 1.5 + n.seat * 0.7));

      // 眨眼：每个座位错开相位，约每 1.8 秒眨一次
      final cyc = (pulse * 0.55 + n.seat * 0.137) % 1;
      final sy = cyc > 0.972 ? 0.12 : 1.0;
      for (final e in av.eyes) {
        e.scaleY(sy);
      }

      // 转头注视当前发言者（限幅 + 缓动）
      var want = 0.0;
      if (hasActor && n.seat != actorSeat && n.avatarVisible) {
        final rel = _wrapAngle(math.atan2(ax - n.x, az - n.z) - n.yaw);
        want = rel.clamp(-0.62, 0.62);
      }
      n.look += (want - n.look) * (1 - math.pow(0.02, dt).toDouble());
      av.head.rotY(n.look);

      // 发言者：光环脉动 + 轻微起伏
      if (n.acting) {
        n.ringOpacity = 0.55 + 0.35 * math.sin(pulse * 3.2);
        n.y = math.sin(pulse * 3.2) * 0.03;
      } else {
        n.y = 0;
      }
    }
  }

  /// 提灯当前光强系数（呼吸用）。
  double _lanternFlicker = 1;
  double get lanternFlicker => _lanternFlicker;

  /// 把场景里的全部可见部件交给渲染器。
  void collect(Scene3dRenderer r) {
    for (final p in statics) {
      r.add(p.mesh, p.local);
    }
    for (final n in seats) {
      final g = n.group;
      for (final p in n.stool) {
        r.add(p.mesh, g.mul(p.local));
      }
      if (n.tombVisible) {
        for (final p in n.tomb) {
          r.add(p.mesh, g.mul(p.local));
        }
      }
      if (n.avatarVisible) {
        for (final p in n.avatar.parts) {
          r.add(p.mesh, g.mul(p.local));
        }
      }
    }
  }
}

/// 把角度差归一化到 (-π, π]，避免转头时绕远路。
double _wrapAngle(double a) => math.atan2(math.sin(a), math.cos(a));

/// 简易折行：按字数切、最多 [maxLines] 行、超出加省略号。
/// 与网页端 `scene3d.js` 的 `wrap(text, per, maxLines)` 同口径（按字符数而非像素宽度）。
List<String> wrapSpeech(String text, int per, int maxLines) {
  final out = <String>[];
  for (var i = 0; i < text.length && out.length < maxLines; i += per) {
    out.add(text.substring(i, math.min(i + per, text.length)));
  }
  if (text.length > per * maxLines && out.isNotEmpty) {
    final last = out.last;
    out[out.length - 1] = '${last.substring(0, math.max(0, last.length - 1))}…';
  }
  return out;
}
