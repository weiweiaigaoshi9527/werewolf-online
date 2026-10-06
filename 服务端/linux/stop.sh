#!/usr/bin/env bash
# ============================================================
#  狼人杀 Online · 服务端 停止脚本
#   1) 停本目录的服务端（只匹配 werewolf-online.jar，不影响别的实例）
#   2) 停本目录启动时拉起过的语音微服务（按 logs/voice-pids.txt 记录，
#      只停自己启动的，不动别人已经在跑的 ASR/TTS）
# ============================================================
set -u
cd "$(dirname "$0")"
APP_DIR="$(pwd)"

# ---- 1) 服务端 ----
PIDS="$(pgrep -f 'werewolf-online\.jar' 2>/dev/null || true)"
if [ -z "$PIDS" ]; then
  echo "本目录的服务端未在运行。"
else
  echo "正在停止服务端进程：$PIDS"
  kill $PIDS 2>/dev/null || true
  for _ in $(seq 1 60); do
    sleep 0.2
    pgrep -f 'werewolf-online\.jar' >/dev/null 2>&1 || break
  done
  PIDS="$(pgrep -f 'werewolf-online\.jar' 2>/dev/null || true)"
  if [ -n "$PIDS" ]; then
    echo "优雅停止超时，强制结束：$PIDS"
    kill -9 $PIDS 2>/dev/null || true
  fi
  echo "服务端已停止。"
fi

# ---- 2) 本目录启动过的语音微服务 ----
VPIDFILE="$APP_DIR/logs/voice-pids.txt"
if [ ! -f "$VPIDFILE" ]; then
  echo "（本目录未启动语音微服务，其它实例运行中的 ASR/TTS 保持不动）"
  exit 0
fi

echo "正在停止本目录启动的语音微服务..."
while read -r vp; do
  case "$vp" in
    ''|*[!0-9]*) continue ;;
  esac
  if kill -0 "$vp" 2>/dev/null; then
    echo "  停止 PID $vp"
    kill "$vp" 2>/dev/null || true
    sleep 0.3
    if kill -0 "$vp" 2>/dev/null; then kill -9 "$vp" 2>/dev/null || true; fi
  fi
done < "$VPIDFILE"
rm -f "$VPIDFILE"
echo "已停止。"
