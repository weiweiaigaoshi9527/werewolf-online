#!/usr/bin/env bash
# 把 flutter build linux 的 bundle 打成 .deb 与便携 tar.xz。
# 由 .github/workflows/client-release.yml 的 linux job 调用（也可在任何 GNU/Linux 上手动跑）。
set -euo pipefail

VERSION="${VERSION:?需要传入 VERSION}"
APP_ID="cn.werewolf.client"
APP_NAME="狼人杀"
BIN_NAME="werewolf_app"          # Flutter Linux 产出的可执行文件名（= CMakeLists 的 BINARY_NAME）
PKG="werewolf-client"
BUNDLE="build/linux/x64/release/bundle"
OUT="dist"
STAGE="build/pkg/${PKG}-${VERSION}"

[ -d "$BUNDLE" ] || { echo "找不到 $BUNDLE，请先执行 flutter build linux --release" >&2; exit 1; }

rm -rf "$STAGE"
mkdir -p "$STAGE/DEBIAN" "$STAGE/opt/${PKG}" "$STAGE/usr/bin" \
         "$STAGE/usr/share/applications" "$STAGE/usr/share/icons/hicolor/512x512/apps"

cp -a "$BUNDLE"/. "$STAGE/opt/${PKG}/"
ln -sf "/opt/${PKG}/${BIN_NAME}" "$STAGE/usr/bin/${PKG}"

if [ -f packaging/linux/icon-512.png ]; then
  cp packaging/linux/icon-512.png "$STAGE/usr/share/icons/hicolor/512x512/apps/${PKG}.png"
fi

cat > "$STAGE/usr/share/applications/${PKG}.desktop" <<EOF
[Desktop Entry]
Type=Application
Version=1.0
Name=${APP_NAME}
Name[en_US]=Werewolf Client
GenericName=Werewolf online game client
Comment=局域网/公网狼人杀跨端客户端
Exec=${PKG} %u
Icon=${PKG}
Terminal=false
Categories=Game;Network;
Keywords=werewolf;狼人杀;social deduction;
MimeType=x-scheme-handler/werewolf;
EOF

ARCH="$(dpkg --print-architecture)"
DEPENDS="libgtk-3-0, libglu1-mesa, libstdc++6, libgstreamer1.0-0, libgstreamer-plugins-base1.0-0, gstreamer1.0-plugins-good"
SIZE=$(( $(du -sk "$STAGE" | cut -f1) ))

cat > "$STAGE/DEBIAN/control" <<EOF
Package: ${PKG}
Version: ${VERSION}
Architecture: ${ARCH}
Maintainer: werewolf <werewolf@example.com>
Installed-Size: ${SIZE}
Depends: ${DEPENDS}
Section: games
Priority: optional
Homepage: https://github.com/
Description: ${APP_NAME} 跨端客户端
 基于 Flutter 的狼人杀线上客户端，连接同一套 Spring Boot 服务端
 （REST /api + WebSocket /ws 与 /ws/voice），支持 Windows / Linux / Android 互通。
EOF

mkdir -p "$OUT"
dpkg-deb --root-owner-group --build "$STAGE" "$OUT/${PKG}_${VERSION}_${ARCH}.deb"
tar -C "$BUNDLE" -cJf "$OUT/${PKG}-${VERSION}-linux-x64-portable.tar.xz" .

echo "产出："
ls -lh "$OUT"
