import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 好友：好友 / 申请 / 搜索 三个子页，点好友进入私聊。
class FriendsScreen extends StatefulWidget {
  const FriendsScreen({super.key});
  @override
  State<FriendsScreen> createState() => _FriendsScreenState();
}

class _FriendsScreenState extends State<FriendsScreen> with SingleTickerProviderStateMixin {
  late final TabController _tabs = TabController(length: 3, vsync: this);

  @override
  void dispose() {
    _tabs.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('好友'),
        bottom: TabBar(
          controller: _tabs,
          labelColor: kAccent,
          unselectedLabelColor: kDim,
          indicatorColor: kAccent,
          tabs: const [Tab(text: '好友'), Tab(text: '申请'), Tab(text: '搜索')],
        ),
      ),
      body: TabBarView(
        controller: _tabs,
        children: const [_FriendList(), _RequestList(), _SearchList()],
      ),
    );
  }
}

class _FriendList extends StatefulWidget {
  const _FriendList();
  @override
  State<_FriendList> createState() => _FriendListState();
}

class _FriendListState extends State<_FriendList> {
  List<dynamic> _list = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => _loading = true);
    try {
      _list = await context.read<AppStore>().api.getList('/api/friends');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  @override
  Widget build(BuildContext context) {
    return StateBox(
      loading: _loading,
      empty: _list.isEmpty,
      emptyText: '还没有好友，去「搜索」添加吧',
      child: RefreshIndicator(
        onRefresh: load,
        child: ListView(
          padding: const EdgeInsets.all(12),
          children: [
            for (final f in _list)
              Card(
                margin: const EdgeInsets.symmetric(vertical: 5),
                child: ListTile(
                  leading: Stack(children: [
                    WwAvatar(avatarId: f['avatarId'], avatarUrl: f['avatarUrl'], frameColor: f['frameColor']),
                    if (f['online'] == true)
                      Positioned(right: 0, bottom: 0, child: Container(width: 11, height: 11, decoration: BoxDecoration(color: kGreen, shape: BoxShape.circle, border: Border.all(color: kPanel, width: 2)))),
                  ]),
                  title: Text('${f['nickname']}', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                  subtitle: Text('@${f['username']} · Lv.${f['level'] ?? 1}', style: const TextStyle(color: kDim, fontSize: 12)),
                  trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                    if ((f['unread'] ?? 0) is num && (f['unread'] as num) > 0)
                      Badge.count(count: (f['unread'] as num).toInt(), backgroundColor: kBlood),
                    IconButton(
                      icon: const Icon(Icons.chat_bubble_outline, size: 20, color: kAccent),
                      onPressed: () => Navigator.push(context, MaterialPageRoute(builder: (_) => ChatScreen(peerId: (f['id'] as num).toInt(), peerName: '${f['nickname']}'))),
                    ),
                    IconButton(
                      icon: const Icon(Icons.person_remove_alt_1_outlined, size: 20, color: kDim),
                      onPressed: () async {
                        final s = context.read<AppStore>();
                        await s.api.post('/api/friends/remove', {'id': f['id']});
                        load();
                      },
                    ),
                  ]),
                ),
              ),
          ],
        ),
      ),
    );
  }
}

class _RequestList extends StatefulWidget {
  const _RequestList();
  @override
  State<_RequestList> createState() => _RequestListState();
}

class _RequestListState extends State<_RequestList> {
  List<dynamic> _list = const [];
  bool _loading = true;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    setState(() => _loading = true);
    try {
      _list = await context.read<AppStore>().api.getList('/api/friends/requests');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _handle(int reqId, bool accept) async {
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/friends/requests/$reqId/${accept ? 'accept' : 'reject'}');
      if (!mounted) return;
      snack(context, accept ? '已同意' : '已拒绝');
      load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return StateBox(
      loading: _loading,
      empty: _list.isEmpty,
      emptyText: '暂无好友申请',
      child: ListView(
        padding: const EdgeInsets.all(12),
        children: [
          for (final r in _list)
            Card(
              margin: const EdgeInsets.symmetric(vertical: 5),
              child: ListTile(
                leading: WwAvatar(avatarId: r['from']?['avatarId'], avatarUrl: r['from']?['avatarUrl']),
                title: Text('${r['from']?['nickname']}', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                subtitle: Text((r['greeting'] ?? '').toString().isEmpty ? '请求添加你为好友' : '${r['greeting']}', style: const TextStyle(color: kDim, fontSize: 12)),
                trailing: Row(mainAxisSize: MainAxisSize.min, children: [
                  TextButton(onPressed: () => _handle((r['reqId'] as num).toInt(), false), child: const Text('拒绝')),
                  FilledButton(onPressed: () => _handle((r['reqId'] as num).toInt(), true), child: const Text('同意')),
                ]),
              ),
            ),
        ],
      ),
    );
  }
}

class _SearchList extends StatefulWidget {
  const _SearchList();
  @override
  State<_SearchList> createState() => _SearchListState();
}

class _SearchListState extends State<_SearchList> {
  final _kw = TextEditingController();
  List<dynamic> _list = const [];
  bool _loading = false;

  Future<void> _search() async {
    if (_kw.text.trim().isEmpty) return;
    setState(() => _loading = true);
    final s = context.read<AppStore>();
    try {
      _list = await s.api.getList('/api/friends/search?kw=${Uri.encodeComponent(_kw.text.trim())}');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _add(int id) async {
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/friends/request', {'userId': id});
      if (!mounted) return;
      snack(context, '已发送好友申请');
      _search();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Column(
      children: [
        Padding(
          padding: const EdgeInsets.all(12),
          child: Row(children: [
            Expanded(
              child: TextField(
                controller: _kw,
                decoration: const InputDecoration(hintText: '搜索用户名 / 昵称', isDense: true),
                onSubmitted: (_) => _search(),
              ),
            ),
            const SizedBox(width: 8),
            IconButton.filled(onPressed: _search, icon: const Icon(Icons.search), style: IconButton.styleFrom(backgroundColor: kAccent), color: const Color(0xFF1A1206)),
          ]),
        ),
        Expanded(
          child: StateBox(
            loading: _loading,
            empty: _list.isEmpty,
            emptyText: _kw.text.trim().isEmpty ? '输入关键字搜索玩家' : '没有找到用户',
            child: ListView(
              padding: const EdgeInsets.symmetric(horizontal: 12),
              children: [
                for (final u in _list)
                  Card(
                    margin: const EdgeInsets.symmetric(vertical: 5),
                    child: ListTile(
                      leading: WwAvatar(avatarId: u['avatarId'], avatarUrl: u['avatarUrl']),
                      title: Text('${u['nickname']}', style: const TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                      subtitle: Text('@${u['username']} · Lv.${u['level'] ?? 1}', style: const TextStyle(color: kDim, fontSize: 12)),
                      trailing: u['isFriend'] == true
                          ? const Text('已是好友', style: TextStyle(color: kGreen, fontSize: 12))
                          : (u['pending'] == true
                              ? const Text('已申请', style: TextStyle(color: kDim, fontSize: 12))
                              : FilledButton(onPressed: () => _add((u['id'] as num).toInt()), child: const Text('添加'))),
                    ),
                  ),
              ],
            ),
          ),
        ),
      ],
    );
  }
}

/// 私聊窗口。
class ChatScreen extends StatefulWidget {
  final int peerId;
  final String peerName;
  const ChatScreen({super.key, required this.peerId, required this.peerName});
  @override
  State<ChatScreen> createState() => _ChatScreenState();
}

class _ChatScreenState extends State<ChatScreen> {
  final _input = TextEditingController();
  final _scroll = ScrollController();
  List<dynamic> _msgs = const [];
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
      _msgs = await s.api.getList('/api/friends/messages?peerId=${widget.peerId}');
      // 打开会话即视为已读，同步一次服务端聚合，清掉底部导航的红点
      s.refreshUnread();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _send() async {
    final t = _input.text.trim();
    if (t.isEmpty) return;
    _input.clear();
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/friends/messages', {'toId': widget.peerId, 'content': t});
      await _load();
      _scrollBottom();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  void _scrollBottom() {
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (_scroll.hasClients) _scroll.jumpTo(_scroll.position.maxScrollExtent);
    });
  }

  @override
  Widget build(BuildContext context) {
    _scrollBottom();
    // 合并 WS 实时推来的对方消息：不必重开页面就能看到新消息（按 id 去重，避免与 REST 列表重复）
    final store = context.watch<AppStore>();
    final shown = <dynamic>[..._msgs];
    final ids = shown.map((m) => m is Map ? m['id'] : null).toSet();
    for (final m in store.livePrivate) {
      if (m['from'] == widget.peerId && !ids.contains(m['id'])) shown.add(m);
    }
    return Scaffold(
      appBar: AppBar(title: Text('与 ${widget.peerName} 的对话')),
      body: Column(
        children: [
          Expanded(
            child: _loading
                ? const Center(child: CircularProgressIndicator(color: kAccent))
                : ListView(
                    controller: _scroll,
                    padding: const EdgeInsets.all(12),
                    children: [
                      for (final m in shown)
                        Align(
                          alignment: m['mine'] == true ? Alignment.centerRight : Alignment.centerLeft,
                          child: Container(
                            margin: const EdgeInsets.symmetric(vertical: 4),
                            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
                            constraints: const BoxConstraints(maxWidth: 300),
                            decoration: BoxDecoration(
                              color: m['mine'] == true ? kAccent.withOpacity(.18) : kPanel,
                              borderRadius: BorderRadius.circular(12),
                              border: Border.all(color: kBorder),
                            ),
                            child: Text('${m['content']}', style: const TextStyle(color: kText)),
                          ),
                        ),
                    ],
                  ),
          ),
          SafeArea(
            top: false,
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 12, 12),
              child: Row(children: [
                Expanded(
                  child: TextField(
                    controller: _input,
                    decoration: const InputDecoration(hintText: '发消息…', isDense: true),
                    onSubmitted: (_) => _send(),
                  ),
                ),
                const SizedBox(width: 8),
                IconButton.filled(onPressed: _send, icon: const Icon(Icons.send), style: IconButton.styleFrom(backgroundColor: kAccent), color: const Color(0xFF1A1206)),
              ]),
            ),
          ),
        ],
      ),
    );
  }
}
