import 'dart:convert';
import 'dart:io';

import 'package:flutter/foundation.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';
import 'package:web_socket_channel/io.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/replay_engine.dart';
import 'package:werewolf_app/store.dart';

/// 打真实服务端的端到端契约测试。
///
/// 默认跳过（保证离线 CI 与 `flutter test` 的确定性）。启用方式：
///   set WW_LIVE_BASE=https://localhost:11111
///   set WW_LIVE_USER=winclient01
///   set WW_LIVE_PASS=Ww@test2026
///   flutter test test/live_contract_test.dart --timeout 120s
///
/// 它驱动的是**客户端真实代码路径**（ApiClient / AppStore / WsClient），不是另写一套请求，
/// 因此任何一次通过都等价于「三端共用的协议层此刻仍然对齐」。
///
/// ⚠️ 跑这套用例期间不要用 curl 或别的客户端登录同一个账号：服务端是单会话，
/// 新登录会回收旧 token，会让跑到一半的用例莫名报「未登录或会话已过期」（不是产品 bug）。
/// 同理，用 --plain-name 只跑某一条会跳过第 2 条的登录，后续用例必然 401。
void main() {
  final base = (Platform.environment['WW_LIVE_BASE'] ?? '').trim();
  final user = (Platform.environment['WW_LIVE_USER'] ?? '').trim();
  final pass = (Platform.environment['WW_LIVE_PASS'] ?? '').trim();
  final user2 = (Platform.environment['WW_LIVE_USER2'] ?? '').trim();
  final pass2 = (Platform.environment['WW_LIVE_PASS2'] ?? '').trim();
  final enabled = base.isNotEmpty && user.isNotEmpty && pass.isNotEmpty;
  // 双人结队与踢人需要两个真实客户端，故第二个账号可选。
  final enabled2 = enabled && user2.isNotEmpty && pass2.isNotEmpty;

  group('live 契约（真实服务端）', () {
    late ServerConfig config;
    late ApiClient api;
    late AppStore store;

    setUpAll(() {
      HttpOverrides.global = _InsecureOverrides();
      SharedPreferences.setMockInitialValues({});
      config = ServerConfig(baseUrl: base, allowInsecure: true);
      api = ApiClient(config);
      store = AppStore(api);
    });

    tearDownAll(() {
      try {
        store.dispose();
      } catch (_) {}
      HttpOverrides.global = null;
    });

    test('1. /api/health 可达且数据库在线', () async {
      final h = await api.get('/api/health');
      expect(h['ok'], isTrue);
      expect(h['db'], 'up');
      debugPrint('[live] health ok=${h['ok']} app=${h['app']} version=${h['version']}');
    });

    test('2. 登录签发 token 并建立 /ws 长连接', () async {
      final ok = await store.login(user, pass);
      expect(ok, isTrue, reason: '登录应成功并拿到 token');
      expect(store.token, isNotNull);
      expect(store.loggedIn, isTrue);
      expect(store.user?['username'], user);
      await waitFor('WS 已连接', () => store.wsConnected ? true : null, timeoutMs: 15000);
    });

    test('3. 建房 + 加 AI 补位，房间状态经 WS 推回', () async {
      await store.createRoom();
      expect(store.inRoom, isTrue);
      final roomNo = store.room?['roomNo'];
      expect(roomNo, isNotNull, reason: '建房必须拿到房号');
      debugPrint('[live] 房间 $roomNo');

      // 服务端 add-ai 一次只补一个 AI（网页版 quickAiRoom 只调一次，实际只凑到 2 人），
      // 故循环补到 6 人再断言；房间视图里 AI 的标志位是 ai，不是 game.state 用的 bot。
      for (var i = 0; i < 8; i++) {
        final p = store.room?['players'];
        if (p is List && p.length >= 6) break;
        await store.addAi();
        await Future<void>.delayed(const Duration(milliseconds: 400));
      }
      final players = await waitFor('AI 补位后的座位表', () {
        final p = store.room?['players'];
        return (p is List && p.length >= 6) ? p : null;
      }, timeoutMs: 20000);
      final bots = players.where((p) => p['ai'] == true).length;
      expect(bots, greaterThanOrEqualTo(5), reason: 'add-ai 循环应把空位补成 AI');
      debugPrint('[live] 座位 ${players.length}，其中 AI $bots');
    });

    test('4. 准备并开局，收到定制化的 game.state 视图', () async {
      await store.setReady(true);
      await Future<void>.delayed(const Duration(milliseconds: 500));
      await store.startGame();
      final g = await waitFor('game.state 推送', () => store.game, timeoutMs: 25000);
      expect(g['phase'], isNotNull, reason: '必须带当前阶段');
      expect(g['roomNo'], isNotNull);
      expect(g['seats'], isA<List>());
      expect((g['seats'] as List).length, greaterThanOrEqualTo(6));
      expect(g['mySeat'], isA<num>());
      expect(g['myInfo'], isA<Map>(), reason: '每个座位都要拿到自己的身份牌');
      expect((g['myInfo'] as Map)['role'], isNotNull);
      debugPrint('[live] 开局 phase=${g['phase']} day=${g['day']} mySeat=${g['mySeat']} '
          'role=${(g['myInfo'] as Map)['role']} voiceAvailable=${g['voiceAvailable']}');
    });

    test('5. 强制结束并回到房间等待态（自清理，不留野局）', () async {
      await store.endGame();
      await waitFor('对局视图清空', () => store.game == null ? true : null, timeoutMs: 20000);
      await store.leaveRoom();
      expect(store.inRoom, isFalse);
    });

    test('6. /ws/voice 用同一 token 可握手并回 voice.hello', () async {
      final ch = IOWebSocketChannel.connect(config.wsUriVoice(store.token!),
          pingInterval: const Duration(seconds: 10));
      addTearDown(() async {
        try {
          await ch.sink.close();
        } catch (_) {}
      });
      String? hello;
      final deadline = DateTime.now().add(const Duration(seconds: 15));
      final sub = ch.stream.listen((d) {
        if (d is String && d.contains('voice.hello')) hello = d;
      });
      addTearDown(sub.cancel);
      while (hello == null && DateTime.now().isBefore(deadline)) {
        await Future<void>.delayed(const Duration(milliseconds: 100));
      }
      expect(hello, isNotNull, reason: '语音通道必须回 voice.hello');
      final m = jsonDecode(hello!) as Map<String, dynamic>;
      expect(m['type'], 'voice.hello');
      expect(m.containsKey('enabled'), isTrue);
      debugPrint('[live] voice.hello enabled=${m['enabled']} sampleRate=${m['sampleRate']}');
    });
    test('7. 模式开关必须四字段齐发（缺字段会被后端原始 boolean 绑成 false）', () async {
      await store.createRoom();
      await store.setMode(duoMode: true);
      expect(store.room?['duoMode'], isTrue);
      await store.setMode(huntCity: true);
      expect(store.room?['huntCity'], isTrue);
      expect(store.room?['duoMode'], isTrue, reason: '只切 huntCity 绝不能把 duoMode 静默关掉');
      await store.setMode(voiceMode: true, anonymous: true);
      expect(store.room?['duoMode'], isTrue);
      expect(store.room?['huntCity'], isTrue);
      // 未显式传入的项必须按当前房间视图回填，而不是回落到 false
      await store.setMode(voiceMode: false);
      expect(store.room?['anonymous'], isTrue);
      expect(store.room?['duoMode'], isTrue);
      await store.leaveRoom();
    });

    test('8. 双客户端：duo 邀请→轮询→接受→解除，再转让房主并踢人', () async {
      final b = AppStore(ApiClient(config));
      addTearDown(b.dispose);
      expect(await b.login(user2, pass2), isTrue, reason: '第二个账号登录失败');
      await waitFor('B 的 WS 已连接', () => b.wsConnected ? true : null, timeoutMs: 15000);

      await store.createRoom();
      final roomNo = '${store.room?['roomNo']}';
      await b.joinRoom(roomNo);
      await waitFor('房主视图里出现 B',
          () => (store.room?['players'] as List?)?.any((p) => p['userId'] == b.user?['id']) == true
              ? true
              : null);

      await store.setMode(duoMode: true);
      final aId = store.user!['id'] as int;
      final bId = b.user!['id'] as int;
      await store.inviteDuo(bId);

      final inv = await pollUntil(b.pollDuoInvite, reason: 'B 轮询到组队邀请');
      expect(inv['fromUserId'], aId);
      expect(inv['fromName'], store.user?['nickname']);

      await b.acceptDuo(aId);
      final pair = b.duoPairOfMine();
      expect(pair, isNotNull, reason: '接受后 B 必须查到自己所在的座位对');
      expect(pair!.length, 2);
      expect(pair.contains(b.mySeat), isTrue);

      await b.cancelDuo();
      await waitFor('解除后 duos 清空',
          () => ((b.room?['duos'] as List?)?.isEmpty ?? false) ? true : null);

      // 转让房主：A 交出管理权，B 成为房主后把 A 踢出
      await store.transferHost(bId);
      await waitFor('A 不再是房主', () => store.isHost ? null : true);
      await waitFor('B 已成为房主', () => b.isHost ? true : null);
      await b.kickPlayer(aId);
      await waitFor('A 收到 room.kicked 并清空房间', () => store.room == null ? true : null);
      await b.leaveRoom();
    }, skip: enabled2 ? false : '未配置 WW_LIVE_USER2 / WW_LIVE_PASS2，跳过双客户端用例');
    test('9. 房主的动作会以 room.event 实时推到另一个客户端（布告栏链路）', () async {
      final b = AppStore(ApiClient(config));
      addTearDown(b.dispose);
      expect(await b.login(user2, pass2), isTrue);
      await waitFor('B 的 WS 已连接', () => b.wsConnected ? true : null, timeoutMs: 15000);

      await store.createRoom();
      await b.joinRoom('${store.room?['roomNo']}');
      await waitFor('B 进入房间', () => b.inRoom ? true : null);

      // 服务端在 setMode / add-ai 里都会 broadcastEvent 一条布告，验证它真能落到对端
      await store.setMode(anonymous: true);
      await waitFor('B 收到 room.event 布告', () => b.roomFeed.isNotEmpty ? true : null, timeoutMs: 10000);
      expect(b.roomFeed.last, contains('房主更新了模式'));
      // 布告必须同时带来 room.state（匿名开关真的生效）
      expect(b.room?['anonymous'], isTrue);

      await store.addAi();
      final feedLen = b.roomFeed.length;
      expect(b.roomFeed.last, contains('房主添加了 AI 补位玩家'));
      // 连续同类布告应折叠而不是各占一行
      await store.addAi();
      await waitFor('第二次补 AI 的布告折叠',
          () => b.roomFeed.length < feedLen + 2 && b.roomFeed.last.contains('×') ? true : null,
          timeoutMs: 10000);
      await store.leaveRoom();
      await b.leaveRoom();
    }, skip: enabled2 ? false : '未配置第二个账号');

    test('10. 未读聚合接口按 {messages,requests} 校准本地计数', () async {
      expect(store.token, isNotNull, reason: '复用前面用例已登录的会话');
      store.unreadMessages = 42;
      store.unreadRequests = 42;
      await store.refreshUnread();
      expect(store.unreadMessages, 0, reason: '服务端聚合应覆盖本地脏值');
      expect(store.unreadRequests, 0);
      expect(store.unreadTotal, 0);
    });
    test('11. 论坛：发帖→列表可见→详情→回复→删除（自清理）', () async {
      final stamp = DateTime.now().millisecondsSinceEpoch;
      final title = '三端自测帖 $stamp';
      final created = await api.post('/api/forum', {
        'title': title,
        'content': '这是 Flutter 三端客户端契约测试发的帖子，随后会删除。',
        'category': '综合',
      });
      expect(created['ok'], isTrue);
      final id = (created['id'] as num).toInt();

      final list = await api.getList('/api/forum');
      final mine = list.cast<Map>().where((p) => p['id'] == id).toList();
      expect(mine, hasLength(1), reason: '新帖必须出现在无分页的全量列表里');
      expect(mine.first['author'], isNotNull);
      expect(mine.first['at'].toString(), contains('T'), reason: 'at 是无时区 ISO 串');

      final detail = await api.get('/api/forum/$id');
      expect(detail['title'], title);
      expect(detail['replies'], isA<List>());

      await api.post('/api/forum/$id/reply', {'content': '自测回复一条'});
      final after = await api.get('/api/forum/$id');
      expect((after['replies'] as List), hasLength(1));
      expect(after['replyCount'], 1);

      await api.post('/api/forum/$id/delete');
      final gone = (await api.getList('/api/forum')).cast<Map>().where((p) => p['id'] == id);
      expect(gone, isEmpty, reason: '删除后不应再出现在列表里');
    });

    test('12. 排行榜 + 能力门控：store.loadFeatures 后 feat() 判定正确', () async {
      final r = await api.get('/api/ranking?by=gold&limit=10');
      expect(r['by'], 'gold');
      final list = r['list'] as List;
      expect(list, isNotEmpty);
      final first = list.first as Map;
      expect(first['rank'], 1);
      expect(first['nickname'], isNotNull);
      expect((first['gold'] as num), greaterThanOrEqualTo(0));

      await store.loadFeatures();
      expect(store.disabledFeatures, isA<List<String>>());
      // 本机实测管理员没关任何功能；断言门控判定为"启用"，同时不依赖具体集合
      expect(store.feat('forum'), !store.disabledFeatures.contains('forum'));
      expect(store.feat('__never_exists__'), isTrue, reason: '不在 disabled 数组里即视为启用');
    });

    test('13. 资料编辑：写入→读回→回滚，音色设置与分角色战绩', () async {
      final before = await api.get('/api/user/profile');
      final origSign = before['signature'] ?? '';
      final stamp = DateTime.now().second;
      await api.post('/api/user/profile-info', {'signature': '契约测试签名 $stamp'});
      final after = await api.get('/api/user/profile');
      expect(after['signature'], '契约测试签名 $stamp');
      // 回滚，别把测试账号资料弄脏
      await api.post('/api/user/profile-info', {'signature': origSign});
      final back = await api.get('/api/user/profile');
      expect(back['signature'], origSign);

      final g = await api.post('/api/user/voice-gender', {'gender': 'M'});
      expect(g['voiceGender'], 'M');
      final cleared = await api.post('/api/user/voice-gender', {'gender': ''});
      expect(cleared['voiceGender'], anyOf('', isEmpty, isNull));

      final rs = await api.getList('/api/user/rolestats');
      expect(rs, isA<List>(), reason: 'rolestats 返回数组，客户端必须用 getList');
    });

    test('14. 负例契约：兑换码与对自己的互赞必须被服务端拒绝并给出中文原因', () async {
      Future<String> errOf(Future<void> Function() f) async {
        try {
          await f();
          return '<没有报错>';
        } on AppError catch (e) {
          return e.message;
        }
      }

      expect(
        await errOf(() => store.redeem('NOSUCHCODE9999')),
        contains('兑换码'),
        reason: '服务端错误文案已很具体（不存在/已停用/已过期/已被领完/已兑换过）',
      );

      final me = store.user!['id'] as int;
      final self = await errOf(() => api.post('/api/game/kudos', {
            'gameId': 1,
            'toUserId': me,
            'type': 'PRAISE',
            'note': null,
          }));
      expect(self, isNot('<没有报错>'), reason: '不能给自己点赞');

      final k = await api.get('/api/game/kudos/1');
      expect(k['byUser'], isA<Map>());
    });
    test('15. 更新动态是静态文件 /data/changelog.json（不是 API）', () async {
      final v = await api.getStatic('/data/changelog.json');
      expect('${v['updated']}', isNotEmpty);
      final items = v['items'] as List;
      expect(items, isNotEmpty);
      final first = items.first as Map;
      expect(first['tag'], isNotNull, reason: '网页版按 tag 上色，缺了会渲染成空白徽章');
      expect('${first['text']}', isNotEmpty);
      debugPrint('[live] changelog ${v['updated']} 共 ${items.length} 条');
    });

    test('16. 下载中心三种 url 形态（文件 / 站点根 / null）', () async {
      final v = await api.get('/api/downloads');
      final items = (v['items'] as List).cast<Map>();
      expect(items, isNotEmpty);
      final files = items.where((e) => e['url'] != null && '${e['url']}'.startsWith('/download/')).toList();
      expect(files, isNotEmpty, reason: '至少要有一个可下载的安装包');
      final f = files.first;
      expect(((f['size'] as num?) ?? 0).toInt(), greaterThan(0));
      expect(f['available'], isTrue);
      // 站点根条目（PWA）与未发布条目（url 为 null）都必须能被区分出来
      expect(items.any((e) => e['url'] == '/'), isTrue, reason: 'PWA 条目 url 应为站点根');
      debugPrint('[live] downloads ${items.length} 项，首个文件 ${(f['size'] as num) / 1048576} MB');
    });

    test('17. 非管理员访问 /api/admin/** 必须 403 且给出中文原因', () async {
      expect(store.user?['admin'], isNot(true), reason: '测试账号不应是管理员');
      try {
        await api.getList('/api/admin/users');
        fail('普通账号不该能读用户列表');
      } on AppError catch (e) {
        expect(e.message, contains('管理员'));
      }
    });

    test('18. 头像上传：multipart 字段 file → 返回相对 avatarUrl 且可匿名 GET', () async {
      // 最小合法 PNG（1x1 透明），服务端会用 ImageIO 居中裁方重编码成 128x128
      const pngB64 =
          'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAC0lEQVR42mNkAAIAAAoAAv/lxKUAAAAASUVORK5CYII=';
      final bytes = base64Decode(pngB64);
      final v = await api.postFile('/api/user/avatar', field: 'file', filename: 'probe.png', bytes: bytes);
      final url = '${v['avatarUrl'] ?? ''}';
      expect(url, startsWith('/uploads/'), reason: '服务端给的是相对路径，客户端必须自己拼 baseUrl');
      expect(v['id'], store.user?['id']);

      final probe = await http.get(Uri.parse('$base$url'));
      expect(probe.statusCode, 200, reason: '/uploads/** 必须可匿名访问，否则头像显示不出来');
      expect(probe.headers['content-type'], contains('image/png'));
      debugPrint('[live] avatar $url (${probe.contentLength} bytes)');
    });

    test('19. 头像过大要被服务端拒绝（2MB 上限的客户端预检有依据）', () async {
      final big = Uint8List(2 * 1024 * 1024 + 10); // 仅用于触发体积校验，内容无意义
      try {
        await api.postFile('/api/user/avatar', field: 'file', filename: 'big.png', bytes: big);
        fail('超过 2MB 不应被接受');
      } on AppError catch (e) {
        expect(e.message, contains('2MB'));
      }
    });
    test('20. 真实对局的回放能逐帧折完，座位/存活/胜负重建自洽', () async {
      final stats = await api.get('/api/user/stats');
      final recent = (stats['recent'] as List?) ?? const [];
      if (recent.isEmpty) {
        // 没有已完成对局时不空过：改验「读别人的录像必须被拒」（IDOR 守卫）。
        // 服务端对非参与者给 403 无权查看该对局回放，对不存在的局给 400 对局不存在。
        var refused = false;
        try {
          await api.get('/api/game/replay/1');
        } on AppError catch (e) {
          refused = true;
          debugPrint('[live] 该账号无已完成对局，改验越权读取被拒：${e.message}');
        }
        expect(refused, isTrue, reason: '普通账号不该能读到别人的对局录像');
        return;
      }
      final gid = (recent.first['gameId'] as num).toInt();
      final r = await api.get('/api/game/replay/$gid');
      expect(r['roomNo'], isNotNull);
      final roles = parseSeatRoles(r['seatRoles']);
      expect(roles, isNotEmpty, reason: 'seatRoles 是空格分隔字符串，解析不出来就无法渲染座位条');
      final events = ((r['events'] as List?) ?? const []).map((e) => ReplayEvent.fromJson(e as Map)).toList();
      expect(events, isNotEmpty);
      // seq 必须连续且等于下标，否则"帧号"就没有意义
      for (var i = 0; i < events.length; i++) {
        expect(events[i].seq, i, reason: '第 $i 帧的 seq 不连续，播放器定位会错位');
      }

      final seats = roles.keys.toList()..sort();
      final p = ReplayPlayer(events, seats: seats)..seatRoles = roles;
      var prevAlive = seats.length;
      for (var i = 0; i < events.length; i++) {
        p.jumpTo(i);
        final s = p.state;
        expect(s.alive.every((x) => seats.contains(x)), isTrue, reason: '第 $i 帧出现了不存在的座位');
        // 只允许"复活卡"造成存活回升
        final revived = events[i].type == 'ITEM_EFFECT' && events[i].detail.contains('复活');
        if (!revived) {
          expect(s.alive.length <= prevAlive, isTrue, reason: '第 $i 帧存活人数无端增加');
        }
        prevAlive = s.alive.length;
        expect(replayLine(events[i], roles).text.trim(), isNotEmpty, reason: '第 $i 帧渲染成空行');
      }
      final fin = p.state;
      debugPrint('[live] 回放 #$gid 共 ${events.length} 帧，终局存活 ${fin.alive.length}/${seats.length} '
          'over=${fin.over} winner=${fin.winner.isEmpty ? (r['winner'] ?? '') : fin.winner}');
      if (fin.over) expect(fin.winner, isNotEmpty);
    });

    test('21. 不存在的对局回放要给出服务端中文错误而不是崩溃', () async {
      try {
        await api.get('/api/game/replay/99999999');
        fail('不存在的对局不该返回 200');
      } on AppError catch (e) {
        expect(e.message, isNotEmpty);
        debugPrint('[live] replay 错误文案：${e.message}');
      }
    });
  }, skip: enabled ? false : '未设置 WW_LIVE_BASE / WW_LIVE_USER / WW_LIVE_PASS，跳过打真实服务端的契约测试');
}

/// 轮询等待某个由客户端状态体现的条件成立（WS 推送是异步的，不能同步断言）。
Future<T> waitFor<T>(String what, T? Function() read, {int timeoutMs = 15000}) async {
  final deadline = DateTime.now().add(Duration(milliseconds: timeoutMs));
  while (DateTime.now().isBefore(deadline)) {
    final v = read();
    if (v != null) return v;
    await Future<void>.delayed(const Duration(milliseconds: 120));
  }
  throw StateError('等待超时：$what（${timeoutMs}ms 内未达成）');
}

/// 异步版：条件本身要发一次请求才能判定（如 duo 邀请只能靠 GET /duo/pending 拿）。
Future<T> pollUntil<T>(Future<T?> Function() read, {String reason = '', int timeoutMs = 15000}) async {
  final deadline = DateTime.now().add(Duration(milliseconds: timeoutMs));
  while (DateTime.now().isBefore(deadline)) {
    final v = await read();
    if (v != null) return v;
    await Future<void>.delayed(const Duration(milliseconds: 300));
  }
  throw StateError('轮询超时：${reason.isEmpty ? '条件' : reason}（${timeoutMs}ms 内未达成）');
}

class _InsecureOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? ctx) =>
      super.createHttpClient(ctx)..badCertificateCallback = (X509Certificate c, String h, int p) => true;
}
