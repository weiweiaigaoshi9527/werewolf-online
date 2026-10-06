# 狼人杀 · 跨端 App（Flutter）

一套 Flutter 代码，产出 **Windows / macOS / Linux / Android / iOS / Web** 六端；
全部连接**同一个后端**（就是本仓库里已有的 Spring Boot 服务：REST `/api` + WebSocket `/ws`），
因此**任意设备之间天然互通**，后端逻辑/规则/AI 的更新对所有端即时生效。

```
Flutter App (win/mac/linux/android/ios/web)
        │  HTTPS  /api/*        （登录、建房、加AI、准备、开局、行动、聊天、强制结束、健康检查）
        │  WSS    /ws?token=    （room.state / game.state / game.ended / game.chat / 好友 / 邀请 …）
        ▼
   同一台 Spring Boot 服务端  ←  网页版也用这一套，三端共后端 = 互通
```

## 目录结构

```
werewolf_app/
├── lib/
│   ├── config.dart          # 服务器地址 + token 持久化（shared_preferences）
│   ├── api.dart             # REST 客户端（Bearer 鉴权，与网页版同契约）
│   ├── ws.dart              # WebSocket 客户端（断线重连 + 心跳 + 按 type 分发）
│   ├── store.dart           # 全局状态：鉴权/房间/对局视图，消费 WS 推送
│   ├── theme.dart           # 暗黑哥特配色（与网页一致）
│   ├── main.dart            # 根路由：服务器设置→登录→大厅→房间→对局
│   └── screens/             # server_setup / login / lobby / room / game
├── windows/ android/ ios/ macos/ linux/ web/   # flutter create 生成的各端工程
└── dist/windows/            # 已构建好的 Windows 桌面版（werewolf_app.exe 可直接双击）
```

## 首次使用

1. 启动服务端（见 `DEPLOY-server.md`）。
2. 打开 App → 在「服务器设置」填服务端地址：
   - 局域网：`https://192.168.x.x:11111`（保持「允许自签名证书」开启）。
   - 公网：`https://你的域名`（正式证书，可关掉自签名开关）。
3. 「测试连接」通过后「保存并进入」→ 登录/注册 → 建房或加房 → 加 AI → 开局。

> 所有设备填**同一个地址**即可联机对战。

## 构建各端

前置：安装 Flutter SDK（`flutter doctor` 检查各端工具链）。

```bash
cd werewolf_app
flutter pub get

flutter build windows            # → build/windows/x64/runner/Release/
flutter build linux              # → build/linux/x64/release/bundle/
flutter build macos              # → build/macos/Build/Products/Release/   （需在 macOS 上）
flutter build apk --release      # → build/app/outputs/flutter-apk/app-release.apk
flutter build ipa                # → iOS 打包，需在 macOS + Xcode + Apple 开发者账号
flutter build web                # → build/web/  （Web 端不能用自签名证书，需正式 HTTPS）
```

开发调试：`flutter run -d windows`（或 `-d chrome` / 已连接的手机）。

### ⚠️ 关键坑（本机实测，务必看）

- **工程路径必须纯 ASCII**。放在含中文的 `G:\狼人杀\` 下，`flutter build windows` 会因
  Dart 快照路径乱码失败（`Unable to read file: ...app.dill`）。主工程因此定在
  **`G:\wwapp\werewolf_app`**；App 运行本身不依赖所在路径（只连服务器）。
- **不要用 robocopy 整目录复制工程**。`*/flutter/ephemeral/.plugin_symlinks` 会被复制成
  断裂的 reparse point，之后连 `build windows` 都会崩在 `Cannot create link ... errno 183`
  （Flutter 会为**所有**平台目录建插件链接）。拷贝必须排除
  `build .dart_tool ephemeral .plugin_symlinks`。
- **`windows/runner/*.cpp` 里不要写中文注释**。runner 按代码页 936 编译，无 BOM 的 UTF-8
  中文会触发 C4819 且被当作错误。窗口标题用 `L"\u72fc\u4eba\u6740"` 转义即可。
- **含中文的 `.ps1` 必须存成 UTF-8 with BOM**，否则 Windows PowerShell 5.1 按 GBK 解析会
  静默吞掉后面的行（症状是脚本"少执行一步"，极难查）。
- **插件版本要和 Flutter 版本成对**。`record_android` 1.4.0+ 的 build.gradle 写
  `compileSdk = flutter.compileSdkVersion`，而 Flutter 3.24.5 不给插件子工程注入 `flutter`
  扩展 → 构建直接失败。本工程锁 `record 6.0.0 + record_android 1.3.1`（硬编码 compileSdk 34）。
- **`audioplayers_android` 要求 compileSdk 35**，而 `flutter.compileSdkVersion` 是 34，
  所以 `android/app/build.gradle` 里显式写了 `compileSdk = 35`，并需装 `platforms;android-35`。
- **本机（大陆网络）Gradle 取不到官方源**：`maven.google.com`、`repo.maven.apache.org` 均不可达。
  已做三件事：① `android/settings.gradle` 与 `android/build.gradle` 把阿里云镜像排在 google()/
  mavenCentral() 之前（官方源保留兜底，CI 不受影响）；② gradle wrapper 发行包走腾讯云镜像；
  ③ 插件自带 `buildscript{}` 那一层由**本地** `GRADLE_USER_HOME\init.d\cn-buildscript-mirror.gradle`
  前插镜像（不进仓库，CI 无需）。
- **iOS 只能在 macOS 上构建/签名**（Xcode + Apple Developer）。
- **Web** 端 `dart:io`（自签名证书放行）不可用，必须正式 HTTPS。

## 已实现范围

服务器设置 → 登录/注册 → 大厅（建房/房号加入/观战）→ 房间（座位/准备/加 AI/板子/四种模式/
**房主踢人与转让房主**/**双人组队结队：邀请·轮询·接受·解除**/外链邀请）→ 对局（阶段·座位·记录流·
全角色行动面板·聊天·强制结束）→ 语音（麦克风 16k PCM 上行 + 服务端逐句 TTS 下行 + 字幕与说话光环）→
商店/VIP/装扮/金币流水、好友与私聊、工单、复盘、板子自定义编辑器、维护横幅。

**实时性（P2）已接通**：`room.event` 进房间布告栏（连续同类折叠成 `×N`）、`game.chat`/`narrator`
进对局文字流（此前对局内文字聊天在 App 里根本看不见）、`chat.msg`/`friend.request`/`room.invite`/
`ticket.update`/`system` 走全局浮层（`lib/notice.dart`，挂在 `MaterialApp.builder` 上，任何页面都能收到；
`room.invite` 带「加入」按钮一键进房），未读数由 WS 增量 + `GET /api/friends/unread` 校准，
底部「好友」导航显示红点。

**P3 增量页已补齐**：论坛（列表/分类筛选/发帖/详情/回复/删自己的帖）、排行榜（胜率·财富·等级三榜，
前三名奖牌、点他人可看资料）、我的背包 `GET /api/shop/inventory`（按类型分组，功能道具「带入」、
装扮「装备」）、资料编辑（签名/所在地/注册地/生日/语音音色 + 分角色战绩与累计时长）、
兑换码、对局结算互赞 kudos（👍/🫂/🚩，举报可填原因，附服务端汇总计数）。
入口按 `GET /api/config/features` 的 `{"disabled":[...]}` 做**能力门控**：管理员关掉某功能时，
对应入口直接隐藏（`AppStore.feat(id)`），而不是点进去才报"功能已被管理员关闭"。

**已补齐的三块**：
- 更新动态：数据是**静态文件** `/data/changelog.json`（不是 API，网页版也直接 fetch 它），用 `ApiClient.getStatic` 读。
- 下载中心：`GET /api/downloads`，注意 `url` 三态——`/download/{file}` 是安装包、`/` 是站点根（PWA 条目）、
  `null` 是"即将提供"；App 内不直接落盘，复制链接交给浏览器（大文件更稳且支持断点续传）。
- 自定义头像上传：`POST /api/user/avatar`，multipart 字段名必须是 `file`、≤2MB，服务端居中裁方重编码成
  128×128 PNG，返回的 `avatarUrl` 是**相对路径** `/uploads/...`（`/uploads/**` 可匿名 GET）。
  用 `file_picker`（Windows 侧是纯 Dart FFI，不引入 C++ 编译），三端构建均已实测通过。
  ⚠️ 服务端 `unequip AVATAR` 只复位 `avatarId`、**不清 `avatarUrl`**，所以一旦上传自定义头像就无法真正回退，
  界面上不提供假的"恢复默认"按钮。
- 后台管理（`admin.dart`，仅 `user.admin` 可见）：概览/用户/房间/工单/兑换码/论坛/系统七分节。
  **服务端没有任何二次确认参数**，restart/shutdown/delete/dissolve/end-all 收到即执行，
  因此危险操作在客户端强制输入确认词。`/api/admin/local/**` 要求来源是 127.0.0.1 + `X-WW-Local: 1`，
  手机/远程 App 拿不到，故只用 `/api/admin/server/*` 与 `/api/admin/ops/*`。
  另注意网页版批量删除发的是 `op="del"` 而后端只认 `"delete"`（会静默不执行），这里发 `"delete"`。

**逐帧回放播放器**（`lib/replay_engine.dart` 纯逻辑 + `screens/replay.dart` UI）：
一帧 = 一个事件（`seq` 连续且等于数组下标）。状态机按帧重建棋盘——`PLAYER_DIED` 是存活的
**唯一权威标记**（`VOTE_RESULT`/`SHOOT`/`WHITE_WOLF_BLOWUP` 只记动作，出局由随后的
`PLAYER_DIED` 落定，绝不重复扣命）；警长只能由 `SHERIFF_WIN` 重建（响应里没有 `sheriffSeat`）；
白痴翻牌是"活着但无投票权"；`ITEM_EFFECT` 的"复活卡发动"可让已出局者回场；
`seatRoles` 是**字符串** `"1:预言家 2:狼人"` 而不是对象。
与网页版一致：首屏直接铺满全部事件、速度档 慢1200/中600/快250ms、播完再点=从头重播；
比网页版多做：后退一帧、拖动进度条跳转、座位条随帧更新（网页版播放时座位区是不变的）。

**本轮已补齐**：后台的语音/AI 参数编辑（`admin.dart` 第 8 分节）、抽牌与结算动画
（`lib/animations.dart`）、逐帧回放播放器（`lib/replay_engine.dart`）。
网页版功能侧目前只剩后台的极少数长尾配置项。

## ⚠️ 不要直接跑 `dart fix --apply`

本仓库大量文案是「插值紧跟中文」（`'${e}号'`、`'$timeoutMin分钟'`）。Dart 的插值标识符遇到
CJK 会**停止解析**，`'$e号'` 不但不插值、还会原样输出字面量 `$e号`（已实测）。
而 `unnecessary_brace_in_string_interps` 这条 lint 认为花括号多余，`dart fix --apply`
会照着改——**改完不报错，只是中文文案全坏**。

相关 lint 已在 `lib/replay_engine.dart`、`tool/replay_probe.dart` 用 `ignore_for_file` 钉住。
要自动修复请**按 code 单跑**并跳过这一条：

```powershell
dart fix --apply --code=prefer_const_constructors
dart fix --apply --code=curly_braces_in_flow_control_structures
```

## 构建与验证

```powershell
# 本机一键出 Windows + Android（含全部环境变量与镜像；产物进 release-artifacts\）
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1
powershell -ExecutionPolicy Bypass -File tools\build-all.ps1 -Target android -Server https://你的域名:11111
```

`--dart-define=WW_SERVER=https://host:11111` 可把服务器地址**烘焙进包**，装好直接进登录页；
不预置则首启走「服务器设置」页手工填。用户手工填过的地址优先于烘焙值。

Linux 桌面包必须在 GNU/Linux 上构建（Flutter 不支持跨平台产出），由
`.github/workflows/client-release.yml` 的 linux job 出 `.deb` 与便携 `tar.xz`
（打包脚本 `packaging/linux/make-deb.sh`）。

### 测试

```powershell
flutter test                          # 离线套件：协议编解码/URL 拼装/路由冒烟/页面渲染/WS 派发
# 打真实服务端的端到端契约测试（默认 skip，靠环境变量开启）
set WW_LIVE_BASE=https://localhost:11111
set WW_LIVE_USER=winclient01&& set WW_LIVE_PASS=...
set WW_LIVE_USER2=winclient02&& set WW_LIVE_PASS2=...   # 双人结队/踢人用例需要第二个账号
flutter test test/live_contract_test.dart --timeout 150s
```

`live_contract_test.dart` 驱动的是客户端**真实代码路径**（`ApiClient`/`AppStore`/`WsClient`），
覆盖登录→WS 建连→建房→AI 补位→开局发牌→`/ws/voice` 握手→强制结算→四字段模式→duo 全流程→
转让房主→踢人→论坛发帖回复删除→排行榜与门控→资料写入回滚→兑换码/自我互赞负例，
因此它通过即等价于"三端共用的协议层仍然对齐后端"。
页面级测试靠 `ApiClient(config, client: ...)` 这个注入点用 `MockClient` 喂固定响应
（见 `test/p3_screens_test.dart`），所以"进页面即发请求"的页面也能离线断言结构与门控。
`test/fixtures/server_views.dart` 是从真实响应抓下来的视图快照，供页面渲染测试离线使用；
`WW_GOLDEN=1 flutter test --update-goldens` 可产出/比对渲染基线
（注意 `flutter test` 环境无 CJK 字体，golden 里中文显示为方块，只锁结构）。

## 更新策略

- **后端改动**（规则、AI、数值、新增接口）：只更新服务端，**三端即时生效，无需重装 App**。
- **界面/交互改动**：改 `lib/` 后重新构建对应端并分发。这是"原生重写"相对"网页套壳"的代价。
- 注意：桌面端 `shared_preferences` 落在 Windows Known Folder（`%APPDATA%\cn.werewolf\werewolf_app`），
  **改 `%APPDATA%` 环境变量并不能隔离它**（Known Folder API 不认该变量），所以直接启动 exe 会接上
  已存登录态；服务端同 token 顶号互踢，验证时慎用真实账号。

