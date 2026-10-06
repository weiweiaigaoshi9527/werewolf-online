import 'dart:math' as math;
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';
import 'admin.dart';
import 'edit_profile.dart';
import 'forum.dart';
import 'inventory.dart';
import 'ranking.dart';
import 'updates.dart';
import 'tickets.dart';
import 'replay.dart';

/// 我的：资料 / 签到 / 战绩 / 流水 / 昵称 / 语音性别，并进入工单与复盘。
class ProfileScreen extends StatefulWidget {
  const ProfileScreen({super.key});
  @override
  State<ProfileScreen> createState() => _ProfileScreenState();
}

class _ProfileScreenState extends State<ProfileScreen> {
  Map<String, dynamic> _p = const {};
  Map<String, dynamic> _stats = const {};
  bool _loading = true;
  bool _checkedIn = false;
  int _streak = 0;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final s = context.read<AppStore>();
    try {
      final f = await Future.wait([
        s.api.get('/api/user/profile'),
        s.api.get('/api/user/stats'),
        s.api.get('/api/user/checkin-status'),
      ]);
      _p = f[0];
      _stats = f[1];
      _checkedIn = f[2]['doneToday'] == true;
      _streak = (f[2]['streak'] as num?)?.toInt() ?? 0;
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _checkin() async {
    final s = context.read<AppStore>();
    try {
      final r = await s.api.post('/api/user/checkin');
      if (!mounted) return;
      snack(context, '签到成功 +${r['gold']}金 +${r['exp']}经验');
      await s.refreshUser();
      _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  Future<void> _editNickname() async {
    final c = TextEditingController(text: '${_p['nickname'] ?? ''}');
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: kPanel,
        title: const Text('修改昵称'),
        content: TextField(controller: c, maxLength: 12, decoration: const InputDecoration(hintText: '新昵称')),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('保存')),
        ],
      ),
    );
    if (ok != true) return;
    // 对话框 await 之后原 State 可能已销毁，用 context 前必须先判 mounted
    if (!mounted) return;
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/user/nickname', {'nickname': c.text.trim()});
      await s.refreshUser();
      if (!mounted) return;
      _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final level = (_p['level'] as num?) ?? 1;
    final exp = (_p['exp'] as num?) ?? 0;
    final need = (100 * math.pow(level.toDouble(), 1.5)).floor();
    return Scaffold(
      appBar: AppBar(
        title: const Text('我的'),
        actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))],
      ),
      body: StateBox(
        loading: _loading,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(children: [
                  WwAvatar(avatarId: _p['avatarId'], avatarUrl: _p['avatarUrl'], frameColor: _p['frameColor'], size: 58),
                  const SizedBox(width: 14),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Row(children: [
                        Flexible(child: Text('${_p['nickname']}', style: const TextStyle(color: kMoon, fontSize: 17, fontWeight: FontWeight.bold), overflow: TextOverflow.ellipsis)),
                        if ((_p['vipTitle'] ?? _p['title']) != null)
                          Padding(padding: const EdgeInsets.only(left: 6), child: Text('${_p['vipTitle'] ?? _p['title']}', style: const TextStyle(color: kAccent, fontSize: 11))),
                      ]),
                      const SizedBox(height: 2),
                      Text('@${_p['username']} · Lv.$level', style: const TextStyle(color: kDim, fontSize: 12.5)),
                      const SizedBox(height: 8),
                      ClipRRect(
                        borderRadius: BorderRadius.circular(6),
                        child: LinearProgressIndicator(value: need == 0 ? 1 : (exp / need).clamp(0, 1), minHeight: 6, backgroundColor: kBg, valueColor: const AlwaysStoppedAnimation(kAccent)),
                      ),
                      const SizedBox(height: 4),
                      Text('💰 ${_p['gold'] ?? 0} 金币 · 经验 $exp/$need', style: const TextStyle(color: kDim, fontSize: 12)),
                    ]),
                  ),
                ]),
              ),
            ),
            const SizedBox(height: 10),
            Row(children: [
              Expanded(
                child: FilledButton.icon(
                  onPressed: _checkedIn ? null : _checkin,
                  icon: const Icon(Icons.event_available),
                  label: Text(_checkedIn ? '今日已签到' : '每日签到'),
                  style: FilledButton.styleFrom(backgroundColor: _checkedIn ? kPanel2 : kAccent),
                ),
              ),
              const SizedBox(width: 10),
              Expanded(child: OutlinedButton.icon(onPressed: _editNickname, icon: const Icon(Icons.edit, size: 18), label: const Text('改昵称'))),
            ]),
            if (_streak > 0) Padding(padding: const EdgeInsets.only(top: 6, left: 4), child: Text('已连续签到 $_streak 天', style: const TextStyle(color: kDim, fontSize: 12))),
            const SectionTitle('战绩'),
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(children: [
                  _statRow('总场次', '${_stats['total'] ?? 0}', '胜率', '${_stats['winRate'] ?? 0}%'),
                  _statRow('好人胜率', '${_stats['goodWinRate'] ?? 0}%', '狼人胜率', '${_stats['wolfWinRate'] ?? 0}%'),
                ]),
              ),
            ),
            const SectionTitle('快捷入口'),
            Wrap(spacing: 10, runSpacing: 10, children: [
              _quick(context, '编辑资料', Icons.badge_outlined,
                  () => Navigator.push(context, MaterialPageRoute(builder: (_) => const EditProfileScreen()))),
              if (context.read<AppStore>().feat('shop'))
                _quick(context, '我的背包', Icons.inventory_2_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const InventoryScreen()))),
              if (context.read<AppStore>().feat('forum'))
                _quick(context, '论坛', Icons.forum_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ForumScreen()))),
              if (context.read<AppStore>().feat('ranking'))
                _quick(context, '排行榜', Icons.leaderboard_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const RankingScreen()))),
              _quick(context, '工单', Icons.support_agent, () => Navigator.push(context, MaterialPageRoute(builder: (_) => const TicketsScreen()))),
              _quick(context, '复盘', Icons.play_circle_outline, () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ReplayListScreen()))),
              _quick(context, '流水', Icons.account_balance_wallet_outlined, () => _transactions()),
              if (context.read<AppStore>().feat('news'))
                _quick(context, '更新动态', Icons.new_releases_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const NewsScreen()))),
              if (context.read<AppStore>().feat('download'))
                _quick(context, '下载中心', Icons.download_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const DownloadsScreen()))),
              if (context.read<AppStore>().user?['admin'] == true)
                _quick(context, '后台管理', Icons.admin_panel_settings_outlined,
                    () => Navigator.push(context, MaterialPageRoute(builder: (_) => const AdminScreen()))),
            ]),
            const SectionTitle('最近对局'),
            if (((_stats['recent'] as List?) ?? const []).isEmpty)
              const Text('还没有对局记录', style: TextStyle(color: kDim, fontSize: 13))
            else
              for (final r in ((_stats['recent'] as List?) ?? const []))
                Card(
                  margin: const EdgeInsets.symmetric(vertical: 4),
                  child: ListTile(
                    dense: true,
                    title: Text('${r['role']} · ${r['faction'] == 'WOLF' ? '狼人' : '好人'}', style: const TextStyle(color: kMoon, fontSize: 14)),
                    subtitle: Text('${r['won'] == true ? '胜利' : '失败'} · ${r['survived'] == true ? '存活' : '出局'}${r['mvp'] == true ? ' · MVP' : ''}', style: TextStyle(color: r['won'] == true ? kGreen : kDim, fontSize: 12)),
                    trailing: TextButton(onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ReplayScreen(gameId: (r['gameId'] as num).toInt()))), child: const Text('复盘')),
                  ),
                ),
          ],
        ),
      ),
    );
  }

  Widget _statRow(String l1, String v1, String l2, String v2) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(children: [
          Expanded(child: Column(children: [Text(v1, style: const TextStyle(color: kAccent, fontSize: 18, fontWeight: FontWeight.bold)), Text(l1, style: const TextStyle(color: kDim, fontSize: 12))])),
          Expanded(child: Column(children: [Text(v2, style: const TextStyle(color: kAccent, fontSize: 18, fontWeight: FontWeight.bold)), Text(l2, style: const TextStyle(color: kDim, fontSize: 12))])),
        ]),
      );

  Widget _quick(BuildContext context, String label, IconData icon, VoidCallback onTap) => ActionChip(
        avatar: Icon(icon, size: 18, color: kAccent),
        label: Text(label, style: const TextStyle(color: kText)),
        backgroundColor: kPanel2,
        onPressed: onTap,
      );

  Future<void> _transactions() async {
    final s = context.read<AppStore>();
    List<dynamic> list;
    try {
      list = await s.api.getList('/api/user/transactions');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
      return;
    }
    if (!mounted) return;
    showModalBottomSheet(
      context: context,
      backgroundColor: kPanel,
      isScrollControlled: true,
      builder: (_) => DraggableScrollableSheet(
        expand: false,
        initialChildSize: 0.7,
        builder: (c, ctrl) => ListView(
          controller: ctrl,
          padding: const EdgeInsets.all(14),
          children: [
            const Text('金币流水', style: TextStyle(color: kMoon, fontSize: 16, fontWeight: FontWeight.bold)),
            const SizedBox(height: 8),
            for (final t in list)
              ListTile(
                dense: true,
                title: Text('${t['reason']}', style: const TextStyle(color: kText, fontSize: 13)),
                subtitle: Text('${(t['time'] ?? '').toString().substring(0, 16).replaceFirst('T', ' ')} · 余额 ${t['balanceAfter']}', style: const TextStyle(color: kDim, fontSize: 11)),
                trailing: Text('${(t['delta'] as num) > 0 ? '+' : ''}${t['delta']}', style: TextStyle(color: (t['delta'] as num) >= 0 ? kGreen : kBlood, fontWeight: FontWeight.bold)),
              ),
          ],
        ),
      ),
    );
  }
}
