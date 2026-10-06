# 狼人杀 · 多平台客户端构建说明

> 架构说明：本项目的全部功能都在网页端（Spring Boot + WebSocket）。
> 各平台客户端是"壳"——加载同一个服务器页面，因此**网页端更新功能，所有客户端自动同步**，无需发版。
> 壳的三项平台适配：自签证书信任（安装 werewolf-cert.cer）、麦克风权限（语音同传）、服务器地址设置。

## 各平台一览

| 平台 | 形态 | 构建环境 | 状态 |
|---|---|---|---|
| Windows | NSIS 安装包 | 本机（Windows） | ✅ 已产出 `狼人杀 Setup 2.0.0.exe` |
| Linux | 便携 tar.gz | 本机 | ✅ 已产出 `werewolf-client-2.0.0.tar.gz` |
| macOS | dmg | 需 macOS | ⚙️ 配置就绪，一条命令构建 |
| Android | APK | 需 Android Studio / SDK | ⚙️ 工程就绪（android/ 目录） |
| iOS | ipa | 需 macOS + Xcode | ⚙️ 工程就绪（ios/ 目录） |
| 全平台 | PWA | 无需构建 | ✅ 浏览器「安装应用」即用（推荐路径） |

## 桌面端（Windows / Linux / macOS）

```bash
cd G:\wwelectron
npm install
npm run dist:win    # Windows NSIS 安装包
npm run dist:linux  # Linux tar.gz / deb / AppImage*
npm run dist:mac    # macOS dmg（必须在 macOS 上执行）
```
产物在 `release/`。注意：electron-builder 26 在 Windows 上打 AppImage 会缺 linux 版 mksquashfs（本机已实测），Linux 分发用 tar.gz 或 deb。

## Android（APK）

前置：安装 [Android Studio](https://developer.android.com/studio)（自带 SDK）或仅命令行 SDK。

```bash
cd G:\wolfmobile\android
# local.properties 写入 sdk.dir=C:\\path\\to\\Android\\Sdk（若未自动生成）
./gradlew assembleDebug     # 调试包 app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # 发布包（需签名配置）
```

已内置的适配：
- `network_security_config.xml`：信任用户安装的 CA——手机上安装 `werewolf-cert.cer` 后，自签 HTTPS 不再告警
- `RECORD_AUDIO` 权限 + WebView 自动授予 getUserMedia（语音同传麦克风可用）
- `server.url` 需改成你自己的服务器地址（`capacitor.config.json`；仓库里已是占位符 `your-server.example.com`）

## iOS（ipa）

前置：一台 macOS + Xcode。

```bash
cd wolfmobile
npm run ios:open        # 打开 Xcode 工作区
# Xcode 里选 Team 签名 → Product → Build/Run
```

## 服务器地址

**所有客户端都不再内置预设服务器**，需各自指定：
- 桌面端（Electron）：首次启动进入「连接服务器」页手工填写，之后持久化；如需给分发包预填，改 `main.js` 的 `DEFAULT_SERVER`
- 移动端（Capacitor）：构建前把 `capacitor.config.json` 的 `server.url` 改成你的服务器地址（当前是占位符）

## 证书

自签证书 SAN 覆盖：localhost / 主机名 / 127.0.0.1 / 局域网 IP。如需按域名访问，在 `make-cert.bat` 的 SAN 里追加你自己的域名即可。
给玩家分发 `config\werewolf-cert.cer`：手机上安装到"设置→安全→安装证书→CA 证书"，即可去掉浏览器警告（Android App 内由网络安全配置自动信任）。
IP 或域名变更后：双击 `make-cert.bat` 重新生成 + 重启服务。
