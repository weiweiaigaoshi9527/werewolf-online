import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 工单：提交问题 / 建议 / 举报，查看我的工单与回复。
class TicketsScreen extends StatefulWidget {
  const TicketsScreen({super.key});
  @override
  State<TicketsScreen> createState() => _TicketsScreenState();
}

class _TicketsScreenState extends State<TicketsScreen> {
  List<dynamic> _list = const [];
  bool _loading = true;

  static const statusText = {'OPEN': '待处理', 'PROCESSING': '处理中', 'REPLIED': '已回复', 'CLOSED': '已关闭'};

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    try {
      _list = await context.read<AppStore>().api.getList('/api/tickets');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _create() async {
    final r = await showModalBottomSheet<bool>(
        context: context,
        isScrollControlled: true,
        backgroundColor: kPanel,
        builder: (_) => const _CreateTicketSheet(category: '问题'));
    if (r == true) _load();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('工单')),
      floatingActionButton: FloatingActionButton.extended(
        backgroundColor: kAccent,
        foregroundColor: const Color(0xFF1A1206),
        onPressed: _create,
        icon: const Icon(Icons.add),
        label: const Text('提交工单'),
      ),
      body: StateBox(
        loading: _loading,
        empty: _list.isEmpty,
        emptyText: '还没有提交过工单',
        child: RefreshIndicator(
          onRefresh: _load,
          child: ListView(
            padding: const EdgeInsets.fromLTRB(12, 8, 12, 90),
            children: [
              for (final t in _list)
                Card(
                  margin: const EdgeInsets.symmetric(vertical: 5),
                  child: ListTile(
                    leading: CircleAvatar(backgroundColor: kPanel2, child: Text('#${t['id']}', style: const TextStyle(color: kDim, fontSize: 12))),
                    title: Text('${t['title'] ?? t['category']}', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                    subtitle: Text('${t['category']} · ${statusText[t['status']] ?? t['status']}', style: const TextStyle(color: kDim, fontSize: 12)),
                    trailing: TextButton(onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => _TicketDetail(id: (t['id'] as num).toInt()))), child: const Text('查看')),
                  ),
                ),
            ],
          ),
        ),
      ),
    );
  }
}

class _CreateTicketSheet extends StatefulWidget {
  final String category;
  const _CreateTicketSheet({required this.category});
  @override
  State<_CreateTicketSheet> createState() => _CreateTicketSheetState();
}

class _CreateTicketSheetState extends State<_CreateTicketSheet> {
  final _title = TextEditingController();
  final _content = TextEditingController();
  String _cat = '问题';

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: EdgeInsets.fromLTRB(16, 16, 16, MediaQuery.of(context).viewInsets.bottom + 16),
      child: Column(
        mainAxisSize: MainAxisSize.min,
        crossAxisAlignment: CrossAxisAlignment.stretch,
        children: [
          const Text('提交工单', style: TextStyle(color: kMoon, fontSize: 16, fontWeight: FontWeight.bold)),
          const SizedBox(height: 12),
          DropdownButtonFormField<String>(
            value: _cat,
            dropdownColor: kPanel,
            decoration: const InputDecoration(labelText: '类型'),
            items: const ['问题', '建议', '举报', '其他'].map((c) => DropdownMenuItem(value: c, child: Text(c))).toList(),
            onChanged: (v) => setState(() => _cat = v ?? _cat),
          ),
          const SizedBox(height: 10),
          TextField(controller: _title, decoration: const InputDecoration(labelText: '标题（可选）')),
          const SizedBox(height: 10),
          TextField(controller: _content, minLines: 3, maxLines: 6, decoration: const InputDecoration(labelText: '内容')),
          const SizedBox(height: 14),
          FilledButton(
            onPressed: () async {
              if (_content.text.trim().isEmpty) {
                snack(context, '请填写内容', error: true);
                return;
              }
              final s = context.read<AppStore>();
              try {
                await s.api.post('/api/tickets', {'category': _cat, 'title': _title.text.trim(), 'content': _content.text.trim()});
                if (context.mounted) {
                  snack(context, '已提交');
                  Navigator.pop(context, true);
                }
              } on AppError catch (e) {
                if (context.mounted) snack(context, e.message, error: true);
              }
            },
            child: const Padding(padding: EdgeInsets.symmetric(vertical: 6), child: Text('提交')),
          ),
        ],
      ),
    );
  }
}

class _TicketDetail extends StatefulWidget {
  final int id;
  const _TicketDetail({required this.id});
  @override
  State<_TicketDetail> createState() => _TicketDetailState();
}

class _TicketDetailState extends State<_TicketDetail> {
  Map<String, dynamic> _t = const {};
  final _reply = TextEditingController();
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
      _t = await s.api.get('/api/tickets/${widget.id}');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _send() async {
    final t = _reply.text.trim();
    if (t.isEmpty) return;
    _reply.clear();
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/tickets/${widget.id}/reply', {'content': t});
      await _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final thread = (_t['thread'] as List?) ?? const [];
    return Scaffold(
      appBar: AppBar(title: Text('工单 #${widget.id}')),
      body: StateBox(
        loading: _loading,
        child: Column(
          children: [
            Expanded(
              child: ListView(
                padding: const EdgeInsets.all(14),
                children: [
                  Text('${_t['title'] ?? _t['category']}', style: const TextStyle(color: kMoon, fontSize: 16, fontWeight: FontWeight.bold)),
                  const SizedBox(height: 4),
                  Text('${_t['category']} · 状态 ${_t['status']} · ${(_t['at'] ?? '').toString().substring(0, 16).replaceFirst('T', ' ')}', style: const TextStyle(color: kDim, fontSize: 12)),
                  const SizedBox(height: 10),
                  Text('${_t['content']}', style: const TextStyle(color: kText, height: 1.5)),
                  const SectionTitle('回复记录'),
                  for (final r in thread)
                    Container(
                      margin: const EdgeInsets.symmetric(vertical: 5),
                      padding: const EdgeInsets.all(10),
                      decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10), border: Border.all(color: kBorder)),
                      child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                        Text('${r['author']}${r['admin'] == true ? '（官方）' : ''}', style: TextStyle(color: r['admin'] == true ? kAccent : kDim, fontSize: 12)),
                        const SizedBox(height: 4),
                        Text('${r['content']}', style: const TextStyle(color: kText)),
                      ]),
                    ),
                ],
              ),
            ),
            SafeArea(
              top: false,
              child: Padding(
                padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
                child: Row(children: [
                  Expanded(child: TextField(controller: _reply, decoration: const InputDecoration(hintText: '补充回复…', isDense: true), onSubmitted: (_) => _send())),
                  const SizedBox(width: 8),
                  IconButton.filled(onPressed: _send, icon: const Icon(Icons.send), style: IconButton.styleFrom(backgroundColor: kAccent), color: const Color(0xFF1A1206)),
                ]),
              ),
            ),
          ],
        ),
      ),
    );
  }
}
