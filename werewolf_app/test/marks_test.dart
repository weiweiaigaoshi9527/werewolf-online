import 'package:flutter_test/flutter_test.dart';
import 'package:werewolf_app/marks.dart';

/// 座位标记纯逻辑（等级循环 + 持久化编解码）回归。
/// 口径对齐网页版 `static/js/app.js` 的 `MARK`：等级 0 未标 / 1 可疑 / 2 好人 / 3 狼人。
void main() {
  group('markLabels 与 cycleMark', () {
    test('等级名称与序号一一对应', () {
      expect(markLabels, ['未标', '可疑', '好人', '狼人']);
    });

    test('循环推进：未标 → 可疑 → 好人 → 狼人 → 未标', () {
      expect(cycleMark(null), 1);
      expect(cycleMark(0), 1);
      expect(cycleMark(1), 2);
      expect(cycleMark(2), 3);
      expect(cycleMark(3), 0); // 回到未标
      expect(cycleMark(4), 1); // 容忍越界值
    });
  });

  test('marksKey 与网页端 localStorage 同键名', () {
    expect(marksKey('200728'), 'ww_marks_200728');
  });

  group('parseMarks', () {
    test('空 / null / 损坏 / 类型不符一律当空表', () {
      expect(parseMarks(null), isEmpty);
      expect(parseMarks(''), isEmpty);
      expect(parseMarks('{bad'), isEmpty);
      expect(parseMarks('[1,2]'), isEmpty);
      expect(parseMarks('"x"'), isEmpty);
      expect(parseMarks('3'), isEmpty);
    });

    test('解析座位号 → 等级（键值都是数字）', () {
      expect(parseMarks('{"2":3,"5":1}'), {2: 3, 5: 1});
    });

    test('字符串数字的键与值同样能解析', () {
      expect(parseMarks('{"7":"2"}'), {7: 2});
    });

    test('越界等级对 4 取模', () {
      expect(parseMarks('{"1":7}'), {1: 3});
      expect(parseMarks('{"1":-1}'), {1: 3});
    });

    test('无法解析的条目被跳过', () {
      expect(parseMarks('{"a":1,"2":"x","3":2}'), {3: 2});
    });
  });

  group('encodeMarks', () {
    test('空表 / 全 0 表返回空串（调用方据此删除该键）', () {
      expect(encodeMarks(const {}), '');
      expect(encodeMarks(const {3: 0}), '');
    });

    test('只写非 0 等级，键为字符串', () {
      expect(encodeMarks(const {2: 3, 5: 0, 6: 1}), '{"2":3,"6":1}');
    });

    test('编解码往返一致', () {
      const m = {1: 1, 4: 2, 9: 3};
      expect(parseMarks(encodeMarks(m)), m);
    });
  });
}
