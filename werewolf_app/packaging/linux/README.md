# Linux 版出包（两条路，任选其一）

Flutter 的 Linux 桌面包**只能在 GNU/Linux 上构建**（依赖 GTK/clang/ninja 的原生工具链，
Windows 上无法交叉产出）。本机没有 WSL 也没有 Docker，所以走下面任一条。

---

## A. 任意一台 Debian/Ubuntu 机器（含你的 VPS）——最快

```bash
# 1) 取代码（把客户端工程拷到纯 ASCII 路径，别放中文目录）
sudo mkdir -p /opt/wsw && sudo chown $USER /opt/wsw
#    从 Windows 传：scp -r G:/wwapp/werewolf_app  user@vps:/opt/wsw/

cd /opt/wsw/werewolf_app

# 2) 工具链（Ubuntu 22.04）
sudo apt-get update
sudo apt-get install -y clang cmake ninja-build pkg-config libgtk-3-dev \
  libglu1-mesa-dev libstdc++-12-dev dpkg-dev xz-utils git unzip curl \
  libgstreamer1.0-dev libgstreamer-plugins-base1.0-dev
#    最后两个是 audioplayers 的 Linux 后端（GStreamer）必需，缺了 CMake 会直接失败；
#    在 GitHub 的 runner 镜像上它们还可能因预装包导致依赖解析失败
#    （libgstreamer1.0-dev Depends: libunwind-dev → held broken packages），
#    因此 CI 里 Linux 是在干净的 ubuntu:22.04 容器中构建的。

# 3) Flutter（与 CI 固定同一版本，避免"本机能编 CI 不能编"）
git clone --depth 1 --branch 3.24.5 https://github.com/flutter/flutter.git /opt/flutter
export PATH="/opt/flutter/bin:$PATH"
flutter --version
flutter pub get

# 4) 构建 + 打 deb 与便携包
flutter build linux --release --dart-define=WW_SERVER=https://你的域名:11111
VERSION=1.1.0 bash packaging/linux/make-deb.sh
#    → dist/werewolf-client_1.1.0_amd64.deb
#    → dist/werewolf-client-1.1.0-linux-x64-portable.tar.xz
```

安装/运行：

```bash
sudo apt install ./dist/werewolf-client_1.1.0_amd64.deb
werewolf-client            # 或从应用菜单点「狼人杀」
# 便携包解压即用：tar xf dist/...portable.tar.xz -C ~/ww && ./ww/werewolf_app
```

> 连自签证书的局域网服务端时，App 内「服务器设置」里保持"允许自签名证书"开启即可
> （Dart 侧 `HttpOverrides` 放行，不依赖系统 CA）。

## B. GitHub Actions

`.github/workflows/client-release.yml` 的 `linux` job 就是上面这套步骤的自动化版本，
额外产出 `windows` / `android` 包并在打 tag（`v*`）时汇总成 Release 附件。
仓库根目录 = 本工程（`werewolf_app/`）。

---

## make-deb.sh 做了什么

- 把 `build/linux/x64/release/bundle/` 整体装到 `/opt/werewolf-client/`，
  `/usr/bin/werewolf-client` 软链到其中的可执行文件（Flutter 产物必须整目录一起装，
  因为 `lib/libapp.so`、`lib/libflutter_linux_gtk.so` 与 `data/` 是相对定位的）。
- 生成 `.desktop`（名称「狼人杀」、`Categories=Game;Network;`、注册 `x-scheme-handler/werewolf`）
  与 hicolor 图标（用 `packaging/linux/icon-512.png`）。
- `DEBIAN/control` 的 Depends 给的是 `libgtk-3-0, libglu1-mesa, libstdc++6,
  libgstreamer1.0-0, libgstreamer-plugins-base1.0-0, gstreamer1.0-plugins-good`
  （最后三项是音效所需：对局音效走 GStreamer 播放运行时合成的 WAV）；
  `Architecture` 取 `dpkg --print-architecture`，`Installed-Size` 按实际体积算。
- 同时产出便携 `tar.xz`，给不方便装包的环境。

## 常见坑

- 在**含中文的路径**下构建会失败（原生工具链路径乱码），务必放 ASCII 路径。
- 用 `cp -r`/`robocopy` 跨机器搬工程时，要排除 `build/`、`.dart_tool/`、
  `*/flutter/ephemeral/`、`.plugin_symlinks/`，否则会出现
  `Cannot create link ... errno 183`。
- Ubuntu 24.04 构建出的包在 22.04 上可能因 glibc 版本更高而跑不了；
  要分发就固定用 **ubuntu-22.04** 构建。
