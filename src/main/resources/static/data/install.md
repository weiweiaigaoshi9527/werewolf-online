# 狼人杀 Online · 服务端安装指导

面向自建服务器的完整安装与部署指引。按顺序阅读：先满足环境要求，再启动源码服务，最后按需配置证书、AI、语音与客户端。

技术栈：Spring Boot 3 + H2 内嵌文件库 + 原生 HTML/JS/CSS + WebSocket + OpenAI 兼容 AI。

---

## 一、环境要求

运行本项目只需两项基础环境，脚本已内置可选的便携版（`tools/` 目录），推荐优先使用：

- **JDK 21**（必须 21 或更高）。项目基于 Spring Boot 3.5，低于 17 无法运行。
  - 脚本默认使用内置目录 `tools\jdk-21`，无需单独安装。
  - 如需手动安装，请配置 `JAVA_HOME` 指向 JDK 21，并把 `%JAVA_HOME%\bin` 加入 `PATH`。
- **Maven 3.9+**（仅构建需要）。脚本默认使用内置目录 `tools\apache-maven-3.9.16`。
  - 首次构建需联网下载依赖，建议配置国内镜像以加速。

其他要求：

- **操作系统**：Windows 10/11（启动脚本为 `.bat`）。
- **磁盘**：至少预留 2GB（依赖、H2 数据库、日志与可选语音模型）。
- **端口**：默认 `11111`（Web/HTTPS）；语音微服务另用 `5001`、`5002`。
- **路径建议**：项目与语音服务请放在**纯 ASCII 路径**（如 `G:\werewolf-voice`），非 ASCII 中文路径可能导致原生库/Python 加载失败。

验证环境：

```
java -version     应显示 21.x
mvn -v            应显示 3.9.x
```

---

## 二、源码启动（start.bat）

最简启动方式：**双击项目根目录的 `start.bat`**。

脚本会自动完成：

1. 切到项目目录，使用内置 `tools\jdk-21` 作为 `JAVA_HOME`。
2. 检测 `target\werewolf-online-0.1.0-SNAPSHOT.jar`：若不存在，自动调用 `build.bat` 构建。
3. 若已安装语音环境，自动拉起 ASR/TTS 微服务并置 `VOICE_ENABLED=true`；未安装则退回纯文字模式。
4. 若缺少 `config\keystore.p12`，自动调用 `make-cert.bat` 生成自签证书。
5. 以 `--spring.profiles.active=https` 启动服务，并进入**守护循环**（后台点「重启」会以退出码 86 结束进程，脚本自动重新拉起）。

启动成功后控制台会打印访问地址：

- 本机访问：`https://localhost:11111`
- 局域网访问：`https://<本机IP>:11111`

常用参数（可改环境变量后重启）：

- `ADMIN_BOOTSTRAP_USERNAMES`：启动时把指定已存在用户名置为管理员（逗号分隔），默认 `weiwei`。
- `VOICE_HOME`：语音服务家目录，默认 `G:\werewolf-voice`。

停止服务：关闭控制台窗口，或双击 `stop.bat`，也可在窗口按 `Ctrl+C`。

数据位置：账号与战绩存于项目目录 `data/`（H2 文件库），头像上传在 `uploads/`。**删除 `data/` 即重置全部数据（谨慎）。**

---

## 三、构建（build.bat）

当修改了 Java 代码**或任何前端静态资源**（`src/main/resources/static/` 下的 HTML/JS/CSS/Markdown）后，都需要重新构建，因为静态资源会被打进 jar：

1. 双击 `build.bat`。
2. 脚本设置 `JAVA_HOME=tools\jdk-21`，并将 `tools\apache-maven-3.9.16\bin` 加入 `PATH`。
3. 执行 `mvn -B -DskipTests package`。
4. 产物：`target\werewolf-online-0.1.0-SNAPSHOT.jar`。

说明与建议：

- `-DskipTests` 跳过测试，加快构建。需要跑测试时自行执行 `mvn test`。
- 构建完成后重新运行 `start.bat` 使改动生效。
- 加速构建可在 `%USERPROFILE%\.m2\settings.xml` 配置阿里云等 Maven 镜像。
- 首次构建会下载大量依赖，耗时较长属正常现象。

---

## 四、HTTPS 自签证书（make-cert.bat）

语音麦克风（`getUserMedia`）要求「安全上下文」，只有 `https://` 或 `localhost` 才能授权麦克风；用 `http://<局域网IP>` 访问时，其他设备的麦克风会被浏览器拒绝。因此项目默认以 HTTPS 提供。

生成/重生成证书：

1. 双击 `make-cert.bat`。
2. 脚本使用内置 `tools\jdk-21\bin\keytool.exe` 生成 PKCS12 证书。
3. 输出到 `config\keystore.p12`，口令固定为 `changeit`，有效期 3650 天。
4. SAN 包含：`localhost`、本机主机名（`%COMPUTERNAME%`）、`127.0.0.1` 以及当前局域网 IPv4。

要点：

- `start.bat` 在检测到证书缺失时会自动调用本脚本，通常无需手动执行。
- **本机 IP 变化后**，重新运行 `make-cert.bat` 并重启服务，否则用 IP 访问会出现证书名称不匹配告警（用主机名/localhost 访问不受影响）。
- 证书为自签，浏览器首次访问会提示「不安全/您的连接不是私密连接」，点「高级 → 继续访问」信任一次即可（**每台设备各信任一次**）。
- 不需要麦克风时，也可退回纯 HTTP：直接执行 `java -jar target\werewolf-online-0.1.0-SNAPSHOT.jar`（不加 `--spring.profiles.active=https`）。

---

## 五、后台 AI 配置（/admin）

后台管理页与玩家界面相互独立，浏览器访问 **`https://localhost:11111/admin`**（或 `/install` 同级的独立页面）。

1. **登录权限**：仅管理员可进入。服务器对所有 `/api/admin/**` 强制鉴权（未登录 401、非管理员 403）。
2. **谁是管理员**：
   - 第一个注册的用户自动成为管理员；
   - 或在 `application.yml` 配置 `admin.bootstrap-usernames`（环境变量 `ADMIN_BOOTSTRAP_USERNAMES`，逗号分隔）在启动时指定；
   - 若系统中一个管理员都没有，启动时会兜底把最早注册的用户提为管理员。
3. **AI API 配置**：进入「AI API 配置」页，填写 OpenAI 兼容服务的 Base URL、API Key、模型，设置思考深度/温度/超时/重试/并发，勾选「启用 AI」并保存。
   - 点「测试连通」验证配置是否可用。
   - 想先离线试玩：Base URL 填 `mock://local` 并启用，即可用内置离线假 AI 跑通整局。
   - 配置存数据库，重启后保留。

若 AI 不发言或秒过：确认已启用且连通测试通过；未启用时 AI 座位会走随机代打（保证对局不卡死）。

---

## 六、语音微服务（SenseVoice 5001 + Kokoro 5002）

语音由两个独立的 Python 微服务提供，家目录固定为 **`G:\werewolf-voice`**（纯 ASCII 路径）：

- **SenseVoice（ASR，语音转文字）**：监听端口 **5001**。
- **Kokoro（TTS，文字转语音）**：监听端口 **5002**。

一键安装：

1. 双击 `voice\install_voice.bat`。
2. 脚本将 Python 3.11 安装到 `G:\werewolf-voice\py311`，创建 `venv-asr` 与 `venv-tts` 两个虚拟环境。
3. 通过国内镜像（阿里云 PyPI + modelscope + hf-mirror）安装依赖并下载模型：
   - ASR：`iic/SenseVoiceSmall` 与标点模型 `punc_ct`；
   - TTS：`hexgrad/Kokoro-82M`。
4. 复制 `voice\asr_service.py`、`voice\tts_service.py` 到 `G:\werewolf-voice`。

运行机制：

- `start.bat` 检测到 `venv-asr`/`venv-tts` 与两个服务脚本后，会以最小化窗口拉起两个服务，并置 `VOICE_ENABLED=true`。
- 任一缺失时自动退回纯文字模式，功能不受影响。
- 首次加载模型需等待数十秒；模型目录由 `WW_MODEL_DIR` 指向 `G:\werewolf-voice\models`。
- 语音地址、总开关、每座位音色、逐句节奏、VAD、匿名伪装等可在后台「语音服务配置」页调整，保存即时生效并持久化。

---

## 七、客户端（Electron 与 PWA）

服务端就绪后，客户端有两种形态，且都属于 WebView 套壳，**加载的是服务器上的实时网页**：

### 1. 桌面客户端（Electron）

- 工程位于 `G:\wwelectron`，`main.js` 加载实时网页，`setup.html` 提供服务端地址输入框（首次运行手工填写并测试连接）。
- 已处理自签证书信任与麦克风放行。
- 使用 `@electron/packager` 打包；安装 Electron 卡在 GitHub 时可设 `ELECTRON_MIRROR=https://npmmirror.com/mirrors/electron/`。
- 由于加载实时网页，**后端/前端改动自动生效，一般无需重打包客户端**。

### 2. 移动 / 通用客户端（PWA）

- 由 `static/manifest.json` + `sw.js`（网络优先）+ `icons/` 构成。
- 浏览器打开站点后选择「安装到主屏幕」即可，离线可开。
- 注意：manifest 必须使用 `.json`（`application/json`），用 `.webmanifest` 会被当作 `octet-stream` 导致安装失败。

> 旧的 Flutter 工程 `werewolf_app/` 已降为 legacy，仅作参考，不再作为主客户端。

---

## 八、常见问题排查

- **端口被占用**：修改 `src/main/resources/application.yml` 的 `server.port`，重新 `build.bat` 后重启。
- **浏览器提示证书不安全**：属自签证书正常现象，点「高级 → 继续访问」；每台设备需各自信任一次。
- **用 IP 访问报证书名称不匹配**：本机 IP 变了，重跑 `make-cert.bat` 并重启；或改用主机名/localhost 访问。
- **麦克风无法授权**：确认使用 `https://` 访问（HTTP + 局域网 IP 会被浏览器拒绝），且已在证书页面点击继续访问。
- **AI 不发言 / 秒过**：进 `/admin` 的「AI API 配置」确认已启用且「测试连通」通过。
- **进不去后台**：`/admin` 仅管理员；用最早注册的账号登录，或配置 `ADMIN_BOOTSTRAP_USERNAMES` 后重启。
- **前端改动没生效**：静态资源已打进 jar，必须重新 `build.bat` 并重启服务；浏览器可强制刷新（Ctrl+F5）。
- **语音服务未启动 / 无声**：确认 `G:\werewolf-voice` 下存在 `venv-asr`、`venv-tts` 与两个服务脚本；首次加载模型需等待数十秒；查看 `tts_core.log`、`tts_kokoro.log`、`tts_zh.log` 等日志。
- **首次构建失败**：检查网络与 Maven 镜像配置；确认 `tools\jdk-21`、`tools\apache-maven-3.9.16` 存在或已自行安装。
- **忘记管理员账号 / 需重置数据**：删除项目目录 `data/` 后重启即清空所有数据（谨慎操作）。
- **原生构建报路径错误**：Flutter/CMake/Python 等原生构建路径含中文会失败，请复制到纯 ASCII 路径（如 `G:\wwapp`）再构建。
