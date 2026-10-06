import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';

/// 查看某位玩家的公开档案（底部弹窗）。数据来自 /api/friends/profile/{id}。
Future<void> showWwProfile(BuildContext context, int userId, {String? title}) {
  return showModalBottomSheet(
    context: context,
    backgroundColor: kPanel,
    isScrollControlled: true,
    builder: (_) => _ProfileSheet(userId: userId, title: title),
  );
}

class _ProfileSheet extends StatefulWidget {
  final int userId;
  final String? title;
  const _ProfileSheet({required this.userId, this.title});
  @override
  State<_ProfileSheet> createState() => _ProfileSheetState();
}

class _ProfileSheetState extends State<_ProfileSheet> {
  Map<String, dynamic> _p = const {};
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    final s = context.read<AppStore>();
    try {
      _p = await s.api.get('/api/friends/profile/${widget.userId}');
    } on AppError catch (e) {
      _error = e.message;
    }
    if (mounted) setState(() => _loading = false);
  }

  String _fmtTime(dynamic v) => (v == null) ? '—' : '$v'.toString().substring(0, 10);

  @override
  Widget build(BuildContext context) {
    return SafeArea(
      child: Padding(
        padding: const EdgeInsets.fromLTRB(18, 14, 18, 20),
        child: StateBox(
          loading: _loading,
          error: _error,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(children: [
                  WwAvatar(avatarId: _p['avatarId'], avatarUrl: _p['avatarUrl'], frameColor: _p['frameColor'], size: 56),
                  const SizedBox(width: 12),
                  Expanded(
                    child: Column(crossAxisAlignment: CrossAxisAlignment.start, children: [
                      Text('${_p['nickname'] ?? ''}', style: const TextStyle(color: kMoon, fontSize: 18, fontWeight: FontWeight.bold)),
                      const SizedBox(height: 2),
                      Text('@${_p['username'] ?? ''} · Lv.${_p['level'] ?? 1}${_p['online'] == true ? ' · 在线' : ''}',
                          style: const TextStyle(color: kDim, fontSize: 12.5)),
                    ]),
                  ),
                  if (_p['title'] != null) Text('${_p['title']}', style: const TextStyle(color: kAccent, fontSize: 12)),
                ]),
                const Divider(color: kBorder, height: 26),
                if (_p['signature'] != null && '${_p['signature']}'.isNotEmpty)
                  InfoRow('签名', '${_p['signature']}'),
                InfoRow('金币', '${_p['gold'] ?? 0}'),
                InfoRow('经验', '${_p['exp'] ?? 0}'),
                if (_p['birthday'] != null) InfoRow('生日', '${_p['birthday']}'),
                if (_p['location'] != null) InfoRow('所在地', '${_p['location']}'),
                if (_p['regLocation'] != null) InfoRow('注册地', '${_p['regLocation']}'),
                InfoRow('注册时间', _fmtTime(_p['createdAt'])),
                InfoRow('最近在线', _fmtTime(_p['lastOnlineAt'])),
                InfoRow('累计时长', '${(((_p['onlineSeconds'] as num?) ?? 0).toInt() ~/ 3600)} 小时'),
              ],
            ),
          ),
        ),
      ),
    );
  }
}
