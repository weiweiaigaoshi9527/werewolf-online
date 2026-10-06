import 'dart:async';
import 'package:flutter/foundation.dart';
import 'api.dart';
import 'config.dart';
import 'models.dart';
import 'sfx.dart';
import 'voice.dart';
import 'ws.dart';

/// 全局状态：鉴权、当前房间、当前对局视图；消费 WS 推送并驱动界面。
class AppStore extends ChangeNotifier {
  final ApiClient api;
  WsClient? _ws;
  StreamSubscription<Map<String, dynamic>>? _wsub;

  String? token;
  Map<String, dynamic>? user;
  Map<String, dynamic>? room; // room.state 视图
  Map<String, dynamic>? game; // game.state 视图（整条消息即视图）
  bool wsConnected = false;
  bool busy = false;
  String maintenance = ''; // 系统维护通知（底部横幅），与网页端一致

  // ---------- 语音通道 ----------
  VoiceClient? voice;
  final List<VoiceCaption> voiceCaptions = [];
  String? talkingName; // 当前正在发声的展示名（座位光环）
  void Function(String text)? onVoiceRelay; // relay 字幕回填到发言输入框

  void _ensureVoice(bool available, int mySeat) {
    if (available && token != null) {
      if (voice == null) {
        voice = VoiceClient(api.config, token!)
          ..onCaption = _handleCaption
          ..onSpeaking = (n) {
            talkingName = n;
            notifyListeners();
          }
          ..onDenied = (_) => notifyListeners();
        voice!.connect();
      }
      voice!.mySeat = mySeat;
    } else if (voice != null) {
      _closeVoice();
    }
  }

  void _handleCaption(VoiceCaption c) {
    voiceCaptions.add(c);
    while (voiceCaptions.length > 8) {
      voiceCaptions.removeAt(0);
    }
    if (c.mine && c.kind == 'relay') onVoiceRelay?.call(c.text);
    notifyListeners();
  }

  void _closeVoice() {
    try {
      voice?.close();
    } catch (_) {}
    voice = null;
    voiceCaptions.clear();
    talkingName = null;
  }

  Future<bool> startMic() async => voice?.startMic() ?? false;
  Future<void> stopMic() async => voice?.stopMic();
  bool get micRecording => voice?.recording ?? false;

  AppStore(this.api);

  ServerConfig get config => api.config;
  bool get serverConfigured => config.configured;

  Future<void> clearServer() async {
    logoutLocal();
    config.baseUrl = '';
    await config.save();
    notifyListeners();
  }

  bool get loggedIn => token != null && user != null;
  bool get inRoom => room != null && (room!['roomNo'] != null);
  bool get inGame => game != null && game!['phase'] != null;
  bool get isHost => user != null && room != null && room!['hostUserId'] == user!['id'];
  int get mySeat => (room?['players'] as List?)?.firstWhere(
        (p) => p['userId'] == user?['id'],
        orElse: () => {'seat': -1},
      )['seat'] ?? -1;

  Future<bool> login(String username, String password) async {
    final r = await api.post('/api/auth/login', {'username': username, 'password': password});
    return _afterAuth(r);
  }

  Future<bool> register(String username, String password, String nickname) async {
    final r = await api.post('/api/auth/register',
        {'username': username, 'password': password, 'nickname': nickname});
    return _afterAuth(r);
  }

  bool _afterAuth(Map<String, dynamic> r) {
    token = r['token'] as String?;
    api.token = token;
    user = r['user'] as Map<String, dynamic>?;
    ServerConfig.saveToken(token);
    _connectWs();
    fetchMaintenance();
    refreshUnread();
    loadFeatures();

    notifyListeners();
    return token != null;
  }

  /// 拉取当前生效的维护通知（登录后 / 恢复会话时）。
  Future<void> fetchMaintenance() async {
    try {
      final n = await api.get('/api/room/notice');
      final msg = n['message'];
      maintenance = msg is String ? msg : '';
      notifyListeners();
    } catch (_) {}
  }

  /// 重新拉取本人档案视图（金币/等级/装备等在商店、签到、VIP 变更后刷新顶部数据）。
  Future<void> refreshUser() async {
    if (token == null) return;
    try {
      user = await api.get('/api/auth/me');
      notifyListeners();
    } catch (_) {}
  }

  // ---------- 能力门控 ----------
  /// 管理员关闭的功能 id 集合。来自 GET /api/config/features 的 `{"disabled":[...]}`——
  /// 注意它是「被关闭 id 的数组」而不是布尔映射，**不在数组里就是启用**。
  /// 合法 id：quickai spectate shop friends forum ranking checkin news tickets
  /// download guide voiceMode duo items invite addai
  List<String> disabledFeatures = const [];

  bool feat(String id) => !disabledFeatures.contains(id);

  /// 该接口匿名可访问，登录前后各拉一次即可；拉不到就当作全开（不阻塞界面）。
  Future<void> loadFeatures() async {
    try {
      final v = await api.get('/api/config/features');
      final d = v['disabled'];
      disabledFeatures = d is List ? d.map((e) => '$e').toList() : const [];
      notifyListeners();
    } on AppError {
      disabledFeatures = const [];
    }
  }

  /// 兑换码：成功返回兑换后的金币余额，失败抛 AppError（服务端文案已很具体）。
  Future<int> redeem(String code) async {
    final r = await api.post('/api/redeem', {'code': code.trim()});
    await refreshUser();
    return (r['gold'] as num?)?.toInt() ?? 0;
  }

  /// 启动时若已有 token，恢复会话与当前房间/对局。
  Future<void> restore() async {
    if (token == null) return;
    api.token = token;
    try {
      user = await api.get('/api/auth/me');
      _connectWs();
      fetchMaintenance();
      refreshUnread();
      loadFeatures();

      final my = await api.get('/api/room/my');
      final r = my['room'];
      if (r is Map<String, dynamic> && r['roomNo'] != null) {
        room = r;
        if (r['status'] == 'PLAYING') {
          try {
            game = await api.get('/api/game/state');
          } catch (_) {}
        }
      }
      notifyListeners();
    } catch (_) {
      logoutLocal();
    }
  }

  void _connectWs() {
    _disconnectWs();
    if (token == null) return;
    _ws = WsClient(api.config, token!, (ok) {
      wsConnected = ok;
      notifyListeners();
    });
    _ws!.connect();
    _wsub = _ws!.messages.listen(handleWsEvent);
  }

  void _disconnectWs() {
    _wsub?.cancel();
    _wsub = null;
    _ws?.close();
    _ws = null;
    wsConnected = false;
  }

  /// 消费一条 WS 下行。公开是为了能直接用固定载荷做派发单测（不必真连服务端）。
  void handleWsEvent(Map<String, dynamic> m) {
    switch (m['type']) {
      case 'room.state':
        final r = m['room'];
        if (r is Map<String, dynamic> && r['roomNo'] != null) {
          room = r;
        } else {
          room = null;
          roomFeed.clear();
        }
        break;
      case 'room.left':
      case 'room.kicked':
        room = null;
        game = null;
        roomFeed.clear();
        gameChat.clear();
        _closeVoice();
        if (m['type'] == 'room.kicked') {
          _notify('${m['message'] ?? '你被移出房间'}', kind: 'warn');
        }
        break;
      case 'room.event': // 布告栏：补 AI、有人进房、房主更新模式等
        final msg = m['message'];
        if (msg is String && msg.isNotEmpty) _pushFeed(msg);
        break;
      case 'room.invite': // 好友发来的进房邀请，可一键加入
        final no = m['roomNo'];
        _notify('${m['fromName'] ?? '好友'} 邀请你加入房间 ${no ?? ''}',
            kind: 'invite', roomNo: no == null ? null : '$no');
        break;
      case 'game.state':
        game = m;
        _ensureVoice(m['voiceAvailable'] == true, (m['mySeat'] as num?)?.toInt() ?? -1);
        break;
      case 'game.ended':
        game = null; // 随后 room.state 会把房间复位到等待中
        gameChat.clear();
        _closeVoice();
        break;
      case 'game.chat':
        unawaited(sfx.play('msg'));
        gameChat.add(WwChatLine(
          name: '${m['name'] ?? ''}',
          text: '${m['text'] ?? ''}',
          seat: (m['seat'] as num?)?.toInt(),
          kind: 'chat',
        ));
        _cap(gameChat, 200);
        break;
      case 'narrator': // 旁白（夜晚结算等），只走 WS 不进 feed
        gameChat.add(WwChatLine(name: '${m['name'] ?? '旁白'}', text: '${m['text'] ?? ''}', kind: 'narrator'));
        _cap(gameChat, 200);
        break;
      case 'chat.msg': // 私聊：ChatScreen 按 peerId 过滤 livePrivate 实时追加
        livePrivate.add(m);
        _cap(livePrivate, 100);
        unreadMessages++;
        unawaited(sfx.play('msg'));
        _notify('${m['fromName'] ?? '好友'}：${m['content'] ?? ''}');
        break;
      case 'friend.request':
        unreadRequests++;
        _notify('${(m['from'] is Map ? (m['from'] as Map)['nickname'] : null) ?? '有人'} 请求添加你为好友');
        break;
      case 'friend.accepted':
        _notify('你的好友申请已通过');
        break;
      case 'ticket.update':
        _notify('工单 #${m['id'] ?? ''} 状态更新：${_ticketStatus('${m['status'] ?? ''}')}', kind: 'info');
        break;
      case 'welcome':
        final msg = m['message'];
        if (msg is String && msg.isNotEmpty) debugPrint('WS welcome: $msg');
        break;
      case 'system':
        _notify('${m['message'] ?? ''}', kind: 'warn', ttl: const Duration(seconds: 8));
        break;
      case 'maintenance':
        final msg = m['message'];
        maintenance = msg is String ? msg : '';
        break;
      case 'error':
        debugPrint('WS error: ${m['message']}');
        break;
    }
    notifyListeners();
  }

  static String _ticketStatus(String s) => switch (s) {
        'REPLIED' => '已回复',
        'PROCESSING' => '处理中',
        'CLOSED' => '已关闭',
        'OPEN' => '待处理',
        _ => s,
      };

  void _pushFeed(String message) {
    // 连续重复的布告（例如反复补 AI）折叠成「消息 ×N」，避免刷屏。
    // 注意别用 substring(message.length + 1) 去解析计数：两条完全相同时它会越界抛 RangeError。
    if (roomFeed.isNotEmpty) {
      final last = roomFeed.last;
      if (last == message) {
        roomFeed[roomFeed.length - 1] = '$message ×2';
        return;
      }
      if (last.startsWith('$message ×')) {
        final tail = int.tryParse(last.substring(message.length + 2));
        if (tail != null) {
          roomFeed[roomFeed.length - 1] = '$message ×${tail + 1}';
          return;
        }
      }
    }
    roomFeed.add(message);
    _cap(roomFeed, 60);
  }

  static void _cap<T>(List<T> list, int max) {
    while (list.length > max) {
      list.removeAt(0);
    }
  }

  // ---------- 实时通知与未读 ----------
  /// 对局音效（与网页版 SFX 同一套音），开关状态持久化。
  final Sfx sfx = Sfx();
  final List<WwNotice> notices = [];
  final List<String> roomFeed = [];
  final List<WwChatLine> gameChat = [];
  final List<Map<String, dynamic>> livePrivate = [];
  int unreadMessages = 0;
  int unreadRequests = 0;
  int _noticeSeq = 0;

  int get unreadTotal => unreadMessages + unreadRequests;

  void _notify(String text,
      {String kind = 'info', String? roomNo, Duration ttl = const Duration(milliseconds: 4500)}) {
    notices.add(WwNotice(++_noticeSeq, text, kind: kind, roomNo: roomNo, ttl: ttl));
    _cap(notices, 6); // 浮层最多叠 6 条，多了挡画面
  }

  void dismissNotice(int id) => notices.removeWhere((n) => n.id == id);

  /// 切换音效开关（与网页版 btn-sfx 同语义），并通知界面刷新图标。
  Future<bool> toggleSfx() async {
    final on = await sfx.toggle();
    if (on) unawaited(sfx.play('click')); // 开启时给一声即时反馈
    notifyListeners();
    return on;
  }

  /// 未读以服务端聚合为准（打开好友页、登录后各拉一次）。
  Future<void> refreshUnread() async {
    if (token == null) return;
    try {
      final u = await api.get('/api/friends/unread');
      unreadMessages = (u['messages'] as num?)?.toInt() ?? 0;
      unreadRequests = (u['requests'] as num?)?.toInt() ?? 0;
      notifyListeners();
    } on AppError {
      // 未读拉不到不影响主流程，保持旧值
    }
  }

  // ---------- 房间 / 对局动作（走 REST，服务端再经 WS 广播回来） ----------
  Future<void> _run(Future<void> Function() f) async {
    busy = true;
    notifyListeners();
    try {
      await f();
    } finally {
      busy = false;
      notifyListeners();
    }
  }

  Future<void> createRoom() => _run(() async => room = await api.post('/api/room/create'));
  Future<void> joinRoom(String no) => _run(() async => room = await api.post('/api/room/join', {'roomNo': no.trim()}));
  Future<void> addAi() => _run(() => api.post('/api/room/add-ai'));
  Future<void> setReady(bool ready) => _run(() => api.post('/api/room/ready', {'ready': ready}));
  Future<void> kickPlayer(int userId) => _run(() async => room = await api.post('/api/room/kick', {'userId': userId}));
  Future<void> transferHost(int userId) =>
      _run(() async => room = await api.post('/api/room/transfer', {'userId': userId}));
  Future<void> leaveRoom() => _run(() async {
        await api.post('/api/room/leave');
        room = null;
        game = null;
        _closeVoice();
      });
  Future<void> startGame() => _run(() => api.post('/api/game/start', {}));
  Future<void> submitAction(Map<String, dynamic> a) => _run(() => api.post('/api/game/action', a));
  Future<void> sendChat(String text) => _run(() => api.post('/api/game/chat', {'text': text}));
  Future<void> endGame() => _run(() => api.post('/api/game/end', {}));
  /// 模式开关。后端 ModeBody 是 4 个 **原始 boolean** 的 record，缺字段会被 Jackson 绑成
  /// false —— 只发 2 个字段等于每次切换都把 huntCity / duoMode 静默关掉（还会覆盖网页端房主的设置）。
  /// 因此未显式传入的项一律以当前房间视图回填。
  Future<void> setMode({bool? voiceMode, bool? anonymous, bool? huntCity, bool? duoMode}) =>
      _run(() async => room = await api.post('/api/room/mode', {
            'voiceMode': voiceMode ?? (room?['voiceMode'] == true),
            'anonymous': anonymous ?? (room?['anonymous'] == true),
            'huntCity': huntCity ?? (room?['huntCity'] == true),
            'duoMode': duoMode ?? (room?['duoMode'] == true),
          }));
  Future<void> setItemMatch(bool on) =>
      _run(() async => room = await api.post('/api/room/item', {'itemMatch': on}));

  // ---------- 双人结队 duo ----------
  // 房间视图里的 duos 是「座位号对」数组（1-based），而 duoInvite 服务端恒为 null，
  // 所以收到的邀请必须靠轮询 GET /api/room/duo/pending 拿（与网页版 pollDuoInvite 一致）。
  Future<void> inviteDuo(int targetUserId) =>
      _run(() => api.post('/api/room/duo/invite', {'targetUserId': targetUserId}));
  Future<void> acceptDuo(int fromUserId) =>
      _run(() async => room = await api.post('/api/room/duo/accept', {'fromUserId': fromUserId}));
  Future<void> cancelDuo() => _run(() async => room = await api.post('/api/room/duo/cancel', {}));

  /// 拉取发给我的组队邀请；无邀请或网络异常都返回 null（轮询用，不打扰界面）。
  Future<Map<String, dynamic>?> pollDuoInvite() async {
    if (token == null) return null;
    try {
      final v = await api.get('/api/room/duo/pending');
      final inv = v['invite'];
      return inv is Map<String, dynamic> ? inv : null;
    } on AppError {
      return null;
    }
  }

  /// 我所在的双人组对（座位号对里含我的座位时返回该对，否则 null）。
  List<int>? duoPairOfMine() {
    final my = mySeat;
    final duos = room?['duos'];
    if (my <= 0 || duos is! List) return null;
    for (final d in duos) {
      if (d is List && d.length == 2 && (d[0] == my || d[1] == my)) {
        return [(d[0] as num).toInt(), (d[1] as num).toInt()];
      }
    }
    return null;
  }
  Future<void> spectate(String no) => _run(() async {
        room = await api.post('/api/room/spectate', {'roomNo': no.trim()});
        if (room?['status'] == 'PLAYING') {
          try {
            game = await api.get('/api/game/state');
          } catch (_) {}
        }
      });

  /// 对局结束后返回房间：清掉对局视图，重新拉取房间状态（房间若已解散则回大厅）。与网页“返回房间”一致。
  Future<void> returnToRoom() => _run(() async {
        game = null;
        _closeVoice();
        final my = await api.get('/api/room/my');
        final r = my['room'];
        room = (r is Map<String, dynamic> && r['roomNo'] != null) ? r : null;
      });

  void logoutLocal() {
    _disconnectWs();
    _closeVoice();
    token = null;
    user = null;
    room = null;
    game = null;
    maintenance = '';
    api.token = null;
    ServerConfig.saveToken(null);
    notifyListeners();
  }

  Future<void> logout() async {
    try {
      await api.post('/api/auth/logout');
    } catch (_) {}
    logoutLocal();
  }

  @override
  void dispose() {
    try {
      sfx.dispose();
    } catch (_) {}
    _disconnectWs();
    _closeVoice();
    super.dispose();
  }
}
