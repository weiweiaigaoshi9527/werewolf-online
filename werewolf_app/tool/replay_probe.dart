// 真实录像探针：在真实服务器上跑完一局 AI 对局，然后拉取该局的回放，
// 用 lib/replay_engine.dart 逐帧折一遍，断言状态重建的不变量。
//
// 这不是单元测试（它会真的开局、消耗 AI 额度、耗时十几分钟），所以放在 tool/ 下手动跑：
//   set WW_LIVE_BASE=https://localhost:11111
//   set WW_LIVE_USER=winclient01&& set WW_LIVE_PASS=...
//   dart run tool/replay_probe.dart
//
// 纯 Dart（不 import Flutter）：replay_engine 本身就是无 Flutter 依赖的纯逻辑层。
// CLI 探针就是要往 stdout 打进度，故豁免 avoid_print。
// ignore_for_file: avoid_print
// ⚠️ 也别按 unnecessary_brace_in_string_interps 去掉 `'${timeoutMin}分钟'` 的花括号：
// Dart 插值标识符遇到 CJK 会停止解析，`'$timeoutMin分钟'` 不插值、直接输出字面量。
// ignore_for_file: unnecessary_brace_in_string_interps
import 'dart:async';
import 'dart:convert';
import 'dart:io';

import 'package:http/http.dart' as http;
import 'package:werewolf_app/replay_engine.dart';

class _InsecureBadCert extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? c) =>
      super.createHttpClient(c)..badCertificateCallback = (a, b, c) => true;
}

Future<void> main() async {
  HttpOverrides.global = _InsecureBadCert();
  final base = (Platform.environment['WW_LIVE_BASE'] ?? '').trim();
  final user = (Platform.environment['WW_LIVE_USER'] ?? '').trim();
  final pass = (Platform.environment['WW_LIVE_PASS'] ?? '').trim();
  if (base.isEmpty || user.isEmpty || pass.isEmpty) {
    stderr.writeln('需要 WW_LIVE_BASE / WW_LIVE_USER / WW_LIVE_PASS');
    exitCode = 2;
    return;
  }
  final timeoutMin = int.tryParse(Platform.environment['WW_PROBE_MIN'] ?? '') ?? 22;

  final h = {'content-type': 'application/json'};
  String? token;

  Future<dynamic> call(String method, String path, [Object? body]) async {
    final url = Uri.parse('$base$path');
    final headers = {...h, if (token != null) 'authorization': 'Bearer $token'};
    // 三元表达式里不能用级联语法，所以 GET/POST 各自单独发
    final res = method == 'GET'
        ? await http.get(url, headers: headers).timeout(const Duration(seconds: 30))
        : await http
            .post(url, headers: headers, body: jsonEncode(body ?? {}))
            .timeout(const Duration(seconds: 30));
    if (res.statusCode >= 400) {
      throw StateError('$method $path → ${res.statusCode} ${res.body.isEmpty ? '' : res.body}');
    }
    return res.body.isEmpty ? null : jsonDecode(res.body);
  }

  final login = await call('POST', '/api/auth/login', {'username': user, 'password': pass}) as Map;
  token = login['token'] as String?;
  print('登录 ok，userId=${(login['user'] as Map)['id']}');

  var room = await call('POST', '/api/room/create') as Map;
  final roomNo = room['roomNo'];
  print('建房 $roomNo');
  for (var i = 0; i < 6; i++) {
    final players = (room['players'] as List?) ?? const [];
    if (players.length >= 6) break;
    room = await call('POST', '/api/room/add-ai') as Map;
  }
  print('补位到 ${(room['players'] as List).length} 人');
  await call('POST', '/api/room/ready', {'ready': true});
  await call('POST', '/api/game/start', {});
  print('已开局，等待看门狗自动代打（每回合上限 25s）…');

  final deadline = DateTime.now().add(Duration(minutes: timeoutMin));
  Map? finalState;
  var lastLog = '';
  while (DateTime.now().isBefore(deadline)) {
    await Future<void>.delayed(const Duration(seconds: 3));
    Map? st;
    try {
      st = await call('GET', '/api/game/state') as Map?;
    } on StateError {
      continue; // 结算瞬间可能短暂取不到
    }
    if (st == null) continue;
    final line = '${st['phase']} 第${st['day']}天 存活${(st['seats'] as List?)?.where((s) => s['alive'] == true).length}';
    if (line != lastLog) {
      lastLog = line;
      print('  $line');
    }
    if (st['phase'] == 'GAME_OVER' || st['winner'] != null) {
      finalState = st;
      break;
    }
  }

  if (finalState == null) {
    print('!! ${timeoutMin}分钟内未自然结束，强制收场并放弃折帧');
    try {
      await call('POST', '/api/game/end', {});
    } catch (_) {}
    exitCode = 1;
    return;
  }
  final gid = (finalState['gameId'] as num?)?.toInt();
  print('本局结束 winner=${finalState['winner']} gameId=$gid');
  if (gid == null) {
    stderr.writeln('终局状态里没有 gameId，无法取回放');
    exitCode = 1;
    return;
  }

  final r = await call('GET', '/api/game/replay/$gid') as Map;
  final roles = parseSeatRoles(r['seatRoles']);
  final events = ((r['events'] as List?) ?? const []).map((e) => ReplayEvent.fromJson(e as Map)).toList();
  print('真实录像：${events.length} 帧，seatRoles=${roles.length} 座，winner=${r['winner']}');
  if (events.isEmpty || roles.isEmpty) {
    stderr.writeln('录像为空，折帧无从验证');
    exitCode = 1;
    return;
  }

  // 折帧不变量
  for (var i = 0; i < events.length; i++) {
    if (events[i].seq != i) throw StateError('第 $i 帧 seq 不连续（实际 ${events[i].seq}）');
  }
  final seats = roles.keys.toList()..sort();
  final p = ReplayPlayer(events, seats: seats)..seatRoles = roles;
  var prevAlive = seats.length;
  final aliveTrace = <int>[];
  for (var i = 0; i < events.length; i++) {
    p.jumpTo(i);
    final s = p.state;
    if (!s.alive.every(seats.contains)) throw StateError('第 $i 帧出现不存在的座位');
    final revived = events[i].type == 'ITEM_EFFECT' && events[i].detail.contains('复活');
    if (!revived && s.alive.length > prevAlive) throw StateError('第 $i 帧存活人数无端增加');
    prevAlive = s.alive.length;
    aliveTrace.add(s.alive.length);
    if (replayLine(events[i], roles).text.trim().isEmpty) throw StateError('第 $i 帧渲染成空行');
  }
  final fin = p.state;
  print('折帧完成：存活 ${fin.alive.length}/${seats.length}，警长=${fin.sheriff ?? '无'}，over=${fin.over}，winner=${fin.winner}');
  print('存活曲线：${aliveTrace.join('→')}');
  print('事件类型分布：${events.fold(<String, int>{}, (m, e) { m[e.type] = (m[e.type] ?? 0) + 1; return m; })}');
  if (!fin.over || fin.winner.isEmpty) {
    stderr.writeln('最后一帧不是 GAME_OVER，折帧终点与顶层 winner 不一致');
    exitCode = 1;
    return;
  }

  // 与网页版同源的一次性校验：把每帧文案打前 6 行，便于人工核对措辞
  print('前 6 帧文案：');
  for (final e in events.take(6)) {
    final l = replayLine(e, roles);
    print('  ${e.day}天 ${l.emoji} ${l.text}');
  }
  await call('POST', '/api/room/leave');
  print('OK 真实录像端到端折帧通过');
}
