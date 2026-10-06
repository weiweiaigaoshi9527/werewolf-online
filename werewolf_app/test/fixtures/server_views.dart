/// 真实服务端视图的手工快照（抓自 https://localhost:11111 的实际响应，字段名逐字对齐）。
///
/// 用途：让页面级测试与 golden 渲染**不依赖网络**也能跑——直接把服务端下发的 Map 塞进
/// AppStore，驱动 renderGame / renderRoom 那条真实路径。字段一旦和服务端对不上，
/// 契约测试（live_contract_test.dart）会先红，这里只是回放用的静态样本。
library;

Map<String, dynamic> roomView({
  String roomNo = '200728',
  int hostUserId = 2180,
  bool duoMode = false,
  int maxSeats = 24,
  List<List<int>>? duos,
  List<Map<String, dynamic>>? players,
}) =>
    {
      'roomNo': roomNo,
      'status': 'WAITING',
      'hostUserId': hostUserId,
      'maxSeats': maxSeats,
      'board': {'狼人': 2, '村民': 2, '预言家': 1, '女巫': 1},
      'boardName': '入门9人局',
      'players': players ??
          [
            _player(seat: 1, userId: 2180, nickname: '三端自测', host: true),
            _player(seat: 2, userId: -1000000016, nickname: '月影', ai: true, ready: true, online: true),
            _player(seat: 3, userId: -1000000017, nickname: '夜枭', ai: true, ready: true, online: true),
            _player(seat: 4, userId: -1000000018, nickname: '青梧', ai: true, ready: true, online: true),
            _player(seat: 5, userId: -1000000019, nickname: '白蘅', ai: true, ready: true, online: true),
            _player(seat: 6, userId: 3021, nickname: '阿尔法', ready: true, online: true),
          ],
      'voiceMode': false,
      'anonymous': false,
      'itemMatch': true,
      'huntCity': false,
      'duoMode': duoMode,
      // duos 是「座位号对」数组（1-based）；duoInvite 服务端恒为 null，邀请要轮询 GET /api/room/duo/pending
      'duos': duos ?? (duoMode ? [
            [1, 6]
          ] : <List<int>>[]),
      'duoInvite': null,
    };

Map<String, dynamic> _player({
  required int seat,
  required int userId,
  required String nickname,
  bool ai = false,
  bool host = false,
  bool ready = false,
  bool online = false,
}) =>
    {
      'seat': seat,
      'userId': userId,
      'nickname': nickname,
      'avatarId': seat % 8 + 1,
      'level': ai ? 1 : 12,
      'ready': ready,
      'online': online,
      'host': host,
      'ai': ai,
      'avatarUrl': null,
      'nickColor': null,
      'frameColor': null,
      'title': null,
      'vip': 0,
    };

/// 白天发言阶段的 game.state（服务端按座位定制：只有本人能看到自己的 role）。
Map<String, dynamic> gameStateDay({int mySeat = 1, int roomNo = 200728}) => {
      'type': 'game.state',
      'phase': 'DAY_SPEECH',
      'day': 2,
      'roomNo': roomNo,
      'seats': [
        _seat(1, alive: true, role: '预言家', seerChecks: {3: true}),
        _seat(2, alive: true, role: null),
        _seat(3, alive: false, deathDay: 1),
        _seat(4, alive: true, isSheriff: true),
        _seat(5, alive: true),
        _seat(6, alive: true),
      ],
      'feed': [
        {'day': 1, 'type': 'NIGHT_ACTION', 'actor': 2, 'target': 3, 'detail': '狼人击杀 3号'},
        {'day': 1, 'type': 'SPEECH', 'actor': 1, 'target': null, 'detail': '昨晚查验 3号，是狼人'},
        {'day': 1, 'type': 'VOTE_DETAIL', 'actor': null, 'target': 3, 'detail': '票型 3→3, 4→3, 5→3'},
        {'day': 1, 'type': 'PLAYER_DIED', 'actor': 3, 'target': null, 'detail': '投票放逐'},
      ],
      'mySeat': mySeat,
      'myTurn': true,
      'actionKind': 'SPEECH',
      'currentActor': mySeat,
      'sheffSeat': 4,
      'pkCandidates': [2, 5],
      'anonymous': false,
      'voiceMode': false,
      'voiceAvailable': true,
      'myInfo': {
        'role': '预言家',
        'faction': 'GOD',
        'seerChecks': {'3': true},
        'witchSaveAvailable': false,
        'witchPoisonAvailable': false,
        'killedTonight': null,
        'guardLastTarget': null,
        'itemReveal': false,
        'itemDoubleVote': false,
        'itemImmune': false,
        'partner': null,
      },
      'deadlineMs': 45000,
      'spectator': false,
      'hostUserId': 2180,
      'gameId': 137,
    };

Map<String, dynamic> _seat(int seat,
        {bool alive = true,
        String? role,
        bool isSheriff = false,
        int? deathDay,
        Map<int, bool>? seerChecks}) =>
    {
      'seat': seat,
      'nickname': '座位$seat',
      'avatarId': seat,
      'avatarUrl': null,
      'nickColor': null,
      'frameColor': null,
      'title': null,
      'bot': seat >= 2 && seat <= 5,
      'alive': alive,
      'isSheriff': isSheriff,
      'silenced': false,
      'idiotRevealed': false,
      'userId': 2180 + seat,
      if (role != null) 'role': role,
      if (deathDay != null) 'deathDay': deathDay,
      if (seerChecks != null) 'seerChecks': seerChecks,
    };

/// 终局（GAME_OVER）视图：结算揭示与互赞面板都用它。
Map<String, dynamic> gameStateOver({int mySeat = 1, int roomNo = 200728, int gameId = 1638}) => {
      'type': 'game.state',
      'phase': 'GAME_OVER',
      'day': 3,
      'roomNo': roomNo,
      'seats': [
        _seat(1, alive: true, role: '预言家'),
        _seat(2, alive: false, role: '狼人'),
        _seat(3, alive: false, role: '村民'),
        _seat(4, alive: true, role: '女巫'),
        _seat(5, alive: false, role: '狼人'),
        _seat(6, alive: true, role: '猎人'),
      ],
      'feed': [
        {'day': 3, 'type': 'GAME_OVER', 'actor': 0, 'target': 0, 'detail': 'winner=好人'},
      ],
      'mySeat': mySeat,
      'myTurn': false,
      'actionKind': null,
      'currentActor': 0,
      'sheffSeat': 1,
      'anonymous': false,
      'voiceMode': false,
      'voiceAvailable': false,
      'myInfo': {'role': '预言家', 'faction': 'GOD', 'seerChecks': {'2': true}},
      'winner': '好人阵营',
      'spectator': false,
      'hostUserId': 2180,
      'gameId': gameId,
    };

/// 论坛列表（GET /api/forum，全量无分页；正文超 200 字服务端已截断）。
List<Map<String, dynamic>> forumList() => [
      {
        'id': 65,
        'title': '语音功能上线啦',
        'content': 'ASR/TTS 全链路打通，欢迎体验！',
        'category': '综合',
        'pinned': false,
        'hidden': false,
        'replyCount': 3,
        'userId': 1,
        'author': '阿尔法',
        'at': '2026-10-02T23:55:02.020877',
      },
      {
        'id': 64,
        'title': '预言家首夜该验谁',
        'content': '我一般验中间位，因为两侧容易自爆式发言抢身份。',
        'category': '攻略',
        'pinned': true,
        'hidden': false,
        'replyCount': 12,
        'userId': 1442,
        'author': 'QA',
        'at': '2026-10-01T09:12:33.112233',
      },
    ];

/// 排行榜（GET /api/ranking?by=win|gold|level&limit=）。
Map<String, dynamic> rankingView({String by = 'win'}) => {
      'by': by,
      'list': [
        {
          'userId': 100,
          'nickname': 'weiwei',
          'level': 690,
          'gold': 99966463,
          'games': 53,
          'wins': 18,
          'mvp': 9,
          'winRate': 34.0,
          'rank': 1
        },
        {
          'userId': 101,
          'nickname': '夜枭',
          'level': 42,
          'gold': 15230,
          'games': 120,
          'wins': 31,
          'mvp': 12,
          'winRate': 25.8,
          'rank': 2
        },
      ],
    };

/// 商店目录 / 背包（注意：inventory 用 itemDefId，catalog 用 id + owned）。
List<Map<String, dynamic>> shopCatalog() => [
      {'id': 97, 'type': 'FUNCTION', 'name': '双倍经验卡', 'description': '一局后经验翻倍', 'price': 160, 'asset': 'EXPDOUBLE', 'owned': false},
      {'id': 12, 'type': 'AVATAR', 'name': '银月', 'description': '头像框', 'price': 80, 'asset': 'moon', 'owned': true},
      {'id': 31, 'type': 'TITLE', 'name': '猎城者', 'description': '称号', 'price': 240, 'asset': 'hunter', 'owned': false, 'origPrice': 300, 'discounted': true},
    ];

List<Map<String, dynamic>> shopInventory() => [
      {'itemDefId': 501, 'type': 'FUNCTION', 'name': '双倍经验卡', 'asset': 'EXPDOUBLE'},
      {'itemDefId': 502, 'type': 'AVATAR', 'name': '银月', 'asset': 'moon'},
    ];

/// 工单列表（status：OPEN / REPLIED / PROCESSING / CLOSED）。
List<Map<String, dynamic>> ticketList() => [
      {
        'id': 7,
        'category': 'bug',
        'title': '夜里麦克风没声音',
        'content': '点击录音没有光环。',
        'status': 'REPLIED',
        'roomNo': 200728,
        'targetUserId': null,
        'at': '2026-10-02T21:03:11.5',
        'replies': 2,
      },
    ];

/// 结算互赞汇总（GET /api/game/kudos/{gameId}，byUser 的键是字符串化 userId）。
Map<String, dynamic> kudosView(int gameId) => {
      'gameId': gameId,
      'byUser': {
        '3021': {'PRAISE': 3, 'COMFORT': 1, 'REPORT': 0},
        '2180': {'PRAISE': 1},
      },
    };

/// 功能开关（GET /api/config/features）：disabled 是**被关闭 id 的数组**，不在数组里即启用。
Map<String, dynamic> featuresView({List<String> disabled = const []}) => {'disabled': disabled};

/// 下载中心（GET /api/downloads）。
Map<String, dynamic> downloadsView() => {
      'updated': '2026-10-03',
      'banner': 'v2.0.0 全新界面',
      'items': [
        {
          'platform': 'windows',
          'label': 'Windows 桌面版',
          'icon': '🖥️',
          'os': 'Windows 10 / 11',
          'arch': 'x64',
          'version': '2.0.0',
          'note': '安装版',
          'file': 'werewolf-setup-2.0.0-win64.exe',
          'available': true,
          'size': 80450359,
          'url': '/download/werewolf-setup-2.0.0-win64.exe',
        },
        {
          'platform': 'pwa',
          'label': '网页版',
          'icon': '🌐',
          'os': '任意',
          'arch': '',
          'version': '',
          'note': '直接访问',
          'file': '',
          'available': true,
          'size': 0,
          'url': '/',
        },
      ],
    };
