/*
 * 狼人杀 3D 视角：用 Three.js 把对局渲染成「围坐圆桌」的 3D 场景。
 *
 * 设计原则：
 *  - 数据完全复用 2D 界面的 game.state 推送，不改动任何对局逻辑；
 *  - 角色造型由后端下发的 seat.skin3d 决定（商城「3D 皮肤」）；匿名模式下后端已打乱皮肤；
 *  - 角色为「坐姿人形」（腿/躯干/手臂/五官），配合程序化布纹、物理材质与呼吸/眨眼动画；
 *  - 出局玩家立墓碑（隐藏角色本体），不再躺倒；
 *  - 依赖 js/lib/three.min.js（已内置在项目里，离线可用，不走 CDN）。
 */
(function () {
  'use strict';

  var AVAILABLE = (typeof THREE !== 'undefined') && !!window.WebGLRenderingContext;

  var R_SEAT = 5.0;      // 座位到圆心的距离
  var R_TABLE = 3.1;     // 圆桌半径
  var FOCUS_Y = 1.1;     // 相机注视点高度
  var SEAT_TOP = 0.46;   // 凳面高度（坐姿角色以此对齐）

  var canvas = null, renderer = null, scene = null, camera = null;
  var root = null;
  var seatNodes = [];
  var bubble = null, bubbleSig = '';
  var active = false, rafId = 0, lastT = 0;
  var az = 0, el = 0.62, dist = 16.5;            // 相机目标值
  var caz = 0, cel = 0.62, cdist = 16.5;         // 相机当前值（阻尼用）
  var drag = null;
  var lights = null, lantern = null, stars = null;
  var seatSignature = '';
  var pulse = 0;
  var night = true;
  var actorSeat = 0;                             // 当前行动者座位（用于头部注视）
  var look = { x: 0, y: FOCUS_Y, z: 0 };         // 相机注视点（近景时跟随当前发言者）
  var baseAngle = 0;                             // 近景时相机绕到发言者「外侧」的基准角

  /* ================= 皮肤定义 ================= */
  /* 与后端 ShopCatalog 的 SKIN3D asset 一一对应；未知皮肤回退 villager。
   * body=衣料主色 head=肤色 accent=点缀色 cape=披风 hair=发色 tex=程序化布纹样式 */
  var SKINS = {
    villager: { name: '村民', body: 0x6b7a99, head: 0xf0d9c0, accent: 0x8fa6c8, hat: 'none',    hair: 0x3a2a1c, tex: 'plain' },
    jester:   { name: '弄臣', body: 0xa8323f, head: 0xf5e6d0, accent: 0xf0c04a, hat: 'jester',  hair: 0x2b2118, tex: 'stripe', cape: 0x7a1f2a },
    ghost:    { name: '游魂', body: 0x9fd8e8, head: 0xe4f7ff, accent: 0x7fe6ff, hat: 'none',    tex: 'plain', ghost: true },
    wolf:     { name: '狼影', body: 0x565c68, head: 0x8b909c, accent: 0x2f3440, hat: 'ears',    tex: 'fur', cape: 0x2b3038 },
    knight:   { name: '骑士', body: 0xb9c3d4, head: 0xd8dee9, accent: 0x8fa2c0, hat: 'helm',    tex: 'metal', metal: 0.92, cape: 0x2b3a5c },
    witch:    { name: '女巫', body: 0x5b3a8c, head: 0xf0d9c0, accent: 0xc9a3ff, hat: 'witchhat',hair: 0x1e1430, tex: 'rune', cape: 0x3a2560 },
    frost:    { name: '霜灵', body: 0x8fd8ff, head: 0xe6f7ff, accent: 0x4fb8ff, hat: 'crystals',tex: 'rune', glow: 0x2a6f9c },
    vampire:  { name: '血族', body: 0x2a2030, head: 0xe8dfe6, accent: 0xd03050, hat: 'collar',  hair: 0x120c14, tex: 'plain', cape: 0x8c1a2b },
    shadow:   { name: '影舞者', body: 0x3a2a55, head: 0x6a5a85, accent: 0x9a6cff, hat: 'hood',   tex: 'rune', cape: 0x1a1230, wings: true },
    royal:    { name: '王族', body: 0x8c1a2b, head: 0xf0d9c0, accent: 0xffd76a, hat: 'crown',   hair: 0x2b2118, tex: 'plain', cape: 0xc8a03a },
    reaper:   { name: '死神', body: 0x1a1a22, head: 0x26262f, accent: 0x7f8cff, hat: 'hood',    tex: 'rune', cape: 0x101018, scythe: true }
  };

  function skinOf(id) { return SKINS[id] || SKINS.villager; }

  /* ================= 生命周期 ================= */

  function isAvailable() {
    if (!AVAILABLE) return false;
    try {
      var c = document.createElement('canvas');
      return !!(c.getContext('webgl') || c.getContext('experimental-webgl'));
    } catch (e) { return false; }
  }

  /** 程序化地面贴图：细碎噪点 + 微弱明暗，避免大片纯色显得很「塑料」。 */
  function groundTexture() {
    var s = 256, c = document.createElement('canvas');
    c.width = c.height = s;
    var x = c.getContext('2d');
    x.fillStyle = '#0c1220';
    x.fillRect(0, 0, s, s);
    for (var i = 0; i < 5200; i++) {
      var v = 18 + Math.random() * 34;
      x.fillStyle = 'rgba(' + (v + 10) + ',' + (v + 18) + ',' + (v + 40) + ',0.55)';
      x.fillRect(Math.random() * s, Math.random() * s, 1 + Math.random() * 2, 1 + Math.random() * 2);
    }
    var t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(9, 9);
    return t;
  }

  function init(canvasEl) {
    if (renderer) return true;
    if (!isAvailable()) return false;
    canvas = canvasEl;
    renderer = new THREE.WebGLRenderer({ canvas: canvas, antialias: true, alpha: true });
    renderer.setPixelRatio(Math.min(window.devicePixelRatio || 1, 2));
    if (THREE.sRGBEncoding && 'outputEncoding' in renderer) renderer.outputEncoding = THREE.sRGBEncoding;
    if (THREE.ACESFilmicToneMapping) {
      renderer.toneMapping = THREE.ACESFilmicToneMapping;
      renderer.toneMappingExposure = 1.06;
    }
    renderer.shadowMap.enabled = true;
    if (THREE.PCFSoftShadowMap) renderer.shadowMap.type = THREE.PCFSoftShadowMap;

    scene = new THREE.Scene();
    scene.fog = new THREE.Fog(0x05070f, 24, 46);

    camera = new THREE.PerspectiveCamera(45, 1, 0.1, 240);

    // 地面
    var ground = new THREE.Mesh(
      new THREE.CircleGeometry(15, 72),
      new THREE.MeshStandardMaterial({ map: groundTexture(), color: 0x2a3550, roughness: 1, metalness: 0 })
    );
    ground.rotation.x = -Math.PI / 2;
    ground.position.y = -0.01;
    ground.receiveShadow = true;
    scene.add(ground);

    // 圆桌：桌面 + 桌沿 + 桌腿 + 中央符文盘
    var wood = new THREE.MeshStandardMaterial({ color: 0x5a3b22, roughness: 0.72, metalness: 0.06 });
    var woodDark = new THREE.MeshStandardMaterial({ color: 0x33210f, roughness: 0.92 });
    var top = new THREE.Mesh(new THREE.CylinderGeometry(R_TABLE, R_TABLE, 0.32, 72), wood);
    top.position.y = 1.0;
    top.castShadow = true; top.receiveShadow = true;
    scene.add(top);
    var rim = new THREE.Mesh(new THREE.TorusGeometry(R_TABLE, 0.08, 14, 72), woodDark);
    rim.rotation.x = Math.PI / 2;
    rim.position.y = 1.16;
    scene.add(rim);
    var leg = new THREE.Mesh(new THREE.CylinderGeometry(0.55, 0.9, 0.95, 28), woodDark);
    leg.position.y = 0.47;
    leg.castShadow = true;
    scene.add(leg);
    var rune = new THREE.Mesh(
      new THREE.CircleGeometry(R_TABLE * 0.72, 56),
      new THREE.MeshBasicMaterial({ color: 0x1b2650, transparent: true, opacity: 0.6 })
    );
    rune.rotation.x = -Math.PI / 2;
    rune.position.y = 1.17;
    scene.add(rune);

    // 桌面中央的提灯（暖色点光，给场景一个视觉焦点）
    var lamp = new THREE.Group();
    lamp.position.y = 1.16;
    var cage = new THREE.Mesh(
      new THREE.CylinderGeometry(0.16, 0.2, 0.36, 16, 1, true),
      new THREE.MeshStandardMaterial({ color: 0x2b2b33, roughness: 0.8, metalness: 0.5, side: THREE.DoubleSide })
    );
    cage.position.y = 0.18;
    lamp.add(cage);
    var flame = new THREE.Mesh(
      new THREE.SphereGeometry(0.11, 16, 12),
      new THREE.MeshBasicMaterial({ color: 0xffc879 })
    );
    flame.position.y = 0.2;
    lamp.add(flame);
    var pl = new THREE.PointLight(0xffb765, 1.1, 9, 2);
    pl.position.y = 0.25;
    lamp.add(pl);
    scene.add(lamp);
    lantern = { light: pl, flame: flame };

    root = new THREE.Group();
    scene.add(root);

    // 星空（夜晚显示）
    var starGeo = new THREE.BufferGeometry();
    var pos = new Float32Array(700 * 3);
    for (var i = 0; i < 700; i++) {
      var a = Math.random() * Math.PI * 2, h = 0.12 + Math.random() * 0.85;
      var r = 30 + Math.random() * 22;
      pos[i * 3] = Math.cos(a) * r;
      pos[i * 3 + 1] = h * 34;
      pos[i * 3 + 2] = Math.sin(a) * r;
    }
    starGeo.setAttribute('position', new THREE.BufferAttribute(pos, 3));
    stars = new THREE.Points(starGeo, new THREE.PointsMaterial({
      color: 0xcfe0ff, size: 0.34, sizeAttenuation: true, transparent: true, opacity: 0.85, fog: false
    }));
    scene.add(stars);

    // 光照
    var amb = new THREE.AmbientLight(0x8899cc, 0.5);
    var hemi = new THREE.HemisphereLight(0x8fb0ff, 0x141a2a, 0.5);
    var dir = new THREE.DirectionalLight(0xcfe0ff, 1.0);
    dir.position.set(7, 13, 9);
    dir.castShadow = true;
    dir.shadow.mapSize.width = 1024;
    dir.shadow.mapSize.height = 1024;
    dir.shadow.camera.left = -9; dir.shadow.camera.right = 9;
    dir.shadow.camera.top = 9; dir.shadow.camera.bottom = -9;
    dir.shadow.camera.near = 1; dir.shadow.camera.far = 42;
    dir.shadow.bias = -0.0012;
    scene.add(amb); scene.add(hemi); scene.add(dir);
    lights = { amb: amb, hemi: hemi, dir: dir };

    bindPointer();
    return true;
  }

  function setActive(on, canvasEl) {
    if (on) {
      if (!init(canvasEl)) return false;
      active = true;
      resize();
      if (!rafId) { lastT = performance.now(); rafId = requestAnimationFrame(loop); }
      return true;
    }
    active = false;
    if (rafId) { cancelAnimationFrame(rafId); rafId = 0; }
    return true;
  }

  function resize() {
    if (!renderer || !canvas) return;
    var w = canvas.clientWidth || canvas.parentNode.clientWidth || 640;
    var h = canvas.clientHeight || canvas.parentNode.clientHeight || 420;
    if (w === 0 || h === 0) return;
    renderer.setSize(w, h, false);
    camera.aspect = w / h;
    camera.updateProjectionMatrix();
  }

  /* ================= 交互 ================= */

  function bindPointer() {
    canvas.style.touchAction = 'none';
    // 触屏没有滚轮：用多指跟踪实现双指张合缩放，单指仍为旋转
    var pointers = {};          // pointerId -> {x, y}
    var pinch = null;           // 上一次双指间距

    function count() { return Object.keys(pointers).length; }
    function pinchDist() {
      var ids = Object.keys(pointers);
      var a = pointers[ids[0]], b = pointers[ids[1]];
      var dx = a.x - b.x, dy = a.y - b.y;
      return Math.sqrt(dx * dx + dy * dy);
    }

    canvas.addEventListener('pointerdown', function (e) {
      pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
      canvas.setPointerCapture && canvas.setPointerCapture(e.pointerId);
      if (count() >= 2) { drag = null; pinch = count() === 2 ? pinchDist() : null; }
      else { drag = { x: e.clientX, y: e.clientY }; pinch = null; }
    });
    canvas.addEventListener('pointermove', function (e) {
      if (!(e.pointerId in pointers)) return;
      pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
      if (count() >= 2) {
        var now = pinchDist();
        // 双指张开 = 拉近（dist 变小）；收拢 = 推远
        if (pinch && now > 0 && pinch > 0) dist = Math.max(8, Math.min(30, dist * (pinch / now)));
        pinch = now;
        return;
      }
      if (!drag) return;
      az -= (e.clientX - drag.x) * 0.006;
      el = Math.max(0.18, Math.min(1.35, el + (e.clientY - drag.y) * 0.004));
      drag.x = e.clientX; drag.y = e.clientY;
    });
    function endPointer(e) {
      delete pointers[e.pointerId];
      pinch = null;
      var n = count();
      if (n === 0) { drag = null; }
      else { var k = Object.keys(pointers)[0]; drag = { x: pointers[k].x, y: pointers[k].y }; }
    }
    canvas.addEventListener('pointerup', endPointer);
    canvas.addEventListener('pointercancel', endPointer);
    canvas.addEventListener('wheel', function (e) {
      e.preventDefault();
      dist = Math.max(8, Math.min(30, dist + e.deltaY * 0.012));
    }, { passive: false });
  }

  function resetCamera() { az = 0; el = 0.62; dist = 16.5; }

  /** 直接设定相机（方位角/俯仰/距离），供「近景/远景」等快捷视角使用。 */
  function setCamera(a, e, d) {
    if (a != null) az = a;
    if (e != null) el = e;
    if (d != null) dist = Math.max(3, Math.min(30, d));
  }

  /* ================= 文本贴图 ================= */

  var FONT = '"Microsoft YaHei","PingFang SC","Noto Sans CJK SC",system-ui,sans-serif';

  function roundRect(ctx, x, y, w, h, r) {
    ctx.beginPath();
    ctx.moveTo(x + r, y);
    ctx.arcTo(x + w, y, x + w, y + h, r);
    ctx.arcTo(x + w, y + h, x, y + h, r);
    ctx.arcTo(x, y + h, x, y, r);
    ctx.arcTo(x, y, x + w, y, r);
    ctx.closePath();
  }

  function makeTextSprite(lines, opt) {
    opt = opt || {};
    var fs = opt.fontSize || 40;
    var lh = Math.round(fs * 1.34);
    var pad = opt.pad == null ? 16 : opt.pad;
    var c = document.createElement('canvas');
    var ctx = c.getContext('2d');
    var font = (opt.bold ? '700 ' : '') + fs + 'px ' + FONT;
    ctx.font = font;
    var maxW = 0;
    for (var i = 0; i < lines.length; i++) maxW = Math.max(maxW, ctx.measureText(lines[i]).width);
    c.width = Math.ceil(maxW + pad * 2);
    c.height = Math.ceil(lines.length * lh + pad * 2);
    ctx = c.getContext('2d');
    ctx.font = font;
    ctx.textBaseline = 'middle';
    if (opt.bg) {
      ctx.fillStyle = opt.bg;
      roundRect(ctx, 0, 0, c.width, c.height, opt.radius == null ? 14 : opt.radius);
      ctx.fill();
      if (opt.border) { ctx.lineWidth = 3; ctx.strokeStyle = opt.border; ctx.stroke(); }
    }
    ctx.fillStyle = opt.color || '#e8eefc';
    for (var j = 0; j < lines.length; j++) {
      var x = opt.align === 'left' ? pad : (opt.align === 'right' ? c.width - pad : c.width / 2);
      ctx.textAlign = opt.align || 'center';
      ctx.fillText(lines[j], x, pad + lh * (j + 0.5));
    }
    var tex = new THREE.CanvasTexture(c);
    if ('colorSpace' in tex && THREE.SRGBColorSpace) tex.colorSpace = THREE.SRGBColorSpace;
    tex.anisotropy = 4;
    var mat = new THREE.SpriteMaterial({ map: tex, transparent: true, depthTest: false, depthWrite: false });
    var sp = new THREE.Sprite(mat);
    var s = opt.scale || 0.0055;
    sp.scale.set(c.width * s, c.height * s, 1);
    sp.renderOrder = opt.order || 10;
    return sp;
  }

  function disposeSprite(sp) {
    if (!sp) return;
    if (sp.material) { if (sp.material.map) sp.material.map.dispose(); sp.material.dispose(); }
    if (sp.parent) sp.parent.remove(sp);
  }

  /** 墓碑正面碑文贴图（直接贴在碑面上，而不是浮空精灵）。 */
  function tombTexture(name, seat) {
    var c = document.createElement('canvas');
    c.width = 256; c.height = 256;
    var x = c.getContext('2d');
    x.fillStyle = '#8d93a3';
    x.fillRect(0, 0, 256, 256);
    x.strokeStyle = 'rgba(60,66,80,0.75)';
    x.lineWidth = 8;
    x.strokeRect(10, 10, 236, 236);
    x.textAlign = 'center';
    x.fillStyle = '#3b4152';
    x.font = '700 40px ' + FONT;
    x.fillText('R.I.P', 128, 88);
    x.font = '700 34px ' + FONT;
    x.fillText('#' + seat, 128, 140);
    x.font = '26px ' + FONT;
    var nm = String(name || '');
    if (nm.length > 7) nm = nm.slice(0, 6) + '…';
    x.fillText(nm, 128, 186);
    var t = new THREE.CanvasTexture(c);
    if ('colorSpace' in t && THREE.SRGBColorSpace) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  /* ================= 材质与程序化布纹 ================= */

  function hex6(v) { return '#' + ('000000' + (v >>> 0).toString(16)).slice(-6); }

  /** 程序化衣料贴图：让衣服不再是「一块纯色塑料」。 */
  function clothTexture(base, accent, style) {
    var s = 128, c = document.createElement('canvas');
    c.width = c.height = s;
    var x = c.getContext('2d');
    x.fillStyle = hex6(base);
    x.fillRect(0, 0, s, s);
    var i;
    if (style === 'stripe') {
      x.fillStyle = hex6(accent);
      for (i = 0; i < s; i += 16) x.fillRect(i, 0, 8, s);
    } else if (style === 'fur') {
      for (i = 0; i < 3200; i++) {
        var v = 50 + Math.random() * 95;
        x.fillStyle = 'rgba(' + (v | 0) + ',' + (v | 0) + ',' + ((v + 10) | 0) + ',0.45)';
        x.fillRect(Math.random() * s, Math.random() * s, 1 + Math.random() * 2, 2 + Math.random() * 4);
      }
    } else if (style === 'metal') {
      for (i = 0; i < s; i += 2) {
        x.fillStyle = 'rgba(255,255,255,' + (0.025 + Math.random() * 0.07).toFixed(3) + ')';
        x.fillRect(0, i, s, 1);
      }
      x.strokeStyle = 'rgba(0,0,0,0.18)';
      x.lineWidth = 1;
      for (i = 0; i < s; i += 32) { x.beginPath(); x.moveTo(0, i); x.lineTo(s, i); x.stroke(); }
    } else if (style === 'rune') {
      x.strokeStyle = 'rgba(255,255,255,0.16)';
      x.lineWidth = 1.6;
      for (i = 0; i < 16; i++) {
        x.beginPath();
        x.arc(Math.random() * s, Math.random() * s, 4 + Math.random() * 8, 0, Math.PI * 2);
        x.stroke();
      }
    } else {
      for (i = 0; i < s; i += 2) {
        x.fillStyle = 'rgba(0,0,0,' + (i % 4 === 0 ? 0.055 : 0.022) + ')';
        x.fillRect(0, i, s, 1);
        x.fillRect(i, 0, 1, s);
      }
    }
    var t = new THREE.CanvasTexture(c);
    t.wrapS = t.wrapT = THREE.RepeatWrapping;
    t.repeat.set(2, 2);
    if ('colorSpace' in t && THREE.SRGBColorSpace) t.colorSpace = THREE.SRGBColorSpace;
    return t;
  }

  /**
   * 材质工厂。kind: cloth(布料/皮革) / metal(甲片) / skin(皮肤) / accent(点缀)。
   * 布料用 sheen 做出织物柔光，甲片用 clearcoat 做出金属高光。
   */
  function matFor(kind, color, sk, texStyle) {
    var p = {
      color: color,
      roughness: 0.8, metalness: 0.03,
      transparent: false, opacity: 1
    };
    if (kind === 'metal') { p.roughness = 0.26; p.metalness = 0.92; p.clearcoat = 0.65; p.clearcoatRoughness = 0.22; }
    else if (kind === 'skin') { p.roughness = 0.58; p.metalness = 0.0; p.clearcoat = 0.14; p.clearcoatRoughness = 0.5; }
    else if (kind === 'accent') { p.roughness = 0.42; p.metalness = 0.45; }
    else { p.roughness = 0.86; p.metalness = 0.02; p.sheen = 0.55; p.sheenRoughness = 0.72; }
    var M = THREE.MeshPhysicalMaterial || THREE.MeshStandardMaterial;
    var m = new M(p);
    if (m.sheenColor) m.sheenColor = new THREE.Color(0xffffff);
    if (kind === 'cloth' && texStyle && sk && !sk.ghost) m.map = clothTexture(color, sk.accent, texStyle);
    if (sk && sk.glow) m.emissive = new THREE.Color(sk.glow);
    if (sk && sk.ghost) { m.transparent = true; m.opacity = 0.7; m.roughness = 0.35; }
    return m;
  }

  /* ================= 角色造型（坐姿人形，按皮肤构建） ================= */

  function capsule(r, len, mat, seg) {
    return new THREE.Mesh(new THREE.CapsuleGeometry(r, len, 4, seg || 12), mat);
  }

  function lathe(profile, mat) {
    var pts = profile.map(function (p) { return new THREE.Vector2(p[0], p[1]); });
    return new THREE.Mesh(new THREE.LatheGeometry(pts, 20), mat);
  }

  /**
   * 构建一个皮肤对应的坐姿人形（不含椅子与光环）。
   * 局部坐标 y=0 为地面，凳面在 SEAT_TOP；头/眼/躯干引用挂在 userData 上供动画使用。
   */
  function buildAvatar(skinId) {
    var sk = skinOf(skinId);
    var g = new THREE.Group();

    var cloth = matFor('cloth', sk.body, sk, sk.tex);
    var skinM = matFor('skin', sk.head, sk);
    var accent = matFor('accent', sk.accent, sk);
    var dark = new THREE.MeshStandardMaterial({ color: 0x2a2a33, roughness: 0.7 });

    function add(mesh, cast) {
      if (cast !== false) mesh.castShadow = true;
      g.add(mesh);
      return mesh;
    }

    // ---- 腿（坐姿：大腿前伸、小腿垂下、脚踩地） ----
    for (var side = -1; side <= 1; side += 2) {
      var thigh = capsule(0.072, 0.20, cloth, 12);
      thigh.rotation.x = Math.PI / 2 + 0.10;
      thigh.position.set(0.105 * side, 0.50, 0.15);
      add(thigh);
      var shin = capsule(0.062, 0.24, cloth, 12);
      shin.position.set(0.105 * side, 0.28, 0.34);
      add(shin);
      var foot = new THREE.Mesh(new THREE.BoxGeometry(0.13, 0.07, 0.22), dark);
      foot.position.set(0.105 * side, 0.045, 0.40);
      add(foot);
    }

    // ---- 骨盆 + 躯干（车削出腰细胸宽的轮廓） ----
    var pelvis = new THREE.Mesh(new THREE.SphereGeometry(0.19, 18, 14), cloth);
    pelvis.scale.set(1, 0.72, 0.86);
    pelvis.position.y = 0.55;
    add(pelvis);

    var torso = lathe([
      [0.150, 0.00], [0.180, 0.06], [0.210, 0.14], [0.232, 0.24],
      [0.240, 0.32], [0.230, 0.40], [0.185, 0.47], [0.120, 0.52], [0.075, 0.53]
    ], cloth);
    torso.position.y = 0.56;
    add(torso);

    // ---- 肩 / 臂 / 手 ----
    for (var s2 = -1; s2 <= 1; s2 += 2) {
      var shoulder = new THREE.Mesh(new THREE.SphereGeometry(0.085, 16, 12), cloth);
      shoulder.position.set(0.20 * s2, 1.02, 0);
      add(shoulder);

      var upper = capsule(0.058, 0.17, cloth, 12);
      upper.position.set(0.245 * s2, 0.895, 0.015);
      upper.rotation.z = -0.20 * s2;
      add(upper);

      var elbow = new THREE.Mesh(new THREE.SphereGeometry(0.056, 14, 10), cloth);
      elbow.position.set(0.272 * s2, 0.765, 0.03);
      add(elbow);

      var fore = capsule(0.05, 0.17, cloth, 12);
      fore.position.set(0.272 * s2, 0.70, 0.125);
      fore.rotation.x = 0.95;
      add(fore);

      var hand = new THREE.Mesh(new THREE.SphereGeometry(0.055, 14, 12), skinM);
      hand.scale.set(1, 0.8, 1.15);
      hand.position.set(0.272 * s2, 0.632, 0.215);
      add(hand);
    }

    // ---- 脖子 + 头 ----
    var neck = new THREE.Mesh(new THREE.CylinderGeometry(0.058, 0.07, 0.11, 14), skinM);
    neck.position.y = 1.115;
    add(neck);

    var head = new THREE.Mesh(new THREE.SphereGeometry(0.17, 26, 20), skinM);
    head.scale.set(1, 1.06, 1.03);
    head.position.y = 1.30;
    add(head);

    // ---- 五官 ----
    var eyeWhite = new THREE.MeshStandardMaterial({ color: 0xf6f7fb, roughness: 0.32 });
    var eyeDark = new THREE.MeshStandardMaterial({ color: 0x1b1a22, roughness: 0.22 });
    var mouthMat = new THREE.MeshStandardMaterial({ color: 0x7c3a44, roughness: 0.55 });
    var eyes = [];
    for (var e = -1; e <= 1; e += 2) {
      var w = new THREE.Mesh(new THREE.SphereGeometry(0.033, 14, 12), eyeWhite);
      w.position.set(0.063 * e, 1.325, 0.134);
      w.scale.set(1, 0.86, 0.8);
      g.add(w);
      var pup = new THREE.Mesh(new THREE.SphereGeometry(0.019, 12, 10), eyeDark);
      pup.position.set(0.063 * e, 1.325, 0.152);
      pup.scale.set(1, 1, 0.7);
      g.add(pup);
      eyes.push({ white: w, pupil: pup });
      var brow = new THREE.Mesh(new THREE.BoxGeometry(0.064, 0.013, 0.02), dark);
      brow.position.set(0.063 * e, 1.368, 0.146);
      brow.rotation.z = 0.13 * e;
      g.add(brow);
    }
    var nose = new THREE.Mesh(new THREE.ConeGeometry(0.022, 0.06, 10), skinM);
    nose.rotation.x = Math.PI / 2;
    nose.position.set(0, 1.303, 0.156);
    g.add(nose);
    var mouth = new THREE.Mesh(new THREE.BoxGeometry(0.058, 0.012, 0.016), mouthMat);
    mouth.position.set(0, 1.262, 0.150);
    g.add(mouth);

    // ---- 头发（兜帽/头盔会盖住，跳过） ----
    if (sk.hat !== 'hood' && sk.hat !== 'helm' && sk.hair != null) {
      var hairMat = new THREE.MeshStandardMaterial({ color: sk.hair, roughness: 0.85 });
      var hair = new THREE.Mesh(new THREE.SphereGeometry(0.178, 22, 16, 0, Math.PI * 2, 0, 1.05), hairMat);
      hair.scale.set(1, 1.06, 1.05);
      hair.position.y = 1.30;
      add(hair, false);
    }

    // ---- 披风 ----
    if (sk.cape != null) {
      var cape = new THREE.Mesh(
        new THREE.ConeGeometry(0.40, 0.92, 20, 1, true, 0, Math.PI * 1.3),
        new THREE.MeshPhysicalMaterial({
          color: sk.cape, roughness: 0.88, side: THREE.DoubleSide,
          sheen: 0.6, sheenRoughness: 0.7
        })
      );
      cape.position.set(0, 0.80, -0.05);
      cape.rotation.y = Math.PI * 0.35;
      add(cape);
    }

    // ---- 头部/身体装饰（按皮肤） ----
    switch (sk.hat) {
      case 'ears': {
        for (var q = -1; q <= 1; q += 2) {
          var ear = new THREE.Mesh(new THREE.ConeGeometry(0.072, 0.20, 10), cloth);
          ear.position.set(0.115 * q, 1.465, -0.01);
          ear.rotation.z = -0.20 * q;
          add(ear);
        }
        var snout = new THREE.Mesh(new THREE.ConeGeometry(0.085, 0.19, 12), cloth);
        snout.rotation.x = Math.PI / 2;
        snout.position.set(0, 1.278, 0.20);
        add(snout);
        break;
      }
      case 'helm': {
        var helm = new THREE.Mesh(
          new THREE.SphereGeometry(0.198, 24, 18, 0, Math.PI * 2, 0, Math.PI * 0.66),
          matFor('metal', sk.body, sk, 'metal')
        );
        helm.position.y = 1.31;
        add(helm);
        var visor = new THREE.Mesh(new THREE.BoxGeometry(0.24, 0.05, 0.05), dark);
        visor.position.set(0, 1.30, 0.155);
        g.add(visor);
        var plume = new THREE.Mesh(new THREE.ConeGeometry(0.05, 0.30, 10),
          new THREE.MeshStandardMaterial({ color: 0xd24b5a, roughness: 0.72 }));
        plume.position.y = 1.60;
        add(plume);
        break;
      }
      case 'witchhat': {
        var brim = new THREE.Mesh(new THREE.CylinderGeometry(0.30, 0.30, 0.035, 28), cloth);
        brim.position.y = 1.435;
        add(brim);
        var cone = new THREE.Mesh(new THREE.ConeGeometry(0.195, 0.48, 22), cloth);
        cone.position.y = 1.66;
        cone.rotation.z = 0.10;
        add(cone);
        break;
      }
      case 'jester': {
        var jbase = new THREE.Mesh(new THREE.SphereGeometry(0.196, 20, 14, 0, Math.PI * 2, 0, Math.PI * 0.5),
          matFor('cloth', sk.accent, sk, 'stripe'));
        jbase.position.y = 1.40;
        add(jbase);
        for (var k = 0; k < 3; k++) {
          var horn = new THREE.Mesh(new THREE.ConeGeometry(0.055, 0.26, 10),
            new THREE.MeshStandardMaterial({ color: k === 1 ? 0x2f8f6f : sk.body, roughness: 0.7 }));
          var ang = (k - 1) * 0.72;
          horn.position.set(Math.sin(ang) * 0.13, 1.60, Math.cos(ang) * 0.05);
          horn.rotation.z = -ang * 0.6;
          add(horn);
        }
        break;
      }
      case 'crown': {
        var crown = new THREE.Mesh(new THREE.CylinderGeometry(0.185, 0.185, 0.13, 20, 1, true), accent);
        crown.position.y = 1.50;
        add(crown);
        for (var c2 = 0; c2 < 5; c2++) {
          var spike = new THREE.Mesh(new THREE.ConeGeometry(0.045, 0.15, 8), accent);
          var a2 = (c2 / 5) * Math.PI * 2;
          spike.position.set(Math.cos(a2) * 0.165, 1.61, Math.sin(a2) * 0.165);
          add(spike);
        }
        break;
      }
      case 'hood': {
        var hoodMat = new THREE.MeshPhysicalMaterial({
          color: sk.cape != null ? sk.cape : sk.body, roughness: 0.92,
          side: THREE.DoubleSide, sheen: 0.5, sheenRoughness: 0.8
        });
        var hood = new THREE.Mesh(new THREE.SphereGeometry(0.238, 22, 18, 0, Math.PI * 2, 0, Math.PI * 0.76), hoodMat);
        hood.position.y = 1.305;
        add(hood);
        var shroud = new THREE.Mesh(new THREE.ConeGeometry(0.30, 0.44, 18, 1, true), hoodMat);
        shroud.position.y = 1.14;
        shroud.rotation.x = Math.PI;
        add(shroud);
        break;
      }
      case 'crystals': {
        for (var c3 = 0; c3 < 5; c3++) {
          var cr = new THREE.Mesh(new THREE.OctahedronGeometry(0.055 + Math.random() * 0.035),
            new THREE.MeshPhysicalMaterial({
              color: sk.accent, emissive: new THREE.Color(0x2f7fbf),
              roughness: 0.2, metalness: 0.1, transparent: true, opacity: 0.85, clearcoat: 0.8
            }));
          var a3 = (c3 / 5) * Math.PI * 2;
          cr.position.set(Math.cos(a3) * 0.22, 1.42 + (c3 % 2) * 0.14, Math.sin(a3) * 0.22);
          g.add(cr);
        }
        break;
      }
      case 'collar': {
        var collar = new THREE.Mesh(new THREE.ConeGeometry(0.26, 0.30, 18, 1, true),
          new THREE.MeshPhysicalMaterial({ color: sk.accent, roughness: 0.55, side: THREE.DoubleSide, sheen: 0.6 }));
        collar.position.y = 1.13;
        collar.rotation.x = Math.PI;
        add(collar);
        break;
      }
      default: break;
    }

    // ---- 影翼 ----
    if (sk.wings) {
      for (var w = -1; w <= 1; w += 2) {
        var wing = new THREE.Mesh(new THREE.ConeGeometry(0.18, 0.80, 4),
          new THREE.MeshPhysicalMaterial({ color: sk.accent, roughness: 0.62, transparent: true, opacity: 0.72, side: THREE.DoubleSide }));
        wing.position.set(0.34 * w, 0.95, -0.20);
        wing.rotation.z = w * 0.9;
        wing.rotation.x = 0.32;
        add(wing);
      }
    }

    // ---- 镰刀 ----
    if (sk.scythe) {
      var rod = new THREE.Mesh(new THREE.CylinderGeometry(0.03, 0.03, 1.6, 8),
        new THREE.MeshStandardMaterial({ color: 0x3a3a44, roughness: 0.62 }));
      rod.position.set(0.40, 0.82, 0.10);
      rod.rotation.z = 0.13;
      add(rod);
      var blade = new THREE.Mesh(new THREE.TorusGeometry(0.30, 0.04, 8, 20, Math.PI * 0.9),
        new THREE.MeshPhysicalMaterial({ color: 0xc9d2e6, roughness: 0.22, metalness: 0.88, clearcoat: 0.7 }));
      blade.position.set(0.52, 1.52, 0.10);
      blade.rotation.set(Math.PI / 2, 0, -0.5);
      add(blade);
    }

    g.userData = { head: head, torso: torso, eyes: eyes };
    return g;
  }

  /** 墓碑（出局玩家展示）：碑身 + 圆顶 + 底座 + 碑文。 */
  function buildTomb(seat, name) {
    var g = new THREE.Group();
    var stone = new THREE.MeshStandardMaterial({ color: 0x8d93a3, roughness: 0.95, metalness: 0.04 });
    var slab = new THREE.Mesh(new THREE.BoxGeometry(0.78, 1.0, 0.16), stone);
    slab.position.y = 0.6;
    slab.castShadow = true; slab.receiveShadow = true;
    g.add(slab);
    var cap = new THREE.Mesh(new THREE.CylinderGeometry(0.39, 0.39, 0.16, 26), stone);
    cap.rotation.x = Math.PI / 2;
    cap.position.y = 1.1;
    cap.castShadow = true;
    g.add(cap);
    var plinth = new THREE.Mesh(new THREE.BoxGeometry(0.96, 0.14, 0.34),
      new THREE.MeshStandardMaterial({ color: 0x6d7383, roughness: 0.98 }));
    plinth.position.y = 0.07;
    plinth.castShadow = true;
    g.add(plinth);
    var face = new THREE.Mesh(
      new THREE.PlaneGeometry(0.66, 0.66),
      new THREE.MeshStandardMaterial({ map: tombTexture(name, seat), roughness: 0.95 })
    );
    face.position.set(0, 0.66, 0.086);
    g.add(face);
    return g;
  }

  /* ================= 座位节点 ================= */

  function clearSeats() {
    for (var i = 0; i < seatNodes.length; i++) {
      var n = seatNodes[i];
      disposeSprite(n.label);
      if (n.group.parent) n.group.parent.remove(n.group);
      n.group.traverse(function (o) {
        if (o.geometry) o.geometry.dispose();
        if (o.material) {
          if (o.material.map) o.material.map.dispose();
          o.material.dispose();
        }
      });
    }
    seatNodes = [];
  }

  function buildSeats(seats) {
    clearSeats();
    var N = seats.length;
    for (var k = 0; k < N; k++) {
      var s = seats[k];
      var a = Math.PI / 2 + (k / N) * Math.PI * 2;   // 1 号位在正前方（靠近初始相机）
      var x = Math.cos(a) * R_SEAT, z = Math.sin(a) * R_SEAT;

      var g = new THREE.Group();
      g.position.set(x, 0, z);
      g.rotation.y = Math.atan2(-x, -z);              // 面朝桌心

      // 凳子：凳面 + 靠背（坐姿角色坐在凳面上）
      var chairMat = new THREE.MeshPhysicalMaterial({ color: 0x2a3350, roughness: 0.9, sheen: 0.3 });
      var stool = new THREE.Mesh(new THREE.CylinderGeometry(0.46, 0.50, SEAT_TOP, 24), chairMat);
      stool.position.y = SEAT_TOP / 2;
      stool.receiveShadow = true;
      g.add(stool);
      var back = new THREE.Mesh(new THREE.BoxGeometry(0.86, 0.70, 0.11), chairMat);
      back.position.set(0, 0.92, -0.46);
      back.castShadow = true;
      g.add(back);
      for (var lg = -1; lg <= 1; lg += 2) {
        var chairLeg = new THREE.Mesh(new THREE.CylinderGeometry(0.05, 0.06, SEAT_TOP, 10), chairMat);
        chairLeg.position.set(0.32 * lg, SEAT_TOP / 2, 0);
        g.add(chairLeg);
      }

      // 地面光环
      var ring = new THREE.Mesh(
        new THREE.RingGeometry(0.74, 0.95, 44),
        new THREE.MeshBasicMaterial({ color: 0x6cf0ff, transparent: true, opacity: 0, side: THREE.DoubleSide })
      );
      ring.rotation.x = -Math.PI / 2;
      ring.position.y = 0.02;
      g.add(ring);

      // 角色本体（按皮肤构建，出局时隐藏）
      var avatar = buildAvatar(s.skin3d);
      g.add(avatar);

      // 墓碑（出局时显示）
      var tomb = buildTomb(s.seat, s.nickname);
      tomb.visible = false;
      g.add(tomb);

      var node = {
        seat: s.seat, group: g, avatar: avatar, tomb: tomb, ring: ring,
        label: null, sig: '', skin: s.skin3d || 'villager', acting: false, look: 0,
        baseYaw: g.rotation.y
      };
      root.add(g);
      seatNodes.push(node);
    }
  }

  /** 每个座位的静态信息签名：变了才重建名牌/造型，避免每次推送都重画。 */
  function seatSig(s, v) {
    return [s.seat, s.alive ? 1 : 0, s.isSheriff ? 1 : 0, s.silenced ? 1 : 0,
            s.seat === v.mySeat ? 1 : 0, s.nickname || '', s.title || '', s.bot ? 1 : 0].join('|');
  }

  function refreshLabel(node, s, v) {
    disposeSprite(node.label);
    var dead = !s.alive;
    var sk = skinOf(node.skin);
    var lines = ['#' + s.seat + ' ' + (s.nickname || '') + (s.seat === v.mySeat ? '（我）' : '')];
    if (sk.name !== '村民') lines.push(sk.name);
    if (s.title) lines.push(s.title);
    if (dead) lines.push('已出局');
    var sp = makeTextSprite(lines, {
      fontSize: 34, pad: 14, radius: 12, scale: 0.0052, order: 10,
      bg: dead ? 'rgba(24,28,40,0.88)' : 'rgba(12,18,34,0.84)',
      border: dead ? 'rgba(120,128,150,0.55)'
                   : (s.isSheriff ? 'rgba(255,206,84,0.95)' : 'rgba(120,170,255,0.45)'),
      color: dead ? '#8b93a8' : (s.seat === v.mySeat ? '#8ef0ff' : '#e8eefc')
    });
    sp.position.set(0, dead ? 1.72 : 2.28, 0);
    node.group.add(sp);
    node.label = sp;
  }

  function refreshBubble(v) {
    var feed = v.feed || [];
    var last = null;
    for (var i = feed.length - 1; i >= 0; i--) {
      var t = feed[i].type;
      if (t === 'SPEECH' || t === 'LAST_WORDS') { last = feed[i]; break; }
    }
    var sig = last ? (last.day + '|' + last.actor + '|' + last.detail) : '';
    if (sig === bubbleSig) return;
    bubbleSig = sig;
    disposeSprite(bubble);
    bubble = null;
    if (!last || !last.detail) return;
    var txt = String(last.detail).trim();
    if (txt === '' || txt === '过') return;
    var node = null;
    for (var k = 0; k < seatNodes.length; k++) if (seatNodes[k].seat === last.actor) node = seatNodes[k];
    if (!node) return;
    var sp = makeTextSprite(wrap(txt, 14, 4), {
      fontSize: 34, pad: 18, radius: 16, scale: 0.0052, order: 20,
      bg: 'rgba(20,28,48,0.92)', border: 'rgba(140,200,255,0.55)', color: '#eaf3ff'
    });
    sp.position.set(0, node.tomb.visible ? 3.0 : 3.35, 0);
    node.group.add(sp);
    bubble = sp;
  }

  /** 简易中文换行：按字数折行，最多 maxLines 行，超出加省略号。 */
  function wrap(text, per, maxLines) {
    var out = [];
    for (var i = 0; i < text.length && out.length < maxLines; i += per) out.push(text.slice(i, i + per));
    if (text.length > per * maxLines && out.length) out[out.length - 1] = out[out.length - 1].slice(0, per - 1) + '…';
    return out;
  }

  /* ================= 状态更新 ================= */

  function update(v) {
    if (!renderer || !v || !v.seats) return;
    var sig = v.seats.map(function (s) { return s.seat + ':' + (s.skin3d || 'villager'); }).join(',');
    if (sig !== seatSignature) { seatSignature = sig; buildSeats(v.seats); bubbleSig = ''; }

    night = String(v.phase || '').indexOf('NIGHT') === 0;
    var over = v.phase === 'GAME_OVER';
    if (lights) {
      lights.amb.intensity = night ? 0.32 : 0.7;
      lights.hemi.intensity = night ? 0.28 : 0.6;
      lights.dir.intensity = night ? 0.6 : 1.15;
      lights.amb.color.setHex(night ? 0x6d82b8 : 0x93a6cc);
      lights.dir.color.setHex(night ? 0xa8c0ff : 0xffe9c4);
      scene.fog.color.setHex(night ? 0x05070f : 0x0d1526);
    }
    if (lantern) lantern.light.intensity = night ? 1.5 : 0.6;
    if (stars) stars.visible = night;

    actorSeat = over ? 0 : (v.currentActor || 0);

    for (var i = 0; i < seatNodes.length; i++) {
      var node = seatNodes[i];
      var s = null;
      for (var k = 0; k < v.seats.length; k++) if (v.seats[k].seat === node.seat) s = v.seats[k];
      if (!s) continue;

      var alive = !!s.alive;
      // 出局：隐藏角色本体，立起墓碑
      node.avatar.visible = alive;
      node.tomb.visible = !alive;

      var isActor = !over && s.seat === v.currentActor;
      var isMe = s.seat === v.mySeat;
      node.ring.material.opacity = isActor ? 0.9 : (isMe ? 0.32 : 0);
      node.ring.material.color.setHex(isActor ? 0x6cf0ff : 0x8ea8ff);
      node.acting = isActor;

      var ns = seatSig(s, v);
      if (ns !== node.sig) { node.sig = ns; refreshLabel(node, s, v); }
    }

    refreshBubble(v);
    if (!active) { active = true; if (!rafId) { lastT = performance.now(); rafId = requestAnimationFrame(loop); } }
  }

  /* ================= 渲染循环 ================= */

  function loop(t) {
    rafId = 0;
    if (!active || !renderer) return;
    var dt = Math.min(0.05, (t - lastT) / 1000 || 0.016);
    lastT = t;
    pulse += dt;

    // 相机阻尼：避免拖动时生硬跳变
    var k = 1 - Math.pow(0.001, dt);
    caz += (az - caz) * k;
    cel += (el - cel) * k;
    cdist += (dist - cdist) * k;

    // 当前行动者坐标（供镜头近景跟随 + 其他人「转头看」）
    var ax = 0, az2 = 0, hasActor = false;
    for (var j = 0; j < seatNodes.length; j++) {
      if (seatNodes[j].seat === actorSeat && seatNodes[j].avatar.visible) {
        ax = seatNodes[j].group.position.x; az2 = seatNodes[j].group.position.z; hasActor = true;
        break;
      }
    }

    // 近景（拉近到一定距离内）自动把注视点移到当前发言者身上，
    // 否则镜头永远对着桌面中心，放大也看不清角色造型。
    var nearFocus = cdist < 10.5 && hasActor;
    var wantX = 0, wantY = FOCUS_Y, wantZ = 0;
    if (nearFocus) { wantX = ax; wantZ = az2; wantY = 1.20; }
    var kk = 1 - Math.pow(0.05, dt);
    look.x += (wantX - look.x) * kk;
    look.y += (wantY - look.y) * kk;
    look.z += (wantZ - look.z) * kk;

    // 近景时把相机绕到发言者的「外侧」（远离桌心那一侧），否则圆桌会横在镜头与角色之间。
    var wantBase = nearFocus ? Math.atan2(ax, az2) : 0;
    var db = wantBase - baseAngle;
    db = Math.atan2(Math.sin(db), Math.cos(db));
    baseAngle += db * kk;

    var cy = Math.sin(cel) * cdist, cr = Math.cos(cel) * cdist;
    var ca = baseAngle + caz;
    camera.position.set(look.x + Math.sin(ca) * cr, cy + look.y, look.z + Math.cos(ca) * cr);
    camera.lookAt(look.x, look.y, look.z);

    // 提灯呼吸
    if (lantern) {
      var f = 0.9 + 0.16 * Math.sin(pulse * 6.1);
      lantern.light.intensity = (night ? 1.5 : 0.6) * f;
      lantern.flame.scale.setScalar(0.94 + 0.08 * Math.sin(pulse * 6.1));
    }

    for (var i = 0; i < seatNodes.length; i++) {
      var n = seatNodes[i];
      var ud = n.avatar.userData;

      // 朝向：平时面向桌心；近景聚焦时，被聚焦的发言者转身面向镜头，方便看清皮肤造型。
      var showOff = nearFocus && n.seat === actorSeat && n.avatar.visible;
      var wantYaw = n.baseYaw;
      if (showOff) {
        wantYaw = Math.atan2(camera.position.x - n.group.position.x, camera.position.z - n.group.position.z);
      }
      var dyaw = wantYaw - n.group.rotation.y;
      dyaw = Math.atan2(Math.sin(dyaw), Math.cos(dyaw));
      if (Math.abs(dyaw) > 0.0005) {
        n.group.rotation.y += dyaw * (1 - Math.pow(showOff ? 0.02 : 0.06, dt));
      }

      // 呼吸：躯干沿自身底部轻微伸缩
      if (ud && ud.torso) ud.torso.scale.y = 1 + 0.014 * Math.sin(pulse * 1.5 + n.seat * 0.7);
      // 眨眼：每个座位错开相位，约每 1.8 秒眨一次、持续 ~50ms
      if (ud && ud.eyes) {
        var cyc = (pulse * 0.55 + n.seat * 0.137) % 1;
        var sy = cyc > 0.972 ? 0.12 : 1;
        for (var e2 = 0; e2 < ud.eyes.length; e2++) {
          ud.eyes[e2].white.scale.y = 0.86 * sy;
          ud.eyes[e2].pupil.scale.y = sy;
        }
      }
      // 转头注视当前发言者（限制幅度，缓动）
      if (ud && ud.head) {
        var want = 0;
        if (hasActor && n.seat !== actorSeat && n.avatar.visible) {
          var dx = ax - n.group.position.x, dz = az2 - n.group.position.z;
          var rel = Math.atan2(dx, dz) - n.group.rotation.y;
          rel = Math.atan2(Math.sin(rel), Math.cos(rel));
          want = Math.max(-0.62, Math.min(0.62, rel));
        }
        n.look += (want - n.look) * (1 - Math.pow(0.02, dt));
        ud.head.rotation.y = n.look;
      }
      if (n.acting) {
        n.ring.material.opacity = 0.55 + 0.35 * Math.sin(pulse * 3.2);
        n.group.position.y = Math.sin(pulse * 3.2) * 0.03;
      } else if (n.group.position.y !== 0) {
        n.group.position.y = 0;
      }
    }

    renderer.render(scene, camera);
    rafId = requestAnimationFrame(loop);
  }

  window.Scene3D = {
    isAvailable: isAvailable,
    setActive: setActive,
    update: update,
    resize: resize,
    resetCamera: resetCamera,
    setCamera: setCamera,
    skins: function () { return Object.keys(SKINS); }
  };
})();
