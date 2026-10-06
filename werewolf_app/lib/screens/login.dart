import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../api.dart';
import '../store.dart';
import '../theme.dart';

class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});
  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  final _user = TextEditingController();
  final _pass = TextEditingController();
  final _nick = TextEditingController();
  bool _register = false;
  bool _busy = false;
  String _err = '';

  Future<void> _submit() async {
    if (_busy) return;
    setState(() {
      _busy = true;
      _err = '';
    });
    final store = context.read<AppStore>();
    try {
      if (_register) {
        await store.register(_user.text.trim(), _pass.text, _nick.text.trim());
      } else {
        await store.login(_user.text.trim(), _pass.text);
      }
    } on AppError catch (e) {
      if (mounted) setState(() => _err = e.message);
    } catch (e) {
      if (mounted) setState(() => _err = '未知错误：$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('狼人杀'),
        actions: [
          IconButton(
            tooltip: '服务器设置',
            icon: const Icon(Icons.dns),
            onPressed: () {
              context.read<AppStore>().clearServer();
            },
          )
        ],
      ),
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: SingleChildScrollView(
            padding: const EdgeInsets.all(24),
            child: Card(
              child: Padding(
                padding: const EdgeInsets.all(22),
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.stretch,
                  children: [
                    Row(children: [
                      Expanded(
                        child: _tab('登录', !_register, () => setState(() => _register = false)),
                      ),
                      const SizedBox(width: 8),
                      Expanded(
                        child: _tab('注册', _register, () => setState(() => _register = true)),
                      ),
                    ]),
                    const SizedBox(height: 18),
                    TextField(controller: _user, decoration: const InputDecoration(labelText: '用户名')),
                    const SizedBox(height: 12),
                    TextField(
                        controller: _pass,
                        obscureText: true,
                        decoration: const InputDecoration(labelText: '密码')),
                    if (_register) ...[
                      const SizedBox(height: 12),
                      TextField(controller: _nick, decoration: const InputDecoration(labelText: '昵称（可留空）')),
                    ],
                    if (_err.isNotEmpty)
                      Padding(
                        padding: const EdgeInsets.only(top: 12),
                        child: Text(_err, style: const TextStyle(color: kBlood)),
                      ),
                    const SizedBox(height: 18),
                    ElevatedButton(
                      onPressed: _busy ? null : _submit,
                      child: Padding(
                        padding: const EdgeInsets.symmetric(vertical: 8),
                        child: Text(_busy ? '请稍候…' : (_register ? '缔结契约' : '进入村庄'), style: const TextStyle(fontSize: 16)),
                      ),
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

  Widget _tab(String label, bool active, VoidCallback onTap) {
    return GestureDetector(
      onTap: onTap,
      child: Container(
        padding: const EdgeInsets.symmetric(vertical: 10),
        decoration: BoxDecoration(
          color: active ? kAccent : kBg,
          borderRadius: BorderRadius.circular(8),
          border: Border.all(color: active ? kAccent : kBorder),
        ),
        child: Text(label,
            textAlign: TextAlign.center,
            style: TextStyle(
                color: active ? const Color(0xFF1A1206) : kDim, fontWeight: FontWeight.bold, letterSpacing: 2)),
      ),
    );
  }
}
