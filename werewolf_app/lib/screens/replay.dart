import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../replay_engine.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 复盘列表：从本人最近对局进入某一局的事件回放。
/// 没有独立列表端点，复用 GET /api/user/stats 的 recent（最多 20 条，最新在前）。
class ReplayListScreen extends StatefulWidget {
  const ReplayListScreen({super.key});
  @override
  State<ReplayListScreen> createState() => _ReplayListScreenState();
}

class _ReplayListScreenState extends State<ReplayListScreen> {
  List<dynamic> _recent = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final s = context.read<AppStore>();
    try {
      final st = await s.api.get('/api/user/stats');
      _recent = (st['recent'] as List?) ?? const [];
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('对局复盘')),
      body: StateBox(
        loading: _loading,
        empty: _recent.isEmpty,
        emptyText: '还没有对局记录',
        child: ListView(
          padding: const EdgeInsets.all(12),
          children: [
            for (final r in _recent)
              Card(
                margin: const EdgeInsets.symmetric(vertical: 4),
                child: ListTile(
                  title: Text('局 #${r['gameId']} · ${r['role']}', style: const TextStyle(color: kMoon)),
                  subtitle: Text(
                    '${r['faction'] == 'WOLF' ? '狼人' : '好人'} · ${r['won'] == true ? '胜利' : '失败'}'
                    ' · 存活到第 ${r['day'] ?? '?'} 天${r['survived'] == true ? '（活到最后）' : ''}'
                    '${r['mvp'] == true ? ' · 🏆MVP' : ''}',
                    style: const TextStyle(color: kDim, fontSize: 12),
                  ),
                  trailing: const Icon(Icons.play_circle_outline, color: kAccent),
                  onTap: () => Navigator.push(
                      context,
                      MaterialPageRoute(
                          builder: (_) => ReplayScreen(gameId: (r['gameId'] as num).toInt()))),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 逐帧回放播放器。
///
/// 与网页版一致的语义：**一帧 = 一个事件**；首屏直接把全部事件铺满（等价于停在末帧），
/// 播完后再点播放会从头重播；速度档位沿用 慢1200 / 中600 / 快250ms（另加一档极慢）。
/// 比网页版多做的：可后退一帧、可拖动进度条跳转、座位条按帧重建存活/警长/翻牌状态
/// （网页版播放过程中座位区是不变的）。
class ReplayScreen extends StatefulWidget {
  final int gameId;
  const ReplayScreen({super.key, required this.gameId});
  @override
  State<ReplayScreen> createState() => _ReplayScreenState();
}

class _ReplayScreenState extends State<ReplayScreen> {
  final _scroll = ScrollController();
  ReplayPlayer? _player;
  Map<int, String> _roles = const {};
  List<ReplayEvent> _events = const [];
  bool _loading = true;
  String? _error;
  Timer? _ticker;
  Map<String, dynamic> _meta = const {};

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _ticker?.cancel(); // 离开页面必须停表，否则后台还在推进
    _scroll.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    final s = context.read<AppStore>();
    try {
      _meta = await s.api.get('/api/game/replay/${widget.gameId}');
      _roles = parseSeatRoles(_meta['seatRoles']);
      _events = ((_meta['events'] as List?) ?? const [])
          .map((e) => ReplayEvent.fromJson(e as Map))
          .toList(growable: false);
      final seats = _roles.keys.toList()..sort();
      _player = ReplayPlayer(_events, seats: seats.isEmpty ? const [] : seats)
        ..seatRoles = _roles
        // 首屏=全量（与网页版 replayStepAll 一致），此时按播放等于从头重播
        ..jumpTo(_events.isEmpty ? -1 : _events.length - 1);
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  void _tick() {
    final p = _player;
    if (p == null) return;
    if (!p.step()) {
      _ticker?.cancel();
      _ticker = null;
    }
    setState(() {});
    _scrollToEnd();
  }

  void _play() {
    final p = _player;
    if (p == null || p.total == 0) return;
    if (p.playing) {
      _pause();
      return;
    }
    p.play();
    _ticker?.cancel();
    _ticker = Timer.periodic(Duration(milliseconds: p.intervalMs), (_) => _tick());
    setState(() {});
  }

  void _pause() {
    _ticker?.cancel();
    _ticker = null;
    _player?.pause();
    if (mounted) setState(() {});
  }

  void _step() {
    _pause();
    final p = _player;
    if (p == null) return;
    if (p.atEnd) return;
    p.step();
    setState(() {});
    _scrollToEnd();
  }

  void _back() {
    _pause();
    final p = _player;
    if (p == null || !p.back()) return;
    setState(() {});
    _scrollToEnd();
  }

  void _jump(double v) {
    final p = _player;
    if (p == null) return;
    _pause();
    p.jumpTo(v.round());
    setState(() {});
    _scrollToEnd();
  }

  void _scrollToEnd() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  void _setSpeed(String label) {
    final p = _player;
    if (p == null) return;
    p.speedLabel = label;
    if (p.playing) {
      _ticker?.cancel();
      _ticker = Timer.periodic(Duration(milliseconds: p.intervalMs), (_) => _tick());
    }
    setState(() {});
  }

  @override
  Widget build(BuildContext context) {
    final p = _player;
    final shown = p == null ? const <ReplayEvent>[] : _events.sublist(0, (p.index + 1).clamp(0, _events.length));
    final st = p?.state;
    return Scaffold(
      appBar: AppBar(
        title: Text('复盘 · 局 #${widget.gameId}'),
        actions: [
          if (_events.isNotEmpty)
            Padding(
              padding: const EdgeInsets.only(right: 6),
              child: DropdownButtonHideUnderline(
                child: DropdownButton<String>(
                  value: p?.speedLabel ?? '中',
                  dropdownColor: kPanel2,
                  style: const TextStyle(color: kText, fontSize: 13),
                  items: [for (final k in ReplayPlayer.speeds.keys) DropdownMenuItem(value: k, child: Text(k))],
                  onChanged: (v) => v == null ? null : _setSpeed(v),
                ),
              ),
            ),
        ],
      ),
      body: StateBox(
        loading: _loading,
        error: _error,
        child: Column(
          children: [
            _header(),
            if (st != null && _roles.isNotEmpty) _seatStrip(st),
            Expanded(
              child: _events.isEmpty
                  ? const Center(child: Text('这局没有可回放的事件', style: TextStyle(color: kDim)))
                  : ListView.builder(
                      controller: _scroll,
                      padding: const EdgeInsets.fromLTRB(14, 4, 14, 10),
                      itemCount: shown.length,
                      itemBuilder: (_, i) => _line(_events[i], i == shown.length - 1),
                    ),
            ),
            _controls(p),
          ],
        ),
      ),
    );
  }

  Widget _header() {
    final started = '${_meta['startedAt'] ?? ''}'.replaceFirst('T', ' ');
    return Padding(
      padding: const EdgeInsets.fromLTRB(14, 10, 14, 6),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('房间 ${_meta['roomNo'] ?? ''} · ${_meta['winner'] ?? '—'} 胜'
              '${started.isEmpty ? '' : ' · ${started.length >= 16 ? started.substring(0, 16) : started}'}',
              style: const TextStyle(color: kAccent, fontSize: 14.5, fontWeight: FontWeight.bold)),
          const SizedBox(height: 4),
          Text('身份：${_roles.entries.map((e) => '${e.key}:${e.value}').join('  ')}',
              style: const TextStyle(color: kDim, fontSize: 12, height: 1.5)),
        ],
      ),
    );
  }

  /// 按当前帧重建的座位条：存活实心、出局划掉并标死因、警长带 👑、白痴翻牌标 🃏。
  Widget _seatStrip(ReplayState st) {
    // 高度必须容得下三行文字（15 + 10.5 + 10 及其行高）加内边距，
    // 之前给 66 会溢出 8px，线上直接画成黄黑条纹。
    return SizedBox(
      height: 86,
      child: ListView(
        scrollDirection: Axis.horizontal,
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
        children: [
          for (final seat in st.seats)
            Container(
              width: 64,
              margin: const EdgeInsets.only(right: 8),
              padding: const EdgeInsets.symmetric(vertical: 5),
              decoration: BoxDecoration(
                color: st.isAlive(seat) ? kPanel : kPanel.withOpacity(.45),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                    color: st.sheriff == seat ? kAccent : (st.isAlive(seat) ? kBorder : kBlood.withOpacity(.6))),
              ),
              child: Column(
                children: [
                  Text('${st.sheriff == seat ? '👑' : ''}$seat',
                      style: TextStyle(
                          fontSize: 15,
                          fontWeight: FontWeight.bold,
                          color: st.isAlive(seat) ? kMoon : kDim,
                          decoration: st.isAlive(seat) ? null : TextDecoration.lineThrough)),
                  const SizedBox(height: 2),
                  Text(st.seatRoles[seat] ?? '',
                      maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 10.5, color: kDim)),
                  Text(
                    st.isAlive(seat)
                        ? (st.noVote.contains(seat) ? '🃏 无投票权' : (st.isWolf(seat) ? '狼' : '好人'))
                        : (kDeathZh[st.deaths[seat]] ?? st.deaths[seat] ?? '出局'),
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 10, color: st.isAlive(seat) ? kGreen : kBlood),
                  ),
                ],
              ),
            ),
        ],
      ),
    );
  }

  Widget _line(ReplayEvent e, bool current) {
    final l = replayLine(e, _roles);
    return Container(
      margin: const EdgeInsets.symmetric(vertical: 2),
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 5),
      decoration: BoxDecoration(
        color: current ? kAccent.withOpacity(.12) : Colors.transparent,
        borderRadius: BorderRadius.circular(8),
      ),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(
            width: 42,
            child: Text('第${e.day}天', style: const TextStyle(color: kDim, fontSize: 11)),
          ),
          Text(l.emoji, style: const TextStyle(fontSize: 13)),
          const SizedBox(width: 6),
          Expanded(child: Text(l.text, style: const TextStyle(fontSize: 13, height: 1.45, color: kText))),
        ],
      ),
    );
  }

  Widget _controls(ReplayPlayer? p) {
    final total = p?.total ?? 0;
    return SafeArea(
      top: false,
      child: Container(
        color: kPanel,
        padding: const EdgeInsets.fromLTRB(6, 0, 14, 6),
        child: Column(
          children: [
            Row(
              children: [
                Text('${(p?.index ?? -1) + 1} / $total',
                    style: const TextStyle(color: kDim, fontSize: 12), textAlign: TextAlign.center),
                Expanded(
                  child: Slider(
                    // clamp 传两个 int 会返回 num，而 Slider 的 value 只接受 double
                    value: ((p?.index ?? -1) + 1).toDouble().clamp(0.0, (total == 0 ? 1 : total).toDouble()),
                    max: (total == 0 ? 1 : total).toDouble(),
                    activeColor: kAccent,
                    inactiveColor: kBorder,
                    onChanged: total == 0 ? null : _jump,
                  ),
                ),
              ],
            ),
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                IconButton(
                  tooltip: '从头',
                  icon: const Icon(Icons.skip_previous),
                  onPressed: p == null || p.atStart ? null : () => _jump(0),
                ),
                IconButton(
                  tooltip: '后退一帧',
                  icon: const Icon(Icons.undo),
                  onPressed: p == null || p.atStart ? null : _back,
                ),
                FilledButton.icon(
                  onPressed: total == 0 ? null : _play,
                  icon: Icon(p?.playing == true ? Icons.pause : Icons.play_arrow),
                  label: Text(p?.playing == true ? '暂停' : (p != null && p.atEnd ? '重播' : '播放')),
                  style: FilledButton.styleFrom(backgroundColor: kAccent, foregroundColor: const Color(0xFF1A1206)),
                ),
                IconButton(
                  tooltip: '下一帧',
                  icon: const Icon(Icons.skip_next),
                  onPressed: p == null || p.atEnd ? null : _step,
                ),
                IconButton(
                  tooltip: '跳到结尾',
                  icon: const Icon(Icons.last_page),
                  onPressed: p == null || p.atEnd ? null : () => _jump((p.total - 1).toDouble()),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}
