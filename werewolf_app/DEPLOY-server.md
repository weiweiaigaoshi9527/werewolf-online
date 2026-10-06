# 服务端部署 · 让六端互通

服务端就是本仓库的 Spring Boot 应用（`target/werewolf-online-0.1.0-SNAPSHOT.jar`），
提供 REST `/api/*` 与 WebSocket `/ws`、`/ws/voice`。所有端（网页 + Flutter App）连同一实例即互通。

---

## A. 局域网快速版（最省事，朋友同网段联机）

1. 双击 `start.bat`（会拉起语音微服务并以 HTTPS 启动游戏服务）。
2. 查本机局域网 IP：`ipconfig`（Windows）/ `ifconfig`（mac/linux），例如 `192.168.1.169`。
3. 各设备 App 的「服务器设置」填：`https://192.168.1.169:11111`，保持「允许自签名证书」开启。
4. 麦克风语音功能要求安全上下文，HTTPS 已满足；首次访问自签名证书需在设备端放行/信任。

> 局限：仅同一局域网；跨网/出远门连不上。要真正跨地互通 → 用下面的公网版。

---

## B. 公网 VPS + 域名 + 正式证书（推荐，跨地互通）

思路：**Nginx 在边缘终结 TLS（Let's Encrypt 正式证书），反向代理到本机 11111 的 Spring 明文服务**。
这样 App 端连 `https://你的域名`（正规证书，无需自签名开关），且证书自动续期。

### 1. 准备
- 一台 VPS（Linux），一个域名，已解析 A 记录到 VPS IP。
- VPS 上装 JDK 21、Nginx、certbot。
- 把 `target/werewolf-online-0.1.0-SNAPSHOT.jar` 和 `data/` 传到 `/opt/werewolf/`。

### 2. 让 Spring 以明文 HTTP 跑在 127.0.0.1:11111（TLS 交给 Nginx）
用默认 profile（不加 `--spring.profiles.active=https`），只监听本机即可：
```
java -jar /opt/werewolf/werewolf-online-0.1.0-SNAPSHOT.jar --server.port=11111
```
（可选 systemd 常驻，见 §4。）

### 3. Nginx 反向代理（含 WebSocket 升级）
`/etc/nginx/sites-available/werewolf`：
```nginx
server {
    listen 80;
    server_name game.example.com;
    location /.well-known/acme-challenge/ { root /var/www/certbot; }
    location / { return 301 https://$host$request_uri; }
}

server {
    listen 443 ssl http2;
    server_name game.example.com;

    ssl_certificate     /etc/letsencrypt/live/game.example.com/fullchain.pem;
    ssl_certificate_key /etc/letsencrypt/live/game.example.com/privkey.pem;

    client_max_body_size 8m;                 # 头像上传

    # REST + 静态
    location / {
        proxy_pass http://127.0.0.1:11111;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    # WebSocket：/ws 与 /ws/voice 需要升级头 + 长连接超时
    location /ws {
        proxy_pass http://127.0.0.1:11111;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
    location /ws/voice {
        proxy_pass http://127.0.0.1:11111;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
        proxy_read_timeout 3600s;
        proxy_send_timeout 3600s;
    }
}
```
申请证书并启用：
```bash
sudo certbot --nginx -d game.example.com       # 自动改 nginx + 配自动续期
sudo systemctl reload nginx
```

### 4. systemd 常驻（可选）
`/etc/systemd/system/werewolf.service`：
```ini
[Unit]
Description=Werewolf Online
After=network.target
[Service]
WorkingDirectory=/opt/werewolf
ExecStart=/usr/bin/java -jar /opt/werewolf/werewolf-online-0.1.0-SNAPSHOT.jar --server.port=11111
Restart=always
Environment=VOICE_ENABLED=true
[Install]
WantedBy=multi-user.target
```
```bash
sudo systemctl enable --now werewolf
```

### 5. App 端连接
「服务器设置」填 `https://game.example.com`（**关掉**自签名开关，因为是正规证书）。
所有设备填这个域名即可跨地互通。

---

## C. 语音微服务（ASR/TTS）说明
- 语音是同机本地 Python 服务（SenseVoice:5001 / Kokoro:5002），只在服务端主机跑，
  客户端通过 `/ws/voice`（经 Nginx 代理）收发音频。
- 若 VPS 无 GPU/内存，可在服务端关掉语音（`VOICE_ENABLED=false`），其余功能不受影响。

## D. CORS / 安全
- 原生 App（Win/mac/Linux/Android/iOS）**不受浏览器 CORS 约束**，直连即可。
- 只有当你还想把 **Web 版**部署到与 API 不同源时，才需要给 `/api` 与 `/ws` 配 CORS / `setAllowedOrigins`（当前 `/ws` 已是 `*`）。
- 后台 `/admin` 仅管理员可进；公网暴露时建议再套一层 Nginx Basic Auth 或限制来源 IP。

## E. 更新流程
- 换后端：替换 jar + `systemctl restart werewolf`，六端即时生效，App 无需重装。
- 换前端（App）：`flutter build` 对应端并分发（见 `README.md`）。

## F. 下载中心（服务端驱动，客户端无需更新）
网页大厅「📲 下载应用」列出的安装包全部由后端提供：
- 安装包放与服务端工作目录同级的 `downloads/` 目录（如 `/opt/werewolf/downloads/`），
  目录位置可用 `--app.downloads-dir=...` 覆盖。
- `downloads/manifest.json` 描述各端条目（platform/label/icon/os/arch/version/file/note）。
- 接口：`GET /api/downloads` 返回清单（服务端自动补全实际文件大小与“是否已就绪”）；
  `GET /download/{file}` 流式下载。
- **上架/更新某个客户端 = 往 `downloads/` 丢文件 + 改 `manifest.json`**，无需改前端、更无需更新已安装的 App。
  没有对应文件的端会显示“即将提供”。
- Windows 版已打包：`downloads/werewolf-app-windows-x64-v1.0.0.zip`。

