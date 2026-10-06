import 'package:flutter_test/flutter_test.dart';
import 'package:werewolf_app/replay_engine.dart';

ReplayEvent ev(int seq, String type, {int day = 1, String phase = 'DAY_VOTE', int actor = 0, int target = 0, String detail = ''}) =>
    ReplayEvent(seq: seq, day: day, phase: phase, type: type, actor: actor, target: target, detail: detail);

void main() {
  group('seatRoles 解析', () {
    test('服务端给的是字符串而不是对象', () {
      final r = parseSeatRoles('1:预言家 2:狼人 3:村民 4:猎人');
      expect(r, {1: '预言家', 2: '狼人', 3: '村民', 4: '猎人'});
    });

    test('兼容 Map 形式', () {
      expect(parseSeatRoles({'1': '狼人', '2': '村民'}), {1: '狼人', 2: '村民'});
    });

    test('空串、坏 token、多余空格都不抛', () {
      expect(parseSeatRoles(''), isEmpty);
      expect(parseSeatRoles(null), isEmpty);
      expect(parseSeatRoles('1:狼人  坏数据 :2 abc:3 x:x'), {1: '狼人'});
    });

    test('含"狼"即判为狼阵营', () {
      final s = ReplayState(seats: [1, 2], roles: const {1: '白狼王', 2: '平民'})
        ..alive.addAll([1, 2]);
      expect(s.isWolf(1), isTrue);
      expect(s.isWolf(2), isFalse);
    });
  });

  group('按帧重建棋盘状态', () {
    final events = <ReplayEvent>[
      ev(0, 'GAME_START', day: 0, phase: 'SETUP', detail: 'board={狼人x2 村民x2} huntByBorder=true'),
      ev(1, 'NIGHT_ACTION', day: 1, phase: 'NIGHT_WOLF', actor: 2, target: 4, detail: 'WOLF_VOTE'),
      ev(2, 'NIGHT_RESOLVE', day: 1, phase: 'DAWN', target: 4, detail: 'SAVED_BY_WITCH'),
      ev(3, 'DAWN_ANNOUNCE', day: 1, phase: 'DAWN', detail: 'deaths=[]'),
      ev(4, 'SHERIFF_WIN', day: 1, phase: 'SHERIFF_ELECTION', actor: 1, detail: '当选警长'),
      ev(5, 'VOTE_DETAIL', day: 1, phase: 'DAY_VOTE', detail: '放逐投票 1→3  2→3  4→1'),
      ev(6, 'VOTE_RESULT', day: 1, phase: 'DAY_VOTE', actor: 3, detail: '放逐出局'),
      ev(7, 'PLAYER_DIED', day: 1, phase: 'DAY_VOTE', actor: 3, detail: 'EXILE'),
      ev(8, 'LAST_WORDS', day: 1, phase: 'LAST_WORDS', actor: 3, detail: '唉，我是真预言家'),
      ev(9, 'SHOOT', day: 1, phase: 'SHOOT', actor: 4, target: 2, detail: '猎人开枪'),
      ev(10, 'PLAYER_DIED', day: 1, phase: 'SHOOT', actor: 4, detail: 'SHOT'),
      ev(11, 'PLAYER_DIED', day: 1, phase: 'SHOOT', actor: 2, detail: 'SHOT'),
      ev(12, 'SHERIFF_WIN', day: 2, phase: 'DAY_VOTE', actor: 0, detail: '警徽撕毁'),
      ev(13, 'GAME_OVER', day: 2, phase: 'GAME_OVER', detail: 'winner=好人'),
    ];
    ReplayState at(int i) => foldTo(ReplayState(seats: [1, 2, 3, 4], roles: const {1: '预言家', 2: '狼人', 3: '村民', 4: '猎人'}), events, i);

    test('开局全员存活', () {
      final s = at(0);
      expect(s.alive, {1, 2, 3, 4});
      expect(s.day, 0);
    });

    test('夜晚行动与女巫救人都不改变存活', () {
      expect(at(2).alive, {1, 2, 3, 4});
    });

    test('平安夜（deaths=[]）不死人', () {
      expect(at(3).alive, {1, 2, 3, 4});
    });

    test('警长由 SHERIFF_WIN 建立（响应里没有 sheriffSeat 字段）', () {
      expect(at(4).sheriff, 1);
    });

    test('VOTE_RESULT 只标记，真正出局由随后的 PLAYER_DIED 落定，不重复计数', () {
      expect(at(6).alive, {1, 2, 3, 4}, reason: 'VOTE_RESULT 本身不该扣命');
      expect(at(7).alive, {1, 2, 4});
      expect(at(7).deaths[3], 'EXILE');
    });

    test('开枪带走两人后各自 PLAYER_DIED，警长若已出局则状态里仍保留其身份标记但不影响存活', () {
      final s = at(11);
      expect(s.alive, {1});
      expect(s.deaths[4], 'SHOT');
      expect(s.deaths[2], 'SHOT');
    });

    test('警徽撕毁后回到无警长', () {
      expect(at(11).sheriff, 1);
      expect(at(12).sheriff, isNull);
    });

    test('GAME_OVER 解析 winner（注意事件里是"winner=好人"，与顶层"好人阵营"格式不同）', () {
      final s = at(13);
      expect(s.over, isTrue);
      expect(s.winner, '好人');
    });

    test('stateAt 是纯函数：重复调用同一帧结果一致，且不倒退', () {
      expect(at(7).alive, at(7).alive);
      expect(at(5).alive.length, greaterThan(at(9).alive.length));
    });

    test('无人出局（VOTE_RESULT actor=0）不扣命', () {
      final e = [ev(0, 'VOTE_RESULT', actor: 0, detail: '无人被放逐')];
      final s = foldTo(ReplayState(seats: [1, 2]), e, 0);
      expect(s.alive, {1, 2});
    });

    test('白痴翻牌：人还活着但失去投票权', () {
      final e = [ev(0, 'IDIOT_REVEAL', actor: 3, detail: '白痴翻牌免死，失去投票权')];
      final s = foldTo(ReplayState(seats: [1, 2, 3]), e, 0);
      expect(s.alive, {1, 2, 3});
      expect(s.noVote, {3});
    });

    test('复活卡可以把已出局的人拉回场上', () {
      final e = [
        ev(0, 'PLAYER_DIED', actor: 5, detail: 'WOLF'),
        ev(1, 'ITEM_EFFECT', actor: 5, detail: '复活卡发动，原地复活'),
      ];
      var s = foldTo(ReplayState(seats: [5]), e, 0);
      expect(s.alive, isEmpty);
      s = foldTo(ReplayState(seats: [5]), e, 1);
      expect(s.alive, {5});
      expect(s.deaths, isEmpty);
    });

    test('护盾/守护水晶/铁布衫这类"挡刀"条目不改存活（出局事件根本不会跟来）', () {
      final e = [ev(0, 'ITEM_EFFECT', actor: 2, detail: '护盾抵挡了今晚的刀')];
      final s = foldTo(ReplayState(seats: [1, 2]), e, 0);
      expect(s.alive, {1, 2});
    });

    test('白狼王自爆：两条 PLAYER_DIED 都记上', () {
      final e = [
        ev(0, 'WHITE_WOLF_BLOWUP', actor: 2, target: 4, detail: '白狼王自爆'),
        ev(1, 'PLAYER_DIED', actor: 2, detail: 'BLOWUP'),
        ev(2, 'PLAYER_DIED', actor: 4, detail: 'BLOWUP'),
      ];
      final s = foldTo(ReplayState(seats: [1, 2, 3, 4]), e, 2);
      expect(s.alive, {1, 3});
      expect(s.deaths[2], 'BLOWUP');
    });

    test('未知 type 与缺字段都不影响已有状态、不抛', () {
      final e = [
        ev(0, 'PLAYER_DIED', actor: 1, detail: 'WOLF'),
        ev(1, 'SOME_FUTURE_EVENT', actor: 9, target: 9, detail: '???'),
        ReplayEvent(seq: 2, day: 0, phase: '', type: '', actor: 0, target: 0, detail: ''),
      ];
      final s = foldTo(ReplayState(seats: [1, 2]), e, 2);
      expect(s.alive, {2});
    });
  });

  group('播放器指针与速度', () {
    final events = [for (var i = 0; i < 5; i++) ev(i, 'SPEECH', actor: i + 1, detail: '发言$i')];

    test('初始停在 -1，step 逐帧推进，末帧自动停表', () {
      final p = ReplayPlayer(events, seats: const [1, 2, 3, 4, 5]);
      expect(p.index, -1);
      expect(p.atStart, isTrue);
      p.play();
      for (var i = 0; i < 5; i++) {
        p.step();
      }
      expect(p.index, 4);
      expect(p.atEnd, isTrue);
      expect(p.playing, isFalse, reason: '走到末帧应自动停止，不再空转定时器');
    });

    test('播完再点播放 = 从头重播（与网页版一致）', () {
      final p = ReplayPlayer(events, seats: const [1, 2, 3, 4, 5])..jumpTo(4);
      expect(p.atEnd, isTrue);
      p.play();
      expect(p.index, -1);
      p.step();
      expect(p.index, 0);
    });

    test('后退与跳转都会先停表', () {
      final p = ReplayPlayer(events, seats: const [1, 2, 3, 4, 5])..play();
      p.step();
      p.step();
      expect(p.playing, isTrue);
      p.back();
      expect(p.index, 0);
      expect(p.playing, isFalse);
      p.jumpTo(3);
      expect(p.index, 3);
      p.jumpTo(99);
      expect(p.index, 4, reason: '越界要夹到最后一帧');
      p.jumpTo(-5);
      expect(p.index, -1, reason: '越下界夹到未开始');
    });

    test('播放中重复点播放无效', () {
      final p = ReplayPlayer(events, seats: const [1])..play();
      final before = p.index;
      p.play();
      expect(p.index, before);
      expect(p.playing, isTrue);
    });

    test('速度档位保留网页的三档并多给一档极慢', () {
      expect(ReplayPlayer.speeds.values.toSet(), {2000, 1200, 600, 250});
      final p = ReplayPlayer(events, seats: const [1]);
      expect(p.intervalMs, 600, reason: '默认中速，与网页版默认档一致');
      p.speedLabel = '快';
      expect(p.intervalMs, 250);
      p.speedLabel = '不存在的档';
      expect(p.intervalMs, 600, reason: '未知档位回落到中速而不是崩');
    });

    test('空事件集不除零', () {
      final p = ReplayPlayer([], seats: const []);
      expect(p.progress, 0);
      expect(p.atEnd, isTrue);
      expect(p.step(), isFalse);
    });

    test('座位集合用注入的真实座位，不从事件里猜（没露过面的座位不能少）', () {
      final p = ReplayPlayer([ev(0, 'SPEECH', actor: 2)], seats: const [1, 2, 3, 4, 5, 6, 7]);
      expect(p.state.seats.length, 7);
      expect(p.state.alive.length, 7, reason: '只出现过 2 号发言，其余座位也必须还在场');
    });
  });

  group('事件文案', () {
    const roles = {1: '预言家', 2: '狼人', 3: '白痴'};
    String text(ReplayEvent e) => replayLine(e, roles).text;

    test('死因机器码翻成中文', () {
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'WOLF')), contains('狼人刀杀'));
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'POISON')), contains('女巫毒杀'));
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'EXILE')), contains('投票放逐'));
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'SHOT')), contains('枪杀'));
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'BLOWUP')), contains('自爆'));
      // 未知死因原样透出，不吞信息
      expect(text(ev(0, 'PLAYER_DIED', actor: 3, detail: 'WEIRD')), contains('WEIRD'));
    });

    test('发言的三种特例：PK 轮、被禁言、普通', () {
      expect(text(ev(0, 'SPEECH', actor: 2, detail: '我是好人')), '2号：我是好人');
      expect(text(ev(0, 'SPEECH', actor: 2, detail: 'PK: 我先说')), contains('（PK 轮）'));
      expect(text(ev(0, 'SPEECH', actor: 2, detail: '[被禁言，过麦]')), contains('被禁言'));
    });

    test('天亮播报区分平安夜与倒牌', () {
      expect(text(ev(0, 'DAWN_ANNOUNCE', detail: 'deaths=[]')), contains('平安夜'));
      expect(text(ev(0, 'DAWN_ANNOUNCE', detail: 'deaths=[4, 7]')), contains('4号、7号'));
    });

    test('夜晚结算摘要把计数翻成人话', () {
      expect(text(ev(0, 'NIGHT_RESOLVE', detail: '0 wolfKill=2 guard=0 save=0 poison=0')), contains('刀 2'));
      expect(text(ev(0, 'NIGHT_RESOLVE', detail: '0 wolfKill=0 guard=0 save=0 poison=0')), contains('无人伤亡'));
      expect(text(ev(0, 'NIGHT_RESOLVE', target: 4, detail: 'PROTECTED_BY_GUARD')), contains('4号被守卫守住'));
    });

    test('平票 PK 能从 Java List toString 里取座位', () {
      expect(text(ev(0, 'VOTE_TIE', detail: '平票 PK: [1, 4]')), contains('1号、4号'));
    });

    test('警长三种结局都有对应说法', () {
      expect(text(ev(0, 'SHERIFF_WIN', actor: 1, detail: '当选警长')), startsWith('1号'));
      expect(text(ev(0, 'SHERIFF_WIN', actor: 0, detail: '警徽撕毁')), contains('撕毁'));
      expect(text(ev(0, 'SHERIFF_WIN', actor: 0, detail: '平票或无当选，本轮无警长')), contains('无警长'));
    });

    test('开枪区分带走与放弃', () {
      expect(text(ev(0, 'SHOOT', actor: 4, target: 2, detail: '猎人开枪')), contains('带走 2号'));
      expect(text(ev(0, 'SHOOT', actor: 4, target: 0, detail: '放弃开枪')), contains('放弃开枪'));
    });

    test('GAME_OVER 文案用事件里的 winner=xxx', () {
      expect(text(ev(0, 'GAME_OVER', detail: 'winner=狼人')), contains('狼人胜'));
    });

    test('未知 type 兜底不抛且给出可读内容', () {
      final l = replayLine(ev(0, 'BRAND_NEW_TYPE', actor: 3, detail: '某种新效果'), roles);
      expect(l.emoji, isNotEmpty);
      expect(l.text, contains('某种新效果'));
    });

    test('所有已知 type 都能出非空文案（防止漏分支导致空白行）', () {
      const all = [
        'GAME_START', 'SPEECH', 'LAST_WORDS', 'PLAYER_DIED', 'SHOOT', 'WHITE_WOLF_BLOWUP',
        'VOTE_RESULT', 'VOTE_DETAIL', 'VOTE_TIE', 'DAWN_ANNOUNCE', 'NIGHT_RESOLVE',
        'NIGHT_ACTION', 'SHERIFF_WIN', 'IDIOT_REVEAL', 'ITEM_EFFECT', 'ITEM_REVEAL', 'GAME_OVER',
      ];
      for (final t in all) {
        final l = replayLine(ev(0, t, actor: 2, target: 3, detail: 'X'), roles);
        expect(l.text.trim(), isNotEmpty, reason: '$t 渲染成空行');
      }
    });
  });
}
