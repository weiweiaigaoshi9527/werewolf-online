# 狼人杀 Online · App 总结（移交版）

> 用途：把本项目完整移交给另一个 AI 助手继续开发时的事实依据。
> 项目根：`G:\狼人杀` ｜ Flutter 客户端开发仓：`G:\wwapp\werewolf_app`
> 历史过程记录见同目录 `项目总结.md`（2026-10-04，偏历程叙事）；本文只写**当前状态**。

---

## 1. 一句话概述

一个可长期开黑的**在线狼人杀**产品：Spring Boot 单体内核提供全部业务与实时协议，客户端有 **Web（PWA）/ Windows / Linux / Android** 四种形态，共用同一套服务端即可互相开局。支持 11 角色、20 套板子、AI 补位（可完全伪装真人）、语音同传、房号制、观战、回放、经济与商店。

---

## 2. 端与形态

| 端 | 技术 | 状态 | 产物位置 |
|---|---|---|---|
| Web（主端） | 原生 HTML/CSS/JS + Service Worker（PWA） | 已上线 | 由服务端 `src/main/resources/static` 直接托管 |
| Windows 桌面 | Flutter 3.24.5 | 已出包 | `G:\狼人杀\客户端`（zip 便携包） |
| Linux 桌面 | Flutter（deb + tar.xz） | 已出包（CI 干净容器构建） | 同上 |
| Android | Flutter（通用/分架构 APK + AAB） | 已出包 | 同上 |

三端 Flutter 客户端**不含任何独立后端逻辑**，只做「协议客户端 + 表现层」，所有规则判定、信息隔离、AI 调度都在服务端。

---

## 3. 技术栈

**服务端**：Spring Boot **3.5.7** ｜ Java **21** ｜ Maven（`pom.xml`，单模块 `werewolf-online`）｜ H2 文件数据库（`jdbc:h2:file:./data/werewolf`）｜ Spring Data JPA（Hibernate，`ddl-auto: update`）｜ Spring WebSocket｜ OpenAI 兼容 HTTP 客户端调 AI。
**语音微服务（可选）**：SenseVoice（ASR，`127.0.0.1:5001`）+ Kokoro（TTS，`127.0.0.1:5002`），由 `voice.enabled` 总开关控制，默认 false。
**Web 前端**：零框架原生三件套（`app.js` / `ux.js` / `nav.js` / `voice.js` / `scene3d.js` / `admin.js` …）+ `themes.css` 多主题。
**Flutter 客户端**：Dart SDK `^3.5.4`，Flutter 3.24.5；依赖 `http`、`web_socket_channel`、`provider`、`shared_preferences`、`record 6.0.0` + `record_android 1.3.1`（**必须成对锁死**）、`audioplayers ^6.1.0`、`file_picker 8.1.2`、`cupertino_icons`；dev 依赖 `flutter_test`、`flutter_lints ^4.0.0`。

---

## 4. 代码位置地图

```
G:\狼人杀\                        服务端工程根（Maven 项目 + Web 静态资源）
├─ src\main\java\com\werewolf\   全部服务端 Java 代码（约 130 个类）
├─ src\main\resources\
│  ├─ application.yml            端口 11111 / H2 / 语音 / 管理员 bootstrap
│  └─ static\                    Web 前端（index.html、admin.html、js\、css\、data\）
├─ data\                         H2 数据库文件（删掉即重置）
├─ uploads\                      用户上传（头像等）
├─ logs\                         运行日志
├─ tools\                        免安装 JDK 21 + Maven（绿色工具链）
├─ start.bat / stop.bat / build.bat / make-cert.bat
├─ 服务管理\服务管理.exe          jpackage 打包的图形化服务管理器
├─ 客户端\                       三端 Flutter 安装包交付目录（含「安装说明.md」）
├─ werewolf_app\                 Flutter 客户端源码镜像（由开发仓 robocopy /MIR 同步，非 git）
├─ 服务端\ / wolfweb-交付\        服务端与 Web 版的历史交付包
└─ APP总结.md ← 本文

G:\wwapp\werewolf_app\           Flutter 客户端开发仓（git，分支 master）
├─ lib\                          全部 Dart 源码
├─ test\                         14 个测试文件（152 通过 / 21 跳过）
├─ packaging\linux\make-deb.sh   Linux deb + tar.xz 出包脚本
├─ .github\workflows\client-release.yml  三端 CI
└─ tools\build-all.ps1           本机一键出 Windows zip + Android APK
```

---

## 5. 整体架构与数据流

```
                 ┌──────────────── 服务端 :11111 ────────────────┐
 Web/PWA  ──────►│ REST  /api/**      业务读写（含鉴权 token）     │
 Flutter  ──────►│ WS    /ws?token=   大厅/房间/对局 实时推送       │
                 │ WS    /ws/voice?token=  语音上行+字幕下行        │
                 │ 静态资源 static/   直接托管 Web 版              │
                 └──────────────────────────────────────────────┘
```

- **鉴权**：登录/注册返回 token，客户端放进 `Authorization` 头（REST）与 `?token=` 查询参数（WS）；token 存 `SharedPreferences`。
- **推送模型**：服务端**按用户定向推送**（`WsPush.send(userId, type, payload)`），不是房间广播裸消息——每个玩家收到的是**自己视角**的快照（`game.state` 逐人不同，保证不越权）。payload 全部塞进一个 JSON 信封，**信封字段 `type` 最后写入**，防止被 payload 同名字段覆盖。
- **游戏推进**：`LiveGameService` 维护对局状态机，行动超时/托管/AI 异步驱动，通过 `sendToUserAsync` 给每个座位下发各自的 `game.state`。

---

## 6. 服务端详解

### 6.1 包结构（`com.werewolf`）

| 包 | 职责 |
|---|---|
| `api` | 全部 REST 控制器（见 6.3） |
| `service` | 业务服务：`AuthService`、`RoomService`、`ShopService`、`VipService`、`RedeemService`、`TicketService`、`FriendService`、`ProfileService`、`SettingsService`、`BoardService`、`DecorationService`、`SettlementService`… |
| `game` | 对局运行时：`LiveGame`、`LiveGameService`、`GameViewBuilder`（视角快照）、`GameAction`、`SeatInfo`、`GamePersistenceService`、`GameSnapshot`、`StaleGameResetter` |
| `rules` | 纯规则层：`GameEngine`、`GamePhase`、`Role`、`Faction`、`Player`、`BoardConfig`、`BoardValidator`、`GameEvent`；`rules/sim` 为随机模拟自测 |
| `ai` | `OpenAiClient`、`AiService`、`AiPolicy`（AI 决策）、`AiContextBuilder`（信息隔离拼装）、`PromptLibrary`、`SpeechSanitizer`（内心策略防泄漏）、`Humanizer`、`PhraseDedup`、`MockProvider` |
| `voice` | `VoiceWebSocketHandler`、`VoiceChannel`、`VoiceService`、`SpeechDirector`、`VadSegmenter`、`AudioEnvelope` |
| `ws` | `HallWebSocketHandler`（`/ws`）、`RoomBroadcaster`、`WsPush`、`PresenceService` |
| `model` | JPA 实体 + DTO（`User`、`Room`、`RoomPlayer`、`RoomDuo`、`GameRecord`、`GameEventLog`、`MatchParticipant`、`RoleStat`、`Inventory`、`ItemDefinition`、`GoldTransaction`、`RedeemCode`、`RedeemUsage`、`UserTicket`、`TicketReply`、`ForumPost`、`ForumReply`、`GameKudos`、`FriendRequest`、`Friendship`、`PrivateMessage`、`LoginSession`、`AiConfig`、`AppSetting`） |
| `repo` | 与实体一一对应的 Spring Data Repository |
| `config` | `WebSocketConfig`、`WebMvcConfig`、`RateLimitFilter`、`AdminInterceptor`、`AdminBootstrapRunner`、`AdminProperties`、`VoiceProperties`、`VoiceSettingsStore`、`UploadResourceConfig` |

### 6.2 持久化

H2 文件库，`Connection` 池 20，`ddl-auto: update`（首次启动自动建表；**生产建议改 `validate`，但不要在首次启动前改**）。头像/装扮等文件存 `uploads\`，通过 `UploadResourceConfig` 暴露静态路径。

### 6.3 REST 接口（按控制器前缀，非穷举）

| 前缀 | 端点（示例） |
|---|---|
| `/api/health` | `GET /health`（返回 app/version/api，客户端「测试连接」用） |
| `/api/auth` | `POST /register`、`POST /login`、`POST /logout`、`GET /me` |
| `/api/user` | `GET /profile`、`POST /nickname`、`POST /avatar`、`POST /equip`、`POST /unequip`、`POST /carry`、`GET /transactions`、`POST /checkin`、`GET /checkin-status`、`GET /rolestats`、`GET /stats`、`POST /profile-info`、`POST /voice-gender` |
| `/api/room` | `POST /create`、`POST /join`、`POST /spectate`、`POST /leave`、`POST /ready`、`POST /kick`、`POST /transfer`、`POST /start`、`POST /add-ai`、`POST /item`、`POST /mode`、`POST /board`、`GET /boards`、`GET /my`、`GET|POST /notice`、`POST /duo/invite|accept|cancel`、`GET /duo/pending` |
| `/api/game` | `POST /start`、`POST /end`、`POST /action`、`POST /chat`、`POST /wolf-chat`、`GET /state`、`POST /feed-summary`、`GET /replay/{gameId}`；`/api/game/kudos` → `GET /{gameId}` |
| `/api/friends` | `GET /search`、`GET /requests`、`POST /request`、`POST /requests/{id}/accept|reject`、`POST /remove`、`GET|POST /messages`、`GET /unread`、`GET /profile/{id}` |
| `/api/forum` | 列表/详情 `GET /{id}`、`POST /{id}/reply|delete|hide|pin` |
| `/api/shop` | `GET /catalog`、`GET /inventory`、`POST /buy` |
| `/api/vip` | `POST /purchase` |
| `/api/redeem` | 玩家兑换入口（管理员侧见下） |
| `/api/tickets` | `GET /support`、`POST /support/message|human`、`GET /{id}`、`POST /{id}/reply` |
| `/api/ranking` | 排行榜 |
| `/api/voice` | `GET /status` |
| `/api/ai-show` | `GET /boards`、`GET /list`、`POST /create|restart|stop`（AI 自动演示局） |
| `/api/config` | `GET /api/config/features`、`GET /api/config/display`（功能开关/展示配置，无需鉴权） |
| `/api/downloads`、`/download/{file}` | 客户端/资源下载列表与文件 |
| `/api/admin/users` | 列表、`POST /{id}/admin|ban|password|delete|vip|gold|edit|impersonate`、`POST /batch`（批量操作） |
| `/api/admin/ops` | `GET /forum`、`GET /online`、`GET /export/users`（CSV）、`POST /end-all`、`POST /cleanup/scan|run` |
| `/api/admin/system` | `GET|POST /display`、`GET|POST /features` |
| `/api/admin/local` | `GET /stats`、`POST /shutdown` |
| `/api/admin/server` | `POST /restart`、`POST /shutdown` |
| `/api/admin/voice` | 语音运维开关 |
| `/api/admin/rooms` | `POST /{id}/end`、`POST /{id}/dissolve` |
| `/api/admin/redeem` | 兑换码管理：列表/新建、`POST /{id}/active|delete` |
| `/api/admin/tickets` | `GET /{id}`、`POST /{id}/reply|status` |
| `/api/admin/ai` | `GET|POST /config`、`POST /test` |

管理员接口统一由 `AdminInterceptor` 拦截校验，`AdminBootstrapRunner` 启动时把 `admin.bootstrap-usernames` 中的用户置为管理员（**首个注册用户无论如何都是管理员**）。

### 6.4 规则层

**角色（11 种，`Role.java`）**：狼人 · 狼王 · 白狼王 · 村民 · 预言家 · 女巫 · 猎人 · 守卫 · 白痴 · 乌鸦 · 禁言长老（`Faction` = WOLF / GOD / VILLAGER；`hasNightAction()` 决定夜间行动顺序）。

**阶段状态机（`GamePhase.java`，顺序即流程）**：
`SETUP` → 夜间六段 `NIGHT_GUARD → NIGHT_WOLF → NIGHT_WITCH → NIGHT_SEER → NIGHT_CROW → NIGHT_SILENCER` → `DAWN` → `SHERIFF_ELECTION`（首日警长竞选）→ `LAST_WORDS` → `SHERIFF_ORDER`（警长决定发言顺序）→ `DAY_SPEAK` → `DAY_VOTE` → `DAY_VOTE_TIEBREAK`（平票 PK）→ `SHOOT`（猎人/狼王开枪）→ `SHERIFF_TRANSFER`（警徽移交/撕毁）→ `GAME_OVER`。

**板子**：`BoardConfig` + `BoardValidator`，20 套预设 6~24 人，房主可视化勾选；胜负规则可切（屠边默认 / 数量规则）。

**核心细节**：警长 1.5 票、警徽流、白狼王自爆、猎人连锁开枪、同守同救「奶穿」、乌鸦诽谤 +1 票、禁言、铁票双倍票权、倒计时隐私（deadline 只推给行动者本人）。

---

## 7. 通信协议

### 7.1 `/ws?token=xxx`（大厅 + 房间 + 对局）

**服务端 → 客户端**（JSON，顶层 `type` 为事件名）：

| type | 含义 / 关键字段 |
|---|---|
| `welcome` | 连接成功欢迎语 |
| `room.state` | 房间快照（`room` 对象；`status == PLAYING` 时客户端补拉 `GET /api/game/state`） |
| `room.left` / `room.kicked` | 离开/被踢（`message`） |
| `room.event` | 布告栏文本（补 AI、进出房、房主改模式…） |
| `room.invite` | 好友邀请进房（`roomNo`、`fromName`） |
| `game.state` | **每人一份、按座位裁剪**的对局快照（含 `mySeat`、`voiceAvailable`、阶段、可行动作等） |
| `game.ended` | 对局结束（`reason`、`forced`） |
| `game.chat` | 公开聊天（`name`/`seat`/`text`） |
| `game.wolfchat` | 狼队频道（仅狼可见） |
| `narrator` | 旁白（夜晚结算文本，只入聊天流） |
| `chat.msg` | 私聊消息（`fromName`、`content`、peerId） |
| `friend.request` / `friend.accepted` | 好友申请 / 通过 |
| `ticket.update` | 工单状态变更 |
| `system` | 全局公告/警告（带 ttl） |
| `maintenance` | 维护通知（`message`、`expiry`） |
| `error` | 错误提示 |

**客户端 → 服务端**：动作走 REST（`/api/game/action`、`/api/game/chat`、`/api/room/**`），`/ws` 本身只做心跳保活。

### 7.2 `/ws/voice?token=xxx`（语音同传）

- 下行：`voice.hello`（`enabled` 等）、`voice.denied`（`reason`，如「现在没有轮到你发言」）、`voice.caption`（识别字幕）、`voice.speaking`（谁在说）、`pong`。
- 上行：`ping`、`voice.start` / `voice.stop` 控制麦克风，其余为 PCM 音频帧。
- 服务端做**能量 VAD 断句** → ASR 识别 → 字幕下发；AI 发言走 **TTS 逐句发声**，且同一座位在真人/AI 间使用**同一 Kokoro 音色**（抹平差异以隐藏 AI）。

---

## 8. Flutter 客户端（`G:\wwapp\werewolf_app\lib`）

### 8.1 文件职责

| 文件 | 职责 |
|---|---|
| `main.dart` | 入口 + 声明式条件路由骨架 |
| `config.dart` | `ServerConfig`：服务器地址、`allowInsecure`（自签证书放行）、token 持久化；`kDefaultPort = 11111`；`bakedServer` 来自 `--dart-define=WW_SERVER`；**已删除内置服务器预设** |
| `api.dart` | REST 封装（含 `getBytes` 用于下载、上传头像） |
| `ws.dart` | `WsClient`：连 `/ws?token=`，25s 心跳，JSON 解析后交给 `store.handleWsEvent`，含断线重连 |
| `store.dart` | `AppStore`：全局状态（`token/user/room/game/wsConnected/busy/maintenance/voice/voiceCaptions/talkingName`）+ WS 事件派发 + 通知/未读/音效 |
| `models.dart` / `notice.dart` / `theme.dart` | 数据模型 / 通知模型 / 配色常量（`kMoon`、`kAccent`、`kBlood`、`kGreen`、`kDim`…） |
| `voice.dart` / `voice_codec.dart` | 语音通道客户端（录音上行）+ PCM 编解码 |
| `sfx.dart` | **运行时合成** 16bit PCM WAV 的音效（不依赖素材文件） |
| `hotkeys.dart` | 纯函数 `hotkeyFor(...)` → `HotkeyAction{target,pass,focusChat,mic,help}`，桌面键盘快捷键 |
| `animations.dart` / `replay_engine.dart` | 动画 / 回放引擎（与服务端 `game.event` 溯源数据配合） |
| `screens/` | 21 个页面（见下） |

### 8.2 路由骨架（`main.dart`）

`ServerConfig.load()` + token → 条件分支：**未配置服务器** → `ServerSetupScreen`；**已配置未登录** → `LoginScreen`；**游戏中** → `GameScreen`；**房间中** → `RoomScreen`；**否则** → `HomeShell`（底部导航壳）。

### 8.3 页面清单（`lib/screens/`）

`server_setup`（连接你的服务器）· `login`（登录/注册）· `home_shell`（导航壳）· `lobby`（大厅/房间列表）· `room`（房间等待区：准备/踢人/转让/板子/模式/补 AI/组队）· `game`（对局主界面：环形座位、身份、阶段操作区、倒计时、聊天、语音字幕、快捷键）· `friends`（好友+私聊）· `forum`（论坛）· `shop`（商店）· `inventory`（背包/装扮）· `vip` · `ranking`（排行榜）· `replay`（回放播放器）· `profile` / `profile_view` / `edit_profile`（个人资料）· `tickets`（工单）· `updates`（更新动态）· `board_editor`（自定义板子）· `admin`（管理员后台，含批量用户操作与 CSV 导出）· `common`（共用组件）。

### 8.4 桌面键盘快捷键（`hotkeys.dart`）

`1-9/0` 选目标座位 · `A` 过麦/放弃 · `F` 聚焦聊天输入框 · `M` 麦克风开关 · `?` 帮助。由 `game.dart` 的 `Focus(onKeyEvent:...)` 分派；打字态（`_isTyping()`）与不可行动状态会自动屏蔽。

### 8.5 平台与测试

支持 `windows` / `linux` / `android`。测试 14 个文件：`config_test`、`widget_test`、`hotkeys_test`、`sfx_test`、`animations_test`、`ws_events_test`、`room_screen_test`、`replay_engine_test`、`replay_screen_test`、`voice_codec_test`、`admin_extra_test`、`updates_admin_test`、`p3_screens_test`、`live_contract_test`（**live 契约测试在未设 `WW_LIVE_*` 环境变量时自动 skip**）。当前基线：**152 通过 / 21 跳过**。

---

## 9. Web 前端（`src\main\resources\static`）

`index.html`（主 SPA）、`admin.html`、`js\app.js`（业务主逻辑，体量最大）、`js\ux.js`（交互增强 + **HOTKEYS 规格**：1-9/0 选座、Enter、Esc、`?` 帮助、`/`、随机主题、密度切换）、`js\nav.js`、`js\voice.js`、`js\scene3d.js`（three.js 场景）、`js\appearance.js` / `js\extras.js`、`js\admin.js`、`js\i18n.js`、`css\style.css` / `css\ux.css` / `css\themes.css`、`data\changelog.json`（更新动态）、`sw.js` + `manifest.webmanifest`（PWA）。

---

## 10. 功能全景

- **对局**：11 角色、20 套板子、夜晚六阶段、警长竞选、发言、放逐投票 + 平票 PK、遗言、开枪/翻牌、屠边/屠城可切、各阶段限时倒计时（隐私）、观战上帝视角、回放时间轴。
- **房间社交**：房号加入、准备/踢人/转让、断线 60s 重连续座、好友、私聊、**双人组队**（保证同阵营，👫 徽章）、论坛、工单、排行榜。
- **AI**：OpenAI 兼容（通义/DeepSeek/Ollama 等）后台可配；四层提示词 + **服务器强制信息隔离** + 狼队协作频道；`SpeechSanitizer` 防内心策略泄漏；失败降级随机代打；**匿名伪装人格**（固定昵称/头像/颜色）；语音同传（ASR + TTS 统一音色）。
- **账号经济**：注册/登录/可自选保留期的登录凭证、等级/经验/金币/MVP、金币流水、签到、商店（头像可上传、头像框、称号、昵称色、功能道具）+ 背包穿戴、兑换码、VIP。
- **体验**：暗黑哥特风 v2（Hero 横幅/玻璃拟态/磁贴布局）、多主题、多语言、移动端适配、音效系统、本地 MP3 背景音乐（存 IndexedDB，不上传）、PWA 一键安装、更新动态。

---

## 11. 构建 / 运行 / 部署

**服务端运行**
- 双击 `start.bat`：自动构建 → 生成自签证书（`config\keystore.p12`）→（可选）拉起语音微服务 → 以 `--spring.profiles.active=https` 启动。
- 或 `服务管理\服务管理.exe`：图形化启停/统计/日志/语音托管与守护自愈。
- 访问 `https://localhost:11111`（自签证书首次点「高级 → 继续访问」）。
- `tools\` 内含免安装 JDK 21 + Maven，无需外部环境。
- **数据**：`data\`（H2 库）、`uploads\`（上传）、`logs\`（日志）；删除 `data` 即重置。
- 测试账号：`alice / werewolf6`、`bob / werewolf6`、`duo_a & duo_b / duo12345`。

**Flutter 客户端**
- ⚠️ **Flutter 不在 PATH**，必须用全路径 `G:\flutter\bin\flutter.bat`、`G:\flutter\bin\dart.bat`，并统一加 **`--no-version-check`**（否则会 `git fetch --tags` 卡 5 分钟）。
- 本机出包：`tools\build-all.ps1`（Windows zip + Android 分架构 APK），产物进 `release-artifacts\`（已 gitignore）。
- 版本号在 `pubspec.yaml` 的 `version:`（当前 `1.2.0+3`）；打 `v*` tag 触发 CI 出三端并汇总 Release。

**三端 CI**：仓库 `weiweiaigaoshi9527/werewolf-client`，工作流 `.github/workflows/client-release.yml`。
- `meta`（读版本号）→ `quality`（`dart run tool/check_workflow.dart` + `flutter analyze --no-pub` + `flutter test`）→ `windows` / `linux` / `android` → `release`（仅 tag 时建 Release）。
- Windows 必须 `runs-on: windows-2022`（`windows-latest` 已迁至 VS 2026，Flutter 3.24.5 会算出错误的生成器 `Visual Studio 16 2019`）。
- Linux 用 `container: ubuntu:22.04` 干净容器（宿主镜像的第三方源会让 `libgstreamer1.0-dev` 依赖解析失败）；apt 需装 `libgstreamer1.0-dev` / `libgstreamer-plugins-base1.0-dev`（`audioplayers` 的 Linux 后端依赖）与 `jq`；容器内必须 `git config --global --add safe.directory '*'`。
- Linux 出包脚本 `packaging\linux\make-deb.sh` → `dist/werewolf-client_<ver>_amd64.deb` + `dist/werewolf-client-<ver>-linux-x64-portable.tar.xz`；deb 的 `Depends` 含 `libgtk-3-0, libglu1-mesa, libstdc++6, libgstreamer1.0-0, libgstreamer-plugins-base1.0-0, gstreamer1.0-plugins-good`。

---

## 12. 给接手方的约定与坑（重要）

1. **Flutter 全路径 + `--no-version-check`**，否则命令必卡死或报错。
2. **`record` 与 `record_android` 必须成对锁死为 `6.0.0` + `1.3.1`**：`record_android` 1.4.0+ 用 `flutter.compileSdkVersion`，而 Flutter 3.24.5 不给插件子工程注入 `flutter` 扩展，构建会直接失败。
3. **修改源码只在开发仓 `G:\wwapp\werewolf_app` 改**，然后用 `robocopy /MIR` 同步到交付镜像 `G:\狼人杀\werewolf_app`，不要反向改镜像。
4. **服务端端口是 11111（HTTPS）**，历史上曾长期写死 8080，任何新增代码/文档/脚本都不要再出现 8080。
5. **`presets` 有两处同名概念，别混**：`ServerConfig.presets`（客户端服务器地址预设，**已删除**）vs 板子预设（`BoardService` / `app.js`，仍然存在）。
6. **WS 信封的 `type` 必须最后写入**（`WsPush.java` 这么实现），payload 里若有同名字段会覆盖事件名。
7. **对局快照必须按座位裁剪**（`GameViewBuilder`）——AI 与真人共用同一份下发通道，任何「偷懒直接广播全量」的改动都会造成越权信息泄漏。
8. **AI 发言必须过 `SpeechSanitizer`**，否则内心策略（中文策略句）会泄漏成公开发言。
9. **静态资源刻意不做强缓存**（`spring.web.resources.cache.cachecontrol.no-cache`）：这是 PWA + Service Worker 应用，强缓存会让服务端更新后界面仍是旧版。
10. **H2 保留字**：历史表字段用过 `day_no` / `role_name` 规避 `day` / `role` / `type`；新增列注意 NOT NULL 迁移（`columnDefinition="... default 0"`）。
11. `git` 不读 Windows 系统代理：需要经代理推 GitHub 时必须 `git config http.proxy` / `https.proxy` 指向 `http://127.0.0.1:3067`。
12. 提交信息、代码注释、文档**统一用中文**。

---

## 13. 当前状态与待办

- 服务端：运行中（`server-run20.out` 为最新日志），端口 11111。
- Flutter 客户端：`pubspec.yaml` 版本 **`1.2.0+3`**；master 最新提交 `b685842`（已推 origin）；tag **`v1.2.0`** 已推送并触发 CI，三端构建 + Release 均成功，产物已取回 `G:\狼人杀\客户端`（`安装说明.md` 已同步为 1.2.0）。
- **已完成：客户端功能对等适配（v1.2.0 批次）**。本轮落地 C 类「平台相关」四项：
  - **外观与主题**：`lib/appearance.dart` + `lib/screens/appearance_panel.dart`（🌙 面板：13 套主题 / 7 种强调色 / 字号 / 密度 / 动效 / 背景亮度 / 自定义背景），热键 `T` 随机主题、`D` 切换密度已接上。
  - **多语言 i18n**：`lib/i18n.dart`（引擎 + `Tx` 组件 + 🌐 语言弹层）、`lib/i18n_data.dart`（照搬 `i18n.js` 的 `I18N_TEXT`/`I18N_TEXT_EXTRA`/`I18N_PATTERNS`）、`lib/i18n_client.dart`（客户端专有补充词条）；中/英/日/韩/俄五语，持久化键 `ww_lang` 与网页端一致。
  - **本地 MP3 背景音乐**：`lib/bgm.dart` + `lib/screens/bgm_panel.dart`（🎵 面板：多选添加 / 列表 / 上一首 / 播放暂停 / 下一首 / 音量 / 循环），沿用本项目约定只存本机绝对路径、不上传、不自动播放。
  - **后台一键清理**：`lib/screens/admin.dart` 接 `/api/admin/ops/cleanup/scan|run`（无用房间 + 无用账号，先预览后执行，危险操作走确认词）；请求体字段名逐字对齐后端 `CleanupBody`（`roomIdleHours` 等，勿意译）。
  - 验证：`flutter analyze` 0 问题；离线套件 **239 项全通过**（新增 `i18n_test` / `bgm_test` / `appearance_test` 与清理请求体单测）。
- **本轮未做：3D 视角场景（P5）** —— 经评估，本机工具链 Flutter 3.24.5 下 `flutter_scene` 需 ≥3.29 且依赖 Impeller、`three_dart`/`flutter_gl` 已停更，均无法保证离线可编译；用户决定**跳过**，对局继续使用 2D 座位圈。若日后要补，需先升级 Flutter 工具链（会影响三端 CI 与产物）。
- 已登记的历史偏差（复核结果）：Web 版 `M` 是座位标记循环，Flutter 端 `M` 为麦克风开关、座位标记另用 `V`——**已确认为有意保留**；Web 版 `T`/`D` 热键**本轮已补齐**；Web 版批量删除发 `op="del"` 而服务端只认 `"delete"`，Flutter 端按服务端契约发 `"delete"`（正确）；`/api/admin/local/**` 需 127.0.0.1 + `X-WW-Local: 1`，客户端**有意不实现**。
- 出包路径（下次沿用）：改完 → 提升 `pubspec.yaml` 版本 → 提交并推 master → 推 `v*` tag → CI 自动出三端并汇总 Release → 取回产物放入 `G:\狼人杀\客户端` 并更新 `客户端\安装说明.md` → `robocopy /MIR` 同步镜像。
- 镜像注意：`robocopy /MIR` 必须排除 `.git`、`build`、`.dart_tool`、`ephemeral`、`.plugin_symlinks`（否则 `*/flutter/ephemeral/.plugin_symlinks` 会被复制成断裂 reparse point，之后连 `flutter build windows` 都崩在 `Cannot create link ... errno 183`）。

</content>
