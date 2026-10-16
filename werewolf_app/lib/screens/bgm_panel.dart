import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';

import '../bgm.dart';
import '../i18n.dart';
import '../theme.dart';

/// 打开「背景音乐」面板（与网页端 🎵 浮层对齐：添加本地 MP3 / 列表 / 上一首 /
/// 播放暂停 / 下一首 / 音量 / 循环）。
Future<void> showBgmPanel(BuildContext context) {
  return showDialog<void>(
    context: context,
    builder: (_) => const BgmPanel(),
  );
}

class BgmPanel extends StatelessWidget {
  const BgmPanel({super.key});

  @override
  Widget build(BuildContext context) {
    return Dialog(
      backgroundColor: kPanel,
      insetPadding: const EdgeInsets.symmetric(horizontal: 20, vertical: 40),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(18),
        side: BorderSide(color: kBorder),
      ),
      child: ConstrainedBox(
        constraints: const BoxConstraints(maxWidth: 480),
        // 面板是独立路由，不跟着主树换 key 重建，所以自己订阅语言与播放状态。
        child: ListenableBuilder(
          listenable: Listenable.merge([bgm, i18n]),
          builder: (context, _) => Padding(
            padding: const EdgeInsets.fromLTRB(18, 14, 18, 16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Row(
                  children: [
                    Tx('🎵 背景音乐',
                        style: TextStyle(color: kMoon, fontWeight: FontWeight.bold, fontSize: 16)),
                    const SizedBox(width: 8),
                    Tx('本地文件 · 不上传', style: TextStyle(color: kFaint, fontSize: 11.5)),
                    const Spacer(),
                    IconButton(
                      tooltip: tr('关闭'),
                      icon: Icon(Icons.close, color: kDim),
                      onPressed: () => Navigator.of(context).maybePop(),
                    ),
                  ],
                ),
                const SizedBox(height: 6),
                OutlinedButton.icon(
                  onPressed: () => _pick(context),
                  icon: const Icon(Icons.add, size: 18),
                  label: const Tx('➕ 添加本地 MP3'),
                ),
                const SizedBox(height: 10),
                _list(context),
                const SizedBox(height: 10),
                _controls(context),
              ],
            ),
          ),
        ),
      ),
    );
  }

  /// 播放列表：当前曲目带 ▶ 前缀，每行右侧 ✕ 可移除（与网页端 .bgm-item 一致）。
  Widget _list(BuildContext context) {
    if (bgm.isEmpty) {
      return Container(
        width: double.infinity,
        padding: const EdgeInsets.symmetric(vertical: 18),
        alignment: Alignment.center,
        decoration: BoxDecoration(
          color: kPanel2,
          borderRadius: BorderRadius.circular(10),
          border: Border.all(color: kBorder),
        ),
        child: Tx('还没有添加音乐，选几首喜欢的 MP3 吧',
            style: TextStyle(color: kDim, fontSize: 12.5)),
      );
    }
    return Container(
      constraints: const BoxConstraints(maxHeight: 240),
      decoration: BoxDecoration(
        color: kPanel2,
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: kBorder),
      ),
      child: ListView.builder(
        shrinkWrap: true,
        padding: const EdgeInsets.symmetric(vertical: 4),
        itemCount: bgm.tracks.length,
        itemBuilder: (c, i) {
          final t = bgm.tracks[i];
          final on = i == bgm.cur;
          return InkWell(
            onTap: () => bgm.play(i),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(12, 6, 6, 6),
              child: Row(
                children: [
                  Expanded(
                    child: Tx(
                      on ? '▶ ${t.name}' : t.name,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(color: on ? kAccent : kText, fontSize: 13),
                    ),
                  ),
                  IconButton(
                    tooltip: tr('移除'),
                    visualDensity: VisualDensity.compact,
                    icon: Icon(Icons.close, size: 16, color: kFaint),
                    onPressed: () => bgm.remove(t.id),
                  ),
                ],
              ),
            ),
          );
        },
      ),
    );
  }

  /// 控制条：⏮ ▶/⏸ ⏭ + 音量滑杆 + 循环勾选（网页端 .bgm-ctrl）。
  Widget _controls(BuildContext context) {
    return Wrap(
      spacing: 8,
      runSpacing: 4,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        IconButton(
          tooltip: tr('上一首'),
          icon: const Icon(Icons.skip_previous),
          onPressed: bgm.isEmpty ? null : bgm.prev,
        ),
        IconButton(
          tooltip: tr('播放/暂停'),
          iconSize: 32,
          color: kAccent,
          icon: Icon(bgm.playing ? Icons.pause_circle : Icons.play_circle),
          onPressed: () async {
            final ok = await bgm.toggle();
            if (!ok && context.mounted) _toast(context, '先添加几首本地 MP3 吧');
          },
        ),
        IconButton(
          tooltip: tr('下一首'),
          icon: const Icon(Icons.skip_next),
          onPressed: bgm.isEmpty ? null : bgm.next,
        ),
        SizedBox(
          width: 140,
          child: Slider(
            value: bgm.volume,
            onChanged: bgm.setVolume,
          ),
        ),
        Text('${(bgm.volume * 100).round()}%', style: TextStyle(color: kDim, fontSize: 12)),
        Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Checkbox(
              value: bgm.loop,
              onChanged: (v) => bgm.setLoop(v ?? false),
            ),
            Tx('循环', style: TextStyle(color: kDim, fontSize: 12.5)),
          ],
        ),
      ],
    );
  }

  /// 选本机 MP3（可多选）。桌面端 file_picker 给的是真实路径，直接存进列表。
  Future<void> _pick(BuildContext context) async {
    FilePickerResult? r;
    try {
      r = await FilePicker.platform.pickFiles(
        type: FileType.custom,
        allowedExtensions: const ['mp3'],
        allowMultiple: true,
      );
    } catch (e) {
      if (context.mounted) _toast(context, '当前平台不支持选择文件：$e');
      return;
    }
    if (r == null) return;
    final paths = <String>[];
    final names = <String>[];
    for (final f in r.files) {
      final p = f.path;
      if (p == null || p.isEmpty) continue;
      paths.add(p);
      names.add(f.name);
    }
    final n = await bgm.addFiles(paths, names);
    if (!context.mounted) return;
    if (n == 0) {
      _toast(context, '没有可用的本地文件路径');
      return;
    }
    _toast(context, '已添加 $n 首到播放列表');
  }

  void _toast(BuildContext context, String msg) {
    ScaffoldMessenger.of(context).showSnackBar(SnackBar(
      content: Tx(msg, style: TextStyle(color: kText)),
      backgroundColor: kPanel2,
      behavior: SnackBarBehavior.floating,
    ));
  }
}
