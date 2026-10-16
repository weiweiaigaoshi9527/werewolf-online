import 'dart:io' show HttpOverrides, HttpClient, SecurityContext, X509Certificate;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'api.dart';
import 'bgm.dart';
import 'config.dart';
import 'i18n.dart';
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
  await appearance.load(); // 外观（主题/强调色/字号/密度/动效/背景）同样要早于第一帧
  await i18n.load(); // 语言（ww_lang）也要早于第一帧，否则会先闪一帧中文再切走
  await bgm.load(); // 背景音乐播放列表（ww_bgm）：只读回列表，不自动播放
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
    // 外观/语言都是全局单例，切换后必须整棵树重挂才能取到新值：用 fingerprint 当 key，
    // 并把字号档位换算成文本缩放（对应网页端 fs-sm / fs-lg）。
    return ListenableBuilder(
      listenable: Listenable.merge([appearance, i18n]),
      builder: (context, _) => MaterialApp(
        title: '狼人杀',
        debugShowCheckedModeBanner: false,
        theme: werewolfTheme(),
        // 全局底部维护通知横幅 + 顶部实时推送浮层（@我、私聊、好友申请、进房邀请、工单回复）
        builder: (context, child) => MediaQuery(
          data: MediaQuery.of(context).copyWith(
            textScaler: TextScaler.linear(appearance.textScale),
          ),
          child: Stack(
            children: [
              // 页面背景舞台（主题渐变 / 自定义背景图 / 背景亮度），对齐网页端 .stage-bg
              const Positioned.fill(child: WwStageBackground()),
              if (child != null) Positioned.fill(child: child),
              Consumer<AppStore>(builder: (context, s, _) => WwNoticeLayer(store: s)),
              Consumer<AppStore>(
                builder: (context, s, _) => s.maintenance.isEmpty
                    ? const SizedBox.shrink()
                    : const Positioned(left: 0, right: 0, bottom: 0, child: _MaintenanceBar()),
              ),
            ],
          ),
        ),
        home: Consumer<AppStore>(builder: (context, s, _) {
          final Widget content;
          if (!s.serverConfigured) {
            content = ServerSetupScreen(config: s.config, onSaved: () => setState(() {}));
          } else if (!s.loggedIn) {
            content = const LoginScreen();
          } else if (s.inGame) {
            content = const GameScreen();
          } else if (s.inRoom) {
            content = const RoomScreen();
          } else {
            content = const HomeShell();
          }
          // 管理员可在后台设置「内容区最大高度」（GET /api/config/display 的 webMaxHeight）。
          // 网页端用它把布局收成窄窗，桌面端同比例收窄以保持一致；手机屏本来就矮，不处理。
          final h = s.displayMaxHeight;
          final Widget boxed;
          if (h <= 0 || h >= 100 || MediaQuery.of(context).size.width < 900) {
            boxed = content;
          } else {
            boxed = Align(
              alignment: Alignment.topCenter,
              child: ConstrainedBox(
                constraints: BoxConstraints(maxHeight: MediaQuery.of(context).size.height * h / 100),
                child: content,
              ),
            );
          }
          // 外观/语言一变就换 key，强制整棵界面重建（颜色令牌与译文都是运行时值）。
          return KeyedSubtree(key: ValueKey('ww-${appearance.fingerprint}-${i18n.lang}'), child: boxed);
        }),
      ),
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
                child: Tx('维护通知：$text',
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
