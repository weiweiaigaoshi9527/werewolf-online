#!/usr/bin/env bash
# ============================================================
#  狼人杀 Online · 服务端 注册为 systemd 常驻服务（需 root）
#  用法：  sudo ./install-service.sh
#  卸载：  sudo ./uninstall-service.sh
# ============================================================
set -eu
cd "$(dirname "$0")"
APP_DIR="$(pwd)"
RUN_USER="${WW_USER:-$(id -un)}"
PORT="${WW_PORT:-11111}"
SERVICE=/etc/systemd/system/werewolf-server.service

if [ "$(id -u)" != "0" ]; then
  echo "请用 root 运行：sudo ./install-service.sh"
  exit 1
fi

cat > "$SERVICE" <<EOF
[Unit]
Description=Werewolf Online Game Server
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=$RUN_USER
WorkingDirectory=$APP_DIR
Environment=WW_PORT=$PORT
Environment=VOICE_ENABLED=false
ExecStart=$APP_DIR/start.sh
ExecStop=$APP_DIR/stop.sh
Restart=on-failure
RestartSec=3
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl enable werewolf-server.service
echo "已注册。启动：  sudo systemctl start werewolf-server"
echo "查看日志：      journalctl -u werewolf-server -f"
echo "访问地址：      http://<本机IP>:$PORT  （若已生成 config/keystore.p12 则为 https）"
