/// 座位标记的**纯逻辑层**：等级循环、持久化编解码。
///
/// 与网页版 `static/js/app.js` 的 `MARK` 同口径：本地、按房间保存、只有自己可见。
/// 等级 0 = 未标 / 1 = 可疑 / 2 = 好人 / 3 = 狼人，每点一次 +1（对 4 取模）。
/// 存储键名沿用网页端的 `ww_marks_<房间号>`，值是一段 JSON 对象（`{"2":3}`）。
/// 这里刻意不引用任何 widget，以便用普通单测覆盖全部编解码分支（见 `test/marks_test.dart`）。
library;

import 'dart:convert';

/// 标记等级名称（下标即等级）。
const List<String> markLabels = ['未标', '可疑', '好人', '狼人'];

/// 循环推进一级：未标 → 可疑 → 好人 → 狼人 → 未标（对 4 取模）。
int cycleMark(int? level) => ((level ?? 0) + 1) % markLabels.length;

/// 网页端 localStorage / 本端 SharedPreferences 共用的键名。
String marksKey(String roomNo) => 'ww_marks_$roomNo';

/// 解析持久化内容；空、损坏、类型不符一律当空表（与网页版 try/catch 一致）。
Map<int, int> parseMarks(String? raw) {
  if (raw == null || raw.isEmpty) return const {};
  try {
    final v = jsonDecode(raw);
    if (v is! Map) return const {};
    final out = <int, int>{};
    v.forEach((k, val) {
      final seat = int.tryParse('$k');
      final lvl = val is num ? val.toInt() : int.tryParse('$val');
      if (seat != null && lvl != null) out[seat] = lvl % markLabels.length;
    });
    return out;
  } catch (_) {
    return const {};
  }
}

/// 编码为 JSON 文本；空表返回空串（此时调用方应删除该键，而不是写入 `{}`）。
String encodeMarks(Map<int, int> marks) {
  final live = marks.entries.where((e) => e.value != 0).toList();
  if (live.isEmpty) return '';
  return jsonEncode({for (final e in live) '${e.key}': e.value});
}
