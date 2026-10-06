import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'board_editor.dart';
import 'profile_view.dart';

/// 等待室。房主可管人（踢出/转让/补 AI/板子/模式），双人组队开启后可结队。
class RoomScreen extends StatefulWidget {
  const RoomScreen({super.key});

  @override
  State<RoomScreen> createState() => _RoomScreenState();
}

class _RoomScreenState extends State<RoomScreen> {
  /// 组队邀请轮询：服务端 duoInvite 恒为 null，只能靠 GET /api/room/duo/pending。
  /// _duoAsked 用来去重，避免同一个邀请每 2 秒弹一次确认框。
  Timer? _duoTimer;
  final Set<int> _duoAsked = {};

  @override
  void dispose() {
    _duoTimer?.cancel();
    super.dispose();
  }

  void _syncDuoPolling(AppStore store) {
    final shouldPoll = store.room != null &&
        store.room!['duoMode'] == true &&
        store.room!['status'] == 'WAITING' &&
        store.user?['id'] != null;
    if (shouldPoll && _duoTimer == null) {
      _duoTimer = Timer.periodic(const Duration(seconds: 2), (_) => _pollDuo());
    } else if (!shouldPoll && _duoTimer != null) {
      _duoTimer?.cancel();
      _duoTimer = null;
      _duoAsked.clear();
    }
  }

  Future<void> _pollDuo() async {
    if (!mounted) return;
    final store = context.read<AppStore>();
    final inv = await store.pollDuoInvite();
    if (inv == null || !mounted) return;
    final from = (inv['fromUserId'] as num?)?.toInt();
    if (from == null || _duoAsked.contains(from)) return;
    if (from == store.user?['id']) return; // 自己发起的不弹
    _duoAsked.add(from);
    final name = '${inv['fromName'] ?? '玩家'}';
    final accept = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('👫 组队邀请'),
        content: Text('$name 邀请你结为双人队伍（同阵营，狼坑不足时整对进好人），接受吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('拒绝')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('接受')),
        ],
      ),
    );
    if (!mounted) return;
    try {
      if (accept == true) {
        await store.acceptDuo(from);
        _toast('已组队');
      } else {
        await store.cancelDuo();
        _toast('已拒绝组队邀请');
      }
    } on AppError catch (e) {
      if (mounted) _toast(e.message, error: true);
    }
  }

  void _toast(String m, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Text(m),
      backgroundColor: error ? kBlood.withOpacity(.92) : null,
    ));
  }

  Future<bool> _confirm(String title, String body) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Text(title),
        content: Text(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('确定')),
        ],
      ),
    );
    return ok == true;
  }

  Future<void> _act(Future<void> Function() f) async {
    try {
      await f();
    } on AppError catch (e) {
      if (mounted) _toast(e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    final room = store.room ?? const {};
    final players = (room['players'] as List?) ?? const [];
    final maxSeats = (room['maxSeats'] as num?)?.toInt() ?? 16;
    final host = store.isHost;
    final waiting = room['status'] == 'WAITING';
    final meId = store.user?['id'];
    final meReady = players.any((p) => p['userId'] == meId && p['ready'] == true);
    _syncDuoPolling(store);

    return Scaffold(
      appBar: AppBar(
        title: Text('房间 ${room['roomNo'] ?? ''}'),
        leading: IconButton(
          icon: const Icon(Icons.logout),
          tooltip: '离开房间',
          onPressed: () => _act(store.leaveRoom),
        ),
        actions: [
          IconButton(
            tooltip: '外链邀请',
            icon: const Icon(Icons.link),
            onPressed: () {
              final no = room['roomNo'];
              if (no == null) return;
              final link = '${store.config.baseUrl}/?join=$no';
              Clipboard.setData(ClipboardData(text: link));
              _toast('已复制邀请链接：$link');
            },
          ),
          if (host && waiting)
            IconButton(
              tooltip: '添加 AI（一次补一个空位）',
              icon: const Icon(Icons.smart_toy),
              onPressed: () => _act(store.addAi),
            ),
        ],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 12, 12, 4),
            child: Row(
              children: [
                const Text('房间号 ', style: TextStyle(color: kDim)),
                Text('${room['roomNo'] ?? ''}',
                    style: const TextStyle(
                        color: kAccent, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: 4)),
                const Spacer(),
                Text('${players.length}/$maxSeats 人', style: const TextStyle(color: kDim)),
              ],
            ),
          ),
          _duoStrip(store, room),
          _feedBoard(store),
          if (host && waiting) _hostPanel(context, store, room),
          Expanded(
            child: GridView.count(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              crossAxisCount: 2,
              mainAxisSpacing: 12,
              crossAxisSpacing: 12,
              childAspectRatio: 1.25,
              children: [
                for (var i = 0; i < maxSeats; i++) _seat(context, store, room, players, i + 1),
              ],
            ),
          ),
          SafeArea(
            child: Padding(
              padding: const EdgeInsets.all(12),
              child: Row(
                children: [
                  Expanded(
                    child: OutlinedButton.icon(
                      onPressed: store.busy ? null : () => _act(() => store.setReady(!meReady)),
                      icon: const Icon(Icons.check_circle_outline),
                      label: Text(meReady ? '取消准备' : '准备'),
                    ),
                  ),
                  if (host) ...[
                    const SizedBox(width: 12),
                    Expanded(
                      child: ElevatedButton.icon(
                        onPressed: (store.busy || players.length < 3)
                            ? null
                            : () => _act(store.startGame),
                        icon: const Icon(Icons.play_arrow),
                        label: const Text('开始游戏'),
                      ),
                    ),
                  ],
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  /// 房间布告栏：room.event 推来的动态（谁进房、补 AI、房主改模式、被移出等）。
  /// 之前这类推送被整个丢弃，房间里发生的事在 App 上要等下一次 room.state 才看得见。
  Widget _feedBoard(AppStore store) {
    if (store.roomFeed.isEmpty) return const SizedBox.shrink();
    final lines = store.roomFeed.take(4).toList();
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 7),
      decoration: BoxDecoration(
        color: kPanel.withOpacity(.6),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: kBorder),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          for (final l in lines.reversed)
            Text('· $l', maxLines: 1, overflow: TextOverflow.ellipsis,
                style: const TextStyle(color: kDim, fontSize: 12)),
          if (store.roomFeed.length > lines.length)
            Text('… 另有 ${store.roomFeed.length - lines.length} 条动态',
                style: const TextStyle(color: kDim, fontSize: 11)),
        ],
      ),
    );
  }

  /// 双人组队的状态条：开了就显示已成的对子与我自己的状态，并提供解除入口。
  Widget _duoStrip(AppStore store, Map room) {
    if (room['duoMode'] != true) return const SizedBox.shrink();
    final duos = (room['duos'] as List?) ?? const [];
    final mine = store.duoPairOfMine();
    return Container(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 2),
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10)),
      child: Row(
        children: [
          const Text('👫', style: TextStyle(fontSize: 15)),
          const SizedBox(width: 8),
          Expanded(
            child: Text(
              duos.isEmpty
                  ? '双人组队已开启：点真人座位上的「组队」发起邀请'
                  : '已组队 ${duos.length} 对：${duos.map((d) => '${(d as List).join('/')}号').join('  ')}',
              style: const TextStyle(color: kDim, fontSize: 12.5),
            ),
          ),
          if (mine != null)
            TextButton(
              onPressed: () => _act(store.cancelDuo),
              child: const Text('解除我的队伍'),
            ),
        ],
      ),
    );
  }

  Widget _hostPanel(BuildContext context, AppStore store, Map room) {
    final board = (room['board'] as Map?) ?? const {};
    final boardTotal = board.values.fold<int>(0, (a, v) => a + ((v as num).toInt()));
    final boardName =
        (room['boardName'] == null || room['boardName'] == 'custom') ? '自定义' : '${room['boardName']}';
    return Card(
      margin: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
        child: Column(
          children: [
            Row(children: [
              const Text('板子  ', style: TextStyle(color: kDim, fontSize: 13)),
              Expanded(
                  child: Text('$boardName · $boardTotal 人',
                      style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold))),
              TextButton(
                onPressed: () =>
                    Navigator.push(context, MaterialPageRoute(builder: (_) => const BoardEditorScreen())),
                child: const Text('配置板子'),
              ),
            ]),
            _switch('语音同传（匿名伪装以藏 AI）', room['voiceMode'] == true, (v) {
              // 开语音同传必须连带匿名，否则座位光环会暴露 AI；关语音时不动匿名。
              _act(() => store.setMode(
                  voiceMode: v, anonymous: v ? true : (room['anonymous'] == true)));
            }),
            _switch('匿名模式（隐藏真实昵称）', room['anonymous'] == true,
                (v) => _act(() => store.setMode(anonymous: v))),
            _switch('猎城规则（狼人数量达标直接判胜）', room['huntCity'] == true,
                (v) => _act(() => store.setMode(huntCity: v))),
            _switch('双人组队（结队者同阵营）', room['duoMode'] == true,
                (v) => _act(() => store.setMode(duoMode: v))),
            _switch('功能道具赛（携带道具本局生效）', room['itemMatch'] != false,
                (v) => _act(() => store.setItemMatch(v))),
          ],
        ),
      ),
    );
  }

  Widget _switch(String label, bool value, ValueChanged<bool> onChanged) {
    return SwitchListTile(
      dense: true,
      contentPadding: EdgeInsets.zero,
      activeColor: kAccent,
      title: Text(label, style: const TextStyle(fontSize: 13.5)),
      value: value,
      onChanged: onChanged,
    );
  }

  Widget _seat(BuildContext context, AppStore store, Map room, List players, int seat) {
    final p = players.cast<Map>().firstWhere(
          (x) => (x['seat'] as num?)?.toInt() == seat,
          orElse: () => const {},
        );
    final filled = p.isNotEmpty && p['userId'] != null;
    final uid = p['userId'];
    final myId = store.user?['id'];
    final isMe = filled && uid == myId;
    // 真人判定：userId 为正数（AI 为负；匿名房不下发 userId）
    final isReal = filled && uid is num && uid > 0;
    final host = store.isHost;
    final waiting = room['status'] == 'WAITING';
    final duoOn = room['duoMode'] == true;
    final myPair = store.duoPairOfMine();
    final isMyPartner = myPair != null && myPair.contains(seat) && !isMe;
    final inSomeDuo = ((room['duos'] as List?) ?? const [])
        .any((d) => d is List && d.length == 2 && (d[0] == seat || d[1] == seat));

    final card = Card(
      shape: RoundedRectangleBorder(
        side: BorderSide(color: isMe ? kAccent : (isMyPartner ? kMoon : kBorder)),
        borderRadius: BorderRadius.circular(14),
      ),
      child: Padding(
        padding: const EdgeInsets.fromLTRB(12, 8, 4, 8),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Row(
              children: [
                Text('#$seat', style: const TextStyle(color: kDim, fontSize: 12)),
                if (p['host'] == true) ...[
                  const SizedBox(width: 4),
                  const Icon(Icons.star, size: 12, color: kMoon),
                ],
                if (isMyPartner) ...[
                  const SizedBox(width: 4),
                  const Text('👫', style: TextStyle(fontSize: 11)),
                ],
                const Spacer(),
                if (isReal && !isMe && host && waiting) _seatMenu(store, seat, uid.toInt(), '${p['nickname']}'),
              ],
            ),
            const SizedBox(height: 4),
            if (filled) ...[
              Text('${p['ai'] == true ? '🤖 ' : ''}${p['nickname'] ?? ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: isMe ? kAccent : kMoon, fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Row(
                children: [
                  Text(p['ready'] == true ? '已准备' : '等待中',
                      style: TextStyle(fontSize: 12, color: p['ready'] == true ? kGreen : kDim)),
                  const Spacer(),
                  if (duoOn && isReal && !isMe && waiting)
                    isMyPartner
                        ? TextButton(
                            style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(48, 28)),
                            onPressed: () => _act(store.cancelDuo),
                            child: const Text('解除', style: TextStyle(fontSize: 12)))
                        : inSomeDuo
                            ? const Text('已组队', style: TextStyle(fontSize: 11.5, color: kDim))
                            : TextButton(
                                style: TextButton.styleFrom(padding: EdgeInsets.zero, minimumSize: const Size(48, 28)),
                                onPressed: store.busy ? null : () => _act(() => store.inviteDuo(uid.toInt())),
                                child: const Text('组队', style: TextStyle(fontSize: 12))),
                ],
              ),
            ] else ...[
              const Spacer(),
              const Text('空座位', style: TextStyle(color: kDim)),
              const Spacer(),
            ],
          ],
        ),
      ),
    );

    if (!isReal || isMe) return card;
    return InkWell(
      borderRadius: BorderRadius.circular(14),
      onTap: () => showWwProfile(context, uid.toInt(), title: '${p['nickname']}'),
      child: card,
    );
  }

  /// 房主对单个真人座位的管理菜单。
  Widget _seatMenu(AppStore store, int seat, int userId, String name) {
    return PopupMenuButton<String>(
      tooltip: '房主管理',
      icon: const Icon(Icons.more_vert, size: 18, color: kDim),
      padding: EdgeInsets.zero,
      onSelected: (v) async {
        if (v == 'kick') {
          if (await _confirm('移出玩家', '把 $name（$seat号）移出房间？')) {
            await _act(() => store.kickPlayer(userId));
          }
        } else if (v == 'transfer') {
          if (await _confirm('转让房主', '把房间交给 $name（$seat号）？你将失去管理权限。')) {
            await _act(() => store.transferHost(userId));
          }
        }
      },
      itemBuilder: (_) => const [
        PopupMenuItem(value: 'kick', child: Text('移出房间')),
        PopupMenuItem(value: 'transfer', child: Text('转让房主')),
      ],
    );
  }
}
