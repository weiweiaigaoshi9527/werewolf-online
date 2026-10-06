import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 我的背包：GET /api/shop/inventory
/// 注意与商店目录的字段差异——背包项用 **itemDefId**（目录用 id），且没有价格/描述/owned；
/// 已用完的功能道具不会出现在这里。装备类走 /api/user/equip|unequip，功能道具走 /carry。
class InventoryScreen extends StatefulWidget {
  const InventoryScreen({super.key});

  @override
  State<InventoryScreen> createState() => _InventoryScreenState();
}

class _InventoryScreenState extends State<InventoryScreen> {
  List<dynamic> _items = const [];
  bool _loading = true;
  String _error = '';

  static const _typeLabel = {
    'AVATAR': '头像',
    'FRAME': '头像框',
    'TITLE': '称号',
    'NICKCOLOR': '昵称颜色',
    'FUNCTION': '功能道具',
  };

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = '';
    });
    try {
      _items = await context.read<AppStore>().api.getList('/api/shop/inventory');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _act(String path, Map<String, dynamic> body, String okMsg) async {
    final s = context.read<AppStore>();
    try {
      await s.api.post(path, body);
      await s.refreshUser();
      await _load();
      if (mounted) snack(context, okMsg);
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    if (!store.feat('shop')) {
      return const Scaffold(body: Center(child: Text('商店与背包已被管理员关闭', style: TextStyle(color: kDim))));
    }
    final groups = <String, List<dynamic>>{};
    for (final it in _items) {
      groups.putIfAbsent('${it['type']}', () => []).add(it);
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text('我的背包'),
        actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))],
      ),
      body: StateBox(
        loading: _loading,
        empty: _error.isEmpty && _items.isEmpty,
        emptyText: _error.isEmpty ? '背包还是空的，去商店逛逛' : _error,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 24),
          children: [
            for (final g in groups.entries) ...[
              SectionTitle('${_typeLabel[g.key] ?? g.key} · ${g.value.length}'),
              for (final it in g.value) _card(it as Map),
            ],
          ],
        ),
      ),
    );
  }

  Widget _card(Map it) {
    final defId = (it['itemDefId'] as num).toInt();
    final type = '${it['type']}';
    final functional = type == 'FUNCTION';
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        dense: true,
        leading: CircleAvatar(
          backgroundColor: kPanel2,
          child: Text(functional ? '✨' : '👤', style: const TextStyle(fontSize: 16)),
        ),
        title: Text('${it['name'] ?? ''}', style: const TextStyle(color: kMoon, fontSize: 14)),
        subtitle: Text('资产标识 ${it['asset'] ?? '—'}', style: const TextStyle(color: kDim, fontSize: 11.5)),
        trailing: functional
            ? TextButton(
                onPressed: () => _act('/api/user/carry', {'itemDefId': defId}, '已设为带入下一局'),
                child: const Text('带入'),
              )
            : TextButton(
                onPressed: () => _act('/api/user/equip', {'itemDefId': defId}, '已装备'),
                child: const Text('装备'),
              ),
      ),
    );
  }
}
