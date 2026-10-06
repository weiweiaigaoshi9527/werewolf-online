import 'package:flutter/material.dart';
import 'package:provider/provider.dart';
import '../store.dart';
import '../theme.dart';
import 'lobby.dart';
import 'shop.dart';
import 'vip.dart';
import 'friends.dart';
import 'profile.dart';

/// 登录后的主框架：底部导航切换 大厅 / 商店 / 会员 / 好友 / 我的，与网页端功能对齐。
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});
  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  int _index = 0;

  static const _screens = [
    LobbyScreen(),
    ShopScreen(),
    VipScreen(),
    FriendsScreen(),
    ProfileScreen(),
  ];

  @override
  Widget build(BuildContext context) {
    // 好友红点：未读私聊 + 待处理好友申请（WS 增量计数，再用服务端聚合校准）
    final store = context.watch<AppStore>();
    final unread = store.unreadTotal;
    return Scaffold(
      body: IndexedStack(index: _index, children: _screens),
      bottomNavigationBar: NavigationBar(
        selectedIndex: _index,
        backgroundColor: kPanel,
        indicatorColor: kAccent.withOpacity(.18),
        onDestinationSelected: (i) {
          setState(() => _index = i);
          if (i == 3) store.refreshUnread(); // 进好友页即拉一次权威未读数
        },
        destinations: [
          const NavigationDestination(icon: Icon(Icons.home_outlined), selectedIcon: Icon(Icons.home), label: '大厅'),
          const NavigationDestination(
              icon: Icon(Icons.storefront_outlined), selectedIcon: Icon(Icons.storefront), label: '商店'),
          const NavigationDestination(
              icon: Icon(Icons.workspace_premium_outlined),
              selectedIcon: Icon(Icons.workspace_premium),
              label: '会员'),
          NavigationDestination(
            icon: Badge(
              isLabelVisible: unread > 0,
              backgroundColor: kBlood,
              label: Text(unread > 99 ? '99+' : '$unread'),
              child: const Icon(Icons.people_outline),
            ),
            selectedIcon: const Icon(Icons.people),
            label: '好友',
          ),
          const NavigationDestination(
              icon: Icon(Icons.person_outline), selectedIcon: Icon(Icons.person), label: '我的'),
        ],
      ),
    );
  }
}
