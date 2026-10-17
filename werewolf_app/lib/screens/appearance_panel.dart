import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../i18n.dart';
import '../theme.dart';

/// 打开「外观」面板（与网页端右上角 🌙 面板对齐：主题 / 强调色 / 字号 / 密度 /
/// 动效 / 背景亮度 / 自定义背景 / 随机主题）。
Future<void> showAppearancePanel(BuildContext context) {
  return showDialog<void>(
    context: context,
    builder: (_) => const AppearancePanel(),
  );
}

class AppearancePanel extends StatelessWidget {
  const AppearancePanel({super.key});

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: kPanel,
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 40),
      shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(18), side: BorderSide(color: kBorder)),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 560),
        child: ListenableBuilder(
          // 同时监听语言：面板是独立路由，不跟着主树换 key 重建，得自己订阅。
          listenable: Listenable.merge([appearance, i18n]),
          builder: (context, _) => SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(18, 14, 18, 18),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Tx('🌙 外观', style: TextStyle(color: kMoon, fontWeight: FontWeight.bold, fontSize: 17)),
                    const Spacer(),
                    TextButton.icon(
                      onPressed: () async {
                        final t = await appearance.randomTheme();
                        if (context.mounted) _toast(context, '${t.icon} ${t.name} 主题已应用');
                      },
                      icon: const Icon(Icons.casino, size: 18),
                      label: const Tx('随机'),
                    ),
                    IconButton(
                      tooltip: tr('关闭'),
                      icon: Icon(Icons.close, color: kDim),
                      onPressed: () => Navigator.of(context).maybePop(),
                    ),
                  ],
                ),
                const SizedBox(height: 4),
                _label('语言'),
                const SizedBox(height: 8),
                _langRow(),
                const SizedBox(height: 18),
                _label('主题（13 套）'),
                const SizedBox(height: 8),
                _themeGrid(context),
                const SizedBox(height: 18),
                _label('强调色（与主题正交）'),
                const SizedBox(height: 8),
                _accentRow(context),
                const SizedBox(height: 18),
                _label('字号'),
                const SizedBox(height: 8),
                _fontRow(),
                const SizedBox(height: 6),
                _densitySwitch(),
                _motionSwitch(),
                const SizedBox(height: 10),
                _label('背景亮度'),
                _brightnessRow(context),
                const SizedBox(height: 6),
                _label('自定义背景'),
                const SizedBox(height: 6),
                _bgRow(context),
                const SizedBox(height: 10),
                Tx('提示：所有外观选择都保存在本机，改完立即生效；热键 T 随机主题、D 切换密度。',
                    style: TextStyle(color: kFaint, fontSize: 12)),
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _label(String s) =>
      Tx(s, style: TextStyle(color: kDim, fontSize: 12.5, letterSpacing: 1));

  /// 语言选择：五种语言的母语名 Chip，当前语言用强调色高亮（语言名本身不参与翻译）。
  Widget _langRow() {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final m in kLangMeta)
          ChoiceChip(
            label: Tx(m[1]),
            selected: i18n.lang == m[0],
            onSelected: (_) => i18n.setLang(m[0]),
            backgroundColor: kPanel2,
            selectedColor: kAccentSoft,
            labelStyle: TextStyle(color: i18n.lang == m[0] ? kAccent : kDim),
            side: BorderSide(color: i18n.lang == m[0] ? kAccent : kBorder),
          ),
      ],
    );
  }

  /// 主题格子：三色预览 + 图标名称，选中项用强调色描边（对齐网页端 .theme-cell）。
  Widget _themeGrid(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 8,
      children: [
        for (final t in kWwThemes)
          InkWell(
            borderRadius: BorderRadius.circular(12),
            onTap: () => appearance.setTheme(t.id),
            child: Container(
              width: 100,
              padding: const EdgeInsets.all(8),
              decoration: BoxDecoration(
                color: kPanel2,
                borderRadius: BorderRadius.circular(12),
                border: Border.all(
                  color: appearance.themeId == t.id ? kAccent : kBorder,
                  width: appearance.themeId == t.id ? 2 : 1,
                ),
              ),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Row(
                    children: [
                      for (final c in t.swatch)
                        Container(
                          width: 18,
                          height: 12,
                          margin: const EdgeInsets.only(right: 3),
                          decoration: BoxDecoration(color: c, borderRadius: BorderRadius.circular(3)),
                        ),
                    ],
                  ),
                  const SizedBox(height: 6),
                  Tx('${t.icon} ${t.name}', style: TextStyle(color: kText, fontSize: 12.5)),
                  if (t.light || t.a11y)
                    Tx(t.a11y ? '无障碍高对比' : '浅色', style: TextStyle(color: kFaint, fontSize: 10.5)),
                ],
              ),
            ),
          ),
      ],
    );
  }

  /// 强调色圆点：`auto` 显示「自动」文字（跟随主题令牌）。
  Widget _accentRow(BuildContext context) {
    return Wrap(
      spacing: 10,
      runSpacing: 8,
      children: [
        for (final a in kWwAccents)
          InkWell(
            borderRadius: BorderRadius.circular(20),
            onTap: () => appearance.setAccent(a.id),
            child: a.main == null
                ? _autoChip()
                : Container(
                    width: 30,
                    height: 30,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      color: a.main,
                      border: Border.all(
                        color: appearance.accentId == a.id ? kMoon : kBorder,
                        width: appearance.accentId == a.id ? 2.5 : 1,
                      ),
                    ),
                  ),
          ),
      ],
    );
  }

  Widget _autoChip() {
    final on = appearance.accentId == 'auto';
    return Container(
      height: 30,
      padding: const EdgeInsets.symmetric(horizontal: 10),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: on ? kAccentSoft : kPanel2,
        borderRadius: BorderRadius.circular(15),
        border: Border.all(color: on ? kAccent : kBorder),
      ),
      child: Tx('自动', style: TextStyle(color: on ? kAccent : kDim, fontSize: 12)),
    );
  }

  Widget _fontRow() {
    const opts = [('sm', '小'), ('md', '中'), ('lg', '大')];
    return Row(
      children: [
        for (final o in opts)
          Padding(
            padding: const EdgeInsets.only(right: 8),
            child: ChoiceChip(
              label: Tx(o.$2),
              selected: appearance.font == o.$1,
              onSelected: (_) => appearance.setFont(o.$1),
              backgroundColor: kPanel2,
              selectedColor: kAccentSoft,
              labelStyle: TextStyle(color: appearance.font == o.$1 ? kAccent : kDim),
              side: BorderSide(color: appearance.font == o.$1 ? kAccent : kBorder),
            ),
          ),
      ],
    );
  }

  Widget _densitySwitch() => SwitchListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        value: appearance.dense,
        onChanged: appearance.setDense,
        title: Tx('紧凑密度', style: TextStyle(color: kText, fontSize: 13.5)),
        subtitle: Tx('收窄间距与圆角，单屏显示更多内容', style: TextStyle(color: kFaint, fontSize: 11.5)),
      );

  Widget _motionSwitch() => SwitchListTile(
        contentPadding: EdgeInsets.zero,
        dense: true,
        value: appearance.motion,
        onChanged: appearance.setMotion,
        title: Tx('界面动效', style: TextStyle(color: kText, fontSize: 13.5)),
        subtitle: Tx('关闭后所有动画与过场立即完成', style: TextStyle(color: kFaint, fontSize: 11.5)),
      );

  Widget _brightnessRow(BuildContext context) => Row(
        children: [
          Expanded(
            child: Slider(
              min: 0.4,
              max: 1.6,
              value: appearance.bright,
              onChanged: appearance.setBright,
            ),
          ),
          SizedBox(
            width: 46,
            child: Tx('${(appearance.bright * 100).round()}%',
                textAlign: TextAlign.right, style: TextStyle(color: kDim, fontSize: 12.5)),
          ),
        ],
      );

  Widget _bgRow(BuildContext context) => Row(
        children: [
          OutlinedButton.icon(
            onPressed: () => _pickBg(context),
            icon: const Icon(Icons.image, size: 18),
            label: const Tx('选择图片'),
          ),
          const SizedBox(width: 10),
          OutlinedButton.icon(
            onPressed: () async {
              await appearance.clearBg();
              if (context.mounted) _toast(context, '已恢复默认背景');
            },
            icon: const Icon(Icons.restart_alt, size: 18),
            label: const Tx('恢复默认'),
          ),
          const SizedBox(width: 10),
          if (appearance.bgPath != null)
            Expanded(
              child: Tx(
                // 只取文件名：不依赖 Platform.pathSeparator（Web 上没有 Platform），
                // 正反斜杠都当分隔符，与桌面/移动结果一致。
                appearance.bgPath!.split(RegExp(r'[/\\]')).last,
                overflow: TextOverflow.ellipsis,
                style: TextStyle(color: kFaint, fontSize: 11.5),
              ),
            ),
        ],
      );

  /// 选择本地图片作背景（网页端限 ≤3MB，这里同样限制）。
  ///
  /// 一律 withData 取字节：桌面/移动 file_picker 给真实路径（走 setBgPath），
  /// Web 拿不到路径、只有字节（走 setBgBytes，用 Image.memory 渲染）。
  Future<void> _pickBg(BuildContext context) async {
    FilePickerResult? picked;
    try {
      picked = await FilePicker.platform.pickFiles(type: FileType.image, withData: true);
    } catch (e) {
      if (context.mounted) _toast(context, '当前平台不支持选择文件：$e');
      return;
    }
    final f = picked?.files.single;
    if (f == null) return;
    if (f.size > 3 * 1024 * 1024) {
      if (context.mounted) _toast(context, '图片过大（≤3MB）');
      return;
    }
    final path = f.path;
    if (path != null && path.isNotEmpty) {
      await appearance.setBgPath(path);
    } else if (f.bytes != null) {
      await appearance.setBgBytes(f.bytes!);
    } else {
      if (context.mounted) _toast(context, '当前平台拿不到图片数据');
      return;
    }
    if (context.mounted) _toast(context, '背景已更新');
  }

  void _toast(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Tx(msg, style: TextStyle(color: kText)),
      backgroundColor: kPanel2,
      behavior: SnackBarBehavior.floating,
    ));
  }
}
