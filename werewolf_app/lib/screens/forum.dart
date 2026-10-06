import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 论坛：列表 + 分类筛选 + 发帖 + 详情回复。
/// 契约照抄服务端 ForumController：列表**无分页**（全量数组，正文超 200 字服务端已截断），
/// 分类不校验枚举（与网页版下拉保持一致的 5 个值），时间是无时区 ISO 串。
class ForumScreen extends StatefulWidget {
  const ForumScreen({super.key});

  @override
  State<ForumScreen> createState() => _ForumScreenState();
}

class _ForumScreenState extends State<ForumScreen> {
  static const categories = ['综合', '攻略', '吐槽', '举报', '招募'];

  List<dynamic> _posts = const [];
  String _filter = '全部';
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
      _posts = await context.read<AppStore>().api.getList('/api/forum');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  List<dynamic> get _shown {
    final list = _filter == '全部' ? [..._posts] : _posts.where((p) => p['category'] == _filter).toList();
    list.sort((a, b) => (b['pinned'] == true ? 1 : 0).compareTo((a['pinned'] == true ? 1 : 0)));
    return list;
  }

  Future<void> _compose() async {
    final title = TextEditingController();
    final body = TextEditingController();
    var cat = categories.first;
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => StatefulBuilder(
        builder: (c, setLocal) => AlertDialog(
          backgroundColor: kPanel,
          title: const Text('发新帖'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                TextField(
                  controller: title,
                  maxLength: 80,
                  decoration: const InputDecoration(hintText: '标题（最多 80 字）'),
                ),
                TextField(
                  controller: body,
                  maxLines: 5,
                  maxLength: 4000,
                  decoration: const InputDecoration(hintText: '正文（最多 4000 字）'),
                ),
                DropdownButton<String>(
                  isExpanded: true,
                  value: cat,
                  dropdownColor: kPanel2,
                  items: [for (final x in categories) DropdownMenuItem(value: x, child: Text(x))],
                  onChanged: (v) => setLocal(() => cat = v ?? cat),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
            FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('发布')),
          ],
        ),
      ),
    );
    if (ok != true || !mounted) return;
    try {
      await context.read<AppStore>().api.post('/api/forum', {
        'title': title.text.trim(),
        'content': body.text.trim(),
        'category': cat,
      });
      await _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    if (!store.feat('forum')) {
      return const Scaffold(
        body: Center(child: Text('论坛已被管理员关闭', style: TextStyle(color: kDim))),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: const Text('论坛'),
        actions: [
          IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
          IconButton(onPressed: _compose, icon: const Icon(Icons.edit_outlined), tooltip: '发新帖'),
        ],
      ),
      body: StateBox(
        loading: _loading,
        empty: _error.isEmpty && _shown.isEmpty,
        emptyText: _error.isEmpty ? '还没有帖子，点右上角发第一帖' : _error,
        child: Column(
          children: [
            SingleChildScrollView(
              scrollDirection: Axis.horizontal,
              padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
              child: Row(
                children: [
                  for (final c in ['全部', ...categories])
                    Padding(
                      padding: const EdgeInsets.only(right: 8),
                      child: ChoiceChip(
                        label: Text(c),
                        selected: _filter == c,
                        onSelected: (_) => setState(() => _filter = c),
                      ),
                    ),
                ],
              ),
            ),
            Expanded(
              child: ListView.builder(
                padding: const EdgeInsets.fromLTRB(12, 0, 12, 20),
                itemCount: _shown.length,
                itemBuilder: (_, i) => _tile(_shown[i] as Map),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _tile(Map p) {
    final mine = p['userId'] == (context.read<AppStore>().user?['id']);
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 5),
      child: ListTile(
        onTap: () async {
          await Navigator.push(context, MaterialPageRoute(builder: (_) => ForumDetailScreen(postId: (p['id'] as num).toInt())));
          if (mounted) _load();
        },
        title: Row(
          children: [
            if (p['pinned'] == true) const Padding(padding: EdgeInsets.only(right: 6), child: Text('📌')),
            Expanded(
              child: Text('${p['title']}',
                  maxLines: 1, overflow: TextOverflow.ellipsis, style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
            ),
          ],
        ),
        subtitle: Padding(
          padding: const EdgeInsets.only(top: 4),
          child: Text(
            '${p['category'] ?? '综合'} · ${p['author'] ?? ''} · ${fmtAt(p['at'])} · ${(p['replyCount'] as num?)?.toInt() ?? 0} 回复${mine ? ' · 我的' : ''}',
            maxLines: 2,
            style: const TextStyle(color: kDim, fontSize: 12),
          ),
        ),
      ),
    );
  }
}

/// 帖子详情与回复。删除仅作者或管理员（服务端校验，客户端只把按钮露给作者）。
class ForumDetailScreen extends StatefulWidget {
  const ForumDetailScreen({super.key, required this.postId});
  final int postId;

  @override
  State<ForumDetailScreen> createState() => _ForumDetailScreenState();
}

class _ForumDetailScreenState extends State<ForumDetailScreen> {
  Map<String, dynamic> _post = const {};
  bool _loading = true;
  final _reply = TextEditingController();

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final s = context.read<AppStore>();
    try {
      _post = await s.api.get('/api/forum/${widget.postId}');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _send() async {
    final t = _reply.text.trim();
    if (t.isEmpty) return;
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/forum/${widget.postId}/reply', {'content': t});
      _reply.clear();
      await _load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  Future<void> _delete() async {
    final ok = await showDialog<bool>(
      context: context,
      builder: (c) => AlertDialog(
        title: const Text('删除帖子'),
        content: const Text('删除后不可恢复，确定吗？'),
        actions: [
          TextButton(onPressed: () => Navigator.pop(c, false), child: const Text('取消')),
          FilledButton(onPressed: () => Navigator.pop(c, true), child: const Text('删除')),
        ],
      ),
    );
    if (ok != true || !mounted) return;
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/forum/${widget.postId}/delete');
      if (mounted) Navigator.pop(context);
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    final replies = (_post['replies'] as List?) ?? const [];
    final mine = _post['userId'] == context.read<AppStore>().user?['id'];
    return Scaffold(
      appBar: AppBar(
        title: Text('${_post['category'] ?? ''} · 论坛', maxLines: 1, overflow: TextOverflow.ellipsis),
        actions: [if (mine) IconButton(tooltip: '删除', icon: const Icon(Icons.delete_outline), onPressed: _delete)],
      ),
      body: StateBox(
        loading: _loading,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 12),
          children: [
            Text('${_post['title']}', style: const TextStyle(color: kMoon, fontSize: 19, fontWeight: FontWeight.bold)),
            const SizedBox(height: 6),
            Text('${_post['author'] ?? ''} · ${fmtAt(_post['at'])}', style: const TextStyle(color: kDim, fontSize: 12)),
            const SizedBox(height: 12),
            Text('${_post['content'] ?? ''}', style: const TextStyle(height: 1.7, fontSize: 14.5)),
            SectionTitle('回复 ${replies.length}'),
            for (final r in replies)
              Container(
                margin: const EdgeInsets.symmetric(vertical: 4),
                padding: const EdgeInsets.all(10),
                decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10)),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text('${r['author'] ?? ''} · ${fmtAt(r['at'])}', style: const TextStyle(color: kDim, fontSize: 11.5)),
                    const SizedBox(height: 4),
                    Text('${r['content'] ?? ''}', style: const TextStyle(fontSize: 13.5, height: 1.5)),
                  ],
                ),
              ),
          ],
        ),
      ),
      bottomNavigationBar: SafeArea(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
          child: Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _reply,
                  maxLength: 2000,
                  decoration: const InputDecoration(hintText: '写回复…', isDense: true),
                  onSubmitted: (_) => _send(),
                ),
              ),
              const SizedBox(width: 8),
              IconButton.filled(
                onPressed: _send,
                icon: const Icon(Icons.send, color: Color(0xFF1A1206)),
                style: IconButton.styleFrom(backgroundColor: kAccent),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

/// 服务端时间是 LocalDateTime.toString()，小数位不定（0/3/6 位），DateTime.tryParse 都能吃。
String fmtAt(Object? iso) {
  final s = iso?.toString();
  if (s == null || s.isEmpty) return '';
  final d = DateTime.tryParse(s);
  if (d == null) return s.length > 16 ? s.substring(0, 16) : s;
  final local = d.toLocal();
  String two(int v) => v.toString().padLeft(2, '0');
  return '${two(local.month)}-${two(local.day)} ${two(local.hour)}:${two(local.minute)}';
}
