import 'package:flutter_test/flutter_test.dart';
import 'package:werewolf_app/feed_filter.dart';

/// 对局记录面板纯逻辑回归：分类、座位聚焦、中文导出。
/// 载荷形状照抄服务端 GameEvent 的序列化字段：day / type / actor / target / detail。
void main() {
  Map<String, dynamic> ev(String type, {int? actor, int? target, String? detail, int day = 1}) => {
        'day': day,
        'type': type,
        if (actor != null) 'actor': actor,
        if (target != null) 'target': target,
        if (detail != null) 'detail': detail,
      };

  group('分类', () {
    test('发言与遗言归「发言」，投票类归「投票」', () {
      expect(feedCatOf('SPEECH'), 'speech');
      expect(feedCatOf('LAST_WORDS'), 'speech');
      expect(feedCatOf('VOTE_RESULT'), 'vote');
      expect(feedCatOf('VOTE_DETAIL'), 'vote');
      expect(feedCatOf('VOTE_TIE'), 'vote');
    });

    test('出局/开枪/自爆归「出局」，夜间两段归「夜间」', () {
      expect(feedCatOf('PLAYER_DIED'), 'death');
      expect(feedCatOf('SHOOT'), 'death');
      expect(feedCatOf('WHITE_WOLF_BLOWUP'), 'death');
      expect(feedCatOf('IDIOT_REVEAL'), 'death');
      expect(feedCatOf('NIGHT_ACTION'), 'night');
      expect(feedCatOf('DAWN_ANNOUNCE'), 'night');
      expect(feedCatOf('GAME_OVER'), 'other');
    });
  });

  group('筛选', () {
    test('cat=all 全过；指定分类只留同类', () {
      final feed = [ev('SPEECH', actor: 1, detail: '我是预言家'), ev('VOTE_RESULT', actor: 2, detail: '2 号被放逐')];
      expect(feed.where((e) => feedMatch(e, cat: 'all')).length, 2);
      expect(feed.where((e) => feedMatch(e, cat: 'speech')).length, 1);
      expect(feed.where((e) => feedMatch(e, cat: 'vote')).length, 1);
    });

    test('座位聚焦：发件人或目标任一是他', () {
      final asActor = ev('SPEECH', actor: 3, detail: 'x');
      final asTarget = ev('VOTE_RESULT', actor: 5, target: 3, detail: 'y');
      final unrelated = ev('SPEECH', actor: 5, detail: 'z');
      expect(feedMatchSeat(asActor, 3), isTrue);
      expect(feedMatchSeat(asTarget, 3), isTrue);
      expect(feedMatchSeat(unrelated, 3), isFalse);
      expect(feedMatchSeat(unrelated, 0), isTrue); // 0 = 不筛选
    });

    test('投票分类兜底：正文里带"投票"字样的其它事件也算', () {
      final e = ev('ITEM_EFFECT', actor: 1, detail: '下一轮投票权翻倍');
      expect(feedCatOf('ITEM_EFFECT'), 'other');
      expect(feedTextZh(e), contains('投票'));
      expect(feedMatchCat(e, 'vote'), isTrue);
      expect(feedMatchCat(ev('GAME_START'), 'vote'), isFalse);
    });
  });

  group('中文文案', () {
    test('预言家查验带出结果', () {
      expect(
        feedTextZh(ev('NIGHT_ACTION', actor: 1, target: 6, detail: 'SEER_CHECK=WOLF')),
        contains('狼人'),
      );
      expect(feedTextZh(ev('NIGHT_ACTION', actor: 1, target: 6, detail: 'SEER_CHECK=GOOD')), contains('好人'));
    });

    test('夜晚结算还原四个关键座位', () {
      final s = feedTextZh(ev('NIGHT_RESOLVE', detail: 'wolfKill=4 guard=0 save=4 poison=7'));
      expect(s, contains('狼刀 4号'));
      expect(s, contains('守卫 无'));
      expect(s, contains('解药 4号'));
      expect(s, contains('毒药 7号'));
    });

    test('出局原因翻成中文，未标注原因时不显示括号', () {
      expect(feedTextZh(ev('PLAYER_DIED', actor: 4, detail: 'WOLF')), '4号 出局（狼人刀杀）');
      expect(feedTextZh(ev('PLAYER_DIED', actor: 4)), '4号 出局');
      expect(feedCauseZh('POISON'), '女巫毒杀');
      expect(feedCauseZh('EXILE'), '投票放逐');
    });

    test('自爆/开枪带出被打包带走的人', () {
      expect(feedTextZh(ev('SHOOT', actor: 2, target: 9, detail: '开枪')), contains('带走 9号'));
      expect(feedTextZh(ev('WHITE_WOLF_BLOWUP', actor: 2, target: 9, detail: '自爆')), contains('带走 9号'));
    });

    test('缺字段不抛，并给出兜底文案', () {
      expect(() => feedTextZh(const {}), returnsNormally);
      expect(feedTextZh(const {}), '对局事件');
    });
  });

  group('导出', () {
    test('首行是抬头，每条带天数与类型前缀', () {
      final out = buildFeedExport(
        roomNo: '200728',
        day: 2,
        feed: [
          ev('SPEECH', actor: 3, detail: '我是好人', day: 1),
          ev('PLAYER_DIED', actor: 4, detail: 'WOLF', day: 2),
        ],
      );
      final lines = out.split('\n');
      expect(lines[0], '狼人杀 Online · 房间 200728 第 2 天记录');
      expect(lines[1], '[第1天][发言] 3号 发言：我是好人');
      expect(lines[2], '[第2天][出局] 4号 出局（狼人刀杀）');
    });

    test('空记录只留抬头', () {
      expect(buildFeedExport(feed: const [], roomNo: '1', day: 1), '狼人杀 Online · 房间 1 第 1 天记录');
    });

    test('未知类型标成「事件」而不是漏行', () {
      final out = buildFeedExport(feed: [ev('MYSTERY', actor: 1, detail: '啥')], roomNo: '1', day: 1);
      expect(out.split('\n')[1], '[第1天][事件] 1号 啥');
    });
  });
}
