import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'api.dart';
import 'config.dart';
import 'marks.dart' as mk;
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
  /// 管理员设置的展示配置：内容区最大高度百分比（0 = 不限）。
  /// 来自匿名可读的 GET /api/config/display，与网页端 --app-max-h 等价。
  int displayMaxHeight = 0;
  /// 语音服务是否就绪（GET /api/voice/status：开关打开且 ASR/TTS 都健康）。
  bool voiceServiceReady = false;
  /// 座位标记（本地、仅自己可见、按房间持久化）：座位号 → 等级 0..3（未标/可疑/好人/狼人）。
  Map<int, int> marks = const {};

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

  // ---------- 座位标记（本地、仅自己可见、按房间持久化） ----------
  /// 已载入标记的房间号；只有与目标房间号不同才重新读盘，避免每次 room.state
  /// 推送都刷一遍存储。房间为 null 时清空。
  String _marksRoom = '';

  Future<void> _syncMarks(String? roomNo) async {
    final want = roomNo ?? '';
    if (want == _marksRoom) return;
    _marksRoom = want;
    if (want.isEmpty) {
      marks = const {};
      notifyListeners();
      return;
    }
    try {
      final p = await SharedPreferences.getInstance();
      marks = mk.parseMarks(p.getString(mk.marksKey(want)));
    } catch (_) {
      marks = const {};
    }
    notifyListeners();
  }

  /// 循环推进某座位的标记并落盘（与网页版 MARK.cycle 同语义）。
  Future<void> cycleMark(int seat) async {
    marks = {...marks, seat: mk.cycleMark(marks[seat])};
    notifyListeners();
    await _saveMarks();
  }

  Future<void> clearMarks() async {
    marks = const {};
    notifyListeners();
    await _saveMarks();
  }

  Future<void> _saveMarks() async {
    if (_marksRoom.isEmpty) return;
    try {
      final p = await SharedPreferences.getInstance();
      final raw = mk.encodeMarks(marks);
      if (raw.isEmpty) {
        await p.remove(mk.marksKey(_marksRoom));
      } else {
        await p.setString(mk.marksKey(_marksRoom), raw);
      }
    } catch (_) {}
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
    loadDisplay();
    loadVoiceStatus();

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
  /// download guide voiceMode items invite addai aiShow
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

  /// 管理员设置的展示配置（匿名可读）。webMaxHeight 为内容区最大高度的百分比 0-100，
  /// 0 表示不限。与网页端 nav.js 的 --app-max-h 等价。
  Future<void> loadDisplay() async {
    try {
      final v = await api.get('/api/config/display');
      final h = (v['webMaxHeight'] as num?)?.toInt() ?? 0;
      displayMaxHeight = h.clamp(0, 100);
      notifyListeners();
    } on AppError {
      displayMaxHeight = 0;
    }
  }

  /// 语音服务就绪状态：开关打开且 ASR / TTS 都健康才认为可用。
  /// 网页端同样在进房前拉一次，未就绪时明确提示「语音服务未就绪」，而不是等用户点麦才报错。
  Future<void> loadVoiceStatus() async {
    try {
      final s = await api.get('/api/voice/status');
      final asr = ((s['health'] as Map?)?['asr'] as Map?)?['ok'] == true;
      final tts = ((s['health'] as Map?)?['tts'] as Map?)?['ok'] == true;
      voiceServiceReady = s['allowVoiceMode'] == true && asr && tts;
    } on AppError {
      voiceServiceReady = false;
    }
    notifyListeners();
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
      loadDisplay();
      loadVoiceStatus();

      final my = await api.get('/api/room/my');
      final r = my['room'];
      if (r is Map<String, dynamic> && r['roomNo'] != null) {
        room = r;
        await _syncMarks('${r['roomNo']}');
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
          wolfChat.clear();
        }
        unawaited(_syncMarks(room == null ? null : '${room!['roomNo']}'));
        break;
      case 'room.left':
      case 'room.kicked':
        room = null;
        game = null;
        roomFeed.clear();
        gameChat.clear();
        wolfChat.clear();
        unawaited(_syncMarks(null));
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
        wolfChat.clear();
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
      case 'game.wolfchat': // 狼队频道：消息只推给存活的狼，不写入对局记录
        wolfChat.add(WwChatLine(
          name: '${m['name'] ?? ''}',
          text: '${m['text'] ?? ''}',
          seat: (m['seat'] as num?)?.toInt(),
          kind: 'wolf',
        ));
        _cap(wolfChat, 200);
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
      case 'support.update': // 在线客服有新回复：不打扰用户，只给面板一个刷新信号
        supportTick++;
        break;
      case 'support.escalated': // 该事件只发给管理员：有人请求转人工
        _notify('有玩家请求人工客服（工单 #${m['ticketId'] ?? ''}）', kind: 'warn');
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
  /// 狼队夜间频道：只有存活的狼在夜晚收得到（服务端逐人推送，非狼根本收不到该类型）。
  final List<WwChatLine> wolfChat = [];
  final List<Map<String, dynamic>> livePrivate = [];
  /// 在线客服有新回复的信号：面板监听它决定是否立即重拉，避免无脑轮询打断输入。
  int supportTick = 0;
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
        unawaited(_syncMarks(null));
        _closeVoice();
      });
  Future<void> startGame() => _run(() => api.post('/api/game/start', {}));
  Future<void> submitAction(Map<String, dynamic> a) => _run(() => api.post('/api/game/action', a));
  Future<void> sendChat(String text) => _run(() => api.post('/api/game/chat', {'text': text}));
  /// 狼队夜间频道发言：服务端只向存活的狼转发，非狼调用会拿到明确错误文案。
  Future<void> sendWolfChat(String text) => _run(() => api.post('/api/game/wolf-chat', {'text': text}));
  /// AI 归纳本局对话（只总结「谁说了什么」，不分析局面）。
  Future<String> feedSummary() async {
    final r = await api.post('/api/game/feed-summary');
    return '${r['summary'] ?? ''}';
  }
  Future<void> endGame() => _run(() => api.post('/api/game/end', {}));
  /// 模式开关。后端 ModeBody 是 3 个 **原始 boolean** 的 record，缺字段会被 Jackson 绑成
  /// false —— 只发 1 个字段等于每次切换都把其它项静默关掉（还会覆盖网页端房主的设置）。
  /// 因此未显式传入的项一律以当前房间视图回填。
  Future<void> setMode({bool? voiceMode, bool? anonymous, bool? huntCity}) =>
      _run(() async => room = await api.post('/api/room/mode', {
            'voiceMode': voiceMode ?? (room?['voiceMode'] == true),
            'anonymous': anonymous ?? (room?['anonymous'] == true),
            'huntCity': huntCity ?? (room?['huntCity'] == true),
          }));
  Future<void> setItemMatch(bool on) =>
      _run(() async => room = await api.post('/api/room/item', {'itemMatch': on}));

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

  // ---------- 在线客服（对话存在工单里，AI 自动答复，可转人工） ----------
  /// 拉取我与在线客服的往来消息。thread 每条含 {content, ai, admin, author, at}；
  /// human = 已有人工接手，needsHuman = 正在排队转接。
  Future<({List<Map<String, dynamic>> thread, bool human, bool needsHuman})> supportThread() async {
    final r = await api.get('/api/tickets/support');
    final th = r['thread'];
    final list = th is List
        ? th.whereType<Map>().map((e) => e.cast<String, dynamic>()).toList()
        : <Map<String, dynamic>>[];
    return (
      thread: list,
      human: r['human'] == true,
      needsHuman: r['needsHuman'] == true,
    );
  }

  /// 向在线客服发一条消息（服务端 600ms 后异步生成 AI 答复并通过 support.update 通知）。
  Future<void> supportSend(String text) => _run(() => api.post('/api/tickets/support/message', {'content': text}));

  /// 转人工：写入承接话术、置为等待人工，并实时通知在线管理员。
  Future<void> supportToHuman() => _run(() => api.post('/api/tickets/support/human'));

  // ---------- 全 AI 队局（观战房） ----------
  /// 观战房可选板子（服务端白名单内的预设）。
  Future<List<Map<String, dynamic>>> aiShowBoards() async {
    final r = await api.get('/api/ai-show/boards');
    return _listOf(r['boards']);
  }

  /// 进行中的全 AI 观战房列表（有活跃对局或仍有人在看）。
  Future<List<Map<String, dynamic>>> aiShowList() async {
    final r = await api.get('/api/ai-show/list');
    return _listOf(r['shows']);
  }

  /// 创建一间全 AI 观战房并立即开局：自己不入座（作为观战者），因此直接进入对局视图。
  Future<void> createAiShow(String boardId) => _run(() async {
        final r = await api.post('/api/ai-show/create', {'boardId': boardId});
        final rm = r['room'];
        if (rm is Map<String, dynamic>) room = rm;
        await _enterWatchingGame();
      });

  /// 房主续开一局（当前局已结束时可用）。
  Future<void> aiShowRestart() => _run(() => api.post('/api/ai-show/restart'));

  /// 房主停止当前全 AI 对局（房间保留，可随时续开）。
  Future<void> aiShowStop() => _run(() => api.post('/api/ai-show/stop'));

  /// 观战/进入进行中的房间：补拉一次对局快照（服务端不会主动给观战者推 game.state 以外的全量）。
  Future<void> _enterWatchingGame() async {
    if (room?['status'] != 'PLAYING') return;
    try {
      game = await api.get('/api/game/state');
    } catch (_) {}
  }

  static List<Map<String, dynamic>> _listOf(Object? v) =>
      v is List ? v.whereType<Map>().map((e) => e.cast<String, dynamic>()).toList() : const [];

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
