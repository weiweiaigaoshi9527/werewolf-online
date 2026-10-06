import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';
import 'profile_view.dart';

/// 排行榜：GET /api/ranking?by=win|gold|level&limit=
/// by 传非法值时服务端按 win 排；limit 默认 50，钳制到 [5,200]；封禁玩家已被剔除。
class RankingScreen extends StatefulWidget {
  const RankingScreen({super.key});

  @override
  State<RankingScreen> createState() => _RankingScreenState();
}

class _RankingScreenState extends State<RankingScreen> {
  static const _modes = [('win', '胜率榜'), ('gold', '财富榜'), ('level', '等级榜')];

  String _by = 'win';
  List<dynamic> _list = const [];
  bool _loading = true;
  String _error = '';

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
      final v = await context.read<AppStore>().api.get('/api/ranking?by=$_by&limit=100');
      _list = (v['list'] as List?) ?? const [];
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  void _pick(String by) {
    if (by == _by) return;
    setState(() => _by = by);
    _load();
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    if (!store.feat('ranking')) {
      return const Scaffold(body: Center(child: Text('排行榜已被管理员关闭', style: TextStyle(color: kDim))));
    }
    final myId = store.user?['id'];
    return Scaffold(
      appBar: AppBar(
        title: const Text('排行榜'),
        actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))],
      ),
      body: Column(
        children: [
          Padding(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 4),
            child: Row(
              children: [
                for (final m in _modes)
                  Expanded(
                    child: ChoiceChip(
                      label: Text(m.$2),
                      selected: _by == m.$1,
                      onSelected: (_) => _pick(m.$1),
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: StateBox(
              loading: _loading,
              empty: _error.isEmpty && _list.isEmpty,
              emptyText: _error.isEmpty ? '还没有上榜数据' : _error,
              child: ListView.builder(
                padding: const EdgeInsets.fromLTRB(12, 4, 12, 20),
                itemCount: _list.length,
                itemBuilder: (_, i) => _row(_list[i] as Map, i == 0, myId),
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _row(Map r, bool top, Object? myId) {
    final rank = (r['rank'] as num?)?.toInt() ?? 0;
    final me = r['userId'] == myId;
    final metric = switch (_by) {
      'gold' => '💰${r['gold'] ?? 0}',
      'level' => 'Lv.${r['level'] ?? 1}',
      _ => '${r['winRate'] ?? 0}% · ${r['wins'] ?? 0}/${r['games'] ?? 0}',
    };
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      shape: RoundedRectangleBorder(
        side: BorderSide(color: me ? kAccent : kBorder),
        borderRadius: BorderRadius.circular(12),
      ),
      child: ListTile(
        dense: true,
        leading: SizedBox(
          width: 34,
          child: Text(
            rank <= 3 ? ['🥇', '🥈', '🥉'][rank - 1] : '$rank',
            textAlign: TextAlign.center,
            style: TextStyle(
              fontSize: rank <= 3 ? 19 : 14,
              color: rank <= 3 ? kMoon : kDim,
              fontWeight: FontWeight.bold,
            ),
          ),
        ),
        title: Text('${r['nickname'] ?? ''}',
            maxLines: 1,
            overflow: TextOverflow.ellipsis,
            style: TextStyle(color: me ? kAccent : kMoon, fontWeight: me ? FontWeight.bold : FontWeight.normal)),
        subtitle: Text('$metric · MVP ${r['mvp'] ?? 0}', style: const TextStyle(color: kDim, fontSize: 12)),
        onTap: () {
          final id = (r['userId'] as num?)?.toInt();
          if (id != null && !me) showWwProfile(context, id, title: '${r['nickname'] ?? ''}');
        },
      ),
    );
  }
}
