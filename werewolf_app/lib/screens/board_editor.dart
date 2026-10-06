import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 板子编辑器：预设一键套用，或逐角色自定义数量；保存到当前房间（与网页一致）。
class BoardEditorScreen extends StatefulWidget {
  const BoardEditorScreen({super.key});
  @override
  State<BoardEditorScreen> createState() => _BoardEditorScreenState();
}

class _BoardEditorScreenState extends State<BoardEditorScreen> {
  List<dynamic> _presets = const [];
  List<dynamic> _roles = const [];
  bool _loading = true;
  final Map<String, int> _counts = {}; // role -> count
  String _boardName = 'custom';

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = context.read<AppStore>();
    try {
      final d = await s.api.get('/api/room/boards');
      _presets = (d['presets'] as List?) ?? const [];
      _roles = (d['roles'] as List?) ?? const [];
      // 以房间当前板子为初值
      final room = s.room;
      final cur = room?['board'];
      if (cur is Map) {
        cur.forEach((k, v) => _counts[k as String] = (v as num).toInt());
      }
      _boardName = (room?['boardName'] as String?) ?? 'custom';
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  int get _total => _counts.values.fold(0, (a, b) => a + b);
  int get _wolves => _roles.where((r) => r['faction'] == 'WOLF').fold(0, (a, r) => a + (_counts[r['role']] ?? 0));

  String? _validate() {
    if (_total < 6) return '总人数至少 6';
    if (_total > 24) return '总人数至多 24';
    if (_wolves < 1) return '至少 1 名狼人';
    if (_wolves > 6) return '狼人至多 6 名';
    if (_wolves >= _total - _wolves) return '狼人数必须小于好人数';
    return null;
  }

  void _applyPreset(Map p) {
    setState(() {
      _counts.clear();
      (p['counts'] as Map).forEach((k, v) => _counts[k as String] = (v as num).toInt());
      _boardName = p['id'] as String;
    });
  }

  Future<void> _save() async {
    final err = _validate();
    if (err != null) {
      snack(context, err, error: true);
      return;
    }
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/room/board', {'counts': _counts, 'boardName': _boardName});
      if (mounted) {
        snack(context, '板子已保存');
        Navigator.pop(context);
      }
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_loading) return const Scaffold(body: Center(child: CircularProgressIndicator(color: kAccent)));
    final err = _validate();
    return Scaffold(
      appBar: AppBar(title: const Text('板子配置')),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: err == null ? kAccent : kPanel2,
        foregroundColor: const Color(0xFF1A1206),
        onPressed: _save,
        icon: const Icon(Icons.check),
        label: const Text('保存板子'),
      ),
      body: ListView(
        padding: const EdgeInsets.fromLTRB(14, 10, 14, 90),
        children: [
          const SectionTitle('预设'),
          Wrap(
            spacing: 8,
            runSpacing: 8,
            children: [
              for (final p in _presets)
                ChoiceChip(
                  label: Text('${p['name']}'),
                  selected: _boardName == p['id'],
                  selectedColor: kAccent.withOpacity(.25),
                  onSelected: (_) => _applyPreset(p),
                ),
            ],
          ),
          const SectionTitle('角色数量（自定义）'),
          for (final r in _roles) _slider(r),
          const SizedBox(height: 8),
          Container(
            padding: const EdgeInsets.all(12),
            decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10), border: Border.all(color: err == null ? kBorder : kBlood)),
            child: Text(
              '合计 $_total 人 · 狼人 $_wolves · 好人 ${_total - _wolves}${err != null ? '  ⚠️ $err' : '  ✅ 合法'}',
              style: TextStyle(color: err == null ? kGreen : kBlood, fontWeight: FontWeight.bold),
            ),
          ),
        ],
      ),
    );
  }

  Widget _slider(Map role) {
    final name = role['role'] as String;
    final cn = role['cnName'];
    final max = (role['max'] as num).toInt();
    final val = _counts[name] ?? 0;
    final isWolf = role['faction'] == 'WOLF';
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 4),
      child: ListTile(
        dense: true,
        title: Text('$cn', style: TextStyle(color: isWolf ? kBlood : kMoon, fontWeight: FontWeight.bold)),
        trailing: SizedBox(
          width: 130,
          child: Row(mainAxisSize: MainAxisSize.min, children: [
            IconButton(splashRadius: 18, iconSize: 18, onPressed: () => setState(() => _counts[name] = (val - 1).clamp(0, max)), icon: const Icon(Icons.remove_circle_outline)),
            SizedBox(width: 26, child: Text('$val', textAlign: TextAlign.center, style: const TextStyle(color: kAccent, fontWeight: FontWeight.bold))),
            IconButton(splashRadius: 18, iconSize: 18, onPressed: () => setState(() => _counts[name] = (val + 1).clamp(0, max)), icon: const Icon(Icons.add_circle_outline)),
          ]),
        ),
      ),
    );
  }
}
