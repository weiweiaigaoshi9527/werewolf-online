import 'dart:io' show HttpOverrides, HttpClient, SecurityContext, X509Certificate;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'api.dart';
import 'config.dart';
import 'notice.dart';
import 'store.dart';
import 'theme.dart';
import 'screens/server_setup.dart';
import 'screens/login.dart';
import 'screens/home_shell.dart';
import 'screens/room.dart';
import 'screens/game.dart';

/// 局域网自签证书调试：忽略证书校验。公网正式证书下无需（可在设置里关掉“允许不安全连接”）。
class _InsecureOverrides extends HttpOverrides {
  @override
  HttpClient createHttpClient(SecurityContext? ctx) =>
      super.createHttpClient(ctx)
        ..badCertificateCallback = (X509Certificate c, String h, int p) => true;
}

Future<void> main() async {
  WidgetsFlutterBinding.ensureInitialized();
  final config = await ServerConfig.load();
  if (config.allowInsecure && !kIsWeb) {
    HttpOverrides.global = _InsecureOverrides();
  }
  final api = ApiClient(config);
  api.token = await ServerConfig.loadToken();
  final store = AppStore(api)..token = api.token;
  await store.sfx.load(); // 音效开关状态要早于第一帧就绪，否则会先响一声再被关掉
  runApp(ChangeNotifierProvider.value(value: store, child: WerewolfApp(config: config, store: store)));
}

class WerewolfApp extends StatefulWidget {
  final ServerConfig config;
  final AppStore store;
  const WerewolfApp({super.key, required this.config, required this.store});

  @override
  State<WerewolfApp> createState() => _WerewolfAppState();
}

class _WerewolfAppState extends State<WerewolfApp> {
  @override
  void initState() {
    super.initState();
    if (widget.config.configured && widget.store.token != null) {
      widget.store.restore();
    }
  }

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: '狼人杀',
      debugShowCheckedModeBanner: false,
      theme: werewolfTheme(),
      // 全局底部维护通知横幅 + 顶部实时推送浮层（@我、私聊、好友申请、进房邀请、工单回复）
      builder: (context, child) => Stack(
        children: [
          if (child != null) Positioned.fill(child: child),
          Consumer<AppStore>(builder: (context, s, _) => WwNoticeLayer(store: s)),
          Consumer<AppStore>(
            builder: (context, s, _) => s.maintenance.isEmpty
                ? const SizedBox.shrink()
                : const Positioned(left: 0, right: 0, bottom: 0, child: _MaintenanceBar()),
          ),
        ],
      ),
      home: Consumer<AppStore>(builder: (context, s, _) {
        if (!s.serverConfigured) {
          return ServerSetupScreen(config: s.config, onSaved: () => setState(() {}));
        }
        if (!s.loggedIn) return const LoginScreen();
        if (s.inGame) return const GameScreen();
        if (s.inRoom) return const RoomScreen();
        return const HomeShell();
      }),
    );
  }
}

/// 底部维护通知条。
class _MaintenanceBar extends StatelessWidget {
  const _MaintenanceBar();

  @override
  Widget build(BuildContext context) {
    final text = context.select<AppStore, String>((s) => s.maintenance);
    if (text.isEmpty) return const SizedBox.shrink();
    return Material(
      color: const Color(0xCC3a1420),
      child: SafeArea(
        top: false,
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 9),
          child: Row(
            children: [
              const Icon(Icons.construction, size: 16, color: Color(0xFFFFD88A)),
              const SizedBox(width: 8),
              Expanded(
                child: Text('维护通知：$text',
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(color: Color(0xFFFFE6B3), fontSize: 13)),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
