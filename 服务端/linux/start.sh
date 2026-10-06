#!/usr/bin/env bash
# ============================================================
#  狼人杀 Online · 服务端（Linux x64）一键启动
#  内置 Java 21 运行时，无需系统安装 Java。
#  用法：  ./start.sh            （前台运行，Ctrl+C 停止）
#          WW_PORT=11222 ./start.sh
#          WW_HEAP=2048m ./start.sh
#          WW_VOICE_HOME=/opt/werewolf-voice ./start.sh   # 指定语音微服务目录
# ============================================================
set -u
cd "$(dirname "$0")"
APP_DIR="$(pwd)"

JAVA="$APP_DIR/jre/bin/java"
JAR="$APP_DIR/werewolf-online.jar"
PORT="${WW_PORT:-11111}"
HEAP="${WW_HEAP:-1024m}"
[ -n "${WW_BIND:-}" ] && BIND="--server.address=$WW_BIND" || BIND=""

if [ ! -x "$JAVA" ]; then
  echo "[错误] 未找到内置 Java 运行时：$JAVA"
  echo "       请确认压缩包已完整解压（不要只复制 jar）。"
  exit 1
fi
if [ ! -f "$JAR" ]; then
  echo "[错误] 未找到服务端程序：$JAR"
  exit 1
fi

mkdir -p config data uploads logs

# ---------------- 局域网 IP ----------------
LAN_IP="$(ip route get 1.1.1.1 2>/dev/null | awk '{for(i=1;i<=NF;i++) if($i=="src"){print $(i+1); exit}}')"
if [ -z "$LAN_IP" ]; then
  LAN_IP="$(hostname -I 2>/dev/null | awk '{print $1}')"
fi
if [ -z "$LAN_IP" ]; then
  LAN_IP="127.0.0.1"
fi

# ---------------- 自签 HTTPS 证书 ----------------
# 语音（麦克风 getUserMedia）要求安全上下文，故默认以 HTTPS 提供。
# JRE 不带 keytool，这里用 openssl 生成 PKCS12；没有 openssl 则自动退回 HTTP。
USE_HTTPS=0
KS="$APP_DIR/config/keystore.p12"
if [ -f "$KS" ]; then
  USE_HTTPS=1
elif command -v openssl >/dev/null 2>&1; then
  echo "[初始化] 生成自签 HTTPS 证书（SAN 含 localhost / 127.0.0.1 / $LAN_IP）…"
  TMP="$(mktemp -d)"
  if openssl req -x509 -newkey rsa:2048 -sha256 -days 3650 -nodes \
        -keyout "$TMP/key.pem" -out "$TMP/cert.pem" \
        -subj "/CN=Werewolf Server" \
        -addext "subjectAltName=DNS:localhost,IP:127.0.0.1,IP:$LAN_IP" >/dev/null 2>&1 &&
     openssl pkcs12 -export -out "$KS" -inkey "$TMP/key.pem" -in "$TMP/cert.pem" \
        -name werewolf -passout pass:changeit >/dev/null 2>&1; then
    USE_HTTPS=1
  fi
  rm -rf "$TMP"
fi

if [ "$USE_HTTPS" = "1" ]; then SCHEME="https"; else SCHEME="http"; fi

# ---------------- 语音微服务（SenseVoice ASR + Kokoro TTS）----------------
#  语音运行时体量很大（Python + torch + 模型，数 GB），不随本包分发，需另行准备。
#  按优先级自动查找语音家目录；找到就拉起两个服务并启用语音，找不到退回纯文字模式。
#  查找顺序：$WW_VOICE_HOME → 本目录/voice → 上级目录/werewolf-voice → ~/werewolf-voice → /opt/werewolf-voice
VOICE_ENABLED=false
VOICE_HOME=""
for cand in "${WW_VOICE_HOME:-}" "$APP_DIR/voice" "$APP_DIR/../werewolf-voice" "$HOME/werewolf-voice" "/opt/werewolf-voice"; do
  if [ -n "$cand" ] && [ -f "$cand/asr_service.py" ] && [ -f "$cand/tts_service.py" ]; then
    VOICE_HOME="$cand"
    break
  fi
done

# 端口是否已被占用（纯 bash，无需 curl/lsof）
port_open() {
  (exec 3<>"/dev/tcp/127.0.0.1/$1") >/dev/null 2>&1 && { exec 3>&- 3<&- 2>/dev/null; return 0; }
  return 1
}

# 在语音目录里找 venv 的 python（兼容 Linux 的 bin/ 与 Windows 布局的 Scripts/）
venv_python() {
  for p in "$1/bin/python" "$1/bin/python3" "$1/Scripts/python.exe"; do
    [ -x "$p" ] && { printf '%s' "$p"; return 0; }
  done
  return 1
}

if [ -n "$VOICE_HOME" ]; then
  A_PY="$(venv_python "$VOICE_HOME/venv-asr" || true)"
  T_PY="$(venv_python "$VOICE_HOME/venv-tts" || true)"
  if [ -n "$A_PY" ] && [ -n "$T_PY" ]; then
    export WW_MODEL_DIR="$VOICE_HOME/models"
    export HF_HOME="$VOICE_HOME/models/hf"
    export HF_ENDPOINT="${HF_ENDPOINT:-https://hf-mirror.com}"
    : > "$APP_DIR/logs/voice-pids.txt"

    start_voice() {  # $1=名称 $2=python $3=服务脚本 $4=端口 $5=日志名
      if port_open "$4"; then
        echo "[语音] $1 已在运行（127.0.0.1:$4），复用"
        return 0
      fi
      echo "[语音] 拉起 $1 ..."
      nohup "$2" "$3" >> "$APP_DIR/logs/voice-$5.log" 2>&1 &
      echo $! >> "$APP_DIR/logs/voice-pids.txt"
      return 0
    }
    start_voice ASR "$A_PY" "$VOICE_HOME/asr_service.py" 5001 asr
    start_voice TTS "$T_PY" "$VOICE_HOME/tts_service.py" 5002 tts

    # 简单等一会儿让模型加载（首次可能要数十秒；不等也不影响，稍后自动可用）
    WAIT="${WW_VOICE_WAIT:-15}"
    i=0
    while [ "$i" -lt "$WAIT" ]; do
      if port_open 5001 && port_open 5002; then break; fi
      sleep 1
      i=$((i + 1))
    done
    if port_open 5001 && port_open 5002; then
      echo "[语音] 状态：ASR=在线  TTS=在线"
    else
      echo "[语音] 状态：ASR/TTS 尚未就绪（首次加载模型较慢，稍后会自动可用）"
    fi
    VOICE_ENABLED=true
  else
    echo "[语音] 找到语音目录 $VOICE_HOME，但缺少 venv-asr / venv-tts 虚拟环境，跳过。"
  fi
fi
VOICE_DESC="未安装（纯文字模式）"
if [ "$VOICE_ENABLED" = "true" ]; then VOICE_DESC="已启用（$VOICE_HOME）"; fi
export VOICE_ENABLED

echo "============================================================"
echo "  狼人杀 Online 服务端（Linux）已启动"
echo "  本机访问:   $SCHEME://localhost:$PORT"
echo "  局域网访问: $SCHEME://$LAN_IP:$PORT    <-- 把这个地址发给玩家"
echo "  后台管理:   $SCHEME://localhost:$PORT/admin   （首个注册账号即管理员）"
echo "  语音同传:   $VOICE_DESC"
echo "  数据目录:   $APP_DIR/data    头像: $APP_DIR/uploads"
echo "  停止服务:   Ctrl+C  或另开终端执行 ./stop.sh"
if [ "$USE_HTTPS" = "1" ]; then
  echo "  注意: 自签证书，浏览器首次访问点『高级 → 继续访问』即可。"
else
  echo "  注意: 当前为 HTTP（未检测到 openssl）；麦克风语音需要 HTTPS。"
fi
if [ "$VOICE_ENABLED" != "true" ]; then
  echo "  提示: 未检测到语音微服务，浏览器麦克风/语音同传不可用；"
  echo "        如需语音，可用 WW_VOICE_HOME=<语音目录> ./start.sh 指定已有的语音环境。"
fi
echo "============================================================"

# ---------------- 守护循环 ----------------
# 后台「服务器控制」里点“重启”会以退出码 86 退出，这里自动重新拉起。
while true; do
  if [ "$USE_HTTPS" = "1" ]; then
    "$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
      -Xms128m -Xmx"$HEAP" -jar "$JAR" \
      --server.port="$PORT" $BIND --spring.profiles.active=https
  else
    "$JAVA" -Dfile.encoding=UTF-8 -Djava.awt.headless=true \
      -Xms128m -Xmx"$HEAP" -jar "$JAR" \
      --server.port="$PORT" $BIND
  fi
  CODE=$?
  if [ "$CODE" = "86" ]; then
    echo "[守护] 收到重启指令，2 秒后重新拉起…"
    sleep 2
    continue
  fi
  echo "[守护] 服务已退出（退出码 $CODE）。"
  break
done
