import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/store.dart';

/// WS 下行派发回归：把服务端**真实形状**的载荷喂进 AppStore.handleWsEvent，
/// 断言界面赖以渲染的状态被正确更新。载荷字段照抄后端发送点：
/// RoomBroadcaster.broadcastEvent / LiveGameService(wsPush "game.chat"/"narrator") /
/// FriendService(chat.msg、room.invite、friend.request) / TicketService(ticket.update)。
void main() {
  AppStore newStore() {
    SharedPreferences.setMockInitialValues({});
    final s = AppStore(ApiClient(ServerConfig(baseUrl: 'https://unit.test.invalid:11111')));
    // 纯派发测试不跑 UI：关掉音效，避免 Sfx.play -> AudioPlayer() 触达 EventChannel
    // （测试绑定未初始化时会异步抛出，逃逸出 try/catch）。
    s.sfx.enabled = false;
    return s;
  }

  group('布告栏 room.event', () {
    test('连续重复才折叠成 ×N（不回头改写历史条目）', () {
      final s = newStore();
      s.handleWsEvent({'type': 'room.event', 'message': '房主添加了 AI 补位玩家'});
      s.handleWsEvent({'type': 'room.event', 'message': '房主添加了 AI 补位玩家'});
      s.handleWsEvent({'type': 'room.event', 'message': '房主添加了 AI 补位玩家'});
      s.handleWsEvent({'type': 'room.event', 'message': '三端自测 加入了房间'});
      s.handleWsEvent({'type': 'room.event', 'message': '房主添加了 AI 补位玩家'});
      expect(s.roomFeed, [
        '房主添加了 AI 补位玩家 ×3',
        '三端自测 加入了房间',
        '房主添加了 AI 补位玩家',
      ]);
    });

    test('空消息与缺字段不入库', () {
      final s = newStore();
      s.handleWsEvent({'type': 'room.event'});
      s.handleWsEvent({'type': 'room.event', 'message': ''});
      expect(s.roomFeed, isEmpty);
    });

    test('最多保留 60 条，新的挤掉最旧的', () {
      final s = newStore();
      for (var i = 0; i < 75; i++) {
        s.handleWsEvent({'type': 'room.event', 'message': '动态 $i'});
      }
      expect(s.roomFeed.length, 60);
      expect(s.roomFeed.first, '动态 15');
      expect(s.roomFeed.last, '动态 74');
    });
  });

  group('对局文字流 game.chat / narrator', () {
    test('聊天带座位与昵称，旁白单独成种', () {
      final s = newStore();
      s.handleWsEvent({'type': 'game.chat', 'room': 7, 'seat': 3, 'name': '3号', 'text': '我是好人'});
      s.handleWsEvent({'type': 'narrator', 'room': 7, 'name': '旁白', 'text': '昨夜 2 号倒牌'});
      expect(s.gameChat.length, 2);
      expect(s.gameChat.first.name, '3号');
      expect(s.gameChat.first.seat, 3);
      expect(s.gameChat.first.kind, 'chat');
      expect(s.gameChat.last.kind, 'narrator');
      expect(s.gameChat.last.text, '昨夜 2 号倒牌');
    });

    test('上限 200 条，长局不会无限吃内存', () {
      final s = newStore();
      for (var i = 0; i < 260; i++) {
        s.handleWsEvent({'type': 'game.chat', 'seat': 1, 'name': 'A', 'text': 'm$i'});
      }
      expect(s.gameChat.length, 200);
      expect(s.gameChat.last.text, 'm259');
    });
  });

  group('未读与红点', () {
    test('私聊：入 livePrivate、未读 +1、浮层显示"谁说什么"', () {
      final s = newStore();
      s.handleWsEvent({
        'type': 'chat.msg', 'id': 88, 'from': 3021, 'to': 2180, 'type2': 'chat',
        'content': '晚上一起？', 'roomNo': null, 'mine': false, 'fromName': '阿尔法',
      });
      expect(s.livePrivate.single['id'], 88);
      expect(s.unreadMessages, 1);
      expect(s.unreadTotal, 1);
      expect(s.notices.last.text, '阿尔法：晚上一起？');
      expect(s.notices.last.kind, 'info');
    });

    test('好友申请：未读申请 +1 并提示昵称', () {
      final s = newStore();
      s.handleWsEvent({
        'type': 'friend.request',
        'reqId': 5,
        'greeting': '带我玩',
        'from': {'id': 3021, 'nickname': '阿尔法'},
      });
      expect(s.unreadRequests, 1);
      expect(s.unreadTotal, 1);
      expect(s.notices.last.text, '阿尔法 请求添加你为好友');
    });

    test('refreshUnread 用服务端聚合覆盖本地计数', () async {
      final s = newStore()
        ..token = 't'
        ..unreadMessages = 9
        ..unreadRequests = 9;
      // 无网络时不应把计数清零，也不应抛出未捕获异常
      await s.refreshUnread();
      expect(s.unreadMessages, 9);
      expect(s.unreadRequests, 9);
    });
  });

  group('邀请与工单', () {
    test('room.invite 生成可一键加入的浮层', () {
      final s = newStore();
      s.handleWsEvent({
        'type': 'room.invite', 'id': 3, 'from': 3021, 'fromName': '阿尔法',
        'content': '来我房间', 'roomNo': '200728', 'mine': false,
      });
      final n = s.notices.last;
      expect(n.kind, 'invite');
      expect(n.roomNo, '200728');
      expect(n.text, contains('阿尔法'));
      s.dismissNotice(n.id);
      expect(s.notices, isEmpty);
    });

    test('ticket.update 把状态翻成中文', () {
      final s = newStore();
      s.handleWsEvent({'type': 'ticket.update', 'id': 7, 'status': 'REPLIED'});
      expect(s.notices.last.text, '工单 #7 状态更新：已回复');
      s.handleWsEvent({'type': 'ticket.update', 'id': 7, 'status': 'CLOSED'});
      expect(s.notices.last.text, '工单 #7 状态更新：已关闭');
    });

    test('浮层最多同时 6 条，避免刷屏挡操作', () {
      final s = newStore();
      for (var i = 0; i < 12; i++) {
        s.handleWsEvent({'type': 'chat.msg', 'id': i, 'from': 1, 'fromName': 'A', 'content': 'm$i'});
      }
      expect(s.notices.length, 6);
      expect(s.notices.last.text, 'A：m11');
    });
  });

  group('房间与对局的清理', () {
    test('被踢：清房间/对局/布告栏/聊天，并弹警告浮层', () {
      final s = newStore();
      s.handleWsEvent({'type': 'room.state', 'room': {'roomNo': '1', 'players': []}});
      s.handleWsEvent({'type': 'room.event', 'message': '有人进房'});
      s.handleWsEvent({'type': 'game.chat', 'seat': 1, 'name': 'A', 'text': 'hi'});
      s.handleWsEvent({'type': 'room.kicked', 'message': '你被房主移出房间'});
      expect(s.room, isNull);
      expect(s.game, isNull);
      expect(s.roomFeed, isEmpty);
      expect(s.gameChat, isEmpty);
      expect(s.notices.last.kind, 'warn');
      expect(s.notices.last.text, '你被房主移出房间');
    });

    test('离开房间（room.state 带空 room）也清空布告栏', () {
      final s = newStore();
      s.handleWsEvent({'type': 'room.event', 'message': 'x'});
      s.handleWsEvent({'type': 'room.state', 'room': {}});
      expect(s.room, isNull);
      expect(s.roomFeed, isEmpty);
    });

    test('game.ended 清掉对局文字流', () {
      final s = newStore();
      s.handleWsEvent({'type': 'game.chat', 'seat': 1, 'name': 'A', 'text': 'hi'});
      s.handleWsEvent({'type': 'game.ended', 'reason': 'WOLVES_WIN', 'forced': false});
      expect(s.game, isNull);
      expect(s.gameChat, isEmpty);
    });
  });

  group('健壮性', () {
    test('未知类型 / 字段缺失 / 类型不对都不抛', () {
      final s = newStore();
      expect(() => s.handleWsEvent({'type': 'totally.unknown'}), returnsNormally);
      expect(() => s.handleWsEvent({'type': 'game.chat'}), returnsNormally);
      expect(() => s.handleWsEvent({'type': 'chat.msg', 'fromName': null}), returnsNormally);
      expect(() => s.handleWsEvent({'type': 'friend.request'}), returnsNormally);
      expect(() => s.handleWsEvent({'type': 'room.event', 'message': 123}), returnsNormally);
      expect(() => s.handleWsEvent({}), returnsNormally);
    });

    test('welcome 与 pong 只用于链路，不进任何列表', () {
      final s = newStore();
      s.handleWsEvent({'type': 'welcome', 'message': '欢迎来到狼人杀'});
      s.handleWsEvent({'type': 'pong', 'time': 1});
      expect(s.notices, isEmpty);
      expect(s.roomFeed, isEmpty);
      expect(s.gameChat, isEmpty);
    });

    test('每次事件都会通知监听者（界面才会重绘）', () {
      final s = newStore();
      var hits = 0;
      s.addListener(() => hits++);
      s.handleWsEvent({'type': 'room.event', 'message': 'a'});
      s.handleWsEvent({'type': 'game.chat', 'seat': 1, 'name': 'A', 'text': 'b'});
      expect(hits, 2);
    });
  });
}
