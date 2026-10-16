import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'board_editor.dart';
import 'profile_view.dart';
import '../i18n.dart';

/// 等待室。房主可管人（踢出/转让/补 AI/板子/模式）。
class RoomScreen extends StatefulWidget {
  const RoomScreen({super.key});

  @override
  State<RoomScreen> createState() => _RoomScreenState();
}

class _RoomScreenState extends State<RoomScreen> {
  void _toast(String m, {bool error = false}) {
    if (!mounted) return;
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Tx(m),
      backgroundColor: error ? kBlood.withOpacity(.92) : null,
    ));
  }

  Future<bool> _confirm(String title, String body) async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: Tx(title),
        content: Tx(body),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Tx('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Tx('确定')),
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

    return Scaffold(
      appBar: AppBar(
        title: Tx('房间 ${room['roomNo'] ?? ''}'),
        leading: IconButton(
          icon: const Icon(Icons.logout),
          tooltip: tr('离开房间'),
          onPressed: () => _act(store.leaveRoom),
        ),
        actions: [
          IconButton(
            tooltip: tr('外链邀请'),
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
              tooltip: tr('添加 AI（一次补一个空位）'),
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
                Tx('房间号 ', style: TextStyle(color: kDim)),
                Tx('${room['roomNo'] ?? ''}',
                    style: TextStyle(
                        color: kAccent, fontSize: 22, fontWeight: FontWeight.bold, letterSpacing: 4)),
                const Spacer(),
                Tx('${players.length}/$maxSeats 人', style: TextStyle(color: kDim)),
              ],
            ),
          ),
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
                      label: Tx(meReady ? '取消准备' : '准备'),
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
                        label: const Tx('开始游戏'),
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
            Tx('· $l', maxLines: 1, overflow: TextOverflow.ellipsis,
                style: TextStyle(color: kDim, fontSize: 12)),
          if (store.roomFeed.length > lines.length)
            Tx('… 另有 ${store.roomFeed.length - lines.length} 条动态',
                style: TextStyle(color: kDim, fontSize: 11)),
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
              Tx('板子 ', style: TextStyle(color: kDim, fontSize: 13)),
              Expanded(
                  child: Tx('$boardName · $boardTotal 人',
                      style: TextStyle(color: kMoon, fontWeight: FontWeight.bold))),
              TextButton(
                onPressed: () =>
                    Navigator.push(context, MaterialPageRoute(builder: (_) => const BoardEditorScreen())),
                child: const Tx('配置板子'),
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
      title: Tx(label, style: const TextStyle(fontSize: 13.5)),
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

    final card = Card(
      shape: RoundedRectangleBorder(
        side: BorderSide(color: isMe ? kAccent : kBorder),
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
                Tx('#$seat', style: TextStyle(color: kDim, fontSize: 12)),
                if (p['host'] == true) ...[
                  const SizedBox(width: 4),
                  Icon(Icons.star, size: 12, color: kMoon),
                ],
                const Spacer(),
                if (isReal && !isMe && host && waiting) _seatMenu(store, seat, uid.toInt(), '${p['nickname']}'),
              ],
            ),
            const SizedBox(height: 4),
            if (filled) ...[
              Tx('${p['ai'] == true ? '🤖 ' : ''}${p['nickname'] ?? ''}',
                  maxLines: 1,
                  overflow: TextOverflow.ellipsis,
                  style: TextStyle(color: isMe ? kAccent : kMoon, fontWeight: FontWeight.bold)),
              const SizedBox(height: 4),
              Tx(p['ready'] == true ? '已准备' : '等待中',
                  style: TextStyle(fontSize: 12, color: p['ready'] == true ? kGreen : kDim)),
            ] else ...[
              const Spacer(),
              Tx('空座位', style: TextStyle(color: kDim)),
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
      tooltip: tr('房主管理'),
      icon: Icon(Icons.more_vert, size: 18, color: kDim),
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
        PopupMenuItem(value: 'kick', child: Tx('移出房间')),
        PopupMenuItem(value: 'transfer', child: Tx('转让房主')),
      ],
    );
  }
}
