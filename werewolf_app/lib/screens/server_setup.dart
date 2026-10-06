import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../config.dart';
import '../store.dart';
import '../theme.dart';

class ServerSetupScreen extends StatefulWidget {
  final ServerConfig config;
  final VoidCallback onSaved;
  const ServerSetupScreen({super.key, required this.config, required this.onSaved});

  @override
  State<ServerSetupScreen> createState() => _ServerSetupScreenState();
}

class _ServerSetupScreenState extends State<ServerSetupScreen> {
  late final TextEditingController _url =
      TextEditingController(text: widget.config.baseUrl.isEmpty ? 'https://' : widget.config.baseUrl);
  late bool _insecure = widget.config.allowInsecure;
  String _msg = '';
  bool _ok = false;
  bool _testing = false;

  Future<void> _test() async {
    setState(() {
      _testing = true;
      _msg = '正在测试…';
      _ok = false;
    });
    widget.config.baseUrl = _url.text.trim();
    final store = context.read<AppStore>();
    final h = await store.api.health();
    if (!mounted) return;
    setState(() {
      _testing = false;
      _ok = h != null;
      _msg = h == null ? '❌ 连接失败：请检查地址、端口与网络' : '✅ 连接成功：${h['app']} v${h['version']}（api ${h['api']}）';
    });
  }

  Future<void> _save() async {
    final url = _url.text.trim();
    if (url.isEmpty || url == 'https://') {
      setState(() => _msg = '请输入服务器地址');
      return;
    }
    widget.config.baseUrl = url;
    widget.config.allowInsecure = _insecure;
    await widget.config.save();
    widget.onSaved();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(24),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    const Text('🌙', textAlign: TextAlign.center, style: TextStyle(fontSize: 44)),
                    const SizedBox(height: 8),
                    const Text('狼人杀',
                        textAlign: TextAlign.center,
                        style: TextStyle(fontSize: 30, color: kMoon, letterSpacing: 8, fontWeight: FontWeight.bold)),
                    const SizedBox(height: 6),
                    const Text('连接你的服务器', textAlign: TextAlign.center, style: TextStyle(color: kDim)),
                    const SizedBox(height: 22),
                    TextField(
                      controller: _url,
                      keyboardType: TextInputType.url,
                      decoration: const InputDecoration(
                        labelText: '服务器地址',
                        hintText: 'https://你的域名:${ServerConfig.kDefaultPort}',
                      ),
                    ),
                    const SizedBox(height: 10),
                    SwitchListTile(
                      contentPadding: EdgeInsets.zero,
                      dense: true,
                      activeColor: kAccent,
                      title: const Text('允许自签名证书（仅局域网调试）', style: TextStyle(fontSize: 13.5)),
                      value: _insecure,
                      onChanged: (v) => setState(() => _insecure = v),
                    ),
                    if (_msg.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 4, bottom: 8),
                        child: Text(_msg, style: TextStyle(color: _ok ? kGreen : kBlood, fontSize: 13.5)),
                      ),
                    const SizedBox(height: 8),
                    OutlinedButton.icon(
                      onPressed: _testing ? null : _test,
                      icon: const Icon(Icons.wifi_tethering, size: 18),
                      label: Text(_testing ? '测试中…' : '测试连接'),
                    ),
                    const SizedBox(height: 10),
                    ElevatedButton(
                      onPressed: widget.config.baseUrl.isEmpty ? _save : _save,
                      child: const Padding(
                        padding: EdgeInsets.symmetric(vertical: 6),
                        child: Text('保存并进入', style: TextStyle(fontSize: 16)),
                      ),
                    ),
                    const SizedBox(height: 12),
                    const Text(
                      '所有设备填同一个服务器地址即可互通；未来更新只需更新服务端，App 无需改。',
                      textAlign: TextAlign.center,
                      style: TextStyle(color: kDim, fontSize: 12, height: 1.6),
                    ),
                  ],
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }
}
