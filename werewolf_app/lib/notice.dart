import 'dart:async';

import 'package:flutter/material.dart';

import 'models.dart';
import 'store.dart';
import 'theme.dart';

/// 全局通知浮层：挂在 MaterialApp.builder 的 Stack 上，因此在任何页面（含 push 出去的
/// 二级页）都能收到 WS 提示，不必每页自己接 ScaffoldMessenger。
class WwNoticeLayer extends StatefulWidget {
  const WwNoticeLayer({super.key, required this.store});

  final AppStore store;

  @override
  State<WwNoticeLayer> createState() => _WwNoticeLayerState();
}

class _WwNoticeLayerState extends State<WwNoticeLayer> {
  final Set<int> _seen = {};
  final List<Timer> _timers = [];

  @override
  void initState() {
    super.initState();
    _schedule();
  }

  @override
  void didUpdateWidget(WwNoticeLayer oldWidget) {
    super.didUpdateWidget(oldWidget);
    _schedule();
  }

  void _schedule() {
    for (final n in widget.store.notices) {
      if (!_seen.add(n.id)) continue;
      _timers.add(Timer(n.ttl, () {
        if (!mounted) return;
        widget.store.dismissNotice(n.id);
        setState(() {});
      }));
    }
  }

  @override
  void dispose() {
    for (final t in _timers) {
      t.cancel();
    }
    super.dispose();
  }

  IconData get _iconFor => Icons.forum_outlined;

  @override
  Widget build(BuildContext context) {
    final visible = widget.store.notices.where((n) => _seen.contains(n.id)).toList();
    if (visible.isEmpty) return const SizedBox.shrink();
    return Positioned(
      top: 10,
      left: 0,
      right: 0,
      child: SafeArea(
        child: Center(
          child: ConstrainedBox(
            constraints: const BoxConstraints(maxWidth: 520),
            child: Column(
              children: [for (final n in visible) _card(n)],
            ),
          ),
        ),
      ),
    );
  }

  Widget _card(WwNotice n) {
    final warn = n.kind == 'warn';
    final invite = n.kind == 'invite';
    return Padding(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 4),
      child: Material(
        color: (invite ? kMoon : warn ? kBlood : const Color(0xEE16202C)).withOpacity(.94),
        borderRadius: BorderRadius.circular(12),
        child: InkWell(
          borderRadius: BorderRadius.circular(12),
          onTap: () {
            widget.store.dismissNotice(n.id);
            setState(() {});
          },
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 9, 8, 9),
            child: Row(
              children: [
                Icon(invite ? Icons.group_add_outlined : (warn ? Icons.warning_amber : _iconFor),
                    size: 17, color: const Color(0xFF1A1206)),
                const SizedBox(width: 9),
                Expanded(
                  child: Text(n.text,
                      style: const TextStyle(color: Color(0xFF1A1206), fontSize: 13.5, height: 1.35)),
                ),
                if (invite && n.roomNo != null)
                  TextButton(
                    onPressed: () {
                      widget.store.dismissNotice(n.id);
                      widget.store.joinRoom(n.roomNo!);
                    },
                    child: const Text('加入', style: TextStyle(fontWeight: FontWeight.bold)),
                  )
                else
                  const Icon(Icons.close, size: 15, color: Color(0x991A1206)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
