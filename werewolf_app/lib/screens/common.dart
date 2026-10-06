import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../store.dart';
import '../theme.dart';

/// 各屏共用的轻量辅助：错误提示、分区标题、信息行、头像 emoji。

/// 把服务端给的 avatarUrl 解析成可加载的绝对地址。
/// 后端上传成功后返回的是**相对路径** `/uploads/avatars/u2180_xxx.png`（网页版靠同源解析，
/// 原生端直接喂给 Image.network 会加载失败并被 errorBuilder 兜回 emoji，看起来像"上传没生效"），
/// 所以要拼上当前服务器 baseUrl；已是 http(s)/data 的原样用，拿不到 baseUrl 时返回 null。
String? resolveAvatarUrl(BuildContext context, String? raw) {
  final v = (raw ?? '').trim();
  if (v.isEmpty) return null;
  if (v.startsWith('http://') || v.startsWith('https://') || v.startsWith('data:image')) return v;
  if (!v.startsWith('/')) return v;
  String base = '';
  try {
    base = Provider.of<AppStore>(context, listen: false).config.baseUrl.trim();
  } catch (_) {
    base = '';
  }
  if (base.isEmpty) return null;
  if (base.endsWith('/')) return '${base.substring(0, base.length - 1)}$v';
  return '$base$v';
}

void snack(BuildContext context, String msg, {bool error = false}) {
  ScaffoldMessenger.of(context).showSnackBar(SnackBar(
    content: Text(msg, style: TextStyle(color: error ? kBlood : kText)),
    backgroundColor: kPanel2,
    behavior: SnackBarBehavior.floating,
  ));
}

/// 与网页版大致一致的头像素（1..16）。
const List<String> kAvatarEmoji = [
  '🐺', '🌙', '🦊', '🦉', '🐻', '🐼', '🐨', '🦁',
  '🐯', '🐮', '🐷', '🐵', '🦇', '🐉', '🧙', '👻',
];
String avatarEmoji(dynamic id) {
  final i = (id is num ? id.toInt() : int.tryParse('$id') ?? 1);
  if (i < 1 || i > kAvatarEmoji.length) return '🐺';
  return kAvatarEmoji[i - 1];
}

class SectionTitle extends StatelessWidget {
  final String text;
  const SectionTitle(this.text, {super.key});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(2, 18, 2, 8),
        child: Text(text, style: const TextStyle(color: kDim, letterSpacing: 2, fontSize: 13)),
      );
}

/// 一行「标签：值」信息。
class InfoRow extends StatelessWidget {
  final String label;
  final String value;
  final Color? valueColor;
  const InfoRow(this.label, this.value, {super.key, this.valueColor});
  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.symmetric(vertical: 6),
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            SizedBox(width: 96, child: Text(label, style: const TextStyle(color: kDim, fontSize: 13))),
            Expanded(
                child: Text(value,
                    style: TextStyle(color: valueColor ?? kText, fontSize: 13.5, height: 1.4))),
          ],
        ),
      );
}

/// 圆形头像（emoji 或网络图）。
class WwAvatar extends StatelessWidget {
  final dynamic avatarId;
  final String? avatarUrl;
  final String? frameColor;
  final double size;
  const WwAvatar({super.key, this.avatarId, this.avatarUrl, this.frameColor, this.size = 44});

  @override
  Widget build(BuildContext context) {
    final url = resolveAvatarUrl(context, avatarUrl);
    final inner = (url != null)
        ? ClipOval(child: Image.network(url, width: size - 8, height: size - 8, fit: BoxFit.cover,
            errorBuilder: (_, __, ___) => Text(avatarEmoji(avatarId), style: TextStyle(fontSize: size * 0.5))))
        : Text(avatarEmoji(avatarId), style: TextStyle(fontSize: size * 0.5));
    return Container(
      width: size,
      height: size,
      decoration: BoxDecoration(
        shape: BoxShape.circle,
        color: kPanel,
        border: Border.all(color: Color(_parseColor(frameColor) ?? 0xFF2A3350), width: 2),
      ),
      alignment: Alignment.center,
      child: inner,
    );
  }
}

int? _parseColor(String? hex) {
  if (hex == null) return null;
  var h = hex.replaceFirst('#', '');
  if (h.length == 6) h = 'FF$h';
  return int.tryParse(h, radix: 16);
}

/// 顶部加载/错误/空态占位。
class StateBox extends StatelessWidget {
  final bool loading;
  final String? error;
  final bool empty;
  final String emptyText;
  final Widget child;
  const StateBox({super.key, required this.loading, this.error, this.empty = false, this.emptyText = '暂无内容', required this.child});

  @override
  Widget build(BuildContext context) {
    if (loading) return const Center(child: Padding(padding: EdgeInsets.all(48), child: CircularProgressIndicator(color: kAccent)));
    if (error != null) return Center(child: Padding(padding: const EdgeInsets.all(24), child: Text(error!, style: const TextStyle(color: kBlood))));
    if (empty) return Center(child: Padding(padding: const EdgeInsets.all(48), child: Text(emptyText, style: const TextStyle(color: kDim))));
    return child;
  }
}
