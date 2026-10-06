#!/usr/bin/env bash
# 卸载 systemd 常驻服务（需 root）
set -eu
if [ "$(id -u)" != "0" ]; then
  echo "请用 root 运行：sudo ./uninstall-service.sh"
  exit 1
fi
systemctl stop werewolf-server.service 2>/dev/null || true
systemctl disable werewolf-server.service 2>/dev/null || true
rm -f /etc/systemd/system/werewolf-server.service
systemctl daemon-reload
echo "已卸载 werewolf-server 服务。"
