import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';
import 'common.dart';
import 'shop.dart';

/// 资料编辑：签名 / 生日 / 所在地 / 注册地 / 语音音色性别 + 分角色战绩。
/// 服务端 POST /api/user/profile-info 对未传字段不改写（null=保持原值），
/// 且 location/regLocation 静默截断到 32 字、signature 到 60 字，所以这里用 maxLength 提前对齐。
class EditProfileScreen extends StatefulWidget {
  const EditProfileScreen({super.key});

  @override
  State<EditProfileScreen> createState() => _EditProfileScreenState();
}

class _EditProfileScreenState extends State<EditProfileScreen> {
  final _sign = TextEditingController();
  final _loc = TextEditingController();
  final _regLoc = TextEditingController();
  String _birthday = '';
  String _voiceGender = '';
  Map<String, dynamic> _p = const {};
  List<dynamic> _roleStats = const [];
  bool _loading = true;
  bool _saving = false;
  bool _uploading = false;

  @override
  void initState() {
    super.initState();
    _load();
  }

  @override
  void dispose() {
    _sign.dispose();
    _loc.dispose();
    _regLoc.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() => _loading = true);
    final s = context.read<AppStore>();
    try {
      // rolestats 返回的是**数组**，必须走 getList；和 get 混在同一个 Future.wait 里
      // 会被推断成 List<Map>，赋值给 List<dynamic> 直接编译不过。
      final p = await s.api.get('/api/user/profile');
      final rs = await s.api.getList('/api/user/rolestats');
      _p = p;
      _sign.text = '${p['signature'] ?? ''}';
      _loc.text = '${p['location'] ?? ''}';
      _regLoc.text = '${p['regLocation'] ?? ''}';
      _birthday = '${p['birthday'] ?? ''}';
      _voiceGender = '${p['voiceGender'] ?? ''}';
      _roleStats = rs;
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _loading = false);
  }

  Future<void> _pickBirthday() async {
    final now = DateTime.now();
    final init = DateTime.tryParse(_birthday) ?? DateTime(now.year - 20, 1, 1);
    final picked = await showDatePicker(
      context: context,
      initialDate: init,
      firstDate: DateTime(1920),
      lastDate: now,
      helpText: '选择生日（服务端按 yyyy-MM-dd 字符串保存）',
    );
    if (picked == null) return;
    String two(int v) => v.toString().padLeft(2, '0');
    setState(() => _birthday = '${picked.year}-${two(picked.month)}-${two(picked.day)}');
  }

  Future<void> _save() async {
    setState(() => _saving = true);
    final s = context.read<AppStore>();
    try {
      await s.api.post('/api/user/profile-info', {
        'signature': _sign.text.trim(),
        'location': _loc.text.trim(),
        'regLocation': _regLoc.text.trim(),
        'birthday': _birthday.isEmpty ? null : _birthday,
      });
      await s.refreshUser();
      if (mounted) snack(context, '资料已保存');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _saving = false);
  }

  Future<void> _setVoice(String g) async {
    final s = context.read<AppStore>();
    try {
      final r = await s.api.post('/api/user/voice-gender', {'gender': g});
      setState(() => _voiceGender = '${r['voiceGender'] ?? ''}');
      if (mounted) {
        snack(context, g.isEmpty ? '已恢复自动判定音色' : (_voiceGender == 'F' ? '语音将用女声' : '语音将用男声'));
      }
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
  }

  /// 自定义头像上传。服务端要求：multipart 字段名必须是 file、≤2MB、ImageIO 能解码，
  /// 且总是居中裁方后重编码成 128×128 PNG，返回完整 user 视图（avatarUrl 是相对路径）。
  Future<void> _uploadAvatar() async {
    final s = context.read<AppStore>();
    FilePickerResult? picked;
    try {
      picked = await FilePicker.platform.pickFiles(type: FileType.image, withData: true);
    } catch (e) {
      if (mounted) snack(context, '当前平台不支持选择文件：$e', error: true);
      return;
    }
    if (picked == null || picked.files.isEmpty || !mounted) return;
    final f = picked.files.first;
    final bytes = f.bytes;
    if (bytes == null) {
      snack(context, '读取文件内容失败，请换一张图片', error: true);
      return;
    }
    // 与服务端同阈值提前拦：超 2MB 服务端必拒，超过 Spring 的 5MB 更是直接被容器挡掉
    if (bytes.length > 2 * 1024 * 1024) {
      snack(context, '头像不得超过 2MB（当前 ${(bytes.length / 1048576).toStringAsFixed(1)}MB）', error: true);
      return;
    }
    setState(() => _uploading = true);
    try {
      final v = await s.api.postFile('/api/user/avatar', field: 'file', filename: f.name, bytes: bytes);
      if (v['id'] != null) {
        s.user = v;
        _p = {..._p, 'avatarUrl': v['avatarUrl'], 'avatarId': v['avatarId']};
        await s.refreshUser();
      }
      if (mounted) snack(context, '头像已更新');
    } on AppError catch (e) {
      if (mounted) snack(context, e.message, error: true);
    }
    if (mounted) setState(() => _uploading = false);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(title: const Text('编辑资料'), actions: [IconButton(onPressed: _load, icon: const Icon(Icons.refresh))]),
      body: StateBox(
        loading: _loading,
        child: ListView(
          padding: const EdgeInsets.fromLTRB(14, 12, 14, 24),
          children: [
            const SectionTitle('个性签名'),
            TextField(
              controller: _sign,
              maxLines: 3,
              maxLength: 60,
              decoration: const InputDecoration(hintText: '一句话介绍自己（最多 60 字）'),
            ),
            const SectionTitle('所在地'),
            TextField(
              controller: _loc,
              maxLength: 32,
              decoration: const InputDecoration(hintText: '现在所在城市（最多 32 字）'),
            ),
            const SizedBox(height: 8),
            TextField(
              controller: _regLoc,
              maxLength: 32,
              decoration: const InputDecoration(hintText: '注册地 / 家乡（最多 32 字）'),
            ),
            const SectionTitle('生日'),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.cake_outlined, color: kAccent),
              title: Text(_birthday.isEmpty ? '未设置' : _birthday, style: const TextStyle(color: kText)),
              trailing: const Text('修改', style: TextStyle(color: kAccent)),
              onTap: _pickBirthday,
            ),
            const SectionTitle('语音音色'),
            Wrap(
              spacing: 10,
              children: [
                for (final o in const [('M', '男声'), ('F', '女声'), ('', '自动')])
                  ChoiceChip(
                    label: Text(o.$2),
                    selected: _voiceGender == o.$1,
                    onSelected: (_) => _setVoice(o.$1),
                  ),
              ],
            ),
            const SizedBox(height: 10),
            const Text('音色只影响你自己发言时服务端 TTS 的合成声线，对局内他人看到的是座位号。',
                style: TextStyle(color: kDim, fontSize: 12, height: 1.5)),
            const SectionTitle('头像与装扮'),
            Row(
              children: [
                WwAvatar(avatarId: _p['avatarId'], avatarUrl: _p['avatarUrl'], frameColor: _p['frameColor'], size: 56),
                const SizedBox(width: 12),
                Expanded(
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      Text(_p['avatarUrl'] == null || '${_p['avatarUrl']}'.isEmpty ? '当前使用内置头像' : '当前使用自定义头像',
                          style: const TextStyle(color: kMoon, fontSize: 13.5)),
                      const SizedBox(height: 6),
                      OutlinedButton.icon(
                        onPressed: _uploading ? null : _uploadAvatar,
                        icon: _uploading
                            ? const SizedBox(width: 15, height: 15, child: CircularProgressIndicator(strokeWidth: 2))
                            : const Icon(Icons.upload_outlined, size: 18),
                        label: Text(_uploading ? '上传中…' : '上传自定义头像'),
                      ),
                    ],
                  ),
                ),
              ],
            ),
            // 服务端 unequip AVATAR 只把 avatarId 复位到 1，并不清 avatarUrl，
            // 所以「恢复默认」在这里是假的——不给按钮，只说明清楚，避免用户以为能回退。
            const Padding(
              padding: EdgeInsets.only(top: 6),
              child: Text('图片会被服务端居中裁方并压成 128×128 PNG（上限 2MB）。注意：一旦上传自定义头像就无法再切回内置头像，'
                  '内置头像/头像框/称号请在商店与背包里更换。', style: TextStyle(color: kDim, fontSize: 11.5, height: 1.5)),
            ),
            ListTile(
              contentPadding: EdgeInsets.zero,
              leading: const Icon(Icons.auto_awesome_motion_outlined, color: kAccent),
              title: const Text('去商店 / 背包换装扮', style: TextStyle(color: kText, fontSize: 14)),
              subtitle: const Text('头像框、称号、昵称颜色、功能道具', style: TextStyle(color: kDim, fontSize: 12)),
              trailing: const Icon(Icons.chevron_right, color: kDim),
              onTap: () => Navigator.push(context, MaterialPageRoute(builder: (_) => const ShopScreen())),
            ),
            const SectionTitle('分角色战绩'),
            if (_roleStats.isEmpty)
              const Text('还没有可统计的对局', style: TextStyle(color: kDim, fontSize: 13))
            else
              Card(
                child: Column(
                  children: [
                    for (final r in _roleStats)
                      ListTile(
                        dense: true,
                        title: Text('${r['role']}', style: const TextStyle(color: kMoon, fontSize: 14)),
                        subtitle: Text('${r['games']} 场 · 胜 ${r['wins']} · 胜率 ${_rate(r)}%',
                            style: const TextStyle(color: kDim, fontSize: 12)),
                        trailing: Text(_dur(r['seconds']), style: const TextStyle(color: kDim, fontSize: 12)),
                      ),
                  ],
                ),
              ),
            const SizedBox(height: 18),
            FilledButton.icon(
              onPressed: _saving ? null : _save,
              icon: _saving ? const SizedBox(width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2)) : const Icon(Icons.save_outlined),
              label: Text(_saving ? '保存中…' : '保存资料'),
            ),
          ],
        ),
      ),
    );
  }

  static String _rate(Map r) {
    final g = (r['games'] as num?)?.toInt() ?? 0;
    if (g == 0) return '0.0';
    final w = (r['wins'] as num?)?.toInt() ?? 0;
    return (w * 100 / g).toStringAsFixed(1);
  }

  /// rolestats 的 seconds 是累计存活/参与时长，折成小时更好读。
  static String _dur(Object? seconds) {
    final s = (seconds as num?)?.toInt() ?? 0;
    if (s <= 0) return '—';
    final h = s / 3600;
    return h >= 1 ? '${h.toStringAsFixed(1)}h' : '${(s / 60).round()}min';
  }
}
