/// 多语言子系统（P3）：把网页端 `static/js/i18n.js` 的机制搬到客户端。
///
/// 网页端走的是「中文原文自动翻译」——按**精确整串**把界面上出现的中文换成译文，
/// 无需给每个元素加 key。Flutter 没有 DOM，但可以等价地用 [Tx] 顶替 [Text]：
/// 凡是经由 Tx 的界面文本都会被 [tr] 翻一次，词典里没有的原样返回中文，
/// 因此对局内容（身份名 / AI 发言 / 旁白 / 昵称）天然不受影响。
///
/// 非 Text 的散装界面串（输入框 hint / tooltip / 对话框标题等）用 [tr] 显式包裹。
/// 持久化键名沿用网页端：`ww_lang`。
library;

import 'package:flutter/material.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'i18n_client.dart';
import 'i18n_data.dart';
import 'theme.dart';

/// 语言元数据：[代码, 母语名, 英文名]。不放国旗 emoji（Windows 不渲染区域指示符）。
const List<List<String>> kLangMeta = [
  ['zh', '中文', 'Chinese'],
  ['en', 'English', 'English'],
  ['ja', '日本語', 'Japanese'],
  ['ko', '한국어', 'Korean'],
  ['ru', 'Русский', 'Russian'],
];

/// 主表 + 增量表 + 骨架表合并后的查表用词典（后写覆盖先写，与网页端 JS 一致）。
final Map<String, Map<String, String>> kI18nDict = () {
  final out = <String, Map<String, String>>{};
  for (final m in kLangMeta) {
    final code = m[0];
    out[code] = <String, String>{
      ...?kI18nBase[code],
      ...?kI18nExtra[code],
      ...?kI18nShell[code],
      ...?kI18nClient[code],
    };
  }
  return out;
}();

/// 语言状态（全局单例，见文件末尾 [i18n]）。
class I18n extends ChangeNotifier {
  static const _kLang = 'ww_lang';

  String _lang = 'zh';
  String get lang => _lang;
  bool get isZh => _lang == 'zh';

  /// 当前语言的母语名，供顶栏/菜单显示。
  String get nativeName =>
      kLangMeta.firstWhere((m) => m[0] == _lang, orElse: () => kLangMeta.first)[1];

  /// 从 SharedPreferences 读取（键名与网页端一致）。非法值一律退回中文。
  Future<void> load() async {
    try {
      final p = await SharedPreferences.getInstance();
      final l = p.getString(_kLang) ?? 'zh';
      _lang = kLangMeta.any((m) => m[0] == l) ? l : 'zh';
      notifyListeners();
    } catch (_) {}
  }

  Future<void> setLang(String code) async {
    if (code == _lang || !kLangMeta.any((m) => m[0] == code)) return;
    _lang = code;
    notifyListeners();
    try {
      final p = await SharedPreferences.getInstance();
      await p.setString(_kLang, code);
    } catch (_) {}
  }

  /// 供测试重置（不落盘）。
  @visibleForTesting
  void debugReset() {
    _lang = 'zh';
    notifyListeners();
  }
}

/// 全局语言单例：界面切语言时由 main.dart 重建整棵树。
final I18n i18n = I18n();

/// 把中文原文翻成当前语言：精确整串优先，其次正则模式，最后原样返回中文。
String tr(String zh) {
  if (zh.isEmpty) return zh;
  final l = i18n.lang;
  if (l == 'zh') return zh;
  final hit = kI18nDict[l]?[zh];
  if (hit != null) return hit;
  final pats = kI18nPatterns[l];
  if (pats != null) {
    for (final p in pats) {
      if (p.re.hasMatch(zh)) {
        return zh.replaceAllMapped(p.re, (m) => _expandRep(p.rep, m));
      }
    }
  }
  return zh;
}

/// 展开译文里的 `$1` / `$&` 分组引用（网页端走 JS `String.replace`，天然支持）。
/// Dart 的 replaceAll 不做分组替换，必须用 replaceAllMapped + 手工展开。
String _expandRep(String rep, Match m) {
  final sb = StringBuffer();
  for (var i = 0; i < rep.length; i++) {
    if (rep[i] != r'$' || i + 1 >= rep.length) {
      sb.write(rep[i]);
      continue;
    }
    final n = rep[i + 1];
    if (n == r'$') {
      sb.write(r'$');
      i++;
    } else if (n == '&') {
      sb.write(m[0] ?? '');
      i++;
    } else {
      final g = int.tryParse(n);
      if (g != null && g <= m.groupCount) {
        sb.write(m[g] ?? '');
        i++;
      } else {
        sb.write(rep[i]); // 无法识别的 $x 原样保留
      }
    }
  }
  return sb.toString();
}

/// [Text] 的多语言替身：与 Text 同签名，渲染时把 data 交给 [tr]。
///
/// 不做成 Text 的子类：Text 的 data 在超类构造里已定型且不可变，包一层才能保证
/// 每次重建都重新查表（切语言时整棵树会被 main.dart 重挂）。
class Tx extends StatelessWidget {
  final String data;
  final TextStyle? style;
  final StrutStyle? strutStyle;
  final TextAlign? textAlign;
  final TextDirection? textDirection;
  final Locale? locale;
  final bool? softWrap;
  final TextOverflow? overflow;
  final TextScaler? textScaler;
  final int? maxLines;
  final String? semanticsLabel;
  final TextWidthBasis? textWidthBasis;
  final TextHeightBehavior? textHeightBehavior;
  final Color? selectionColor;

  const Tx(
    this.data, {
    super.key,
    this.style,
    this.strutStyle,
    this.textAlign,
    this.textDirection,
    this.locale,
    this.softWrap,
    this.overflow,
    this.textScaler,
    this.maxLines,
    this.semanticsLabel,
    this.textWidthBasis,
    this.textHeightBehavior,
    this.selectionColor,
  });

  @override
  Widget build(BuildContext context) => Text(
        tr(data),
        style: style,
        strutStyle: strutStyle,
        textAlign: textAlign,
        textDirection: textDirection,
        locale: locale,
        softWrap: softWrap,
        overflow: overflow,
        textScaler: textScaler,
        maxLines: maxLines,
        semanticsLabel: semanticsLabel,
        textWidthBasis: textWidthBasis,
        textHeightBehavior: textHeightBehavior,
        selectionColor: selectionColor,
      );
}

/// 顶栏语言按钮（🌐），点开底部弹层选语言。
class LangButton extends StatelessWidget {
  const LangButton({super.key});

  @override
  Widget build(BuildContext context) => IconButton(
        tooltip: '语言 / Language',
        icon: const Icon(Icons.language),
        onPressed: () => showLangPicker(context),
      );
}

/// 语言选择弹层：五种语言，母语名 + 英文名，当前项打勾。
/// 与网页端一样，语言名本身不参与翻译（菜单里以 Text 直接渲染）。
Future<void> showLangPicker(BuildContext context) async {
  await showModalBottomSheet<void>(
    context: context,
    backgroundColor: kPanel,
    shape: const RoundedRectangleBorder(
      borderRadius: BorderRadius.vertical(top: Radius.circular(18)),
    ),
    // 弹层高度受 9/16 屏限制，小屏/横屏会溢出，故整体可滚动。
    builder: (c) => SafeArea(
      child: SingleChildScrollView(
        child: Column(
          mainAxisSize: MainAxisSize.min,
          children: [
            Padding(
              padding: const EdgeInsets.fromLTRB(18, 16, 18, 6),
              child: Row(children: [
                Icon(Icons.language, size: 20, color: kAccent),
                const SizedBox(width: 8),
                Text('语言 / Language',
                    style: TextStyle(color: kMoon, fontWeight: FontWeight.bold, fontSize: 15)),
              ]),
            ),
            for (final m in kLangMeta)
              ListTile(
                dense: true,
                title: Text(m[1], style: TextStyle(color: kText)),
                subtitle: Text(m[2], style: TextStyle(color: kFaint, fontSize: 12)),
                trailing: i18n.lang == m[0] ? Icon(Icons.check, color: kAccent) : null,
                onTap: () {
                  i18n.setLang(m[0]);
                  Navigator.pop(c);
                },
              ),
            const SizedBox(height: 8),
          ],
        ),
      ),
    ),
  );
}
