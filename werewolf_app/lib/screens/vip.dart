import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 会员中心：查看当前 VIP 与折扣，用金币开通 / 续费。
class VipScreen extends StatefulWidget {
  const VipScreen({super.key});
  @override
  State<VipScreen> createState() => _VipScreenState();
}

class _VipScreenState extends State<VipScreen> {
  Map<String, dynamic> _info = const {};
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      _info = await context.read<AppStore>().api.get('/api/vip');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _buy(int level, String name) async {
    if (!await _confirm('用金币开通「$name」？')) return;
    // 确认框 await 之后原 State 可能已销毁，用 context 前必须先判 mounted
    if (!mounted) return;
    final store = context.read<AppStore>();
    try {
      final v = await store.api.post('/api/vip/purchase', {'level': level});
      if (!mounted) return;
      setState(() => _info = v);
      await store.refreshUser();
      if (mounted) snack(context, '已开通 ${v['name'] ?? name}');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  Future<bool> _confirm(String msg) async {
    final r = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        backgroundColor: kPanel,
        content: Text(msg, style: const TextStyle(color: kText)),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('确定')),
        ],
      ),
    );
    return r ?? false;
  }

  @override
  Widget build(BuildContext context) {
    final tiers = (_info['tiers'] as List?) ?? const [];
    return Scaffold(
      appBar: AppBar(title: const Text('会员中心')),
      body: StateBox(
        loading: _loading,
        error: _error,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Row(children: [
                      Text(_info['active'] == true ? '👑 ${_info['name']}' : '普通玩家',
                          style: const TextStyle(color: kAccent, fontSize: 18, fontWeight: FontWeight.bold)),
                      const Spacer(),
                      Text('💰 ${_info['gold'] ?? 0}', style: const TextStyle(color: kDim)),
                    ]),
                    const SizedBox(height: 8),
                    InfoRow('状态', _info['active'] == true ? '生效中' : '未开通', valueColor: _info['active'] == true ? kGreen : kDim),
                    if (_info['expireAt'] != null) InfoRow('到期', '${_info['expireAt']}'.toString().substring(0, 10)),
                    InfoRow('商店折扣', '${(((_info['discount'] ?? 1.0) as num) * 100).round()}%'),
                    InfoRow('签到加成', '+${_info['checkinBonusPct'] ?? 0}%'),
                    if (_info['title'] != null) InfoRow('专属称号', '${_info['title']}'),
                  ],
                ),
              ),
            ),
            const SectionTitle('可开通档位'),
            for (final t in tiers)
              Card(
                margin: const EdgeInsets.symmetric(vertical: 6),
                child: ListTile(
                  title: Text(t['name'] ?? '', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                  subtitle: Text('${t['price']} 金 / ${t['days']} 天 · 折扣 ${(((t['discount'] ?? 1.0) as num) * 100).round()}% · 签到 +${t['checkinBonusPct']}%',
                      style: const TextStyle(color: kDim, fontSize: 12.5)),
                  trailing: FilledButton(
                    onPressed: () => _buy((t['level'] as num).toInt(), '${t['name']}'),
                    child: const Text('开通'),
                  ),
                ),
              ),
            const SizedBox(height: 10),
            const Text('VIP 折扣在商店购买时自动生效；签到额外奖励同步提高。',
                style: TextStyle(color: kDim, fontSize: 12, height: 1.5)),
          ],
        ),
      ),
    );
  }
}
