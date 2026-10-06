import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 更新动态。数据不是 API，而是随前端发布的静态文件 `/data/changelog.json`
/// （网页版 btn-news 也是直接 fetch 它），字段：updated + items[{date,tag,text}]。
class NewsScreen extends StatefulWidget {
  const NewsScreen({super.key});

  @override
  State<NewsScreen> createState() => _NewsScreenState();
}

class _NewsScreenState extends State<NewsScreen> {
  Map<String, dynamic> _data = const {};
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
      _data = await context.read<AppStore>().api.getStatic('/data/changelog.json');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  static const _tagColor = {'新增': kGreen, '增强': kAccent, '修复': kMoon, '平台': kBlood};

  @override
  Widget build(BuildContext context) {
    final items = (_data['items'] as List?) ?? const [];
    return Scaffold(
      appBar: AppBar(
        title: const Text('更新动态'),
        actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))],
      ),
      body: StateBox(
        loading: _loading,
        empty: _error.isEmpty && items.isEmpty,
        emptyText: _error.isEmpty ? '还没有更新记录' : _error,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 24),
          children: [
            if (_data['updated'] != null)
              Padding(
                padding: const EdgeInsets.only(bottom: 8, left: 2),
                child: Text('更新于 ${_data['updated']}', style: const TextStyle(color: kDim, fontSize: 12.5)),
              ),
            for (final it in items)
              Padding(
                padding: const EdgeInsets.symmetric(vertical: 5),
                child: Row(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Container(
                      padding: const EdgeInsets.symmetric(horizontal: 7, vertical: 2),
                      decoration: BoxDecoration(
                        color: (_tagColor['${it['tag']}'] ?? kDim).withOpacity(.18),
                        borderRadius: BorderRadius.circular(6),
                        border: Border.all(color: (_tagColor['${it['tag']}'] ?? kDim).withOpacity(.55)),
                      ),
                      child: Text('${it['tag'] ?? '其他'}',
                          style: TextStyle(fontSize: 11.5, color: _tagColor['${it['tag']}'] ?? kDim)),
                    ),
                    const SizedBox(width: 9),
                    Expanded(
                      child: Column(
                        crossAxisAlignment: CrossAxisAlignment.start,
                        children: [
                          Text('${it['text'] ?? ''}', style: const TextStyle(fontSize: 13.5, height: 1.5)),
                          if (it['date'] != null)
                            Padding(
                              padding: const EdgeInsets.only(top: 2),
                              child: Text('${it['date']}', style: const TextStyle(color: kDim, fontSize: 11)),
                            ),
                        ],
                      ),
                    ),
                  ],
                ),
              ),
          ],
        ),
      ),
    );
  }
}

/// 下载中心：GET /api/downloads（服务端运行时 stat 每个文件补 size/available）。
/// 注意 url 三态：`/download/{file}` 是文件、`/` 是站点根（PWA 条目）、null 表示"即将提供"。
class DownloadsScreen extends StatefulWidget {
  const DownloadsScreen({super.key});

  @override
  State<DownloadsScreen> createState() => _DownloadsScreenState();
}

class _DownloadsScreenState extends State<DownloadsScreen> {
  Map<String, dynamic> _data = const {};
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
      _data = await context.read<AppStore>().api.get('/api/downloads');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  void _copy(String url) {
    Clipboard.setData(ClipboardData(text: url));
    snack(context, '链接已复制，可在浏览器打开下载');
  }

  static String human(int bytes) {
    if (bytes <= 0) return '';
    if (bytes < 1024 * 1024) return '${(bytes / 1024).round()} KB';
    if (bytes < 1024 * 1024 * 1024) return '${(bytes / 1048576).toStringAsFixed(1)} MB';
    return '${(bytes / 1073741824).toStringAsFixed(2)} GB';
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    final items = ((_data['items'] as List?) ?? const []).cast<Map>();
    return Scaffold(
      appBar: AppBar(
        title: const Text('下载中心'),
        actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))],
      ),
      body: StateBox(
        loading: _loading,
        empty: _error.isEmpty && items.isEmpty,
        emptyText: _error.isEmpty ? '暂时没有可下载的版本' : _error,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 10, 14, 24),
          children: [
            if ('${_data['banner'] ?? ''}'.isNotEmpty)
              Container(
                margin: const EdgeInsets.only(bottom: 10),
                padding: const EdgeInsets.all(12),
                decoration: BoxDecoration(color: kPanel, borderRadius: BorderRadius.circular(10), border: Border.all(color: kBorder)),
                child: Text('${_data['banner']}', style: const TextStyle(color: kMoon, fontSize: 13, height: 1.5)),
              ),
            for (final it in items) _card(store, it),
            const SizedBox(height: 8),
            const Text('客户端内不直接落盘下载：复制链接后用浏览器打开即可（大文件走浏览器更稳，也便于断点续传）。',
                style: TextStyle(color: kDim, fontSize: 11.5, height: 1.5)),
          ],
        ),
      ),
    );
  }

  Widget _card(AppStore store, Map it) {
    final available = it['available'] == true;
    final url = it['url'] as String?;
    final size = (it['size'] as num?)?.toInt() ?? 0;
    final isSiteRoot = url == '/';
    final full = url == null ? '' : (isSiteRoot ? store.config.baseUrl : '${store.config.baseUrl}$url');
    return Card(
      margin: const EdgeInsets.symmetric(vertical: 5),
      child: ListTile(
        leading: Text('${it['icon'] ?? '📦'}', style: const TextStyle(fontSize: 24)),
        title: Text('${it['label'] ?? it['platform']}', style: const TextStyle(color: kMoon, fontSize: 14.5)),
        subtitle: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              [
                if ('${it['os'] ?? ''}'.isNotEmpty) '${it['os']}',
                if ('${it['arch'] ?? ''}'.isNotEmpty) '${it['arch']}',
                if ('${it['version'] ?? ''}'.isNotEmpty) 'v${it['version']}',
                if (size > 0) human(size),
              ].join(' · '),
              style: const TextStyle(color: kDim, fontSize: 12),
            ),
            if ('${it['note'] ?? ''}'.isNotEmpty)
              Text('${it['note']}', maxLines: 2, style: const TextStyle(color: kDim, fontSize: 11.5, height: 1.4)),
          ],
        ),
        trailing: !available || url == null
            ? const Chip(label: Text('即将提供', style: TextStyle(fontSize: 11.5, color: kDim)), backgroundColor: kPanel2)
            : TextButton(
                onPressed: () => _copy(full),
                child: Text(isSiteRoot ? '复制网址' : '复制链接'),
              ),
      ),
    );
  }
}
