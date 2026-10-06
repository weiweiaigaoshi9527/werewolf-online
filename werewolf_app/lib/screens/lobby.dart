import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'forum.dart';
import 'ranking.dart';

class LobbyScreen extends StatelessWidget {
  const LobbyScreen({super.key});

  Future<void> _join(BuildContext context) async {
    final controller = TextEditingController();
    final store = context.read<AppStore>();
    final no = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: kPanel,
        title: const Text('加入房间'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          maxLength: 6,
          decoration: const InputDecoration(hintText: '输入 6 位房间号'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, controller.text.trim()), child: const Text('加入')),
        ],
      ),
    );
    if (no != null && no.isNotEmpty) {
      try {
        await store.joinRoom(no);
      } on AppError catch (e) {
        if (context.mounted) _toast(context, e.message);
      }
    }
  }

  void _toast(BuildContext context, String msg) =>
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(msg)));

  Future<void> _spectate(BuildContext context) async {
    final controller = TextEditingController();
    final store = context.read<AppStore>();
    final no = await showDialog<String>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: kPanel,
        title: const Text('观战加入'),
        content: TextField(
          controller: controller,
          keyboardType: TextInputType.number,
          maxLength: 6,
          decoration: const InputDecoration(hintText: '输入要观战的 6 位房号'),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, controller.text.trim()), child: const Text('观战')),
        ],
      ),
    );
    if (no != null && no.isNotEmpty) {
      try {
        await store.spectate(no);
      } on AppError catch (e) {
        if (context.mounted) _toast(context, e.message);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    final u = store.user ?? const {};
    return Scaffold(
      appBar: AppBar(
        title: const Text('村庄大厅'),
        actions: [
          IconButton(
            tooltip: store.sfx.enabled ? '关闭音效' : '开启音效',
            icon: Icon(store.sfx.enabled ? Icons.volume_up : Icons.volume_off),
            onPressed: () => store.toggleSfx(),
          ),
          IconButton(tooltip: '服务器设置', icon: const Icon(Icons.dns), onPressed: () => store.clearServer()),
          IconButton(tooltip: '退出登录', icon: const Icon(Icons.logout), onPressed: () => store.logout()),
        ],
      ),
      body: ListView(
        padding: const EdgeInsets.all(16),
        children: [
          Card(
            child: ListTile(
              leading: const CircleAvatar(backgroundColor: kAccent, child: Text('🐺')),
              title: Text('${u['nickname'] ?? ''}', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
              subtitle: Text('@${u['username'] ?? ''} · Lv.${u['level'] ?? 1} · 💰${u['gold'] ?? 0}',
                  style: const TextStyle(color: kDim)),
              trailing: CircleAvatar(
                radius: 8,
                backgroundColor: store.wsConnected ? kGreen : kBlood,
              ),
            ),
          ),
          const SizedBox(height: 16),
          const Text('开始一局', style: TextStyle(color: kDim, letterSpacing: 2)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              _tile(context, '🏠', '创建房间', '成为房主，邀请好友', () async {
                try {
                  await store.createRoom();
                } on AppError catch (e) {
                  if (context.mounted) _toast(context, e.message);
                }
              }),
              _tile(context, '🚪', '房间号加入', '输入 6 位房号', () => _join(context)),
              _tile(context, '👁️', '观战加入', '进入进行中的对局旁观', () => _spectate(context)),
            ],
          ),
          const SizedBox(height: 20),
          const Text('社区', style: TextStyle(color: kDim, letterSpacing: 2)),
          const SizedBox(height: 10),
          Wrap(
            spacing: 12,
            runSpacing: 12,
            children: [
              if (store.feat('forum'))
                _tile(context, '💬', '论坛', '攻略与吐槽',
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ForumScreen()))),
              if (store.feat('ranking'))
                _tile(context, '🏆', '排行榜', '胜率 · 财富 · 等级',
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const RankingScreen()))),
              if (store.feat('friends'))
                _tile(context, '🎁', '兑换码', '输入礼包码领奖励', () => _redeem(context)),
            ],
          ),
          const SizedBox(height: 20),
          const Text('提示', style: TextStyle(color: kDim, letterSpacing: 2)),
          const SizedBox(height: 6),
          const Text('创建房间后可在房间内“添加 AI”补位并开局。所有设备连同一服务器即可联机。',
              style: TextStyle(color: kDim, height: 1.6, fontSize: 13)),
        ],
      ),
    );
  }

  /// 兑换码：成功返回的是**兑换后余额**（服务端语义），所以提示里直接说余额。
  Future<void> _redeem(BuildContext context) async {
    final c = TextEditingController();
    final store = context.read<AppStore>();
    final code = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: kPanel,
        title: const Text('兑换码'),
        content: TextField(controller: c, maxLength: 32, decoration: const InputDecoration(hintText: '输入礼包码')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, c.text.trim()), child: const Text('兑换')),
        ],
      ),
    );
    if (code == null || code.isEmpty) return;
    try {
      final gold = await store.redeem(code);
      if (context.mounted) _toast(context, '兑换成功，当前金币 $gold');
    } on AppError catch (e) {
      if (context.mounted) _toast(context, e.message);
    }
  }

  Widget _tile(BuildContext context, String icon, String title, String sub, VoidCallback onTap) {
    return SizedBox(
      width: 150,
      child: Card(
        clipBehavior: Clip.antiAlias,
        child: InkWell(
          onTap: onTap,
          child: Padding(
            padding: const EdgeInsets.symmetric(vertical: 22, horizontal: 12),
            child: Column(
              children: [
                Text(icon, style: const TextStyle(fontSize: 34)),
                const SizedBox(height: 10),
                Text(title, style: const TextStyle(fontWeight: FontWeight.bold, color: kMoon)),
                const SizedBox(height: 4),
                Text(sub, textAlign: TextAlign.center, style: const TextStyle(color: kDim, fontSize: 12)),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
