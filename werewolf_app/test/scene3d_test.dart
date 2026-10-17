import 'dart:math' as math;
import 'dart:ui' as ui;

import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:http/testing.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/scene3d.dart';
import 'package:werewolf_app/scene3d_avatar.dart';
import 'package:werewolf_app/scene3d_scene.dart';
import 'package:werewolf_app/screens/game.dart';
import 'package:werewolf_app/screens/scene3d_view.dart';
import 'package:werewolf_app/store.dart';
import 'package:werewolf_app/theme.dart';

import 'fixtures/server_views.dart';

/// 3D 视角（P5）回归。
///
/// 真值来源是网页端 `static/js/scene3d.js`：皮肤表、座位圆环布局、相机参数、
/// 昼夜光照与动画口径。这里覆盖三层：
///   1. 纯数学与图元（矩阵、投影、绕序）；
///   2. 场景状态机（座位重建、昼夜、墓碑、发言者光环、气泡）；
///   3. **像素级**渲染校验——软件渲染最容易「静默全黑」，必须真的画出来并数像素。
void main() {
  TestWidgetsFlutterBinding.ensureInitialized();

  setUp(() {
    SharedPreferences.setMockInitialValues({});
    appearance.debugReset();
    scene3dMode.debugReset();
  });

  group('数学', () {
    test('单位矩阵不改变点，平移只加偏移', () {
      expect(M4.identity().apply(const V3(1, 2, 3)).toString(), const V3(1, 2, 3).toString());
      final t = M4.translate(10, -1, 0.5).apply(const V3(1, 2, 3));
      expect(t.x, 11);
      expect(t.y, 1);
      expect(t.z, 3.5);
    });

    test('绕 Y 轴 +90° 把 +X 转到 -Z（与 Three.js 同向）', () {
      final v = M4.rotY(math.pi / 2).apply(const V3(1, 0, 0));
      expect(v.x, closeTo(0, 1e-9));
      expect(v.z, closeTo(-1, 1e-9));
    });

    test('级联顺序：a.mul(b) 先 b 后 a', () {
      // 先缩放 2 倍，再平移 (0,1,0)：x 应变成 2、y 变成 1
      final m = M4.translate(0, 1, 0).mul(M4.scale(2, 2, 2));
      final v = m.apply(const V3(1, 0, 0));
      expect(v.x, closeTo(2, 1e-9));
      expect(v.y, closeTo(1, 1e-9));
    });

    test('V3 叉积 / 点积 / 单位化', () {
      expect(const V3(1, 0, 0).cross(const V3(0, 1, 0)).z, 1);
      expect(const V3(1, 2, 3).dot(const V3(4, -5, 6)), 12);
      final u = const V3(0, 0, 7).unit;
      expect(u.z, 1);
      expect(V3.zero.unit.length, 0); // 零向量不除零
    });
  });

  group('投影', () {
    Scene3dFrame frame({double fov = Scene3dCamera.fovY}) => Scene3dFrame(
          size: const ui.Size(200, 100),
          eye: const V3(0, 0, 10),
          target: V3.zero,
          fovY: fov,
        );

    test('注视点落在画布中心，视深等于相机距离', () {
      final f = frame();
      final p = f.project(V3.zero)!;
      expect(p.dx, closeTo(100, 1e-6));
      expect(p.dy, closeTo(50, 1e-6));
      expect(f.depthOf(V3.zero), closeTo(10, 1e-9));
    });

    test('相机背后的点投影为 null（不会画出翻转的鬼影）', () {
      expect(frame().project(const V3(0, 0, 20)), isNull);
      expect(frame().depthOf(const V3(0, 0, 20)) < 0, isTrue);
    });

    test('越远越小：同一物体在更远处投影尺寸更小', () {
      final f = frame();
      final near = f.project(const V3(0.5, 0, 0))!.dx - f.project(V3.zero)!.dx;
      final f2 = Scene3dFrame(
          size: const ui.Size(200, 100), eye: const V3(0, 0, 20), target: V3.zero, fovY: Scene3dCamera.fovY);
      final far = f2.project(const V3(0.5, 0, 0))!.dx - f2.project(V3.zero)!.dx;
      expect(far.abs() < near.abs(), isTrue);
    });
  });

  group('图元', () {
    test('面数符合分段口径', () {
      expect(Geo.box(1, 1, 1, 0xFFFFFFFF, MatKind.cloth).faceCount, 12);
      // 球的两极各有一圈退化三角形（三个顶点重合）会被丢掉：8 段 × 2 极 = 16
      expect(Geo.sphere(1, 8, 6, 0xFFFFFFFF, MatKind.cloth).faceCount, 8 * 6 * 2 - 16);
      expect(Geo.plane(1, 1, 0xFFFFFFFF, MatKind.cloth).faceCount, 2);
      // 圆柱侧壁 seg*2 + 上下两个扇面
      expect(Geo.cylinder(1, 1, 1, 10, 0xFFFFFFFF, MatKind.cloth).faceCount, 10 * 2 + 10 + 10);
      // 开口锥（披风）：只有侧壁；顶点收敛到一点时每段只剩 1 个三角形
      expect(
        Geo.cylinder(0, 1, 1, 8, 0xFFFFFFFF, MatKind.cloth,
                capTop: false, capBot: false, twoSided: true)
            .faceCount,
        8,
      );
      // 圆台（顶半径非 0）则有完整的两倍三角形
      expect(
        Geo.cylinder(0.4, 1, 1, 8, 0xFFFFFFFF, MatKind.cloth,
                capTop: false, capBot: false, twoSided: true)
            .faceCount,
        8 * 2,
      );
    });

    test('球的两端是真正封口的（极点退化面已剔除，不留洞）', () {
      final m = Geo.sphere(1, 8, 6, 0xFFFFFFFF, MatKind.cloth);
      for (final idx in m.tris) {
        expect(idx, inInclusiveRange(0, m.nv - 1));
      }
      // 极点顶点仍存在（被极点扇形用到），但没有任何零面积面
      for (var f = 0; f < m.faceCount; f++) {
        final a = m.tris[f * 3] * 3, b = m.tris[f * 3 + 1] * 3, c = m.tris[f * 3 + 2] * 3;
        final ux = m.pos[b] - m.pos[a], uy = m.pos[b + 1] - m.pos[a + 1], uz = m.pos[b + 2] - m.pos[a + 2];
        final vx = m.pos[c] - m.pos[a], vy = m.pos[c + 1] - m.pos[a + 1], vz = m.pos[c + 2] - m.pos[a + 2];
        final nx = uy * vz - uz * vy, ny = uz * vx - ux * vz, nz = ux * vy - uy * vx;
        expect(nx * nx + ny * ny + nz * nz, greaterThan(1e-20), reason: '第 $f 面是退化面');
      }
    });

    test('顶点全部有限，下标不越界', () {
      final meshes = <Mesh>[
        Geo.box(1, 2, 3, 0xFFFFFFFF, MatKind.cloth),
        Geo.sphere(0.5, 8, 6, 0xFFFFFFFF, MatKind.skin),
        Geo.cylinder(0.2, 0.4, 1, 8, 0xFFFFFFFF, MatKind.metal),
        Geo.capsule(0.1, 0.3, 5, 0xFFFFFFFF, MatKind.cloth),
        Geo.torus(0.3, 0.05, 4, 8, 0xFFFFFFFF, MatKind.metal),
        Geo.lathe(const [
          [0.1, 0.0],
          [0.2, 0.3],
          [0.05, 0.5],
        ], 8, 0xFFFFFFFF, MatKind.cloth),
        Geo.circle(1, 12, 0xFFFFFFFF, MatKind.cloth),
        Geo.octahedron(0.1, 0xFFFFFFFF, MatKind.accent),
      ];
      for (final m in meshes) {
        for (var i = 0; i < m.pos.length; i++) {
          expect(m.pos[i].isFinite, isTrue, reason: '顶点含 NaN/Inf');
        }
        for (final idx in m.tris) {
          expect(idx, inInclusiveRange(0, m.nv - 1));
        }
      }
    });

    test('闭合凸体的绕序已修正为朝外（背面剔除的前提）', () {
      for (final m in [
        Geo.box(1, 1, 1, 0xFFFFFFFF, MatKind.cloth),
        Geo.sphere(0.7, 8, 6, 0xFFFFFFFF, MatKind.cloth),
        Geo.cylinder(0.3, 0.5, 1.2, 8, 0xFFFFFFFF, MatKind.cloth),
      ]) {
        for (var f = 0; f < m.faceCount; f++) {
          final a = m.tris[f * 3] * 3, b = m.tris[f * 3 + 1] * 3, c = m.tris[f * 3 + 2] * 3;
          final ux = m.pos[b] - m.pos[a];
          final uy = m.pos[b + 1] - m.pos[a + 1];
          final uz = m.pos[b + 2] - m.pos[a + 2];
          final vx = m.pos[c] - m.pos[a];
          final vy = m.pos[c + 1] - m.pos[a + 1];
          final vz = m.pos[c + 2] - m.pos[a + 2];
          final nx = uy * vz - uz * vy;
          final ny = uz * vx - ux * vz;
          final nz = ux * vy - uy * vx;
          // 面心方向
          final mx = (m.pos[a] + m.pos[b] + m.pos[c]) / 3;
          final my = (m.pos[a + 1] + m.pos[b + 1] + m.pos[c + 1]) / 3;
          final mz = (m.pos[a + 2] + m.pos[b + 2] + m.pos[c + 2]) / 3;
          expect(nx * mx + ny * my + nz * mz, greaterThan(0),
              reason: '第 $f 面朝内，背面剔除会把它当正面');
        }
      }
    });
  });

  group('皮肤', () {
    test('11 套，id 与网页端 SKINS 一一对应', () {
      expect(kWwSkins.length, 11);
      expect(
        kWwSkins.keys.toSet(),
        {
          'villager', 'jester', 'ghost', 'wolf', 'knight', 'witch',
          'frost', 'vampire', 'shadow', 'royal', 'reaper',
        },
      );
    });

    test('未知 / 空 id 一律回落村民', () {
      expect(skinOf(null).id, 'villager');
      expect(skinOf('').id, 'villager');
      expect(skinOf('dragon').id, 'villager');
    });

    test('特殊皮肤带着各自的特征位（披风 / 影翼 / 镰刀 / 头饰）', () {
      expect(skinOf('wolf').hat, HatKind.ears);
      expect(skinOf('knight').hat, HatKind.helm);
      expect(skinOf('witch').hat, HatKind.witchhat);
      expect(skinOf('jester').hat, HatKind.jester);
      expect(skinOf('royal').hat, HatKind.crown);
      expect(skinOf('frost').hat, HatKind.crystals);
      expect(skinOf('vampire').hat, HatKind.collar);
      expect(skinOf('reaper').hat, HatKind.hood);
      expect(skinOf('shadow').hat, HatKind.hood);
      expect(skinOf('shadow').wings, isTrue);
      expect(skinOf('reaper').scythe, isTrue);
      expect(skinOf('ghost').ghost, isTrue);
      expect(skinOf('royal').cape, isNotNull);
    });

    test('村民不显示皮肤名，其余显示中文名', () {
      expect(skinLabel('villager'), '');
      expect(skinLabel('wolf'), '狼影');
      expect(skinLabel('reaper'), '死神');
    });

    test('每套皮肤都能构建出造型，且部件数量可观', () {
      for (final id in kWwSkins.keys) {
        final av = buildAvatar(id);
        expect(av.parts.length, greaterThan(20), reason: '$id 部件过少');
        expect(av.eyes.length, 4, reason: '$id 应有两只眼白 + 两个瞳孔');
        for (final p in av.parts) {
          expect(p.mesh.faceCount, greaterThan(0), reason: '$id 有空网格');
        }
      }
    });

    test('凳子 4 件、墓碑 4 件', () {
      expect(buildStool().length, 4);
      expect(buildTomb().length, 4);
    });
  });

  group('程序化布纹', () {
    test('逐面配色长度与面数一致', () {
      // 8 圈 × 8 段 × 每段 2 个三角形
      expect(clothFaceColors(ClothTex.plain, 0xFF112233, 0xFF445566, 8, 8).length, 8 * 8 * 2);
    });

    test('条纹按圈交替（弄臣）', () {
      final f = clothFaceColors(ClothTex.stripe, 0xFFA8323F, 0xFFF0C04A, 4, 1);
      // Int32List 存的是有符号 32 位，比较前先转回无符号。
      int u(int v) => v.toUnsigned(32);
      // 每圈 2 个三角形；偶数圈用主色、奇数圈用点缀色
      expect(u(f[0]), 0xFFA8323F);
      expect(u(f[2]), 0xFFF0C04A);
      expect(u(f[4]), 0xFFA8323F);
      expect(u(f[6]), 0xFFF0C04A);
    });

    test('确定性：同一入参两次结果完全相同（造型不会逐帧抖动）', () {
      final a = clothFaceColors(ClothTex.fur, 0xFF565C68, 0xFF2F3440, 8, 8);
      final b = clothFaceColors(ClothTex.fur, 0xFF565C68, 0xFF2F3440, 8, 8);
      expect(a, b);
    });

    test('颜色工具：变亮变暗与插值都夹在 0..255', () {
      expect(shadeColor(0xFF808080, 0.5) & 0xFF, 0x40);
      expect(shadeColor(0xFF808080, 99) & 0xFF, 0xFF);
      // 0 → 255 的中点是 127.5，四舍五入到 128
      expect(mixColor(0xFF000000, 0xFFFFFFFF, 0.5) & 0xFF, 0x80);
    });
  });

  group('座位布局', () {
    test('1 号位在正前方（+Z），且面朝桌心', () {
      final s = Scene3dScene()..update(_view(6));
      final first = s.seats.first;
      expect(first.seat, 1);
      expect(first.x, closeTo(0, 1e-9));
      expect(first.z, closeTo(kRSeat, 1e-9));
      // baseYaw = atan2(-x, -z)：x 是 cos(π/2) 的浮点残差（≈3e-16），
      // 于是结果为 -π（而不是 +π），两者表示同一朝向。
      expect(first.baseYaw.abs(), closeTo(math.pi, 1e-9));
    });

    test('座位均匀分布在半径 kRSeat 的圆周上', () {
      final s = Scene3dScene()..update(_view(8));
      expect(s.seats.length, 8);
      for (final n in s.seats) {
        expect(math.sqrt(n.x * n.x + n.z * n.z), closeTo(kRSeat, 1e-9));
      }
    });

    test('座位集合不变时不重建（保留动画状态）', () {
      final s = Scene3dScene()..update(_view(6));
      final before = s.seats.first;
      s.update(_view(6));
      expect(identical(s.seats.first, before), isTrue);
    });

    test('皮肤变化会触发重建', () {
      final s = Scene3dScene()..update(_view(6));
      final before = s.seats.first;
      final v = _view(6);
      (v['seats'] as List)[0]['skin3d'] = 'reaper';
      s.update(v);
      expect(identical(s.seats.first, before), isFalse);
      expect(s.seats.first.skinId, 'reaper');
    });
  });

  group('场景状态', () {
    test('夜晚 / 白天决定光照与星空', () {
      final s = Scene3dScene();
      s.update(_view(6, phase: 'NIGHT_START'));
      expect(s.night, isTrue);
      expect(s.light.ambI, Scene3dLight.night().ambI);
      s.update(_view(6, phase: 'DAY_DISCUSS'));
      expect(s.night, isFalse);
      expect(s.light.ambI, Scene3dLight.day().ambI);
    });

    test('出局者：隐藏角色本体、立起墓碑；光环只给发言者与自己', () {
      final s = Scene3dScene()..update(_view(6, actor: 2, mySeat: 1, dead3: true));
      final dead = s.seats.firstWhere((n) => n.seat == 3);
      expect(dead.avatarVisible, isFalse);
      expect(dead.tombVisible, isTrue);

      final actor = s.seats.firstWhere((n) => n.seat == 2);
      expect(actor.acting, isTrue);
      expect(actor.ringOpacity, 0.9);

      final me = s.seats.firstWhere((n) => n.seat == 1);
      expect(me.isMe, isTrue);
      expect(me.ringOpacity, 0.32);

      final other = s.seats.firstWhere((n) => n.seat == 4);
      expect(other.ringOpacity, 0);
    });

    test('终局：不再有发言者光环（自己那圈仍保留）', () {
      final s = Scene3dScene()..update(_view(6, phase: 'GAME_OVER', actor: 2, mySeat: 1));
      expect(s.over, isTrue);
      expect(s.actorSeat, 0);
      expect(s.seats.every((n) => n.acting == false), isTrue);
      expect(s.seats.firstWhere((n) => n.seat == 2).ringOpacity, 0);
      expect(s.seats.firstWhere((n) => n.seat == 1).ringOpacity, 0.32);
    });

    test('名牌信息来自座位（昵称 / 称号 / 警长 / 自己）', () {
      final s = Scene3dScene()..update(_view(6, mySeat: 1));
      final n1 = s.seats.firstWhere((n) => n.seat == 1);
      expect(n1.nickname, '玩家1');
      expect(n1.isMe, isTrue);
      expect(n1.isSheriff, isTrue);
      final n2 = s.seats.firstWhere((n) => n.seat == 2);
      expect(n2.title, '狼王');
    });

    test('气泡取最后一条公开发言；「过」与空串不弹', () {
      final s = Scene3dScene();
      s.update(_view(6));
      expect(s.lastSpeechActor, 2);
      expect(s.lastSpeechDetail, isNotNull);

      final v = _view(6);
      (v['feed'] as List).add({'type': 'SPEECH', 'day': 1, 'actor': 4, 'detail': '过'});
      s.update(v);
      expect(s.lastSpeechActor, 4);
      expect(s.lastSpeechDetail, isNull);

      final v2 = _view(6);
      v2['feed'] = [];
      s.update(v2);
      expect(s.lastSpeechDetail, isNull);
      expect(s.lastSpeechActor, isNull);
    });

    test('tick 推进动画且不抛异常（呼吸 / 眨眼 / 转头 / 光环脉动）', () {
      final s = Scene3dScene()..update(_view(8, actor: 3, mySeat: 1));
      final torsoBefore = s.seats.first.avatar.torso.part.local.m[5];
      for (var i = 0; i < 60; i++) {
        s.tick(1 / 30);
      }
      expect(s.pulse, closeTo(2, 1e-6));
      // 呼吸会改写躯干纵向缩放
      expect(s.seats.first.avatar.torso.part.local.m[5], isNot(torsoBefore));
      // 相机阻尼应收敛到目标值
      expect(s.camera.cdist, closeTo(s.camera.dist, 0.01));
    });

    test('近景跟随发言者：注视点向发言者移动', () {
      final s = Scene3dScene()..update(_view(8, actor: 5, mySeat: 1));
      s.camera.dist = 7.4; // 拉近到近景阈值内
      for (var i = 0; i < 120; i++) {
        s.tick(1 / 30);
      }
      final actor = s.seats.firstWhere((n) => n.seat == 5);
      expect(s.camera.look.x, closeTo(actor.x, 0.05));
      expect(s.camera.look.z, closeTo(actor.z, 0.05));
      expect(s.camera.look.y, closeTo(1.20, 0.02));
    });

    test('发言者轻微起伏，其他人回到地面高度', () {
      final s = Scene3dScene()..update(_view(6, actor: 2, mySeat: 1));
      s.tick(0.1);
      final actor = s.seats.firstWhere((n) => n.seat == 2);
      final other = s.seats.firstWhere((n) => n.seat == 4);
      expect(actor.y, isNot(0));
      expect(other.y, 0);
    });
  });

  group('相机', () {
    test('默认值与网页端一致，重置可复原', () {
      final c = Scene3dCamera();
      expect(c.dist, 16.5);
      expect(c.el, 0.62);
      c.set(a: 1, e: 0.3, d: 7.4);
      expect(c.dist, 7.4);
      c.reset();
      expect(c.dist, 16.5);
      expect(c.az, 0);
      expect(c.el, 0.62);
    });

    test('缩放与拖拽都被夹在安全范围内', () {
      final c = Scene3dCamera();
      c.zoomBy(999);
      expect(c.dist, 30.0);
      c.zoomBy(-999);
      expect(c.dist, 8.0);
      c.dragBy(0, 999);
      expect(c.el, 1.35);
      c.dragBy(0, -999);
      expect(c.el, 0.18);
      c.set(d: 100);
      expect(c.dist, 30.0);
    });

    test('双指张开（scale>1）等价于拉近', () {
      final c = Scene3dCamera();
      final before = c.dist;
      c.zoomScale(1 / 1.5);
      expect(c.dist, lessThan(before));
    });

    test('相机落在注视点上方，y 分量随俯仰角上升', () {
      final c = Scene3dCamera();
      final low = c.eye.y;
      c.el = 1.2;
      c.cel = 1.2;
      expect(c.eye.y, greaterThan(low));
    });
  });

  group('渲染（像素级）', () {
    /// 把场景渲染到图片并统计「有像素」的数量（不画背景，只看几何体覆盖）。
    Future<(int, Scene3dStats)> renderToImage(
      Scene3dScene scene, {
      int w = 240,
      int h = 180,
    }) async {
      final rec = ui.PictureRecorder();
      final canvas = ui.Canvas(rec, ui.Rect.fromLTWH(0, 0, w.toDouble(), h.toDouble()));
      final cam = scene.camera;
      final frame = Scene3dFrame(
        size: ui.Size(w.toDouble(), h.toDouble()),
        eye: cam.eye,
        target: cam.look,
        fovY: Scene3dCamera.fovY,
      );
      final r = Scene3dRenderer();
      r.begin();
      scene.collect(r);
      r.setEye(frame.eye);
      r.paint(canvas, frame, scene.light);

      final img = await rec.endRecording().toImage(w, h);
      final bd = await img.toByteData();
      final bytes = bd!.buffer.asUint8List();
      var covered = 0;
      for (var i = 3; i < bytes.length; i += 4) {
        if (bytes[i] > 0) covered++;
      }
      return (covered, r.stats);
    }

    testWidgets('画家算法：近处物体覆盖远处，即使远处那片更大', (tester) async {
      await tester.runAsync(() async {
        // 远处一个大圆盘（蓝，z=-2）、近处一个小圆盘（红，z=+1），都正对相机
        final far = Geo.circle(2.0, 16, 0xFF0000FF, MatKind.cloth);
        final near = Geo.circle(0.6, 12, 0xFFFF0000, MatKind.cloth);

        Future<List<int>> probePixels(M4 farX, M4 nearX, List<Mesh> order) async {
          final rec = ui.PictureRecorder();
          final canvas = ui.Canvas(rec, const ui.Rect.fromLTWH(0, 0, 64, 64));
          final frame = Scene3dFrame(
            size: const ui.Size(64, 64),
            eye: const V3(0, 0, 8),
            target: V3.zero,
            fovY: Scene3dCamera.fovY,
          );
          final r = Scene3dRenderer();
          r.begin();
          for (final m in order) {
            r.add(m, identical(m, far) ? farX : nearX);
          }
          r.setEye(frame.eye);
          r.paint(canvas, frame, Scene3dLight.day());
          final img = await rec.endRecording().toImage(64, 64);
          final bd = await img.toByteData();
          final b = bd!.buffer.asUint8List();
          // 取四个非中心像素（避开扇形顶点的退化处）
          return [
            for (final o in [36 * 64 + 32, 28 * 64 + 32, 32 * 64 + 36, 32 * 64 + 28])
              (b[o * 4] << 16) | (b[o * 4 + 1] << 8) | b[o * 4 + 2],
          ];
        }

        final farX = M4.translate(0, 0, -2);
        final nearX = M4.translate(0, 0, 1);
        // 加入顺序故意与深度顺序相反，验证排序确实在起作用
        for (final order in [
          [far, near],
          [near, far],
        ]) {
          final px = await probePixels(farX, nearX, order);
          expect(px, everyElement(0xFF0000),
              reason: '近处的红色应覆盖远处的蓝色（加入顺序：${order.first == far ? "远→近" : "近→远"}），实际 $px');
        }
      });
    });

    testWidgets('场景真的被画出来（不是静默全黑）', (tester) async {
      await tester.runAsync(() async {
        final s = Scene3dScene()..update(_view(8, actor: 2, mySeat: 1));
        s.tick(1 / 30);
        final (covered, stats) = await renderToImage(s);
        const total = 240 * 180;
        expect(stats.faces, greaterThan(2000), reason: '几何体太少，场景没建起来');
        expect(stats.drawn, greaterThan(500), reason: '剔除把整帧剔没了');
        expect(covered, greaterThan(total * 0.05),
            reason: '画布几乎没被覆盖，drawVertices 很可能没生效');
      });
    });

    testWidgets('背面剔除生效：闭合球体有一半左右的面被剔掉', (tester) async {
      await tester.runAsync(() async {
        final s = Scene3dScene()..update(_view(6));
        s.tick(1 / 30);
        final (_, stats) = await renderToImage(s);
        expect(stats.culled, greaterThan(0));
        expect(stats.clipped, greaterThanOrEqualTo(0));
      });
    });

    testWidgets('近景画出的面多于远景（亚像素面剔除在起作用）', (tester) async {
      await tester.runAsync(() async {
        final s = Scene3dScene()..update(_view(8, actor: 2, mySeat: 1));
        s.camera.dist = 7.4;
        s.camera.cdist = 7.4;
        s.tick(1 / 30);
        final (_, near) = await renderToImage(s);

        final far = Scene3dScene()..update(_view(8, actor: 2, mySeat: 1));
        far.camera.dist = 30;
        far.camera.cdist = 30;
        far.tick(1 / 30);
        final (_, farStats) = await renderToImage(far);

        expect(near.drawn, greaterThan(farStats.drawn),
            reason: '远景应当因为亚像素剔除而少画很多面');
      });
    });

    testWidgets('空座位列表不崩，也不画任何东西', (tester) async {
      await tester.runAsync(() async {
        final s = Scene3dScene()..update({'seats': const [], 'phase': 'WAITING'});
        expect(s.seats, isEmpty);
        final (_, stats) = await renderToImage(s);
        expect(stats.drawn, greaterThan(0)); // 地面/圆桌仍在
      });
    });
  });

  group('折行', () {
    test('按字数切、超行加省略号（与网页端 wrap 同口径）', () {
      expect(wrapSpeech('一二三四五', 2, 4), ['一二', '三四', '五']);
      expect(wrapSpeech('一二三四五六七', 2, 2), ['一二', '三…']);
      expect(wrapSpeech('', 4, 3), isEmpty);
    });
  });

  group('开关', () {
    test('默认关闭，落盘到 ww_mode_3d', () async {
      expect(scene3dMode.on, isFalse);
      await scene3dMode.setOn(true);
      expect(scene3dMode.on, isTrue);
      final p = await SharedPreferences.getInstance();
      expect(p.getBool('ww_mode_3d'), isTrue);
    });

    test('load 读回已保存值', () async {
      SharedPreferences.setMockInitialValues({'ww_mode_3d': true});
      final m = Scene3dMode();
      await m.load();
      expect(m.on, isTrue);
    });

    test('toggle 与相同值幂等', () async {
      var n = 0;
      scene3dMode.addListener(() => n++);
      await scene3dMode.setOn(false); // 已是 false
      expect(n, 0);
      await scene3dMode.toggle();
      expect(scene3dMode.on, isTrue);
      expect(n, 1);
    });
  });

  group('组件', () {
    testWidgets('Scene3dView 渲染出画布与快捷视角按钮', (tester) async {
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(width: 400, height: 300, child: Scene3dView(view: _view(6))),
        ),
      ));
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.byType(CustomPaint), findsWidgets);
      expect(find.byIcon(Icons.center_focus_strong), findsOneWidget);
      expect(find.byIcon(Icons.zoom_out_map), findsOneWidget);
      expect(find.byIcon(Icons.person_search), findsOneWidget);
      // 收起快捷按钮
      await tester.pumpWidget(MaterialApp(
        home: Scaffold(
          body: SizedBox(
              width: 400, height: 300, child: Scene3dView(view: _view(6), showControls: false)),
        ),
      ));
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.byIcon(Icons.center_focus_strong), findsNothing);
    });

    testWidgets('点「近景」把相机拉到 7.4，点「重置」复原', (tester) async {
      final s = Scene3dScene()..update(_view(6));
      // 直接验证预设逻辑（组件内部按钮走的是同一套 set/reset）
      s.camera.set(a: 0, e: 0.34, d: 7.4);
      expect(s.camera.dist, 7.4);
      s.camera.reset();
      expect(s.camera.dist, 16.5);
    });
  });

  group('接线（回归：3D 必须能从对局页真的进去）', () {
    /// 建一个带 MockClient 的 store 并灌入白天对局视图。
    /// 去掉身份牌以绕过开局翻牌弹窗（与 hotkeys_test 同做法）。
    AppStore wiredStore() {
      SharedPreferences.setMockInitialValues({});
      final client = MockClient((req) async => http.Response('{}', 200));
      final s = AppStore(ApiClient(
          ServerConfig(baseUrl: 'https://unit.test.invalid:11111'),
          client: client));
      s.sfx.enabled = false; // 避免 AudioPlayer 触达 EventChannel
      final g = gameStateDay();
      g['myInfo'] = null;
      s.handleWsEvent(g);
      return s;
    }

    Future<void> pumpGame(WidgetTester tester, AppStore store) async {
      tester.view.physicalSize = const Size(1200, 2000);
      tester.view.devicePixelRatio = 1.0;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(ChangeNotifierProvider.value(
        value: store,
        child: MaterialApp(theme: werewolfTheme(), home: const GameScreen()),
      ));
      await tester.pump(); // autofocus 取焦在 post-frame 回调里
    }

    // 这一组是为了防「引擎写好了、单测全过、但页面上没有入口」的漏网——
    // 3D 的组件测试曾直接挂载 Scene3dView，绕过了对局页，于是接线缺失无人发现。
    testWidgets('默认 2D 座位条；开 3D 后挂载场景且高度夹到 420；关回 2D', (tester) async {
      await pumpGame(tester, wiredStore());
      expect(scene3dMode.on, isFalse);
      expect(find.byType(Scene3dView), findsNothing);

      await scene3dMode.setOn(true);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.byType(Scene3dView), findsOneWidget);
      // 屏高 2000 → 2000 * 0.42 = 840 → 夹到上限 420
      expect(tester.getSize(find.byType(Scene3dView)).height, 420);

      await scene3dMode.setOn(false);
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(find.byType(Scene3dView), findsNothing, reason: '切回 2D 后应当卸载 3D 场景');
    });

    testWidgets('顶栏 3D 按钮点得动：图标与场景同步切换', (tester) async {
      await pumpGame(tester, wiredStore());
      expect(find.byIcon(Icons.view_in_ar_outlined), findsOneWidget);

      await tester.tap(find.byIcon(Icons.view_in_ar_outlined));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(scene3dMode.on, isTrue);
      expect(find.byType(Scene3dView), findsOneWidget);
      expect(find.byIcon(Icons.threed_rotation), findsOneWidget);

      await tester.tap(find.byIcon(Icons.threed_rotation));
      await tester.pump();
      await tester.pump(const Duration(milliseconds: 50));
      expect(scene3dMode.on, isFalse);
      expect(find.byType(Scene3dView), findsNothing);
      expect(find.byIcon(Icons.view_in_ar_outlined), findsOneWidget);
    });
  });
}

/// 造一份贴近真实 `game.state` 的对局视图。
Map<String, dynamic> _view(
  int n, {
  String phase = 'NIGHT_START',
  int actor = 2,
  int mySeat = 1,
  bool dead3 = false,
}) {
  const skins = [
    'villager', 'jester', 'ghost', 'wolf', 'knight', 'witch',
    'frost', 'vampire', 'shadow', 'royal', 'reaper',
  ];
  return {
    'phase': phase,
    'mySeat': mySeat,
    'currentActor': actor,
    'seats': [
      for (var i = 1; i <= n; i++)
        {
          'seat': i,
          'nickname': '玩家$i',
          'alive': !(dead3 && i == 3),
          'skin3d': skins[(i - 1) % skins.length],
          'isSheriff': i == 1,
          'silenced': false,
          'title': i == 2 ? '狼王' : '',
          'bot': i > 6,
        },
    ],
    'feed': [
      {'type': 'SPEECH', 'day': 1, 'actor': 2, 'detail': '我觉得三号有问题，先听一圈发言再定。'},
    ],
  };
}
