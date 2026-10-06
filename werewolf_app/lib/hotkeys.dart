/// 对局页桌面键盘快捷键的**纯逻辑层**。
///
/// 与网页版 `static/js/ux.js` 里的 `HOTKEYS` / `mountHotkeys` 对齐：把「按了什么键」
/// 翻译成一个与界面无关的动作结果，界面层只负责执行。这里刻意不引用任何 widget，
/// 以便用普通单测覆盖全部判定分支（见 `test/hotkeys_test.dart`）。
library;

/// 快捷键触发的动作种类。
enum HotkeyAction {
  /// 选择目标座位；[HotkeyHit.index] 为 0 基下标（1..9 → 0..8，0 → 9）。
  target,

  /// 过麦 / 放弃本次发言或投票（网页版按 A）。
  pass,

  /// 聚焦对局聊天输入框（网页版按 F）。
  focusChat,

  /// 开 / 关麦克风。本端没有网页版的「座位标记」子系统，M 改用于语音按钮。
  mic,

  /// 打开快捷键帮助（? 或 Shift+/）。
  help,
}

/// 一次命中的结果。[index] 仅对 [HotkeyAction.target] 有意义。
class HotkeyHit {
  final HotkeyAction action;
  final int index;

  const HotkeyHit(this.action, {this.index = 0});

  @override
  bool operator ==(Object other) =>
      other is HotkeyHit && other.action == action && other.index == index;

  @override
  int get hashCode => Object.hash(action, index);

  @override
  String toString() => 'HotkeyHit($action, index: $index)';
}

/// 把一次按键解析成一个 [HotkeyHit]，不命中返回 null。
///
/// 判定顺序完全照搬网页版 mountHotkeys（见 ux.js 223-268 行）：
/// 1. `Escape` 交给框架（关闭弹层）处理，本层一律 null；
/// 2. 正在输入（input/textarea/select）时所有快捷键一律不生效；
/// 3. `?` 或 `Shift+/` → 帮助；
/// 4. `a` → 仅当能行动且存在「放弃」入口时过麦；
/// 5. `f` → 聚焦聊天框；`m` → 麦克风；
/// 6. `1`..`9` → 目标下标 0..8；`0` → 目标下标 9；越界或不满足则 null；
/// 7. 其它键 → null。
///
/// 入参：
/// - [key]：字符形式的按键（如 `'a'`、`'1'`、`'?'`、`'/'`、`'Escape'`），大小写不敏感；
/// - [shift]：是否按住 Shift；
/// - [typing]：当前是否正在文本输入（由界面层探测焦点是否在输入框内）；
/// - [canAct]：当前是否轮到自己行动（myTurn 且非终局）；
/// - [targetCount]：可点击的目标按钮数量（存活且非本人座位）；
/// - [hasPass]：当前场景是否存在「放弃 / 过麦」入口。
HotkeyHit? hotkeyFor({
  required String key,
  required bool shift,
  required bool typing,
  required bool canAct,
  required int targetCount,
  required bool hasPass,
}) {
  // 1. Escape 交给框架：网页版同样直接 return，由 nav.js 关闭弹层 / 抽屉。
  if (key.toLowerCase() == 'escape') return null;

  // 2. 正在输入时一切快捷键失效，避免吃掉用户打字。
  if (typing) return null;

  // 统一小写后判定；单字符键大小写不敏感。
  final k = key.toLowerCase();

  // 3. 帮助：? 或 Shift+/（不同键盘布局下 Shift+/ 可能直接是 '?'）。
  if (k == '?' || (shift && k == '/')) return const HotkeyHit(HotkeyAction.help);

  // 4. 过麦：Shift 对字母键无意义（与网页版 toLowerCase 后判定一致）。
  if (k == 'a') {
    return (canAct && hasPass) ? const HotkeyHit(HotkeyAction.pass) : null;
  }

  // 5. 全局可用，不受 canAct 门控。
  if (k == 'f') return const HotkeyHit(HotkeyAction.focusChat);
  if (k == 'm') return const HotkeyHit(HotkeyAction.mic);

  // 6. 目标选择：1..9 → 下标 0..8；0 → 下标 9。
  if (k.length == 1) {
    final code = k.codeUnitAt(0);
    if (code >= 0x31 && code <= 0x39) {
      final index = code - 0x31; // '1' -> 0
      if (canAct && index < targetCount) return HotkeyHit(HotkeyAction.target, index: index);
      return null;
    }
    if (k == '0') {
      if (canAct && 9 < targetCount) return const HotkeyHit(HotkeyAction.target, index: 9);
      return null;
    }
  }

  // 7. 其它键不处理。
  return null;
}

/// 帮助弹窗里的一行：键位 → 说明。
class HotkeyHelp {
  final String keys;
  final String desc;

  const HotkeyHelp(this.keys, this.desc);
}

/// 快捷键帮助文案（中文）。
///
/// 末尾两项显式标注「网页独有、本端不适用」的项：主题（T）/ 密度（D）/ 座位标记（网页版 M），
/// 因为 Flutter 端没有对应子系统，故不实现 T / D，并把 M 复用为麦克风开关。
const List<HotkeyHelp> hotkeyHelp = [
  HotkeyHelp('1 – 9 / 0', '对局中按序号选择目标按钮（第 10 个用 0）'),
  HotkeyHelp('A', '快速过麦（放弃本次发言 / 投票）'),
  HotkeyHelp('F', '聚焦对局聊天输入框'),
  HotkeyHelp('M', '开 / 关麦克风'),
  HotkeyHelp('?', '打开本帮助'),
  HotkeyHelp('Esc', '关闭弹窗 / 浮层（由系统处理）'),
  HotkeyHelp('T / D', '网页版：随机主题 / 切换密度（本端无此子系统，不适用）'),
  HotkeyHelp('M（网页版）', '网页版：循环座位标记（本端无标记子系统，不适用）'),
];
