import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../animations.dart';
import '../api.dart';
import '../hotkeys.dart';
import '../sfx.dart';
import '../store.dart';
import '../theme.dart';

class GameScreen extends StatefulWidget {
  const GameScreen({super.key});
  @override
  State<GameScreen> createState() => _GameScreenState();
}

class _GameScreenState extends State<GameScreen> {
  final _chat = TextEditingController();
  /// 聊天输入框的焦点：供快捷键 F 聚焦（网页版 $('gc-input').focus()）。
  final _chatFocus = FocusNode();
  late final AppStore _store;
  bool _micOn = false;

  /// 身份牌翻牌揭示：GameScreen 的生命周期约等于一局，所以用一次性布尔即可，
  /// 不能用 gameId 去重——game.state 里的 gameId 只在终局那一次下发。
  bool _revealPending = false;
  bool _revealed = false;

  void _maybeScheduleReveal(Map g) {
    if (_revealed || _revealPending) return;
    final info = g['myInfo'];
    if (info is! Map || info['role'] == null) return;
    _revealPending = true;
    final role = '${info['role']}';
    final faction = '${info['faction'] ?? ''}';
    final seat = (g['mySeat'] as num?)?.toInt() ?? 0;
    // 用一次性对话框路由承载，而不是在 build 里塞 Stack：
    // 既不用改动整棵 body 的嵌套，也天然挡住重复触发。
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (!mounted) {
        _revealPending = false;
        return;
      }
      showDialog<void>(
        context: context,
        barrierDismissible: false,
        barrierColor: Colors.transparent,
        builder: (_) => RoleReveal(
          role: role,
          faction: faction,
          seat: seat,
          onDone: () {
            _revealed = true;
            _revealPending = false;
            if (Navigator.of(context).canPop()) Navigator.of(context).pop();
          },
        ),
      );
    });
  }

  @override
  void initState() {
    super.initState();
    _store = context.read<AppStore>();
    _store.addListener(_reactSfx);
    // relay 字幕实时回填到发言输入框（与手动打字共存）
    _store.onVoiceRelay = (t) {
      if (mounted) {
        setState(() => _speech.value = TextEditingValue(
            text: _speech.text + t,
            selection: TextSelection.collapsed(offset: _speech.text.length + t.length),
          ));
      }
    };
  }

  Map<String, dynamic>? _prevGame;

  /// 音效只在状态监听里播：build 会被每次 WS 推送触发，在 build 里放声音会重复响。
  void _reactSfx() {
    final now = _store.game;
    for (final name in sfxEventsFor(_prevGame, now)) {
      unawaited(_store.sfx.play(name));
    }
    if (now != null) _prevGame = now;
  }

  @override
  void dispose() {
    _store.removeListener(_reactSfx);
    _store.onVoiceRelay = null;
    _chat.dispose();
    _chatFocus.dispose();
    _speech.dispose();
    super.dispose();
  }

  void _toast(String m) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(m)));

  Future<void> _toggleMic() async {
    if (_micOn) {
      await _store.stopMic();
      if (mounted) setState(() => _micOn = false);
      return;
    }
    final ok = await _store.startMic();
    if (!mounted) return;
    setState(() => _micOn = ok);
    if (!ok) _toast('无法开启麦克风（语音服务未就绪或权限被拒）');
  }

  Future<void> _act(Map<String, dynamic> a) async {
    try {
      await context.read<AppStore>().submitAction(a);
    } on AppError catch (e) {
      if (mounted) _toast(e.message);
    }
  }

  // ---------------- 桌面键盘快捷键（工作流 B，对齐网页版 ux.js HOTKEYS） ----------------

  /// 数字键 1..9 → 字符。用 LogicalKeyboardKey 判定，避免受输入法/大小写影响。
  /// 注意：LogicalKeyboardKey 重写了 == / hashCode，不能做 const map 的键。
  static final Map<LogicalKeyboardKey, String> _digitHotkeys = {
    LogicalKeyboardKey.digit1: '1',
    LogicalKeyboardKey.digit2: '2',
    LogicalKeyboardKey.digit3: '3',
    LogicalKeyboardKey.digit4: '4',
    LogicalKeyboardKey.digit5: '5',
    LogicalKeyboardKey.digit6: '6',
    LogicalKeyboardKey.digit7: '7',
    LogicalKeyboardKey.digit8: '8',
    LogicalKeyboardKey.digit9: '9',
  };

  /// 把逻辑键翻译成 hotkeyFor 认识的字符；无关按键返回 null。
  static String? _keyName(LogicalKeyboardKey k) {
    if (k == LogicalKeyboardKey.escape) return 'Escape';
    if (k == LogicalKeyboardKey.keyA) return 'a';
    if (k == LogicalKeyboardKey.keyF) return 'f';
    if (k == LogicalKeyboardKey.keyM) return 'm';
    if (k == LogicalKeyboardKey.slash) return '/';
    if (k == LogicalKeyboardKey.question) return '?';
    if (k == LogicalKeyboardKey.digit0) return '0';
    return _digitHotkeys[k];
  }

  /// 是否正在文本输入：看当前主焦点所在的 context 是否存在 EditableText 祖先。
  /// 命中即放行所有按键，绝不吃掉用户的打字（与网页版 input/textarea/select 判定同义）。
  bool _isTyping() {
    final ctx = FocusManager.instance.primaryFocus?.context;
    if (ctx == null) return false;
    return ctx.findAncestorWidgetOfExactType<EditableText>() != null;
  }

  /// 存活且非本人座位的座位号列表（顺序与 _actionPanel 的 target 按钮一致）。
  List<int> _aliveOthers(Map g) {
    final seats = (g['seats'] as List?) ?? const [];
    final mySeat = g['mySeat'];
    return seats
        .where((s) => s is Map && s['alive'] == true && s['seat'] != mySeat)
        .map((s) => (s['seat'] as num).toInt())
        .toList();
  }

  /// 从当前 game.state 提炼快捷键门控信息，口径与 _actionPanel 一一对应。
  ({bool canAct, int targetCount, bool hasPass}) _hotkeyContext() {
    final g = _store.game;
    // canAct：轮到自己且非终局（与 _actionPanel 的分支判断一致）。
    if (g == null || g['phase'] == 'GAME_OVER' || g['myTurn'] != true) {
      return (canAct: false, targetCount: 0, hasPass: false);
    }
    final kind = '${g['actionKind'] ?? ''}';
    final speech = kind == 'SPEECH' || kind == 'PK_SPEECH' || kind == 'LAST_WORDS';
    final witch = kind == 'WITCH';
    // targets(allowPass) 场景：与 _actionPanel 的 targets(...) 调用参数一致。
    const targetKinds = ['CROW', 'SILENCER', 'WOLF_KILL', 'SHOOT', 'VOTE', 'PK_VOTE', 'SHERIFF_VOTE'];
    return (
      canAct: true,
      targetCount: _aliveOthers(g).length,
      hasPass: speech || witch || targetKinds.contains(kind),
    );
  }

  /// 过麦：发言类发送「（过）」，女巫 / 弃票类发送 target:0，与界面按钮行为一致。
  void _doPass() {
    final kind = '${_store.game?['actionKind'] ?? ''}';
    if (kind == 'SPEECH' || kind == 'PK_SPEECH' || kind == 'LAST_WORDS') {
      if (_micOn) _store.stopMic();
      _act({'text': '（过）'});
      if (mounted) setState(() => _micOn = false);
    } else {
      _act({'target': 0});
    }
  }

  /// 快捷键帮助弹窗（内容来自纯逻辑层的 hotkeyHelp）。
  Future<void> _showHotkeyHelp() async {
    await showDialog<void>(
      context: context,
      builder: (ctx) => AlertDialog(
        backgroundColor: kPanel,
        title: const Text('⌨️ 快捷键'),
        content: SingleChildScrollView(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              for (final h in hotkeyHelp)
                Padding(
                  padding: const EdgeInsets.symmetric(vertical: 3),
                  child: Row(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      SizedBox(
                        width: 92,
                        child: Text(h.keys,
                            style: const TextStyle(color: kAccent, fontWeight: FontWeight.bold)),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                          child: Text(h.desc,
                              style: const TextStyle(color: kText, fontSize: 13, height: 1.35))),
                    ],
                  ),
                ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.of(ctx).pop(), child: const Text('关闭')),
        ],
      ),
    );
  }

  /// Focus.onKeyEvent 回调：把按键交给纯逻辑层判定，再执行对应动作。
  /// 只处理 KeyDownEvent（repeat 归到 KeyRepeatEvent，不会长按连发）；
  /// 整段 try/catch —— 快捷键永远不能把对局带崩（与网页版一致）。
  KeyEventResult _onKeyEvent(FocusNode node, KeyEvent event) {
    if (event is! KeyDownEvent) return KeyEventResult.ignored;
    try {
      final key = _keyName(event.logicalKey);
      if (key == null) return KeyEventResult.ignored;
      final ctx = _hotkeyContext();
      final hit = hotkeyFor(
        key: key,
        shift: HardwareKeyboard.instance.isShiftPressed,
        typing: _isTyping(),
        canAct: ctx.canAct,
        targetCount: ctx.targetCount,
        hasPass: ctx.hasPass,
      );
      if (hit == null) return KeyEventResult.ignored;

      switch (hit.action) {
        case HotkeyAction.target:
          final seats = _aliveOthers(_store.game ?? const {});
          if (hit.index >= 0 && hit.index < seats.length) _act({'target': seats[hit.index]});
          break;
        case HotkeyAction.pass:
          _doPass();
          break;
        case HotkeyAction.focusChat:
          _chatFocus.requestFocus();
          break;
        case HotkeyAction.mic:
          unawaited(_toggleMic());
          break;
        case HotkeyAction.help:
          unawaited(_showHotkeyHelp());
          break;
      }
      return KeyEventResult.handled;
    } catch (_) {
      // 快捷键永远不能把对局带崩。
      return KeyEventResult.ignored;
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    final g = store.game ?? const {};
    final phase = (g['phase'] ?? '') as String;
    final seats = (g['seats'] as List?) ?? const [];
    final feed = (g['feed'] as List?) ?? const [];
    final mySeat = g['mySeat'];
    _maybeScheduleReveal(g);

    // 桌面快捷键入口：Focus 铺满整页且自动取焦，任何位置按键都能命中；
    // 在输入框内打字时由 _isTyping 探测后整体放行（详见 lib/hotkeys.dart）。
    return Focus(
      autofocus: true,
      onKeyEvent: _onKeyEvent,
      child: Scaffold(
        appBar: AppBar(
          title: Text('${_phaseLabel(phase)} · 第 ${g['day'] ?? 1} 天'),
          actions: [
            IconButton(
              tooltip: store.sfx.enabled ? '关闭音效' : '开启音效',
              icon: Icon(store.sfx.enabled ? Icons.volume_up : Icons.volume_off),
              onPressed: () => store.toggleSfx(),
            ),
            if (store.isHost && phase != 'GAME_OVER')
              IconButton(
                tooltip: '强制结束',
                icon: const Icon(Icons.stop_circle_outlined, color: kBlood),
                onPressed: () async {
                  try {
                    await store.endGame();
                  } on AppError catch (e) {
                    _toast(e.message);
                  }
                },
              ),
            IconButton(
              tooltip: '离开房间',
              icon: const Icon(Icons.logout),
              onPressed: () async {
                try {
                  await store.leaveRoom();
                } on AppError catch (e) {
                  if (context.mounted) _toast(e.message);
                }
              },
            ),
          ],
        ),
        body: Column(
          children: [
            // 座位
            SizedBox(
              height: 150,
              child: ListView(
                scrollDirection: Axis.horizontal,
                padding: const EdgeInsets.all(10),
                children: [
                  for (final s in seats) _seatChip(s as Map, mySeat),
                ],
              ),
            ),
            // 语音实时字幕
            if (store.voiceCaptions.isNotEmpty) _captionStrip(store),
            // 记录：对局事件流 + WS 实时文字（聊天回声与旁白都只走 game.chat / narrator）
            Expanded(
              child: ListView(
                padding: const EdgeInsets.symmetric(horizontal: 12),
                children: [
                  for (final c in store.gameChat.reversed)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Text(
                        c.kind == 'narrator' ? '📢 ${c.text}' : '💬 ${c.name}：${c.text}',
                        style: TextStyle(
                          fontSize: 13,
                          height: 1.4,
                          color: c.kind == 'narrator' ? kDim : kText,
                          fontStyle: c.kind == 'narrator' ? FontStyle.italic : FontStyle.normal,
                        ),
                      ),
                    ),
                  for (final e in feed.reversed)
                    Padding(
                      padding: const EdgeInsets.symmetric(vertical: 3),
                      child: Text(_feedLine(e as Map, seats), style: const TextStyle(fontSize: 13, height: 1.4)),
                    ),
                ],
              ),
            ),
            // 行动区
            _actionPanel(store, g, seats, mySeat),
            // 聊天
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
                child: Row(
                  children: [
                    Expanded(
                      child: TextField(
                        controller: _chat,
                        focusNode: _chatFocus,
                        decoration: const InputDecoration(hintText: '说点什么…', isDense: true),
                        onSubmitted: (t) {
                          if (t.trim().isEmpty) return;
                          store.sendChat(t.trim());
                          _chat.clear();
                        },
                      ),
                    ),
                    const SizedBox(width: 8),
                    IconButton.filled(
                      onPressed: () {
                        final t = _chat.text.trim();
                        if (t.isEmpty) return;
                        store.sendChat(t);
                        _chat.clear();
                      },
                      icon: const Icon(Icons.send, color: Color(0xFF1A1206)),
                      style: IconButton.styleFrom(backgroundColor: kAccent),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _seatChip(Map s, dynamic mySeat) {
    final alive = s['alive'] != false;
    final isMe = s['seat'] == mySeat;
    return Container(
      width: 96,
      margin: const EdgeInsets.only(right: 8),
      decoration: BoxDecoration(
        color: kPanel2,
        borderRadius: BorderRadius.circular(12),
        border: Border.all(color: isMe ? kAccent : (alive ? kBorder : kBorder.withOpacity(.4))),
      ),
      child: Padding(
        padding: const EdgeInsets.all(8),
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text('#${s['seat']}', style: const TextStyle(color: kDim, fontSize: 11)),
            Text('${s['nickname'] ?? ''}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: alive ? kMoon : kDim, fontSize: 12, fontWeight: FontWeight.bold)),
            if (s['role'] != null) Text('${s['role']}', style: const TextStyle(color: kAccent, fontSize: 11)),
            if (!alive) const Text('出局', style: TextStyle(color: kBlood, fontSize: 11)),
          ],
        ),
      ),
    );
  }

  Widget _actionPanel(AppStore store, Map g, List seats, dynamic mySeat) {
    final phase = g['phase'];
    if (phase == 'GAME_OVER') {
      final gameId = (g['gameId'] as num?)?.toInt();
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        color: kPanel,
        child: Column(
          children: [
            // 胜负横幅先落、身份逐个亮牌。用 TweenAnimationBuilder：它只在 tween.end
            // 变化时才重播，所以对局中的每次 WS 推送不会把动画反复放一遍。
            SettleReveal(
              winner: '${g['winner'] ?? ''}',
              seats: seats
                  .cast<Map>()
                  .map((s) => {'seat': s['seat'], 'role': s['role'], 'alive': s['alive'] != false})
                  .toList(),
            ),
            if (gameId != null) _kudosPanel(store, gameId, seats, mySeat),
            const SizedBox(height: 10),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                FilledButton.icon(
                  onPressed: () => store.returnToRoom(),
                  icon: const Icon(Icons.meeting_room_outlined, size: 18),
                  label: const Text('返回房间'),
                ),
                const SizedBox(width: 12),
                OutlinedButton.icon(
                  onPressed: () async {
                    try {
                      await store.leaveRoom();
                    } on AppError catch (e) {
                      if (context.mounted) _toast(e.message);
                    }
                  },
                  icon: const Icon(Icons.logout, size: 18),
                  label: const Text('退出房间'),
                ),
              ],
            ),
          ],
        ),
      );
    }
    if (g['myTurn'] != true) {
      final actor = g['currentActor'];
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.all(12),
        color: kPanel,
        child: Text(
          actor != null && actor != 0 ? '⏳ 等待 $actor 号行动…' : '🌙 夜晚进行中…',
          textAlign: TextAlign.center,
          style: const TextStyle(color: kDim),
        ),
      );
    }
    final kind = (g['actionKind'] ?? '') as String;
    final aliveOthers = seats
        .where((s) => s['alive'] == true && s['seat'] != mySeat)
        .map((s) => (s['seat'] as num).toInt())
        .toList();

    Widget targets(bool allowPass) => Wrap(
          spacing: 8,
          runSpacing: 8,
          children: [
            for (final t in aliveOthers)
              ActionChip(label: Text('$t 号'), onPressed: () => _act({'target': t})),
            if (allowPass)
              ActionChip(
                  label: const Text('放弃/弃票'),
                  backgroundColor: kPanel,
                  onPressed: () => _act({'target': 0})),
          ],
        );

    return Container(
      width: double.infinity,
      padding: const EdgeInsets.all(12),
      color: kPanel,
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(_actionTitle(kind), style: const TextStyle(color: kAccent, fontWeight: FontWeight.bold)),
          const SizedBox(height: 10),
          if (kind == 'SPEECH' || kind == 'PK_SPEECH' || kind == 'LAST_WORDS')
            _speechPanel(kind)
          else if (kind == 'SHERIFF_SIGNUP')
            Row(children: [
              FilledButton(onPressed: () => _act({'signup': true}), child: const Text('竞选上警')),
              const SizedBox(width: 10),
              OutlinedButton(onPressed: () => _act({'signup': false}), child: const Text('不参与')),
            ])
          else if (kind == 'WITCH')
            _witchPanel(g, aliveOthers)
          else
            targets(const ['CROW', 'SILENCER', 'WOLF_KILL', 'SHOOT', 'VOTE', 'PK_VOTE', 'SHERIFF_VOTE'].contains(kind)),
        ],
      ),
    );
  }

  final _speech = TextEditingController();
  Widget _speechPanel(String kind) {
    final voiceOn = _store.voice?.enabled == true;
    return Row(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Expanded(
          child: TextField(
            controller: _speech,
            minLines: 1,
            maxLines: 3,
            decoration: InputDecoration(
              hintText: voiceOn ? '说话（🎙）或直接打字…（留空则过）' : '输入你的发言…（留空则过）',
              isDense: true,
            ),
          ),
        ),
        const SizedBox(width: 8),
        Column(children: [
          if (voiceOn) ...[
            _micButton(),
            const SizedBox(height: 6),
          ],
          FilledButton(onPressed: () {
            if (_micOn) _store.stopMic();
            _act({'text': _speech.text.trim().isEmpty ? '（过）' : _speech.text.trim()});
            _speech.clear();
            if (mounted) setState(() => _micOn = false);
          }, child: const Text('发言')),
          const SizedBox(height: 6),
          OutlinedButton(onPressed: () {
            if (_micOn) _store.stopMic();
            _act({'text': '（过）'});
            if (mounted) setState(() => _micOn = false);
          }, child: const Text('过')),
        ]),
      ],
    );
  }

  Widget _micButton() {
    return IconButton.filled(
      onPressed: _toggleMic,
      tooltip: _micOn ? '停止说话' : '说话',
      icon: Icon(_micOn ? Icons.mic : Icons.mic_none),
      color: const Color(0xFF1A1206),
      style: IconButton.styleFrom(backgroundColor: _micOn ? kBlood : kAccent),
    );
  }

  Widget _captionStrip(AppStore store) {
    final caps = store.voiceCaptions;
    final show = caps.length > 3 ? caps.sublist(caps.length - 3) : caps;
    return Container(
      width: double.infinity,
      margin: const EdgeInsets.fromLTRB(10, 2, 10, 4),
      padding: const EdgeInsets.symmetric(horizontal: 10, vertical: 6),
      decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10), border: Border.all(color: kBorder)),
      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
        for (final c in show)
          Padding(
            padding: const EdgeInsets.symmetric(vertical: 1),
            child: Text('${c.name}：${c.text}',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(fontSize: 12.5, color: c.kind == 'narrator' ? kDim : (c.mine ? kGreen : kText))),
          ),
      ]),
    );
  }

  // ---------- 结算互赞 kudos ----------
  // POST /api/game/kudos {gameId,toUserId,type,note}；type ∈ PRAISE|COMFORT|REPORT。
  // 规则（服务端）：仅限同局参与者、不能对自己；PRAISE/COMFORT 每目标每类型只能一次，REPORT 最多 3 次。
  // GET /api/game/kudos/{gameId} 返回 {gameId, byUser:{"<uid>":{"PRAISE":n,...}}}（无鉴权）。
  int? _kudosLoadedFor;
  Map<String, dynamic> _kudosByUser = const {};

  Future<void> _loadKudos(AppStore store, int gameId) async {
    try {
      final v = await store.api.get('/api/game/kudos/$gameId');
      final by = v['byUser'];
      if (mounted && by is Map) setState(() => _kudosByUser = by.cast<String, dynamic>());
    } on AppError {
      // 汇总拉不到不影响结算页本身
    }
  }

  Future<void> _kudos(AppStore store, int gameId, int toUserId, String type, String label) async {
    var note = '';
    if (type == 'REPORT') {
      final c = TextEditingController();
      final ok = await showDialog<bool>(
        context: context,
        builder: (_) => AlertDialog(
          backgroundColor: kPanel,
          title: const Text('举报这局的表现'),
          content: TextField(controller: c, maxLength: 200, decoration: const InputDecoration(hintText: '原因（可留空）')),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('提交')),
          ],
        ),
      );
      if (ok != true) return;
      note = c.text.trim();
    }
    try {
      await store.api.post('/api/game/kudos', {'gameId': gameId, 'toUserId': toUserId, 'type': type, 'note': note.isEmpty ? null : note});
      if (mounted) _toast('$label 已送出');
      await _loadKudos(store, gameId);
    } on AppError catch (e) {
      if (mounted) _toast(e.message);
    }
  }

  int _kudosCount(int userId, String type) {
    final e = _kudosByUser['$userId'];
    if (e is Map) return (e[type] as num?)?.toInt() ?? 0;
    return 0;
  }

  Widget _kudosPanel(AppStore store, int gameId, List seats, dynamic mySeat) {
    // 首次渲染这一局的结算面板时拉一次汇总；用 postFrame 回调避免在 build 里 setState。
    if (_kudosLoadedFor != gameId) {
      _kudosLoadedFor = gameId;
      WidgetsBinding.instance.addPostFrameCallback((_) => _loadKudos(store, gameId));
    }
    final others = seats
        .cast<Map>()
        .where((s) => s['userId'] is num && (s['userId'] as num).toInt() > 0 && s['seat'] != mySeat)
        .toList();
    if (others.isEmpty) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 10),
      child: Column(
        children: [
          const Text('给这局的表现点个赞或安慰一下', style: TextStyle(color: kDim, fontSize: 12)),
          const SizedBox(height: 6),
          ConstrainedBox(
            constraints: const BoxConstraints(maxHeight: 150),
            child: ListView(
              shrinkWrap: true,
              children: [
                for (final s in others)
                  ListTile(
                    dense: true,
                    visualDensity: VisualDensity.compact,
                    title: Text('${s['seat']}号 · ${s['nickname'] ?? ''}',
                        maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: kText, fontSize: 13)),
                    subtitle: Text('👍${_kudosCount((s['userId'] as num).toInt(), 'PRAISE')} · 🫂${_kudosCount((s['userId'] as num).toInt(), 'COMFORT')}',
                        style: const TextStyle(color: kDim, fontSize: 11)),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        IconButton(
                          tooltip: '点赞',
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(Icons.thumb_up_outlined, size: 18, color: kGreen),
                          onPressed: () => _kudos(store, gameId, (s['userId'] as num).toInt(), 'PRAISE', '点赞'),
                        ),
                        IconButton(
                          tooltip: '安慰',
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(Icons.volunteer_activism_outlined, size: 18, color: kMoon),
                          onPressed: () => _kudos(store, gameId, (s['userId'] as num).toInt(), 'COMFORT', '安慰'),
                        ),
                        IconButton(
                          tooltip: '举报',
                          visualDensity: VisualDensity.compact,
                          icon: const Icon(Icons.flag_outlined, size: 18, color: kBlood),
                          onPressed: () => _kudos(store, gameId, (s['userId'] as num).toInt(), 'REPORT', '举报'),
                        ),
                      ],
                    ),
                  ),
              ],
            ),
          ),
        ],
      ),
    );
  }

  Widget _witchPanel(Map g, List<int> aliveOthers) {
    final info = (g['myInfo'] as Map?) ?? const {};
    final killed = info['killedTonight'];
    final canSave = info['witchSaveAvailable'] == true && killed != null;
    final canPoison = info['witchPoisonAvailable'] == true;
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        if (canSave)
          ActionChip(label: Text('🧡 救 $killed 号'), onPressed: () => _act({'saveTarget': killed})),
        if (canPoison)
          for (final t in aliveOthers)
            ActionChip(label: Text('☠ $t'), backgroundColor: kPanel2, onPressed: () => _act({'poisonTarget': t})),
        ActionChip(label: const Text('跳过'), backgroundColor: kPanel, onPressed: () => _act({'target': 0})),
      ],
    );
  }

  String _actionTitle(String kind) => const {
        'GUARD': '🛡 选择今晚守护的人',
        'SEER_CHECK': '🔮 选择查验对象',
        'CROW': '🐦⬛ 选择诽谤对象',
        'SILENCER': '🤫 选择禁言对象',
        'WOLF_KILL': '🐺 选择刀杀目标',
        'SHOOT': '🔫 选择开枪带走的人',
        'VOTE': '🗳️ 选择放逐对象',
        'PK_VOTE': '⚔️ PK 投票',
        'SHERIFF_VOTE': '👑 警长投票',
        'WITCH': '🧪 女巫行动',
        'SHERIFF_SIGNUP': '👑 是否竞选警长？',
      }[kind] ??
          '选择目标';

  String _phaseLabel(String p) => const {
        'NIGHT_GUARD': '🌙 守卫行动',
        'NIGHT_WOLF': '🌙 狼人行动',
        'NIGHT_WITCH': '🌙 女巫行动',
        'NIGHT_SEER': '🌙 预言家验人',
        'NIGHT_CROW': '🌙 乌鸦行动',
        'NIGHT_SILENCER': '🌙 禁言长老',
        'DAWN': '🌅 天亮了',
        'SHERIFF_ELECTION': '👑 警长竞选',
        'LAST_WORDS': '💬 遗言',
        'DAY_SPEAK': '☀️ 白天发言',
        'DAY_VOTE': '🗳️ 放逐投票',
        'DAY_VOTE_TIEBREAK': '⚔️ 平票 PK',
        'SHOOT': '🔫 开枪',
        'GAME_OVER': '🏁 游戏结束',
      }[p] ??
          p;

  String _feedLine(Map e, List seats) {
    final t = e['type'];
    String seatName(dynamic seat) {
      final s = seats.cast<Map>().firstWhere((x) => x['seat'] == seat, orElse: () => const {});
      return s.isEmpty ? '$seat 号' : '${s['nickname']}（$seat）';
    }

    final who = e['actorSeat'] != null ? seatName(e['actorSeat']) : '';
    switch (t) {
      case 'SPEECH':
        return '💬 $who：${e['detail'] ?? ''}';
      case 'LAST_WORDS':
        return '🕯 $who 遗言：${e['detail'] ?? ''}';
      case 'VOTE_DETAIL':
        return '🗳 ${e['detail'] ?? ''}';
      case 'PLAYER_DIED':
        return '💀 $who 出局';
      case 'NARRATOR':
        return '📣 ${e['detail'] ?? ''}';
      default:
        return '${e['detail'] ?? t ?? ''}';
    }
  }
}
