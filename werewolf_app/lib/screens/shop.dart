import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 商店：与网页一致，按类型分区展示目录，购买 / 装备 / 卸下 / 携带功能道具。
class ShopScreen extends StatefulWidget {
  const ShopScreen({super.key});
  @override
  State<ShopScreen> createState() => _ShopScreenState();
}

class _ShopScreenState extends State<ShopScreen> {
  static const order = ['AVATAR', 'FRAME', 'TITLE', 'NICKCOLOR', 'FUNCTION'];
  static const labels = {'AVATAR': '头像', 'FRAME': '头像框', 'TITLE': '称号', 'NICKCOLOR': '昵称颜色', 'FUNCTION': '功能道具'};

  List<dynamic> _items = const [];
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
      _items = await context.read<AppStore>().api.getList('/api/shop/catalog');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _act(String path, Map<String, dynamic> body, String okMsg) async {
    final store = context.read<AppStore>();
    try {
      await store.api.post(path, body);
      if (!mounted) return;
      snack(context, okMsg);
      await store.refreshUser();
      await _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final gold = (context.watch<AppStore>().user?['gold']) ?? 0;
    return Scaffold(
      appBar: AppBar(
        title: const Text('商店'),
        actions: [
          Padding(
            padding: const EdgeInsets.only(right: 16),
            child: Center(child: Text('💰 $gold', style: const TextStyle(color: kAccent, fontWeight: FontWeight.bold))),
          ),
          IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
        ],
      ),
      body: RefreshIndicator(
        onRefresh: _load,
        child: _loading
            ? const Center(child: CircularProgressIndicator(color: kAccent))
            : (_error != null
                ? ListView(padding: const EdgeInsets.all(24), children: [Text(_error!, style: const TextStyle(color: kBlood))])
                : _list()),
      ),
    );
  }

  Widget _list() {
    final children = <Widget>[];
    for (final type in order) {
      final group = _items.where((it) => it['type'] == type).toList();
      if (group.isEmpty) continue;
      children.add(SectionTitle(labels[type] ?? type));
      for (final it in group) {
        children.add(_card(it, type));
      }
    }
    return ListView(padding: const EdgeInsets.fromLTRB(14, 4, 14, 20), children: children);
  }

  Widget _card(Map it, String type) {
    final owned = it['owned'] == true;
    final price = it['price'];
    final discount = it['discounted'] == true;
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 6),
      child: ListTile(
        leading: type == 'AVATAR' || type == 'FRAME'
            ? WwAvatar(avatarId: it['asset'], size: 40)
            : CircleAvatar(backgroundColor: kPanel, child: Text(_icon(type), style: const TextStyle(fontSize: 18))),
        title: Text(it['name'] ?? '', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
        subtitle: Text(
          (it['description'] ?? '') + (owned ? '  · 已拥有' : ''),
          style: const TextStyle(color: kDim, fontSize: 12.5),
        ),
        trailing: owned
            ? (type == 'FUNCTION'
                ? TextButton(onPressed: () => _act('/api/user/carry', {'itemDefId': it['id']}, '已设为带入下一局'), child: const Text('带入'))
                : Row(mainAxisSize: MainAxisSize.min, children: [
                    TextButton(onPressed: () => _act('/api/user/equip', {'itemDefId': it['id']}, '已装备'), child: const Text('装备')),
                    TextButton(onPressed: () => _act('/api/user/unequip', {'type': type}, '已卸下'), child: const Text('卸下')),
                  ]))
            : FilledButton(
                onPressed: () => _act('/api/shop/buy', {'itemDefId': it['id']}, '购买成功'),
                style: FilledButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 14)),
                child: Text(discount ? '原价${it['origPrice']} 现$price' : '$price 金'),
              ),
      ),
    );
  }

  String _icon(String type) => const {'TITLE': '🏅', 'NICKCOLOR': '🎨', 'FUNCTION': '⚙️'}[type] ?? '🎁';
}
