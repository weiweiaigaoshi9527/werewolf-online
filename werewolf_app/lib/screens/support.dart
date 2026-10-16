import 'dart:async';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import '../i18n.dart';

/// 在线客服：对话存在工单里，玩家发问后由「在线客服」（AI）自动答复；
/// 解决不了或玩家点「转人工」后由管理员接手（显示为「人工客服」）。
/// 服务端答复是异步生成的，因此面板打开期间每 3 秒轻量轮询一次；
/// 收到 WS 的 support.update 也会立即刷新（与网页版 SUP 面板同策略）。
class SupportScreen extends StatefulWidget {
  const SupportScreen({super.key});
  @override
  State<SupportScreen> createState() => _SupportScreenState();
}

class _SupportScreenState extends State<SupportScreen> {
  final _input = TextEditingController();
  late final AppStore _store;
  Timer? _timer;
  List<Map<String, dynamic>> _thread = const [];
  bool _human = false;
  bool _needsHuman = false;
  bool _loading = true;
  int _tickSeen = -1;

  @override
  void initState() {
    super.initState();
    _store = context.read<AppStore>();
    _tickSeen = _store.supportTick;
    _store.addListener(_onStoreChanged);
    _load();
    _timer = Timer.periodic(const Duration(seconds: 3), (_) => _load());
  }

  @override
  void dispose() {
    _timer?.cancel();
    _store.removeListener(_onStoreChanged);
    _input.dispose();
    super.dispose();
  }

  /// WS 的 support.update 只递增信号；面板据此立即刷新，而不是干等下一次轮询。
  void _onStoreChanged() {
    if (_store.supportTick != _tickSeen) {
      _tickSeen = _store.supportTick;
      unawaited(_load());
    }
  }

  Future<void> _load() async {
    try {
      final r = await _store.supportThread();
      if (!mounted) return;
      setState(() {
        _loading = false;
        _thread = r.thread;
        _human = r.human;
        _needsHuman = r.needsHuman;
      });
    } on AppError {
      // 轮询失败静默处理：不打扰正在打字的用户
      if (mounted) setState(() => _loading = false);
    }
  }

  void _toast(String m) => ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Tx(m)));

  Future<void> _send() async {
    final t = _input.text.trim();
    if (t.isEmpty) return;
    _input.clear();
    // 先本地回显，避免等一次接口往返才看到自己的话
    setState(() => _thread = [
          ..._thread,
          {'content': t, 'ai': false, 'admin': false, 'author': '我', 'at': ''},
        ]);
    try {
      await _store.supportSend(t);
      // 服务端 600ms 后才写入 AI 答复，稍等再拉一次
      unawaited(Future<void>.delayed(const Duration(milliseconds: 900), _load));
    } on AppError catch (e) {
      if (mounted) _toast(e.message);
    }
  }

  Future<void> _toHuman() async {
    try {
      await _store.supportToHuman();
      if (mounted) _toast('已为你转接人工客服');
      await _load();
    } on AppError catch (e) {
      if (mounted) _toast(e.message);
    }
  }

  @override
  Widget build(BuildContext context) {
    final badge = _human ? '人工客服' : (_needsHuman ? '正在转接人工…' : '在线');
    final badgeColor = (_human || _needsHuman) ? kAccent : kGreen;
    return Scaffold(
      appBar: AppBar(
        title: Row(
          children: [
            const Tx('💬 在线客服'),
            const SizedBox(width: 8),
            Container(
              padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 2),
              decoration: BoxDecoration(
                color: badgeColor.withOpacity(.18),
                borderRadius: BorderRadius.circular(999),
                border: Border.all(color: badgeColor),
              ),
              child: Tx(badge, style: TextStyle(color: badgeColor, fontSize: 11)),
            ),
          ],
        ),
      ),
      body: Column(
        children: [
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator())
                : _thread.isEmpty
                    ? Center(
                        child: Padding(
                          padding: const EdgeInsets.all(24),
                          child: Tx(
                            '你好，我是本站在线客服 👋\n把遇到的问题发给我，我会尽快帮你处理；\n需要真人协助也可以点下面的「转人工客服」。',
                            textAlign: TextAlign.center,
                            style: TextStyle(color: kDim, height: 1.7),
                          ),
                        ),
                      )
                    : ListView(
                        reverse: true,
                        padding: const EdgeInsets.all(14),
                        children: [
                          for (final m in _thread.reversed) _msgRow(m),
                        ],
                      ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(14, 6, 14, 10),
              child: Column(
                children: [
                  Row(
                    children: [
                      Expanded(
                        child: TextField(
                          controller: _input,
                          maxLength: 500,
                          decoration: InputDecoration(
                            hintText: tr('描述你遇到的问题…'),
                            isDense: true,
                            counterText: '',
                          ),
                          onSubmitted: (_) => unawaited(_send()),
                        ),
                      ),
                      const SizedBox(width: 8),
                      FilledButton(onPressed: () => unawaited(_send()), child: const Tx('发送')),
                    ],
                  ),
                  if (!_human && !_needsHuman)
                    Align(
                      alignment: Alignment.centerLeft,
                      child: TextButton.icon(
                        onPressed: () => unawaited(_toHuman()),
                        icon: const Icon(Icons.support_agent, size: 16),
                        label: const Tx('🙋 转人工客服'),
                      ),
                    ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _msgRow(Map<String, dynamic> m) {
    final ai = m['ai'] == true;
    final admin = m['admin'] == true;
    final me = !ai && !admin;
    final who = ai ? '在线客服' : (admin ? '人工客服' : '我');
    final at = '${m['at'] ?? ''}'.replaceFirst('T', ' ').trim();
    return Align(
      alignment: me ? Alignment.centerRight : Alignment.centerLeft,
      child: Container(
        margin: const EdgeInsets.symmetric(vertical: 4),
        padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 9),
        constraints: const BoxConstraints(maxWidth: 460),
        decoration: BoxDecoration(
          color: me ? kAccent.withOpacity(.14) : kPanel,
          borderRadius: BorderRadius.circular(12),
          border: Border.all(color: me ? kAccent : kBorder),
        ),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Tx(who,
                style: TextStyle(
                    fontSize: 11,
                    color: ai ? kDim : (admin ? kAccent : kGreen),
                    fontWeight: FontWeight.bold)),
            const SizedBox(height: 3),
            Tx('${m['content'] ?? ''}', style: TextStyle(color: kText, fontSize: 13.5, height: 1.45)),
            if (at.length >= 11)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Tx(at.substring(5, 16), style: TextStyle(color: kDim, fontSize: 10)),
              ),
          ],
        ),
      ),
    );
  }
}
