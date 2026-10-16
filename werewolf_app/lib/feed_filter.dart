/// 对局记录面板的纯逻辑：分类筛选、座位聚焦、中文导出。
/// 与网页版 js/ux.js（分类与导出）和 js/app.js 的 eventTextZh（事件中文文案）同口径，
/// 因此客户端「复制本局记录」导出的日志与网页端逐字一致。
library;

/// 记录分类：id → 显示名（顺序即筛选条的显示顺序）。
const List<(String, String)> feedCategories = [
  ('all', '全部'),
  ('speech', '发言'),
  ('vote', '投票'),
  ('death', '出局'),
  ('night', '夜间'),
];

/// 事件类型 → 中文名（导出时用作 `[发言]` 这类前缀）。
const Map<String, String> feedTypeCn = {
  'GAME_START': '开局',
  'SPEECH': '发言',
  'LAST_WORDS': '遗言',
  'PLAYER_DIED': '出局',
  'DAWN_ANNOUNCE': '天亮',
  'VOTE_RESULT': '投票结果',
  'VOTE_DETAIL': '投票明细',
  'VOTE_TIE': '平票',
  'SHOOT': '开枪',
  'IDIOT_REVEAL': '白痴翻牌',
  'WHITE_WOLF_BLOWUP': '白狼王自爆',
  'SHERIFF_SIGNUP': '上警',
  'SHERIFF_WITHDRAW': '退水',
  'SHERIFF_WIN': '警长',
  'GAME_OVER': '游戏结束',
  'NIGHT_ACTION': '夜间行动',
  'NIGHT_RESOLVE': '夜晚结算',
  'ITEM_REVEAL': '道具查验',
  'ITEM_EFFECT': '道具生效',
};

/// 出局原因的中文（服务端 detail 为枚举标记）。
String feedCauseZh(String? c) => const {
      'WOLF': '狼人刀杀',
      'POISON': '女巫毒杀',
      'EXILE': '投票放逐',
      'SHOT': '枪杀',
      'BLOWUP': '自爆',
    }[c] ??
    (c ?? '');

/// 事件归类：与网页版 ux.js 的 catOf 完全一致。
String feedCatOf(String? type) {
  if (type == 'SPEECH' || type == 'LAST_WORDS') return 'speech';
  if (type != null && type.startsWith('VOTE')) return 'vote';
  if (type == 'PLAYER_DIED' || type == 'SHOOT' || type == 'WHITE_WOLF_BLOWUP' || type == 'IDIOT_REVEAL') {
    return 'death';
  }
  if (type == 'NIGHT_ACTION' || type == 'DAWN_ANNOUNCE') return 'night';
  return 'other';
}

/// 一条记录是否属于某个分类。投票类额外吸收「正文里带投票字样」的其它事件
/// （与网页版 `cat === 'other' && /投票/.test(textContent)` 的兜底同义）。
bool feedMatchCat(Map e, String cat) {
  if (cat == 'all') return true;
  final c = feedCatOf('${e['type'] ?? ''}');
  if (c == cat) return true;
  return cat == 'vote' && c == 'other' && feedTextZh(e).contains('投票');
}

/// 一条记录是否与座位 seat 相关（发件人或目标任一是他）。seat<=0 表示不筛选。
bool feedMatchSeat(Map e, int seat) {
  if (seat <= 0) return true;
  return _seat(e['actor']) == seat || _seat(e['target']) == seat;
}

bool feedMatch(Map e, {String cat = 'all', int seat = 0}) =>
    feedMatchCat(e, cat) && feedMatchSeat(e, seat);

/// 事件 → 中文描述。逐条对齐网页版 eventTextZh，含夜间行动、夜晚结算的还原。
String feedTextZh(Map e) {
  final d = e['detail'] == null ? '' : '${e['detail']}';
  final actor = _seat(e['actor']);
  final target = _seat(e['target']);
  final type = '${e['type'] ?? ''}';
  String seat(int n) => n > 0 ? '$n号' : '';
  switch (type) {
    case 'GAME_START':
      return '对局开始';
    case 'NIGHT_ACTION':
      if (d.startsWith('SEER_CHECK=')) {
        return '预言家（${seat(actor)}）查验 ${seat(target)}：${d.endsWith('WOLF') ? '狼人' : '好人'}';
      }
      final head = d.split('=').first;
      final map = <String, String>{
        'GUARD': '守卫（${seat(actor)}）守护 ${seat(target).isEmpty ? '空' : seat(target)}',
        'WOLF_VOTE': '狼人（${seat(actor)}）提议刀 ${seat(target).isEmpty ? '空刀' : seat(target)}',
        'WOLF_KILL_RESOLVED': '狼队统一刀口 ${seat(target).isEmpty ? '空刀' : seat(target)}',
        'WITCH_SAVE': '女巫（${seat(actor)}）使用解药救 ${seat(target).isEmpty ? '空' : seat(target)}',
        'WITCH_POISON': '女巫（${seat(actor)}）使用毒药毒 ${seat(target).isEmpty ? '空' : seat(target)}',
        'CROW_ACCUSE': '乌鸦（${seat(actor)}）诽谤 ${seat(target).isEmpty ? '无' : seat(target)}',
        'SILENCER': '禁言长老（${seat(actor)}）禁言 ${seat(target).isEmpty ? '无' : seat(target)}',
      };
      return map[head] ?? '夜间行动';
    case 'NIGHT_RESOLVE':
      if (d.startsWith('wolfKill=')) {
        final p = <String, String>{};
        for (final kv in d.split(' ')) {
          final i = kv.indexOf('=');
          if (i > 0) p[kv.substring(0, i)] = kv.substring(i + 1);
        }
        String s(String k) {
          final n = int.tryParse(p[k] ?? '') ?? 0;
          return n > 0 ? '$n号' : '无';
        }

        return '夜晚结算：狼刀 ${s('wolfKill')}，守卫 ${s('guard')}，解药 ${s('save')}，毒药 ${s('poison')}';
      }
      if (d == 'PROTECTED_BY_GUARD') return '守卫守护成功，狼刀被挡下';
      if (d == 'SAVED_BY_WITCH') return '女巫使用解药，被刀者获救';
      return '夜晚结算';
    case 'DAWN_ANNOUNCE':
      return d.isEmpty ? '天亮了' : d;
    case 'PLAYER_DIED':
      return d.isEmpty ? '${seat(actor)} 出局'.trim() : '${seat(actor)} 出局（${feedCauseZh(d)}）'.trim();
    case 'SPEECH':
      return '${seat(actor)} 发言：$d'.trim();
    case 'LAST_WORDS':
      return '${seat(actor)} 遗言：$d'.trim();
    case 'VOTE_DETAIL':
      return d;
    case 'VOTE_RESULT':
      return actor > 0 ? '投票结果：${seat(actor)} 被放逐' : '投票结果：$d';
    case 'VOTE_TIE':
      return d;
    case 'SHOOT':
      return '${seat(actor)} $d${target > 0 ? '，带走 ${seat(target)}' : ''}'.trim();
    case 'IDIOT_REVEAL':
      return '${seat(actor)} 白痴翻牌免死'.trim();
    case 'WHITE_WOLF_BLOWUP':
      return '${seat(actor)} ${d.isEmpty ? '自爆' : d}${target > 0 ? '，带走 ${seat(target)}' : ''}'.trim();
    case 'SHERIFF_SIGNUP':
      return '${seat(actor)} ${d == '上警' ? '上警竞选' : '不上警'}'.trim();
    case 'SHERIFF_WITHDRAW':
      return '${seat(actor)} 退水（退出警长竞选）'.trim();
    case 'SHERIFF_WIN':
      return '${seat(actor)} $d'.trim();
    case 'ITEM_REVEAL':
      return '${seat(actor)} 使用查杀卡：${seat(target)} 是$d'.trim();
    case 'ITEM_EFFECT':
      return '${seat(actor)} $d'.trim();
    case 'GAME_OVER':
      return '游戏结束，${d.replaceFirst('winner=', '')}获胜';
    default:
      final s = '${seat(actor)} $d'.trim();
      return s.isEmpty ? '对局事件' : s;
  }
}

/// 导出用的中文日志（与网页版「⧉ 复制」逐字一致）。
String buildFeedExport({required List feed, required String roomNo, required int day}) {
  final head = '狼人杀 Online · 房间 $roomNo 第 $day 天记录';
  final lines = <String>[head];
  for (final e in feed) {
    final m = e is Map ? e : const {};
    final t = '${m['type'] ?? ''}';
    lines.add('[第${m['day'] ?? day}天][${feedTypeCn[t] ?? '事件'}] ${feedTextZh(m)}');
  }
  return lines.join('\n');
}

int _seat(Object? v) {
  if (v is num) return v.toInt();
  return int.tryParse('${v ?? ''}') ?? 0;
}
