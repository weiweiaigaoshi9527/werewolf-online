import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../platform/export_file.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';
import '../i18n.dart';

/// 批量用户操作的请求体（纯函数，便于单测）。
///
/// op 必须是后端 `switch` **精确匹配**的字面量：`ban` / `admin` / `vip` / `gold` / `delete`。
/// 注意：网页版批量删除发的是 `"del"`，而后端只认 `"delete"`——本端必须发 `"delete"`。
/// value：ban/admin 取 0|1，vip 取等级 0-3，gold 取增减额（可负）；days 供 vip 使用。
Map<String, dynamic> batchUserBody(List<int> ids, String op, {int value = 0, int days = 30}) {
  return {
    'ids': ids,
    'op': op,
    'value': value,
    'days': days,
  };
}

/// 兑换码创建的请求体（纯函数，便于单测）。
///
/// 可选项为空（null 或空串）时**不下发该键**；gold/exp/itemDefId/maxUses 始终下发。
/// expireAt 必须是 ISO 本地日期时间（如 2026-12-31T23:59:00），后端用 `LocalDateTime.parse`
/// 解析；空串/null 表示永不过期。
Map<String, dynamic> redeemBody({
  String? code,
  required int gold,
  required int exp,
  required int itemDefId,
  required int maxUses,
  String? expireAt,
  String? note,
}) {
  return {
    if (code != null && code.isNotEmpty) 'code': code,
    'gold': gold,
    'exp': exp,
    'itemDefId': itemDefId,
    'maxUses': maxUses,
    if (expireAt != null && expireAt.isNotEmpty) 'expireAt': expireAt,
    if (note != null && note.isNotEmpty) 'note': note,
  };
}

/// 一键清理的请求体（纯函数，便于单测）。
///
/// 字段名逐字照抄后端 `CleanupBody` record，**不是**「房间最小存活时长」这类意译：
/// `rooms` / `accounts` / `roomIdleHours` / `accountMinAgeHours` / `accountMaxLevel` /
/// `accountMaxGames`，写错名字后端就当没传、静默按默认值走。
/// 阈值默认值也与后端一致：房间闲置 1 小时、账号注册满 72 小时、等级 ≤ 1、对局数 ≤ 0。
/// run 的语义：rooms 缺省即 true，accounts 缺省即 false；两者都为 false 会被 400 拒绝。
Map<String, dynamic> cleanupBody({
  bool rooms = true,
  bool accounts = false,
  int? roomIdleHours,
  int? accountMinAgeHours,
  int? accountMaxLevel,
  int? accountMaxGames,
}) =>
    {
      'rooms': rooms,
      'accounts': accounts,
      'roomIdleHours': roomIdleHours ?? 1,
      'accountMinAgeHours': accountMinAgeHours ?? 72,
      'accountMaxLevel': accountMaxLevel ?? 1,
      'accountMaxGames': accountMaxGames ?? 0,
    };

/// 清理阈值输入框的解析：空串/非数字/负数一律回落默认值（网页端 `clOptions` 的 `|| 默认`）。
int cleanupNum(String raw, int def) {
  final v = int.tryParse(raw.trim());
  return (v != null && v >= 0) ? v : def;
}

/// 把日期格式化成 `yyyy-MM-dd`（配合当日 23:59:59 拼出后端要的 ISO 本地日期时间）。
String isoDay(DateTime d) =>
    '${d.year.toString().padLeft(4, '0')}-${d.month.toString().padLeft(2, '0')}-${d.day.toString().padLeft(2, '0')}';

/// 后台管理（App 版）。对齐网页 admin.js 的主要操作，端点与字段逐字照抄后端。
///
/// 三条硬约束（都是探查后端源码时确认的）：
/// 1. **服务端没有任何二次确认参数**，restart/shutdown/delete/dissolve/end-all 都是收到即执行，
///    所以危险操作在客户端强制输入确认词。
/// 2. `/api/admin/local/**` 要求 socket 来源是 127.0.0.1 + `X-WW-Local: 1`，
///    手机/远程 App 必然拿不到，因此这里只用 `/api/admin/server/*` 与 `/api/admin/ops/*`。
/// 3. 网页版批量删除发的是 `op="del"`，而后端 switch 只认 `"delete"`（会静默不执行）——
///    这里发 `"delete"`。
class AdminScreen extends StatefulWidget {
  const AdminScreen({super.key, this.initialTab = 0});

  /// 0 概览 / 1 用户 / 2 房间 / 3 工单 / 4 兑换码 / 5 论坛 / 6 系统 / 7 语音·AI。
  /// 支持直达某一分节（例如从工单回复通知点进来直接落在工单页），也让测试可确定性进入指定页。
  final int initialTab;

  @override
  State<AdminScreen> createState() => _AdminScreenState();
}

class _AdminScreenState extends State<AdminScreen> with SingleTickerProviderStateMixin {
  /// 必须在 initState 里建：非管理员分支的 build 直接 return，压根不会碰这个控制器，
  /// 若写成 `late final ... = TabController(...)` 的懒初始化，就会在 dispose() 里
  /// 于销毁阶段现场创建 Ticker，抛 "Looking up a deactivated widget's ancestor is unsafe"。
  TabController? _tabs;

  @override
  void initState() {
    super.initState();
    _tabs = TabController(length: 8, vsync: this, initialIndex: widget.initialTab);
  }

  @override
  void dispose() {
    _tabs?.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final store = context.watch<AppStore>();
    if (store.user?['admin'] != true) {
      return Scaffold(
        body: Center(child: Tx('需要管理员权限', style: TextStyle(color: kDim))),
      );
    }
    return Scaffold(
      appBar: AppBar(
        title: const Tx('后台管理'),
        bottom: TabBar(
          controller: _tabs!,
          isScrollable: true,
          tabAlignment: TabAlignment.start,
          // Tab 的 text 是纯字符串、不经过 Tx，拿不到译文；用 child: Tx 才与其他页面一致。
          tabs: const [
            Tab(child: Tx('概览')),
            Tab(child: Tx('用户')),
            Tab(child: Tx('房间')),
            Tab(child: Tx('工单')),
            Tab(child: Tx('兑换码')),
            Tab(child: Tx('论坛')),
            Tab(child: Tx('系统')),
            Tab(child: Tx('语音·AI')),
          ],
        ),
      ),
      body: TabBarView(
        controller: _tabs!,
        children: const [
          _Overview(),
          _Users(),
          _Rooms(),
          _Tickets(),
          _Redeem(),
          _ForumOps(),
          _System(),
          _VoiceAi(),
        ],
      ),
    );
  }
}

/// 各分节的公共底座：拉一次列表 + 刷新按钮 + 统一的错误/空态。
abstract class _AdminSection extends StatefulWidget {
  const _AdminSection();
}

abstract class _AdminSectionState<T extends _AdminSection> extends State<T> {
  bool loading = true;
  String error = '';
  List<dynamic> rows = const [];

  String get listPath;

  @override
  void initState() {
    super.initState();
    load();
  }

  Future<void> load() async {
    if (mounted) setState(() => loading = true);
    try {
      final r = await context.read<AppStore>().api.getList(listPath);
      if (mounted) setState(() { rows = r; error = ''; });
    } on AppError catch (e) {
      if (mounted) setState(() => error = e.message);
    }
    if (mounted) setState(() => loading = false);
  }

  /// 管理动作统一走 POST，成功后刷新列表；失败显示服务端给的中文原因。
  Future<void> act(String path, [Object? body, String ok = '已完成']) async {
    final store = context.read<AppStore>();
    try {
      await store.api.post(path, body ?? {});
      if (!mounted) return;
      snack(context, ok);
      await load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  Future<bool> danger(String title, String detail, {String word = '确认执行'}) async {
    final c = TextEditingController();
    final ok = await showDialog<bool>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: kPanel,
        title: Tx('⚠️ $title'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Tx(detail, style: const TextStyle(height: 1.5)),
            const SizedBox(height: 12),
            Tx('服务端没有二次确认参数，收到即执行。请输入「$word」继续：',
                style: TextStyle(color: kBlood, fontSize: 12.5)),
            TextField(controller: c, decoration: InputDecoration(hintText: tr(word))),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Tx('取消')),
          FilledButton(
            onPressed: () => Navigator.pop(context, c.text.trim() == word),
            child: const Tx('继续'),
          ),
        ],
      ),
    );
    return ok == true;
  }

  Widget wrap(Widget child) => StateBox(
        loading: loading,
        empty: error.isEmpty && rows.isEmpty,
        emptyText: error.isEmpty ? '暂无数据' : error,
        child: child,
      );

  Widget refreshButton() => IconButton(onPressed: load, icon: const Icon(Icons.refresh));
}

// ---------------- 概览 ----------------

class _Overview extends _AdminSection {
  const _Overview();

  @override
  State<_Overview> createState() => _OverviewState();
}

class _OverviewState extends _AdminSectionState<_Overview> {
  Map<String, dynamic> _sys = const {};
  Map<String, dynamic> _online = const {};

  // ---- 一键清理（/api/admin/ops/cleanup/scan|run）----
  // 阈值输入框与网页端 clOptions() 一一对应：空/非法一律回落默认值（1 / 72 / 1 / 0）。
  final _clRoomHours = TextEditingController(text: '1');
  final _clAccHours = TextEditingController(text: '72');
  final _clAccLevel = TextEditingController(text: '1');
  final _clAccGames = TextEditingController(text: '0');
  bool _clBusy = false;
  Map<String, dynamic>? _clResult;

  @override
  void dispose() {
    _clRoomHours.dispose();
    _clAccHours.dispose();
    _clAccLevel.dispose();
    _clAccGames.dispose();
    super.dispose();
  }

  /// 把当前输入框与目标勾选拼成请求体（默认值/字段名的真值见 [cleanupBody]）。
  Map<String, dynamic> _clOptions({bool rooms = true, bool accounts = false}) => cleanupBody(
        rooms: rooms,
        accounts: accounts,
        roomIdleHours: cleanupNum(_clRoomHours.text, 1),
        accountMinAgeHours: cleanupNum(_clAccHours.text, 72),
        accountMaxLevel: cleanupNum(_clAccLevel.text, 1),
        accountMaxGames: cleanupNum(_clAccGames.text, 0),
      );

  /// 预览：后端 scan 恒扫描两类，rooms/accounts 只是透传，不影响结果。
  Future<void> _clScan(bool rooms, bool accounts) async {
    final api = context.read<AppStore>().api;
    setState(() => _clBusy = true);
    try {
      final d = await api
          .post('/api/admin/ops/cleanup/scan', _clOptions(rooms: rooms, accounts: accounts));
      if (mounted) setState(() => _clResult = d);
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _clBusy = false);
  }

  /// 执行：不可撤销，沿用本端「危险操作必须输入确认词」的约定。
  Future<void> _clRun(bool rooms, bool accounts) async {
    final what = [if (rooms) '无用房间', if (accounts) '无用账号'].join(' + ');
    final ok = await danger('一键清理',
        '确认清理【$what】？\n\n按当前阈值命中的房间/账号将被永久删除，且不可撤销。\n建议先「预览」核对清单。');
    if (!ok || !mounted) return;
    final api = context.read<AppStore>().api;
    setState(() => _clBusy = true);
    try {
      final d = await api
          .post('/api/admin/ops/cleanup/run', _clOptions(rooms: rooms, accounts: accounts));
      if (!mounted) return;
      setState(() => _clResult = d);
      final rd = (d['rooms'] as Map?)?['deleted'] ?? 0;
      final ad = (d['accounts'] as Map?)?['deleted'] ?? 0;
      snack(context, '已清理：房间 $rd 间、账号 $ad 个');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _clBusy = false);
  }

  /// 后端给的是 ISO 本地时间（2026-10-16T20:31:00），按网页端一样截到分钟。
  String _clTime(Object? v) {
    final s = '${v ?? ''}'.replaceFirst('T', ' ');
    return s.length > 16 ? s.substring(0, 16) : s;
  }

  Widget _clField(TextEditingController c, double width) => SizedBox(
        width: width,
        child: TextField(
          controller: c,
          keyboardType: TextInputType.number,
          inputFormatters: [FilteringTextInputFormatter.digitsOnly],
          style: const TextStyle(fontSize: 13),
          decoration: const InputDecoration(
            isDense: true,
            contentPadding: EdgeInsets.symmetric(horizontal: 8, vertical: 8),
            border: OutlineInputBorder(),
          ),
        ),
      );

  /// 命中的清单表（最多展示 100 行，与网页端 clTable 一致）。
  Widget _clTable(List<String> head, List<List<String>> rows) {
    if (rows.isEmpty) {
      return Tx('命中 0 条', style: TextStyle(color: kFaint, fontSize: 12));
    }
    return SingleChildScrollView(
      scrollDirection: Axis.horizontal,
      child: DataTable(
        headingRowHeight: 28,
        dataRowMinHeight: 24,
        dataRowMaxHeight: 30,
        columnSpacing: 16,
        horizontalMargin: 6,
        headingTextStyle: TextStyle(color: kDim, fontSize: 11.5, fontWeight: FontWeight.bold),
        dataTextStyle: TextStyle(color: kText, fontSize: 11.5),
        columns: [for (final h in head) DataColumn(label: Tx(h))],
        rows: [
          for (final r in rows)
            DataRow(cells: [
              for (final c in r)
                DataCell(Tx(c, maxLines: 1, overflow: TextOverflow.ellipsis)),
            ]),
        ],
      ),
    );
  }

  Widget _clResultView() {
    final d = _clResult;
    if (d == null) return const SizedBox.shrink();
    final rooms = (d['rooms'] as Map?) ?? const {};
    final accs = (d['accounts'] as Map?) ?? const {};
    final rItems = ((rooms['items'] as List?) ?? const []).cast<Map>();
    final aItems = ((accs['items'] as List?) ?? const []).cast<Map>();
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Tx('无用房间：命中 ${rooms['count'] ?? 0} 间'
            '${rooms['deleted'] != null ? '，已删除 ${rooms['deleted']} 间' : ''}',
            style: TextStyle(color: kText, fontSize: 13)),
        _clTable(
          const ['房间号', '状态', '房主', '人数', '类型', '创建'],
          [
            for (final r in rItems.take(100))
              [
                '${r['roomNo'] ?? ''}',
                '${r['status'] ?? ''}',
                '${r['hostName'] ?? ''}',
                '${r['playerCount'] ?? 0}',
                r['aiOnly'] == true ? 'AI 观战' : '普通',
                _clTime(r['createdAt']),
              ],
          ],
        ),
        const SizedBox(height: 10),
        Tx('无用账号：命中 ${accs['count'] ?? 0} 个'
            '${accs['deleted'] != null ? '，已删除 ${accs['deleted']} 个' : ''}',
            style: TextStyle(color: kText, fontSize: 13)),
        _clTable(
          const ['ID', '用户名', '昵称', '等级', '金币', '对局', '注册'],
          [
            for (final u in aItems.take(100))
              [
                '${u['id'] ?? ''}',
                '${u['username'] ?? ''}',
                '${u['nickname'] ?? ''}',
                '${u['level'] ?? 0}',
                '${u['gold'] ?? 0}',
                '${u['games'] ?? 0}',
                _clTime(u['createdAt']),
              ],
          ],
        ),
        if (rItems.length > 100 || aItems.length > 100)
          Tx('（仅显示前 100 条）', style: TextStyle(color: kFaint, fontSize: 11.5)),
      ],
    );
  }

  @override
  String get listPath => '/api/admin/ops/online';

  @override
  Future<void> load() async {
    setState(() => loading = true);
    final store = context.read<AppStore>();
    try {
      // get 与 getList 返回类型不同，混进同一个 Future.wait 会被推成 List<Object> 再赋值就编译不过
      final sys = await store.api.get('/api/admin/system');
      final onlineUsers = await store.api.getList('/api/admin/ops/online');
      _sys = sys;
      _online = {'users': onlineUsers};
      error = '';
    } on AppError catch (e) {
      error = e.message;
    }
    if (mounted) setState(() => loading = false);
  }

  @override
  Widget build(BuildContext context) {
    final online = (_online['users'] as List?) ?? const [];
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: StateBox(
        loading: loading,
        child: ListView(
          key: const Key('admin-overview'),
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(14),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Tx('服务 v${_sys['version'] ?? '?'} · ${_sys['serverTime'] ?? ''}',
                        style: TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 10),
                    Wrap(spacing: 18, runSpacing: 10, children: [
                      _kpi('在线', '${_sys['online'] ?? 0}'),
                      _kpi('房间', '${_sys['rooms'] ?? 0}'),
                      _kpi('进行中对局', '${_sys['activeGames'] ?? 0}'),
                      _kpi('注册用户', '${_sys['users'] ?? 0}'),
                    ]),
                  ],
                ),
              ),
            ),
            const SectionTitle('危险运维'),
            Tx('这些操作会直接影响正在游玩的真人，务必先确认。',
                style: TextStyle(color: kDim, fontSize: 12)),
            const SizedBox(height: 8),
            Wrap(spacing: 10, runSpacing: 10, children: [
              OutlinedButton.icon(
                onPressed: () async {
                  if (!await danger('结束全部对局', '将强制结束当前所有进行中的对局（现在 ${_sys['activeGames'] ?? 0} 局），所有玩家会被退回房间。')) return;
                  await act('/api/admin/ops/end-all', {}, '已结束全部对局');
                },
                icon: Icon(Icons.stop_circle_outlined, color: kBlood, size: 18),
                label: const Tx('结束全部对局'),
              ),
              OutlinedButton.icon(
                onPressed: () async {
                  if (!await danger('重启服务', '进程将 exit(86) 并依赖 start.bat 守护循环拉起；没有守护环境时等同于关机。')) return;
                  await act('/api/admin/server/restart', {}, '已发送重启指令');
                },
                icon: Icon(Icons.restart_alt, color: kMoon, size: 18),
                label: const Tx('重启服务'),
              ),
              OutlinedButton.icon(
                onPressed: () async {
                  if (!await danger('关闭服务', '进程 exit(0) 且不会自动拉起，全站立即下线。')) return;
                  await act('/api/admin/server/shutdown', {}, '已发送关机指令');
                },
                icon: Icon(Icons.power_settings_new, color: kBlood, size: 18),
                label: const Tx('关闭服务'),
              ),
            ]),
            const SectionTitle('一键清理'),
            Tx('清理「无用房间」与「无用账号」。建议先点「预览」核对清单，确认无误再执行删除（删除不可撤销）。',
                style: TextStyle(color: kDim, fontSize: 12)),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Tx('无用房间', style: TextStyle(color: kDim, fontSize: 12.5)),
                Tx('创建超过', style: TextStyle(color: kFaint, fontSize: 11.5)),
                _clField(_clRoomHours, 64),
                Tx('小时', style: TextStyle(color: kFaint, fontSize: 11.5)),
                OutlinedButton(
                    onPressed: _clBusy ? null : () => _clScan(true, false),
                    child: const Tx('预览房间')),
                OutlinedButton(
                    onPressed: _clBusy ? null : () => _clRun(true, false),
                    style: OutlinedButton.styleFrom(foregroundColor: kBlood),
                    child: const Tx('清理房间')),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(
              spacing: 8,
              runSpacing: 8,
              crossAxisAlignment: WrapCrossAlignment.center,
              children: [
                Tx('无用账号', style: TextStyle(color: kDim, fontSize: 12.5)),
                Tx('等级 ≤', style: TextStyle(color: kFaint, fontSize: 11.5)),
                _clField(_clAccLevel, 56),
                Tx('对局数 ≤', style: TextStyle(color: kFaint, fontSize: 11.5)),
                _clField(_clAccGames, 56),
                Tx('注册超过', style: TextStyle(color: kFaint, fontSize: 11.5)),
                _clField(_clAccHours, 64),
                Tx('小时', style: TextStyle(color: kFaint, fontSize: 11.5)),
                OutlinedButton(
                    onPressed: _clBusy ? null : () => _clScan(false, true),
                    child: const Tx('预览账号')),
                OutlinedButton(
                    onPressed: _clBusy ? null : () => _clRun(false, true),
                    style: OutlinedButton.styleFrom(foregroundColor: kBlood),
                    child: const Tx('清理账号')),
              ],
            ),
            const SizedBox(height: 8),
            Wrap(spacing: 10, runSpacing: 10, children: [
              OutlinedButton.icon(
                  onPressed: _clBusy ? null : () => _clScan(true, true),
                  icon: const Icon(Icons.search, size: 18),
                  label: const Tx('全部预览')),
              OutlinedButton.icon(
                  onPressed: _clBusy ? null : () => _clRun(true, true),
                  style: OutlinedButton.styleFrom(foregroundColor: kBlood),
                  icon: const Icon(Icons.cleaning_services_outlined, size: 18),
                  label: const Tx('一键清理（房间 + 账号）')),
            ]),
            if (_clBusy)
              const Padding(
                padding: EdgeInsets.symmetric(vertical: 8),
                child: LinearProgressIndicator(),
              ),
            _clResultView(),
            const SizedBox(height: 6),
            Tx('说明：管理员、被封禁、当前在线、以及等级/对局数超阈值的账号一律保留；自己不会被删除。',
                style: TextStyle(color: kFaint, fontSize: 11.5)),
            SectionTitle('在线玩家（${online.length}）'),
            for (final u in online)
              ListTile(
                dense: true,
                leading: const WwAvatar(avatarId: null, avatarUrl: null, size: 30),
                title: Tx('${u['nickname']} @${u['username']}', style: TextStyle(fontSize: 13.5, color: kText)),
                subtitle: Tx('${u['activity'] ?? ''}${(u['roomNo'] ?? '').toString().isEmpty ? '' : ' · 房号 ${u['roomNo']}'}',
                    style: TextStyle(color: kDim, fontSize: 12)),
                trailing: u['admin'] == true ? Tx('管理员', style: TextStyle(color: kMoon, fontSize: 11.5)) : null,
              ),
          ],
        ),
      ),
    );
  }

  Widget _kpi(String k, String v) => Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Tx(v, style: TextStyle(color: kAccent, fontSize: 20, fontWeight: FontWeight.bold)),
          Tx(k, style: TextStyle(color: kDim, fontSize: 12)),
        ],
      );
}

// ---------------- 用户 ----------------

class _Users extends _AdminSection {
  const _Users();

  @override
  State<_Users> createState() => _UsersState();
}

class _UsersState extends _AdminSectionState<_Users> {
  final _kw = TextEditingController();

  /// 批量选择模式：勾选多个用户后统一 POST /api/admin/users/batch。
  bool _selectMode = false;
  final Set<int> _selected = {};

  @override
  String get listPath => '/api/admin/users';

  Future<void> _search() async {
    final store = context.read<AppStore>();
    setState(() => loading = true);
    try {
      final q = _kw.text.trim();
      rows = await store.api.getList(q.isEmpty ? listPath : '$listPath?kw=${Uri.encodeQueryComponent(q)}');
      error = '';
    } on AppError catch (e) {
      error = e.message;
    }
    if (mounted) setState(() => loading = false);
  }

  Future<String?> _prompt(String title, {String hint = '', bool numeric = false, String? initial}) async {
    final c = TextEditingController(text: initial ?? '');
    final v = await showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: kPanel,
        title: Tx(title),
        content: TextField(controller: c, keyboardType: numeric ? const TextInputType.numberWithOptions(signed: true) : null, decoration: InputDecoration(hintText: tr(hint))),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Tx('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, c.text.trim()), child: const Tx('提交')),
        ],
      ),
    );
    return (v == null || v.isEmpty) ? null : v;
  }

  Future<void> _menu(Map u) async {
    final id = (u['id'] as num).toInt();
    final name = '${u['nickname']}';
    final me = context.read<AppStore>().user?['id'];
    final choice = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: kPanel,
      builder: (c) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            ListTile(title: Tx('$name · @$name', style: TextStyle(color: kMoon, fontWeight: FontWeight.bold))),
            if (id != me)
              ListTile(leading: const Icon(Icons.shield_outlined), title: Tx(u['banned'] == true ? '解除封禁' : '封禁（会踢线）'), onTap: () => Navigator.pop(c, 'ban')),
            if (id != me)
              ListTile(leading: const Icon(Icons.admin_panel_settings_outlined), title: Tx(u['admin'] == true ? '取消管理员' : '设为管理员'), onTap: () => Navigator.pop(c, 'admin')),
            ListTile(leading: const Icon(Icons.workspace_premium_outlined), title: const Tx('调 VIP（等级 0-3，天数 ≤0 为永久）'), onTap: () => Navigator.pop(c, 'vip')),
            ListTile(leading: const Icon(Icons.attach_money), title: const Tx('增减金币（可为负）'), onTap: () => Navigator.pop(c, 'gold')),
            ListTile(leading: const Icon(Icons.edit_outlined), title: const Tx('编辑资料（昵称/等级/金币）'), onTap: () => Navigator.pop(c, 'edit')),
            ListTile(leading: const Icon(Icons.password_outlined), title: const Tx('重置密码'), onTap: () => Navigator.pop(c, 'pwd')),
            ListTile(leading: const Icon(Icons.login_outlined), title: const Tx('以该身份登录（复制免登链接）'), onTap: () => Navigator.pop(c, 'imp')),
            if (id != me)
              ListTile(leading: Icon(Icons.delete_forever_outlined, color: kBlood), title: Tx('删除账号（级联物理删除）', style: TextStyle(color: kBlood)), onTap: () => Navigator.pop(c, 'del')),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;
    switch (choice) {
      case 'ban':
        await act('/api/admin/users/$id/ban', {'value': !(u['banned'] == true)}, u['banned'] == true ? '已解除封禁' : '已封禁');
      case 'admin':
        await act('/api/admin/users/$id/admin', {'value': !(u['admin'] == true)}, '权限已更新');
      case 'vip':
        final lv = await _prompt('VIP 等级（0=取消，1-3）', hint: '0-3', numeric: true, initial: '${u['vip'] ?? 0}');
        if (lv == null) return;
        final days = await _prompt('天数（填 0 表示永久）', hint: '如 30', numeric: true, initial: '30');
        if (days == null) return;
        await act('/api/admin/users/$id/vip', {'level': int.tryParse(lv) ?? 0, 'days': int.tryParse(days) ?? 0}, 'VIP 已更新');
      case 'gold':
        final d = await _prompt('金币增减量（可为负）', hint: '如 1000 或 -500', numeric: true);
        if (d == null) return;
        await act('/api/admin/users/$id/gold', {'delta': int.tryParse(d) ?? 0}, '金币已调整');
      case 'edit':
        // 列表响应里不含 exp，编辑表单若把 exp 当 0 提交会误清零 —— 这里干脆不下发 exp。
        final nick = await _prompt('昵称（留空不改，服务端截断 16 字）', initial: '${u['nickname']}');
        final body = <String, dynamic>{if (nick != null && nick.isNotEmpty) 'nickname': nick};
        final lv = await _prompt('等级（留空不改）', numeric: true);
        if (lv != null) body['level'] = int.tryParse(lv);
        final gold = await _prompt('金币（留空不改）', numeric: true);
        if (gold != null) body['gold'] = int.tryParse(gold);
        if (body.isEmpty) return;
        await act('/api/admin/users/$id/edit', body, '资料已更新');
      case 'pwd':
        final p = await _prompt('新密码', hint: '至少 6 位');
        if (p == null) return;
        await act('/api/admin/users/$id/password', {'password': p}, '密码已重置');
      case 'imp':
        final store = context.read<AppStore>();
        try {
          final r = await store.api.post('/api/admin/users/$id/impersonate');
          final link = '${store.config.baseUrl}/?t=${r['token']}';
          await Clipboard.setData(ClipboardData(text: link));
          if (mounted) snack(context, '免登链接已复制（${r['nickname']}）');
        } on AppError catch (e) {
          if (mounted) snack(context, e.message, error: true);
        }
      case 'del':
        if (!await danger('删除账号 ${u['nickname']}', '级联物理删除：战绩/好友/私聊/流水/背包/工单/兑换记录全部清除，且撤销其会话。不可恢复。', word: '删除')) return;
        await act('/api/admin/users/$id/delete', {}, '账号已删除');
    }
  }

  /// 导出全部用户为 CSV：后端直接回原始字节（UTF-8 BOM）。
  /// 落盘细节交给 platform/export_file.dart：桌面/移动写文件（file_picker 的 saveFile +
  /// IO 版兜底写），Web 退化成触发浏览器下载。返回 null 表示用户取消。
  Future<void> _exportCsv() async {
    final store = context.read<AppStore>();
    try {
      final bytes = await store.api.getBytes('/api/admin/ops/export/users');
      final saved = await saveExportFile(
        dialogTitle: '导出用户 CSV',
        fileName: 'users.csv',
        allowedExtensions: const ['csv'],
        bytes: Uint8List.fromList(bytes),
      );
      if (saved == null) return; // 用户取消
      if (mounted) snack(context, '已导出用户 CSV：$saved');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    } catch (e) {
      if (mounted) snack(context, '导出失败：$e', error: true);
    }
  }

  /// 统一提交批量操作，并用 snack 汇报「已完成 done，跳过 skipped」。
  Future<void> _batchSend(String op, {int value = 0, int days = 30}) async {
    final ids = _selected.toList();
    if (ids.isEmpty) {
      snack(context, '请先勾选要操作的用户', error: true);
      return;
    }
    final store = context.read<AppStore>();
    try {
      final r = await store.api.post('/api/admin/users/batch', batchUserBody(ids, op, value: value, days: days));
      if (!mounted) return;
      setState(() {
        _selected.clear();
        _selectMode = false;
      });
      snack(context, '已完成 ${r['done'] ?? 0}，跳过 ${r['skipped'] ?? 0}');
      await load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  /// 批量操作菜单：ban/admin 用 value 0/1 区分封禁与解封、设权与撤权；vip 取等级+天数；
  /// gold 取增减额；delete 走 danger 二次确认（确认词「删除」），op 字面量固定为 "delete"。
  Future<void> _batchMenu() async {
    if (_selected.isEmpty) {
      snack(context, '请先勾选要操作的用户', error: true);
      return;
    }
    final choice = await showModalBottomSheet<String>(
      context: context,
      backgroundColor: kPanel,
      builder: (c) => SafeArea(
        child: ListView(
          shrinkWrap: true,
          children: [
            ListTile(
              title: Tx('已选 ${_selected.length} 人（单次上限 500）',
                  style: TextStyle(color: kMoon, fontWeight: FontWeight.bold)),
            ),
            ListTile(leading: const Icon(Icons.shield_outlined), title: const Tx('封禁'), onTap: () => Navigator.pop(c, 'ban')),
            ListTile(leading: const Icon(Icons.shield_moon_outlined), title: const Tx('解除封禁'), onTap: () => Navigator.pop(c, 'unban')),
            ListTile(leading: const Icon(Icons.admin_panel_settings_outlined), title: const Tx('设为管理员'), onTap: () => Navigator.pop(c, 'admin')),
            ListTile(leading: const Icon(Icons.remove_moderator_outlined), title: const Tx('取消管理员'), onTap: () => Navigator.pop(c, 'unadmin')),
            ListTile(leading: const Icon(Icons.workspace_premium_outlined), title: const Tx('调 VIP（等级 0-3）'), onTap: () => Navigator.pop(c, 'vip')),
            ListTile(leading: const Icon(Icons.attach_money), title: const Tx('增减金币（可为负）'), onTap: () => Navigator.pop(c, 'gold')),
            ListTile(
              leading: Icon(Icons.delete_forever_outlined, color: kBlood),
              title: Tx('删除（级联物理删除）', style: TextStyle(color: kBlood)),
              onTap: () => Navigator.pop(c, 'delete'),
            ),
          ],
        ),
      ),
    );
    if (choice == null || !mounted) return;
    switch (choice) {
      case 'ban':
        await _batchSend('ban', value: 1);
      case 'unban':
        await _batchSend('ban', value: 0);
      case 'admin':
        await _batchSend('admin', value: 1);
      case 'unadmin':
        await _batchSend('admin', value: 0);
      case 'vip':
        final lv = await _prompt('VIP 等级（0=取消，1-3）', hint: '0-3', numeric: true, initial: '1');
        if (lv == null) return;
        final days = await _prompt('天数（填 0 表示永久）', hint: '如 30', numeric: true, initial: '30');
        if (days == null) return;
        await _batchSend('vip', value: int.tryParse(lv) ?? 0, days: int.tryParse(days) ?? 30);
      case 'gold':
        final d = await _prompt('每人金币增减量（可为负）', hint: '如 1000 或 -500', numeric: true);
        if (d == null) return;
        await _batchSend('gold', value: int.tryParse(d) ?? 0);
      case 'delete':
        if (!await danger('批量删除 ${_selected.length} 个账号',
            '级联物理删除：战绩/好友/私聊/流水/背包/工单/兑换记录全部清除，且撤销其会话。不可恢复。', word: '删除')) return;
        await _batchSend('delete');
    }
  }

  /// 单个用户条目：批量模式下显示勾选框，普通模式下点击弹出操作菜单。
  Widget _userTile(Map u) {
    final id = (u['id'] as num).toInt();
    final checked = _selected.contains(id);
    void toggle() => setState(() => checked ? _selected.remove(id) : _selected.add(id));
    return ListTile(
      dense: true,
      leading: _selectMode
          ? Checkbox(value: checked, activeColor: kAccent, onChanged: (_) => toggle())
          : null,
      title: Tx('${u['nickname']}  @${u['username']}',
          maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13.5, color: kText)),
      subtitle: Tx(
        'Lv.${u['level']} · 💰${u['gold']} · VIP${u['vip'] ?? 0}'
        '${u['admin'] == true ? ' · 管理员' : ''}${u['banned'] == true ? ' · 已封禁' : ''}${u['online'] == true ? ' · 在线' : ''}',
        style: TextStyle(fontSize: 11.5, color: u['banned'] == true ? kBlood : kDim),
      ),
      trailing: _selectMode ? null : Icon(Icons.more_vert, color: kDim),
      onTap: _selectMode ? toggle : () => _menu(u),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        actions: [
          IconButton(tooltip: tr('导出 CSV'), onPressed: _exportCsv, icon: const Icon(Icons.file_download_outlined)),
          IconButton(
            tooltip: _selectMode ? tr('退出批量') : tr('批量选择'),
            onPressed: () => setState(() {
              _selectMode = !_selectMode;
              if (!_selectMode) _selected.clear();
            }),
            icon: Icon(_selectMode ? Icons.close : Icons.checklist),
          ),
          refreshButton(),
        ],
        bottom: PreferredSize(
          preferredSize: const Size.fromHeight(56),
          child: Padding(
            padding: const EdgeInsets.fromLTRB(12, 0, 12, 10),
            child: TextField(
              controller: _kw,
              onSubmitted: (_) => _search(),
              decoration: InputDecoration(
                hintText: tr('搜索用户名或昵称'),
                isDense: true,
                prefixIcon: const Icon(Icons.search, size: 18),
                suffixIcon: IconButton(onPressed: _search, icon: const Icon(Icons.arrow_forward)),
              ),
            ),
          ),
        ),
      ),
      bottomNavigationBar: _selectMode
          ? SafeArea(
              child: Padding(
                padding: const EdgeInsets.fromLTRB(14, 6, 14, 8),
                child: Row(
                  children: [
                    Expanded(
                      child: Tx('已选 ${_selected.length} / ${rows.length} 人',
                          style: TextStyle(color: kMoon, fontSize: 13)),
                    ),
                    FilledButton.icon(
                      onPressed: _selected.isEmpty ? null : _batchMenu,
                      icon: const Icon(Icons.playlist_add_check, size: 18),
                      label: const Tx('批量操作'),
                    ),
                  ],
                ),
              ),
            )
          : null,
      body: wrap(ListView(
        children: [
          for (final r in rows) _userTile(r as Map),
        ],
      )),
    );
  }
}

// ---------------- 房间 ----------------

class _Rooms extends _AdminSection {
  const _Rooms();

  @override
  State<_Rooms> createState() => _RoomsState();
}

class _RoomsState extends _AdminSectionState<_Rooms> {
  @override
  String get listPath => '/api/admin/rooms';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: wrap(ListView(
        children: [
          for (final r in rows)
            ListTile(
              dense: true,
              title: Tx('房 ${r['roomNo']} · ${r['hostName'] ?? ''}', style: TextStyle(fontSize: 13.5, color: kText)),
              subtitle: Tx(
                '${r['status'] == 'PLAYING' ? '对局中' : '等待开局'} · ${r['playerCount'] ?? 0}/${r['maxSeats'] ?? 0} 人 · 在线 ${r['onlineCount'] ?? 0}'
                '${r['boardName'] == null ? '' : ' · ${r['boardName']}'}',
                style: TextStyle(fontSize: 11.5, color: r['status'] == 'PLAYING' ? kMoon : kDim),
              ),
              trailing: PopupMenuButton<String>(
                icon: Icon(Icons.more_vert, color: kDim, size: 18),
                onSelected: (v) async {
                  final id = (r['id'] as num).toInt();
                  if (v == 'end') {
                    if (!await danger('强制结束对局', '房 ${r['roomNo']} 的对局会被立即中断，玩家退回等待态。')) return;
                    await act('/api/admin/rooms/$id/end', {}, '对局已结束');
                  } else if (v == 'dissolve') {
                    if (!await danger('解散房间', '先强制结束对局，再删除座位与房间，不可恢复。', word: '解散')) return;
                    await act('/api/admin/rooms/$id/dissolve', {}, '房间已解散');
                  }
                },
                itemBuilder: (_) => const [
                  PopupMenuItem(value: 'end', child: Tx('强制结束对局')),
                  PopupMenuItem(value: 'dissolve', child: Tx('解散房间')),
                ],
              ),
            ),
        ],
      )),
    );
  }
}

// ---------------- 工单 ----------------

class _Tickets extends _AdminSection {
  const _Tickets();

  @override
  State<_Tickets> createState() => _TicketsState();
}

class _TicketsState extends _AdminSectionState<_Tickets> {
  static const _statuses = ['OPEN', 'PROCESSING', 'REPLIED', 'CLOSED', 'ALL'];
  String _status = 'OPEN';

  @override
  String get listPath => '/api/admin/tickets?status=$_status';

  static const _label = {'OPEN': '待处理', 'PROCESSING': '处理中', 'REPLIED': '已回复', 'CLOSED': '已关闭'};

  Future<void> _reply(Map t) async {
    final c = TextEditingController();
    var st = 'REPLIED';
    final ok = await showDialog<bool>(
      context: context,
      builder: (cc) => StatefulBuilder(
        builder: (c2, setLocal) => AlertDialog(
          backgroundColor: kPanel,
          title: Tx('回复工单 #${t['id']}'),
          content: SizedBox(
            width: 420,
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Tx('${t['title']}\n${t['content']}', style: TextStyle(fontSize: 12.5, color: kDim, height: 1.5)),
                const SizedBox(height: 10),
                TextField(controller: c, maxLines: 4, decoration: InputDecoration(hintText: tr('回复内容'))),
                DropdownButton<String>(
                  isExpanded: true,
                  value: st,
                  dropdownColor: kPanel2,
                  items: [for (final s in _label.keys) DropdownMenuItem(value: s, child: Tx(_label[s]!))],
                  onChanged: (v) => setLocal(() => st = v ?? st),
                ),
              ],
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(cc, false), child: const Tx('取消')),
            FilledButton(onPressed: () => Navigator.pop(cc, true), child: const Tx('发送')),
          ],
        ),
      ),
    );
    if (ok != true || c.text.trim().isEmpty) return;
    await act('/api/admin/tickets/${(t['id'] as num).toInt()}/reply', {'content': c.text.trim(), 'status': st}, '已回复并通知举报人');
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: Column(
        children: [
          SingleChildScrollView(
            scrollDirection: Axis.horizontal,
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 6),
            child: Row(
              children: [
                for (final s in _statuses)
                  Padding(
                    padding: const EdgeInsets.only(right: 8),
                    child: ChoiceChip(
                      label: Tx(s == 'ALL' ? '全部' : _label[s] ?? s),
                      selected: _status == s,
                      onSelected: (_) {
                        setState(() => _status = s);
                        load();
                      },
                    ),
                  ),
              ],
            ),
          ),
          Expanded(
            child: wrap(ListView(
              children: [
                for (final t in rows)
                  Card(
                    margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
                    child: ListTile(
                      dense: true,
                      title: Tx('#${t['id']} ${t['title']}', maxLines: 1, overflow: TextOverflow.ellipsis, style: TextStyle(fontSize: 13.5, color: kMoon)),
                      subtitle: Tx('${t['category']} · ${t['reporter']} · ${_label[t['status']] ?? t['status']} · ${t['replies'] ?? 0} 条回复\n${t['content']}',
                          maxLines: 3, style: TextStyle(fontSize: 11.5, color: kDim, height: 1.45)),
                      isThreeLine: true,
                      trailing: TextButton(onPressed: () => _reply(t as Map), child: const Tx('回复')),
                    ),
                  ),
              ],
            )),
          ),
        ],
      ),
    );
  }
}

// ---------------- 兑换码 ----------------

class _Redeem extends _AdminSection {
  const _Redeem();

  @override
  State<_Redeem> createState() => _RedeemState();
}

class _RedeemState extends _AdminSectionState<_Redeem> {
  @override
  String get listPath => '/api/admin/redeem';

  Future<void> _create() async {
    final code = TextEditingController();
    final gold = TextEditingController(text: '100');
    final exp = TextEditingController(text: '0');
    final item = TextEditingController(text: '0');
    final uses = TextEditingController(text: '1');
    final note = TextEditingController();
    DateTime? expire; // 选中的过期日（当日 23:59:59 失效），null 表示永不过期
    final ok = await showDialog<bool>(
      context: context,
      builder: (dialogCtx) => StatefulBuilder(
        builder: (_, setLocal) => AlertDialog(
          backgroundColor: kPanel,
          title: const Tx('新建兑换码'),
          content: SizedBox(
            width: 400,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  TextField(controller: code, decoration: InputDecoration(labelText: tr('码（留空由服务端生成）'))),
                  TextField(controller: gold, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: tr('金币'))),
                  TextField(controller: exp, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: tr('经验'))),
                  TextField(controller: item, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: tr('道具ID（0=不发道具）'))),
                  TextField(controller: uses, keyboardType: TextInputType.number, decoration: InputDecoration(labelText: tr('可用次数'))),
                  ListTile(
                    contentPadding: EdgeInsets.zero,
                    title: Tx(expire == null ? '过期时间：永不过期' : '过期时间：${isoDay(expire!)} 23:59:59'),
                    subtitle: const Tx('留空即永久；选日期后当日 23:59:59 失效'),
                    trailing: Row(
                      mainAxisSize: MainAxisSize.min,
                      children: [
                        TextButton(
                          onPressed: () async {
                            final now = DateTime.now();
                            final picked = await showDatePicker(
                              context: dialogCtx,
                              initialDate: expire ?? now,
                              firstDate: now,
                              lastDate: DateTime(now.year + 5, 12, 31),
                            );
                            if (picked != null) setLocal(() => expire = picked);
                          },
                          child: const Tx('选择日期'),
                        ),
                        if (expire != null)
                          IconButton(onPressed: () => setLocal(() => expire = null), icon: const Icon(Icons.clear, size: 18)),
                      ],
                    ),
                  ),
                  TextField(controller: note, decoration: InputDecoration(labelText: tr('备注（可选）'))),
                ],
              ),
            ),
          ),
          actions: [
            TextButton(onPressed: () => Navigator.pop(context, false), child: const Tx('取消')),
            FilledButton(onPressed: () => Navigator.pop(context, true), child: const Tx('创建')),
          ],
        ),
      ),
    );
    if (ok != true) return;
    if (!mounted) return;
    final store = context.read<AppStore>();
    try {
      final r = await store.api.post('/api/admin/redeem', redeemBody(
        code: code.text.trim(),
        gold: int.tryParse(gold.text) ?? 0,
        exp: int.tryParse(exp.text) ?? 0,
        itemDefId: int.tryParse(item.text) ?? 0,
        maxUses: int.tryParse(uses.text) ?? 1,
        expireAt: expire == null ? null : '${isoDay(expire!)}T23:59:59',
        note: note.text.trim(),
      ));
      if (!mounted) return;
      snack(context, '已生成兑换码 ${r['code']}');
      await load();
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(actions: [
        IconButton(onPressed: _create, icon: const Icon(Icons.add)),
        refreshButton(),
      ]),
      body: wrap(ListView(
        children: [
          for (final r in rows)
            ListTile(
              dense: true,
              title: Tx('${r['code']}', style: TextStyle(color: r['active'] == true ? kMoon : kDim, fontWeight: FontWeight.bold, fontSize: 14)),
              subtitle: Tx(
                '💰${r['gold'] ?? 0} · 经验${r['exp'] ?? 0} · 已用 ${r['usedCount'] ?? 0}/${r['maxUses'] ?? 0}'
                '${(r['expireAt'] ?? '').toString().isEmpty ? ' · 不过期' : ' · 至 ${r['expireAt'].toString().substring(0, 10)}'}'
                '${(r['note'] ?? '').toString().isEmpty ? '' : ' · ${r['note']}'}',
                style: TextStyle(fontSize: 11.5, color: kDim),
              ),
              trailing: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  IconButton(
                    tooltip: r['active'] == true ? tr('停用') : tr('启用'),
                    icon: Icon(r['active'] == true ? Icons.toggle_on : Icons.toggle_off, color: r['active'] == true ? kGreen : kDim),
                    onPressed: () => act('/api/admin/redeem/${(r['id'] as num).toInt()}/active', {'value': !(r['active'] == true)}, '状态已更新'),
                  ),
                  IconButton(
                    tooltip: tr('删除'),
                    icon: Icon(Icons.delete_outline, color: kBlood),
                    onPressed: () async {
                      if (!await danger('删除兑换码 ${r['code']}', '已被领取的记录会一并失效。', word: '删除')) return;
                      await act('/api/admin/redeem/${(r['id'] as num).toInt()}/delete', {}, '已删除');
                    },
                  ),
                ],
              ),
            ),
        ],
      )),
    );
  }
}

// ---------------- 论坛管理 ----------------

class _ForumOps extends _AdminSection {
  const _ForumOps();

  @override
  State<_ForumOps> createState() => _ForumOpsState();
}

class _ForumOpsState extends _AdminSectionState<_ForumOps> {
  @override
  String get listPath => '/api/admin/ops/forum';

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: wrap(ListView(
        children: [
          for (final p in rows)
            Card(
              margin: const EdgeInsets.symmetric(horizontal: 4, vertical: 4),
              child: ListTile(
                dense: true,
                title: Tx('${p['pinned'] == true ? '📌 ' : ''}${p['title']}',
                    maxLines: 1, overflow: TextOverflow.ellipsis,
                    style: TextStyle(fontSize: 13.5, color: p['hidden'] == true ? kDim : kText, decoration: p['hidden'] == true ? TextDecoration.lineThrough : null)),
                subtitle: Tx('${p['category']} · ${p['author']} · ${p['replyCount'] ?? 0} 回复${p['hidden'] == true ? ' · 已隐藏' : ''}',
                    style: TextStyle(fontSize: 11.5, color: kDim)),
                trailing: Row(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    IconButton(
                      tooltip: p['pinned'] == true ? tr('取消置顶') : tr('置顶'),
                      icon: Icon(Icons.push_pin, size: 18, color: p['pinned'] == true ? kAccent : kDim),
                      onPressed: () => act('/api/forum/${(p['id'] as num).toInt()}/pin', {'value': !(p['pinned'] == true)}, '置顶状态已更新'),
                    ),
                    IconButton(
                      tooltip: p['hidden'] == true ? tr('取消隐藏') : tr('隐藏'),
                      icon: Icon(p['hidden'] == true ? Icons.visibility_off : Icons.visibility, size: 18, color: p['hidden'] == true ? kBlood : kDim),
                      onPressed: () => act('/api/forum/${(p['id'] as num).toInt()}/hide', {'value': !(p['hidden'] == true)}, '可见性已更新'),
                    ),
                    IconButton(
                      tooltip: tr('删除（连带全部回复）'),
                      icon: Icon(Icons.delete_outline, size: 18, color: kBlood),
                      onPressed: () async {
                        if (!await danger('删除帖子', '连同该帖全部回复一起删除，不可恢复。', word: '删除')) return;
                        await act('/api/forum/${(p['id'] as num).toInt()}/delete', {}, '帖子已删除');
                      },
                    ),
                  ],
                ),
              ),
            ),
        ],
      )),
    );
  }
}

// ---------------- 系统配置 ----------------

class _System extends _AdminSection {
  const _System();

  @override
  State<_System> createState() => _SystemState();
}

class _SystemState extends _AdminSectionState<_System> {
  /// 与网页 admin.js 的 FEAT_LIST 一致；features 端点返回的是 {flags:{id:bool}}。
  static const feats = [
    ('quickai', '快速 AI 房'), ('spectate', '观战'), ('shop', '商店'), ('friends', '好友'),
    ('forum', '论坛'), ('ranking', '排行榜'), ('checkin', '签到'), ('news', '更新动态'),
    ('tickets', '工单'), ('download', '下载中心'), ('guide', '新手引导'), ('voiceMode', '语音同传'),
    ('items', '功能道具'), ('invite', '邀请外链'), ('addai', '房主补 AI'),
  ];
  Map<String, dynamic> _flags = const {};
  int _maxHeight = 0;
  final _notice = TextEditingController();

  @override
  String get listPath => '/api/admin/system/features';

  @override
  Future<void> load() async {
    setState(() => loading = true);
    final store = context.read<AppStore>();
    try {
      final f = await store.api.get('/api/admin/system/features');
      _flags = (f['flags'] as Map?)?.cast<String, dynamic>() ?? const {};
      final d = await store.api.get('/api/admin/system/display');
      _maxHeight = (d['webMaxHeight'] as num?)?.toInt() ?? 0;
      final n = await store.api.get('/api/room/notice');
      _notice.text = '${n['message'] ?? ''}';
      error = '';
    } on AppError catch (e) {
      error = e.message;
    }
    if (mounted) setState(() => loading = false);
  }

  Future<void> _saveFlags(String id, bool on) async {
    final store = context.read<AppStore>();
    final next = Map<String, dynamic>.from(_flags)..[id] = on;
    try {
      final r = await store.api.post('/api/admin/system/features', {'flags': next});
      setState(() => _flags = (r['flags'] as Map?)?.cast<String, dynamic>() ?? next);
      // 客户端自己的门控缓存也要立刻同步，否则刚关掉的功能在本机还点得进去
      await store.loadFeatures();
      if (mounted) snack(context, '${on ? '已开启' : '已关闭'}：$id');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: StateBox(
        loading: loading,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
          children: [
            const SectionTitle('功能开关'),
            Tx('关闭后服务端会直接拒绝相关操作，三端客户端的入口也会同步隐藏。',
                style: TextStyle(color: kDim, fontSize: 12)),
            for (final f in feats)
              SwitchListTile(
                dense: true,
                contentPadding: EdgeInsets.zero,
                activeColor: kAccent,
                title: Tx('${f.$2}  (${f.$1})', style: const TextStyle(fontSize: 13.5)),
                value: _flags[f.$1] != false,
                onChanged: (v) => _saveFlags(f.$1, v),
              ),
            const SectionTitle('网页最大高度'),
            ListTile(
              contentPadding: EdgeInsets.zero,
              title: Tx('当前 ${_maxHeight == 0 ? '不限制' : '$_maxHeight%'}', style: TextStyle(color: kText, fontSize: 13.5)),
              subtitle: Tx('字段名叫 webMaxHeight，实际语义是「占屏高百分比」，0 表示不限制（服务端 clamp 0-100）',
                  style: TextStyle(color: kDim, fontSize: 11.5)),
              trailing: TextButton(
                onPressed: () async {
                  final store = context.read<AppStore>();
                  // await 之后不再依赖 context：提前把 messenger 取出来用
                  final sm = ScaffoldMessenger.of(context);
                  final v = (_maxHeight == 0) ? 90 : 0;
                  try {
                    final r = await store.api.post('/api/admin/system/display', {'webMaxHeight': v});
                    setState(() => _maxHeight = (r['webMaxHeight'] as num?)?.toInt() ?? v);
                    sm.showSnackBar(SnackBar(content: Tx('已设为 ${_maxHeight == 0 ? '不限制' : '$_maxHeight%'}')));
                  } on AppError catch (e) {
                    sm.showSnackBar(SnackBar(content: Tx(e.message), backgroundColor: kBlood.withOpacity(.92)));
                  }
                },
                child: Tx(_maxHeight == 0 ? '限制为 90%' : '改为不限制'),
              ),
            ),
            const SectionTitle('维护通知'),
            TextField(
              controller: _notice,
              maxLength: 120,
              decoration: InputDecoration(hintText: tr('留空并保存即为清除通知；非空默认展示 6 小时')),
            ),
            Row(
              children: [
                Expanded(
                  child: OutlinedButton(
                    onPressed: () async {
                      final store = context.read<AppStore>();
                      final sm = ScaffoldMessenger.of(context);
                      try {
                        final r = await store.api.post('/api/room/notice', {'message': _notice.text.trim()});
                        sm.showSnackBar(SnackBar(
                            content: Tx(r['cleared'] == true ? '已清除维护通知' : '已下发，触达 ${r['delivered'] ?? 0} 人')));
                        store.fetchMaintenance();
                      } on AppError catch (e) {
                        sm.showSnackBar(SnackBar(content: Tx(e.message), backgroundColor: kBlood.withOpacity(.92)));
                      }
                    },
                    child: Tx(_notice.text.trim().isEmpty ? '清除通知' : '下发通知'),
                  ),
                ),
              ],
            ),
          ],
        ),
      ),
    );
  }
}

// ---------------- 语音 / AI 参数 ----------------

/// 语音与 AI 配置。改错会让全站语音或 AI 发言直接不可用，所以：
/// - 只读展示健康状态，编辑一律走确认框；
/// - `POST /api/admin/voice` 的 body 是 **config 的部分 JSON 直接放顶层**（不包 config）；
/// - `POST /api/admin/ai/config` 没有"部分提交"的承诺，因此一律**回提完整对象**，
///   且**永远不带 apiKey**（服务端语义：不传即不覆盖已存密钥）。
class _VoiceAi extends _AdminSection {
  const _VoiceAi();

  @override
  State<_VoiceAi> createState() => _VoiceAiState();
}

class _VoiceAiState extends _AdminSectionState<_VoiceAi> {
  Map<String, dynamic> _cfg = const {};
  Map<String, dynamic> _health = const {};
  Map<String, dynamic> _ai = const {};

  @override
  String get listPath => '/api/admin/voice'; // 复用底座的路径标识，实际加载见 load()

  @override
  Future<void> load() async {
    setState(() => loading = true);
    final store = context.read<AppStore>();
    try {
      final v = await store.api.get('/api/admin/voice');
      _cfg = (v['config'] as Map?)?.cast<String, dynamic>() ?? const {};
      _health = (v['health'] as Map?)?.cast<String, dynamic>() ?? const {};
      _ai = (await store.api.get('/api/admin/ai/config')).cast<String, dynamic>();
      error = '';
    } on AppError catch (e) {
      error = e.message;
    }
    if (mounted) setState(() => loading = false);
  }

  Future<void> _saveVoice(String key, Object? value, String label) async {
    // 确认框 await 之后再用 context 会踩 async gap，这里先把 store 与 messenger 取出来
    final store = context.read<AppStore>();
    final sm = ScaffoldMessenger.of(context);
    if (!await danger('修改语音参数', '$label = $value\n\n错误的取值会让语音同传或 TTS 直接失效，保存后立即对全服生效。', word: '保存')) {
      return;
    }
    try {
      final r = await store.api.post('/api/admin/voice', {key: value});
      final cfg = (r['config'] as Map?)?.cast<String, dynamic>();
      final h = (r['health'] as Map?)?.cast<String, dynamic>();
      if (mounted) {
        setState(() {
          _cfg = cfg ?? {..._cfg, key: value};
          _health = h ?? _health;
        });
      }
      sm.showSnackBar(const SnackBar(content: Tx('语音参数已保存')));
    } on AppError catch (e) {
      sm.showSnackBar(SnackBar(content: Tx(e.message), backgroundColor: kBlood.withOpacity(.92)));
    }
  }

  Future<void> _saveAi(Map<String, dynamic> patch, String label) async {
    final store = context.read<AppStore>();
    final sm = ScaffoldMessenger.of(context);
    if (!await danger('修改 AI 配置', '$label\n\nAI 参数错误会导致所有托管/AI 玩家发言失败。', word: '保存')) {
      return;
    }
    // 回提完整对象（不带 apiKey）：该端点没承诺支持部分提交
    final body = <String, dynamic>{
      'baseUrl': _ai['baseUrl'],
      'model': _ai['model'],
      'depth': _ai['depth'],
      'temperature': _ai['temperature'],
      'timeoutSeconds': _ai['timeoutSeconds'],
      'maxRetries': _ai['maxRetries'],
      'concurrency': _ai['concurrency'],
      'tokenBudgetPerGame': _ai['tokenBudgetPerGame'],
      'enabled': _ai['enabled'],
      ...patch,
    }..removeWhere((k, v) => v == null);
    try {
      final r = await store.api.post('/api/admin/ai/config', body);
      if (mounted) setState(() => _ai = r.cast<String, dynamic>());
      sm.showSnackBar(const SnackBar(content: Tx('AI 配置已保存')));
    } on AppError catch (e) {
      sm.showSnackBar(SnackBar(content: Tx(e.message), backgroundColor: kBlood.withOpacity(.92)));
    }
  }

  Future<String?> _input(String title, String current, {bool numeric = false}) async {
    final c = TextEditingController(text: current);
    return showDialog<String>(
      context: context,
      builder: (_) => AlertDialog(
        backgroundColor: kPanel,
        title: Tx(title),
        content: TextField(
          controller: c,
          keyboardType: numeric ? const TextInputType.numberWithOptions(decimal: true) : null,
          decoration: const InputDecoration(isDense: true),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Tx('取消')),
          FilledButton(onPressed: () => Navigator.pop(context, c.text.trim()), child: const Tx('确定')),
        ],
      ),
    );
  }

  /// 编辑一个字段：numeric=true 时按 int/double 解析，避免把数字发成字符串。
  Future<void> _edit(String key, String label, {bool numeric = false, bool ai = false}) async {
    final cur = (ai ? _ai[key] : _cfg[key]);
    final v = await _input(label, '$cur', numeric: numeric);
    if (v == null || v.isEmpty) return;
    final parsed = numeric
        ? (int.tryParse(v) ?? double.tryParse(v) ?? v)
        : v;
    if (ai) {
      await _saveAi({key: parsed}, '$label → $parsed');
    } else {
      await _saveVoice(key, parsed, label);
    }
  }

  Widget _row(String label, dynamic value, {VoidCallback? onTap, bool boolValue = false}) => ListTile(
        dense: true,
        title: Tx(label, style: TextStyle(fontSize: 13, color: kText)),
        subtitle: onTap != null ? null : Tx('$value', style: TextStyle(fontSize: 12, color: kDim)),
        trailing: onTap == null
            ? (boolValue ? Icon(value == true ? Icons.check_circle : Icons.remove_circle, size: 18, color: value == true ? kGreen : kDim) : null)
            : Icon(Icons.edit_outlined, size: 16, color: kDim),
        onTap: onTap,
      );

  @override
  Widget build(BuildContext context) {
    final asr = _health['asr'] as Map?;
    final tts = _health['tts'] as Map?;
    return Scaffold(
      appBar: AppBar(actions: [refreshButton()]),
      body: StateBox(
        loading: loading,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(10, 8, 10, 24),
          children: [
            Card(
              child: Padding(
                padding: const EdgeInsets.all(12),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Tx('语音服务健康：${_health['enabled'] == true ? '已启用' : '未启用'}',
                        style: TextStyle(color: _health['enabled'] == true ? kGreen : kBlood, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 4),
                    Tx('ASR：${asr?['ok'] == true ? '正常' : '异常 ${asr?['reason'] ?? ''}'}',
                        style: TextStyle(fontSize: 12.5, color: kDim)),
                    Tx('TTS：${tts?['ok'] == true ? '正常' : '异常 ${tts?['reason'] ?? ''}'}',
                        style: TextStyle(fontSize: 12.5, color: kDim)),
                  ],
                ),
              ),
            ),
            const SectionTitle('语音参数'),
            _row('总开关 enabled', _cfg['enabled'], boolValue: true, onTap: () async {
              await _saveVoice('enabled', !(_cfg['enabled'] == true), '语音总开关 → ${!(_cfg['enabled'] == true)}');
            }),
            _row('允许房间开语音同传 allowVoiceMode', _cfg['allowVoiceMode'], boolValue: true, onTap: () async {
              await _saveVoice('allowVoiceMode', !(_cfg['allowVoiceMode'] == true), '允许语音同传 → ${!(_cfg['allowVoiceMode'] == true)}');
            }),
            _row('ASR 地址 asrUrl', _cfg['asrUrl'], onTap: () => _edit('asrUrl', 'ASR 地址')),
            _row('TTS 地址 ttsUrl', _cfg['ttsUrl'], onTap: () => _edit('ttsUrl', 'TTS 地址')),
            _row('采样率 sampleRate', _cfg['sampleRate'], onTap: () => _edit('sampleRate', '采样率', numeric: true)),
            _row('每字毫秒 msPerChar', _cfg['msPerChar'], onTap: () => _edit('msPerChar', '每字毫秒', numeric: true)),
            _row('单轮最多句数 maxSentencesPerTurn', _cfg['maxSentencesPerTurn'],
                onTap: () => _edit('maxSentencesPerTurn', '单轮最多句数', numeric: true)),
            _row('最长字数 maxSpeechChars', _cfg['maxSpeechChars'],
                onTap: () => _edit('maxSpeechChars', '最长字数', numeric: true)),
            _row('VAD 能量阈值 vadRmsThreshold', _cfg['vadRmsThreshold'],
                onTap: () => _edit('vadRmsThreshold', 'VAD 能量阈值', numeric: true)),
            _row('VAD 静音判定 ms vadSilenceMs', _cfg['vadSilenceMs'],
                onTap: () => _edit('vadSilenceMs', 'VAD 静音判定', numeric: true)),
            _row('VAD 最长语音 ms vadMaxSpeechMs', _cfg['vadMaxSpeechMs'],
                onTap: () => _edit('vadMaxSpeechMs', 'VAD 最长语音', numeric: true)),
            _row('VAD 最短语音 ms vadMinSpeechMs', _cfg['vadMinSpeechMs'],
                onTap: () => _edit('vadMinSpeechMs', 'VAD 最短语音', numeric: true)),
            _row('匿名代号数量 anonNames', (_cfg['anonNames'] as List?)?.length ?? 0,
                onTap: () async {
                  final list = (_cfg['anonNames'] as List?)?.cast<String>() ?? const [];
                  final v = await _input('匿名代号（逗号分隔）', list.join(','));
                  if (v == null) return;
                  await _saveVoice('anonNames', v.split(',').map((e) => e.trim()).where((e) => e.isNotEmpty).toList(), '匿名代号');
                }),
            _row('服务超时 serviceTimeoutSeconds', _cfg['serviceTimeoutSeconds'],
                onTap: () => _edit('serviceTimeoutSeconds', '服务超时秒数', numeric: true)),
            const SectionTitle('AI 配置'),
            _row('已启用 enabled', _ai['enabled'], boolValue: true,
                onTap: () => _saveAi({'enabled': !(_ai['enabled'] == true)}, 'AI 总开关 → ${!(_ai['enabled'] == true)}')),
            _row('接口地址 baseUrl', _ai['baseUrl'], onTap: () => _edit('baseUrl', 'AI 接口地址', ai: true)),
            _row('模型 model', _ai['model'], onTap: () => _edit('model', 'AI 模型名', ai: true)),
            _row('思考深度 depth', _ai['depth'], onTap: () => _edit('depth', '思考深度', numeric: true, ai: true)),
            _row('温度 temperature', _ai['temperature'], onTap: () => _edit('temperature', '温度', numeric: true, ai: true)),
            _row('超时 timeoutSeconds', _ai['timeoutSeconds'],
                onTap: () => _edit('timeoutSeconds', '超时秒数', numeric: true, ai: true)),
            _row('重试 maxRetries', _ai['maxRetries'], onTap: () => _edit('maxRetries', '重试次数', numeric: true, ai: true)),
            _row('并发 concurrency', _ai['concurrency'], onTap: () => _edit('concurrency', '并发数', numeric: true, ai: true)),
            _row('每局 token 预算 tokenBudgetPerGame', _ai['tokenBudgetPerGame'],
                onTap: () => _edit('tokenBudgetPerGame', '每局 token 预算', numeric: true, ai: true)),
            ListTile(
              dense: true,
              title: Tx('密钥 apiKey', style: TextStyle(fontSize: 13, color: kText)),
              subtitle: Tx(_ai['hasKey'] == true ? '已配置（本页面永不回传、也不显示明文）' : '未配置',
                  style: TextStyle(fontSize: 12, color: kDim)),
            ),
            const SectionTitle('连通性测试'),
            Padding(
              padding: const EdgeInsets.symmetric(horizontal: 4),
              child: Wrap(spacing: 10, children: [
                OutlinedButton.icon(
                  onPressed: _testing ? null : _testAi,
                  icon: const Icon(Icons.bolt_outlined, size: 18),
                  label: Tx(_testing ? '测试中…' : '测一次 AI 发言'),
                ),
              ]),
            ),
          ],
        ),
      ),
    );
  }

  bool _testing = false;

  Future<void> _testAi() async {
    setState(() => _testing = true);
    final store = context.read<AppStore>();
    final sm = ScaffoldMessenger.of(context);
    try {
      final r = await store.api.post('/api/admin/ai/test', {});
      sm.showSnackBar(SnackBar(content: Tx('AI 回复：${r['reply'] ?? '（空）'}'), duration: const Duration(seconds: 8)));
    } on AppError catch (e) {
      sm.showSnackBar(SnackBar(content: Tx('测试失败：${e.message}'), backgroundColor: kBlood.withOpacity(.92)));
    }
    if (mounted) setState(() => _testing = false);
  }
}
