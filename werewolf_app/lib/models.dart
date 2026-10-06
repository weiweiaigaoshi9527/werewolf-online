/// 跨层共享的轻量数据模型（不 import 任何 UI / store，避免循环依赖）。
library;

/// 一条实时通知（由 WS 推送产生，全局浮层自动消失）。
class WwNotice {
  WwNotice(this.id, this.text,
      {this.kind = 'info', this.roomNo, this.ttl = const Duration(milliseconds: 4500)});

  final int id;
  final String text;

  /// info / warn / invite
  final String kind;

  /// kind == 'invite' 时可一键加入的房间号
  final String? roomNo;
  final Duration ttl;
}

/// 对局内的一条文字流（普通聊天或旁白），来源是 WS 的 game.chat / narrator。
class WwChatLine {
  WwChatLine({required this.name, required this.text, this.seat, this.kind = 'chat'});

  final String name;
  final String text;
  final int? seat;

  /// chat / narrator
  final String kind;
}
