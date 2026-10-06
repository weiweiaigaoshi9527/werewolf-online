import 'package:flutter_test/flutter_test.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'package:werewolf_app/api.dart';
import 'package:werewolf_app/config.dart';
import 'package:werewolf_app/main.dart';
import 'package:werewolf_app/store.dart';

/// 声明式条件路由的骨架冒烟：未配地址 → 服务器设置页；已配未登录 → 登录页。
/// 这两条分支是所有端（Windows / Linux / Android）启动后的第一屏，回归代价最高。
void main() {
  Future<void> pumpApp(WidgetTester tester, ServerConfig config) async {
    SharedPreferences.setMockInitialValues({});
    final api = ApiClient(config);
    final store = AppStore(api);
    await tester.pumpWidget(ChangeNotifierProvider.value(
      value: store,
      child: WerewolfApp(config: config, store: store),
    ));
    await tester.pumpAndSettle();
  }

  testWidgets('未配置服务器时落在「连接你的服务器」页（不再内置任何预设服务器）', (tester) async {
    await pumpApp(tester, ServerConfig());
    expect(find.text('连接你的服务器'), findsOneWidget);
    expect(find.text('服务器地址'), findsOneWidget);
    expect(find.text('保存并进入'), findsOneWidget);
    // 客户端不应内置任何写死的服务器地址（原先有“公网服务器/本地服务器”快捷项）
    expect(find.text('常用地址：'), findsNothing);
    expect(find.byType(ActionChip), findsNothing);
  });

  testWidgets('已配置但未登录时落在登录页', (tester) async {
    await pumpApp(tester, ServerConfig(baseUrl: 'https://localhost:11111'));
    expect(find.text('用户名'), findsOneWidget);
    expect(find.text('密码'), findsOneWidget);
    expect(find.text('进入村庄'), findsOneWidget);
    expect(find.text('连接你的服务器'), findsNothing);
  });
}
