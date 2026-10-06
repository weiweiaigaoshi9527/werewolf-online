/// 复盘回放的纯逻辑层（不依赖 Flutter，方便单测覆盖状态重建规则）。
///
/// 服务端契约（GET /api/game/replay/{gameId}）：
/// - 顶层 7 个字段：gameId / roomNo / winner / seatRoles / startedAt / endedAt / events；
/// - **一帧 = 一个事件**，`seq` 从 0 起连续且等于数组下标，可直接当帧号；
/// - 事件只有 7 个字段：seq / day / phase / type / actor / target / detail，
///   **没有独立时间戳**；actor=0 表示无主体，target=0 表示无对象（空刀、放弃、无人出局）；
/// - `seatRoles` 是**字符串** `"1:预言家 2:狼人 ..."`，不是对象。

// ⚠️ 不要按 unnecessary_brace_in_string_interps 把 `'${e}号'` 改成 `'$e号'`：
// Dart 的插值标识符在遇到 CJK 时会整体停止解析，`'$e号'` 根本不插值、
// 直接输出字面量 "$e号"（实测，分析器还会顺带报 "e 未被使用"）。
// 也就是说这条 lint 在"插值紧跟中文"的场景下是错的，dart fix 会静默改坏文案。
// ignore_for_file: unnecessary_brace_in_string_interps
library;

class ReplayEvent {
  ReplayEvent({
    required this.seq,
    required this.day,
    required this.phase,
    required this.type,
    required this.actor,
    required this.target,
    required this.detail,
  });

  factory ReplayEvent.fromJson(Map j) => ReplayEvent(
        seq: (j['seq'] as num?)?.toInt() ?? 0,
        day: (j['day'] as num?)?.toInt() ?? 0,
        phase: '${j['phase'] ?? ''}',
        type: '${j['type'] ?? ''}',
        actor: (j['actor'] as num?)?.toInt() ?? 0,
        target: (j['target'] as num?)?.toInt() ?? 0,
        detail: '${j['detail'] ?? ''}',
      );

  final int seq;
  final int day;
  final String phase;
  final String type;
  final int actor;
  final int target;
  final String detail;
}

/// 某一帧时刻重建出的棋盘状态。
class ReplayState {
  ReplayState({required this.seats, Map<int, String>? roles})
      : alive = seats.toSet(),
        seatRoles = roles ?? const {};

  /// 本局出现过的全部座位号（来自 seatRoles 的键）。
  final List<int> seats;
  final Map<int, String> seatRoles;

  /// 当前仍存活的座位。PLAYER_DIED 是唯一权威标记，复活卡可让其重新存活。
  final Set<int> alive;

  /// 白痴翻牌后失去投票权（人还活着）。
  final Set<int> noVote = {};

  /// 当前警长座位，null 表示无警长（含警徽被撕毁）。
  int? sheriff;

  /// 死因（座位 → 原因枚举原文）。
  final Map<int, String> deaths = {};

  int day = 0;
  String phase = '';
  bool over = false;
  String winner = '';

  bool isAlive(int seat) => alive.contains(seat);
  bool isWolf(int seat) => seatRoles[seat]?.contains('狼') ?? false;

  ReplayState copy() {
    final s = ReplayState(seats: seats, roles: seatRoles)
      ..alive.addAll(alive)
      ..noVote.addAll(noVote)
      ..sheriff = sheriff
      ..deaths.addAll(deaths)
      ..day = day
      ..phase = phase
      ..over = over
      ..winner = winner;
    return s;
  }
}

/// 解析 `"1:预言家 2:狼人 3:村民"`；也兼容万一后端改成 Map 的情况。
Map<int, String> parseSeatRoles(dynamic raw) {
  final out = <int, String>{};
  if (raw is Map) {
    raw.forEach((k, v) {
      final s = int.tryParse('$k');
      if (s != null) out[s] = '$v';
    });
    return out;
  }
  for (final token in '$raw'.split(RegExp(r'\s+'))) {
    if (token.isEmpty) continue;
    final i = token.indexOf(':');
    if (i <= 0) continue;
    final s = int.tryParse(token.substring(0, i));
    if (s != null) out[s] = token.substring(i + 1);
  }
  return out;
}

/// 把事件序列折成状态。`stateAt(events, i)` 表示"已经播完第 0..i 帧"之后的棋盘。
ReplayState foldTo(ReplayState base, List<ReplayEvent> events, int upto) {
  final s = base.copy();
  for (var i = 0; i <= upto && i < events.length; i++) {
    _apply(s, events[i]);
  }
  return s;
}

void _apply(ReplayState s, ReplayEvent e) {
  if (e.day > s.day) s.day = e.day;
  if (e.phase.isNotEmpty) s.phase = e.phase;
  switch (e.type) {
    case 'GAME_START':
      break;
    case 'PLAYER_DIED':
      if (e.actor != 0) {
        s.alive.remove(e.actor);
        s.deaths[e.actor] = e.detail;
      }
      break;
    case 'SHERIFF_WIN':
      // detail 明确写了"警徽撕毁 / 平票或无当选，本轮无警长"时 actor 也可能是 0
      final torn = e.detail.contains('撕毁') || e.detail.contains('无警长');
      s.sheriff = (e.actor == 0 || torn) ? null : e.actor;
      break;
    case 'IDIOT_REVEAL':
      // 白痴翻牌：免死但失去投票权，人仍在场
      if (e.actor != 0) s.noVote.add(e.actor);
      break;
    case 'ITEM_EFFECT':
      if (e.detail.contains('复活') && e.actor != 0) {
        s.alive.add(e.actor); // 复活卡发动，原地复活
        s.deaths.remove(e.actor);
      }
      break;
    case 'GAME_OVER':
      s.over = true;
      s.winner = e.detail.startsWith('winner=') ? e.detail.substring(7) : e.detail;
      break;
    default:
      // SHOOT / VOTE_RESULT / WHITE_WOLF_BLOWUP 都只记录动作，真正的出局由随后的
      // PLAYER_DIED 落定；NIGHT_RESOLVE / DAWN_ANNOUNCE 是播报。这里都不改存活，
      // 否则会和 PLAYER_DIED 重复计数。未知 type 一律安全忽略。
      break;
  }
}

/// 播放器：只管"帧指针 + 是否自动播放 + 速度"，不持有任何 Flutter 对象，
/// 定时器由界面层驱动（便于单测，也便于页面离开时确保 cancel）。
class ReplayPlayer {
  /// seats 必须由调用方给出真实座位（来自 seatRoles 的键）。
  /// 不能从事件里猜：整局没在事件里露过面的座位会被漏掉，座位条就少人。
  ReplayPlayer(this.events, {required List<int> seats})
      : seats = List.unmodifiable(seats),
        index = -1;

  final List<ReplayEvent> events;
  final List<int> seats;

  /// 已播到第几帧；-1 表示还没开始。
  int index;
  bool playing = false;

  /// 速度档位与网页版一致（慢 1200ms / 中 600ms / 快 250ms），这里额外给一档"极慢"便于看清发言。
  static const speeds = <String, int>{'极慢': 2000, '慢': 1200, '中': 600, '快': 250};
  String speedLabel = '中';
  int get intervalMs => speeds[speedLabel] ?? 600;

  int get total => events.length;
  bool get atStart => index < 0;
  bool get atEnd => index >= events.length - 1;
  double get progress => total == 0 ? 0 : ((index + 1) / total).clamp(0.0, 1.0);

  ReplayState get state => stateAt(index);

  /// "已经播完第 0..i 帧"之后的棋盘状态。
  ReplayState stateAt(int i) {
    final base = ReplayState(seats: seats, roles: _roles);
    return foldTo(base, events, i);
  }

  Map<int, String> _roles = const {};

  /// 由界面注入身份映射，stateAt 才能带上角色名。
  set seatRoles(Map<int, String> v) {
    _roles = v;
  }

  void play() {
    if (playing) return;
    if (atEnd) index = -1; // 播完再点 = 从头重播（与网页版一致）
    playing = true;
  }

  void pause() => playing = false;

  /// 前进一帧；返回是否还有后续帧。
  bool step() {
    if (atEnd) {
      playing = false;
      return false;
    }
    index++;
    if (index >= events.length - 1) playing = false;
    return true;
  }

  bool back() {
    if (atStart) return false;
    index--;
    playing = false;
    return true;
  }

  void jumpTo(int i) {
    index = i.clamp(-1, total - 1);
    playing = false;
  }

  void reset() {
    index = -1;
    playing = false;
  }
}

/// 死因枚举 → 中文（服务端存的是机器码）。
const kDeathZh = {
  'WOLF': '狼人刀杀',
  'POISON': '女巫毒杀',
  'EXILE': '投票放逐',
  'SHOT': '枪杀',
  'BLOWUP': '自爆',
};

/// 夜晚动作码 → 中文。
const kNightZh = {
  'GUARD': '🛡 守卫守护了',
  'WOLF_VOTE': '🐺 狼人商议刀人',
  'WOLF_KILL_RESOLVED': '🗡 刀口落定',
  'WITCH_SAVE': '🧪 女巫用了解药',
  'WITCH_POISON': '☠️ 女巫用毒',
  'SEER_CHECK=WOLF': '🔮 预言家查验出狼人',
  'SEER_CHECK=GOOD': '🔮 预言家查验是好人',
  'CROW_ACCUSE': '🐦 乌鸦诅咒',
  'SILENCER': '🤐 沉默者禁言',
};

/// 一行的展示文案 + emoji。未知 type 走安全兜底，绝不抛。
({String emoji, String text}) replayLine(ReplayEvent e, Map<int, String> roles) {
  final a = e.actor == 0 ? '' : '${e.actor}号';
  final t = e.target == 0 ? '' : '${e.target}号';
  switch (e.type) {
    case 'GAME_START':
      return (emoji: '🎬', text: '开局：${_briefBoard(e.detail)}');
    case 'SPEECH':
      final pk = e.detail.startsWith('PK: ') ? '（PK 轮）' : '';
      final muted = e.detail.contains('被禁言');
      return (emoji: '💬', text: muted ? '$a 被禁言，过麦' : '$a$pk：${muted ? '' : e.detail}');
    case 'LAST_WORDS':
      return (emoji: '🕯', text: '$a 留遗言：${e.detail}');
    case 'PLAYER_DIED':
      return (emoji: '💀', text: '$a 出局（${kDeathZh[e.detail] ?? e.detail}）');
    case 'SHOOT':
      if (e.detail.contains('放弃')) return (emoji: '🔫', text: '$a 放弃开枪');
      return (emoji: '🔫', text: '${e.detail.contains('狼王') ? '狼王' : '猎人'}$a 开枪带走 $t');
    case 'WHITE_WOLF_BLOWUP':
      return (emoji: '💣', text: '白狼王 $a 自爆${t.isEmpty ? '' : '，带走 $t'}');
    case 'VOTE_RESULT':
      return (emoji: '🗳', text: e.actor == 0 ? '本轮无人出局：${e.detail}' : '🗳 ${e.detail}：$a 被放逐');
    case 'VOTE_DETAIL':
      return (emoji: '🗳', text: e.detail);
    case 'VOTE_TIE':
      return (emoji: '⚖️', text: '平票，进入 PK：${_nums(e.detail)}');
    case 'DAWN_ANNOUNCE':
      final dead = _nums(e.detail);
      return (emoji: '🌅', text: dead.isEmpty ? '平安夜，无人倒牌' : '天亮公布：$dead 倒牌');
    case 'NIGHT_RESOLVE':
      if (e.detail.contains('SAVED_BY_WITCH')) return (emoji: '🧪', text: '${e.target}号被女巫救回');
      if (e.detail.contains('PROTECTED_BY_GUARD')) return (emoji: '🛡', text: '${e.target}号被守卫守住');
      return (emoji: '🌙', text: _resolveBrief(e.detail));
    case 'NIGHT_ACTION':
      final zh = kNightZh.entries.firstWhere(
        (kv) => e.detail == kv.key || e.detail.startsWith(kv.key),
        orElse: () => const MapEntry('?', '🌙 夜晚行动'),
      );
      final who = t.isEmpty ? '' : ' → $t';
      return (emoji: '🌙', text: '${zh.value}$who');
    case 'SHERIFF_WIN':
      if (e.detail.contains('撕毁')) return (emoji: '👑', text: '警徽被撕毁，本局不再有警长');
      if (e.actor == 0) return (emoji: '👑', text: e.detail);
      return (emoji: '👑', text: '$a ${e.detail}');
    case 'IDIOT_REVEAL':
      return (emoji: '🃏', text: '$a 翻牌免死，失去投票权');
    case 'ITEM_EFFECT':
      return (emoji: '✨', text: '${a.isEmpty ? '' : '$a '}${e.detail}');
    case 'ITEM_REVEAL':
      return (emoji: '🔍', text: '$a 使用查杀卡：$t 是${e.detail}');
    case 'GAME_OVER':
      return (emoji: '🏁', text: '本局结束：${e.detail.startsWith('winner=') ? e.detail.substring(7) : e.detail}胜');
    default:
      return (emoji: '·', text: '$a${t.isEmpty ? '' : ' → $t'} ${e.detail.isEmpty ? e.type : e.detail}');
  }
}

String _briefBoard(String detail) {
  final i = detail.indexOf('board=');
  if (i < 0) return detail;
  final j = detail.indexOf('}', i);
  if (j < 0) return detail.substring(i + 6);
  return detail.substring(i + 6, j + 1);
}

String _resolveBrief(String detail) {
  int num(String key) {
    final m = RegExp('$key=(\\d+)').firstMatch(detail);
    return int.tryParse(m?.group(1) ?? '') ?? 0;
  }

  final parts = <String>[
    if (num('wolfKill') > 0) '刀 ${num('wolfKill')}',
    if (num('guard') > 0) '守 ${num('guard')}',
    if (num('save') > 0) '救 ${num('save')}',
    if (num('poison') > 0) '毒 ${num('poison')}',
  ];
  return parts.isEmpty ? '夜晚结算：无人伤亡' : '夜晚结算：${parts.join('、')}';
}

/// 从 `"deaths=[4, 7]"` / `"PK: [1, 4]"` 里取座位号并拼成 "4、7"。
String _nums(String detail) {
  final found = RegExp(r'\d+').allMatches(detail).map((m) => m.group(0)).toList();
  return found.map((e) => '${e}号').join('、');
}
