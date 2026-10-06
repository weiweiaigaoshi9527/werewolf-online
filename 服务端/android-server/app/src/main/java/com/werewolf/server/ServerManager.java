package com.werewolf.server;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 服务端进程管理器（单例）。
 *
 * 负责：拼装 java 启动参数与环境变量、拉起/停止 Spring Boot 进程、收集日志与状态。
 */
public final class ServerManager {

    public enum State {STOPPED, STARTING, RUNNING, STOPPING, FAILED}

    private static final String TAG = "WWServer";
    public static final int DEFAULT_PORT = 11111;
    public static final int DEFAULT_HEAP_MB = 512;
    private static final int MAX_LOG_LINES = 600;

    private static ServerManager INSTANCE;

    public static synchronized ServerManager get(Context c) {
        if (INSTANCE == null) {
            INSTANCE = new ServerManager(c.getApplicationContext());
        }
        return INSTANCE;
    }

    private final Context ctx;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private final Object lock = new Object();

    private Process process;
    private Thread pump;
    private State state = State.STOPPED;
    private boolean https = true;
    private int port = DEFAULT_PORT;
    private int heapMb = DEFAULT_HEAP_MB;
    private long startedAt = 0L;
    private String lastError = "";
    private boolean fallbackTried = false;

    private ServerManager(Context ctx) {
        this.ctx = ctx;
    }

    // ------------------------------------------------------------------ logs

    private void appendLog(String line) {
        synchronized (lock) {
            logs.addLast(line);
            while (logs.size() > MAX_LOG_LINES) {
                logs.removeFirst();
            }
        }
        try {
            File f = RuntimeInstaller.logFile(ctx);
            File parent = f.getParentFile();
            if (parent != null && !parent.exists()) {
                //noinspection ResultOfMethodCallIgnored
                parent.mkdirs();
            }
            try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
                w.write(line);
                w.write("\n");
            }
        } catch (IOException ignore) {
            // 日志写盘失败不影响服务
        }
    }

    public static String stamp() {
        return new SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(new Date());
    }

    public List<String> getLogs() {
        synchronized (lock) {
            return new ArrayList<>(logs);
        }
    }

    public void clearLogs() {
        synchronized (lock) {
            logs.clear();
        }
        //noinspection ResultOfMethodCallIgnored
        RuntimeInstaller.logFile(ctx).delete();
    }

    // ----------------------------------------------------------------- state

    public State getState() {
        return state;
    }

    public boolean isRunning() {
        return state == State.RUNNING || state == State.STARTING;
    }

    public boolean isHttps() {
        return https;
    }

    public int getPort() {
        return port;
    }

    public int getHeapMb() {
        return heapMb;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public String getLastError() {
        return lastError;
    }

    public String getScheme() {
        return https ? "https" : "http";
    }

    public String getLocalUrl() {
        return getScheme() + "://localhost:" + port;
    }

    /** 局域网访问地址；找不到网卡时退回 localhost。 */
    public String getLanUrl() {
        String ip = getLanIp();
        return getScheme() + "://" + (ip == null ? "localhost" : ip) + ":" + port;
    }

    public static String getLanIp() {
        try {
            Enumeration<NetworkInterface> ifaces = NetworkInterface.getNetworkInterfaces();
            if (ifaces == null) {
                return null;
            }
            List<NetworkInterface> ordered = new ArrayList<>();
            while (ifaces.hasMoreElements()) {
                ordered.add(ifaces.nextElement());
            }
            // wlan 优先
            ordered.sort((a, b) -> {
                int ra = a.getName().startsWith("wlan") ? 0 : (a.getName().startsWith("eth") ? 1 : 2);
                int rb = b.getName().startsWith("wlan") ? 0 : (b.getName().startsWith("eth") ? 1 : 2);
                return Integer.compare(ra, rb);
            });
            for (NetworkInterface nif : ordered) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && a.isSiteLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
            // 退一步：任意非回环 IPv4
            for (NetworkInterface nif : ordered) {
                if (!nif.isUp() || nif.isLoopback()) {
                    continue;
                }
                Enumeration<InetAddress> addrs = nif.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress a = addrs.nextElement();
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "lan ip detect failed: " + e);
        }
        return null;
    }

    public boolean isPortOpen() {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 700);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    // ----------------------------------------------------------------- start

    private void applyRuntimeEnv(ProcessBuilder pb) {
        String prefix = RuntimeInstaller.prefix(ctx).getAbsolutePath();
        String jhome = RuntimeInstaller.javaHome(ctx).getAbsolutePath();
        Map<String, String> env = pb.environment();
        env.put("JAVA_HOME", jhome);
        env.put("PATH", jhome + "/bin:" + prefix + "/bin:/system/bin:/system/xbin");
        env.put("LD_LIBRARY_PATH", jhome + "/lib:" + jhome + "/lib/server:" + prefix + "/lib");
        env.put("HOME", RuntimeInstaller.homeDir(ctx).getAbsolutePath());
        env.put("TMPDIR", RuntimeInstaller.tmpDir(ctx).getAbsolutePath());
        env.put("PREFIX", prefix);
        env.put("LANG", "en_US.UTF-8");
        env.put("LC_ALL", "en_US.UTF-8");
        env.put("VOICE_ENABLED", "false");
    }

    /** 生成自签 HTTPS 证书（语音麦克风需要安全上下文）。返回是否可用。 */
    public boolean ensureKeystore(boolean force) {
        File ks = RuntimeInstaller.keystoreFile(ctx);
        if (!force && ks.exists() && ks.length() > 0) {
            return true;
        }
        File keytool = RuntimeInstaller.keytoolBin(ctx);
        if (!keytool.exists()) {
            appendLog("[" + stamp() + "] [APP] 未找到 keytool，无法生成 HTTPS 证书，将以 HTTP 启动");
            return false;
        }
        File parent = ks.getParentFile();
        if (parent != null && !parent.exists()) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        //noinspection ResultOfMethodCallIgnored
        ks.delete();

        String san = "SAN=DNS:localhost,IP:127.0.0.1";
        String ip = getLanIp();
        if (ip != null) {
            san = san + ",IP:" + ip;
        }
        List<String> cmd = new ArrayList<>(Arrays.asList(
                keytool.getAbsolutePath(),
                "-genkeypair",
                "-alias", "werewolf",
                "-keyalg", "RSA",
                "-keysize", "2048",
                "-validity", "3650",
                "-keystore", ks.getAbsolutePath(),
                "-storetype", "PKCS12",
                "-storepass", "changeit",
                "-keypass", "changeit",
                "-dname", "CN=Werewolf Server, OU=WW, O=WW, L=City, ST=State, C=CN",
                "-ext", san));
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(RuntimeInstaller.serverDir(ctx));
            pb.redirectErrorStream(true);
            applyRuntimeEnv(pb);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
                appendLog("[" + stamp() + "] [CERT] " + line);
            }
            int code = p.waitFor();
            if (code == 0 && ks.exists() && ks.length() > 0) {
                appendLog("[" + stamp() + "] [APP] HTTPS 自签证书已生成（SAN 含 " + (ip == null ? "localhost" : ip) + "）");
                return true;
            }
            appendLog("[" + stamp() + "] [APP] keytool 退出码 " + code + "，改用 HTTP");
            return false;
        } catch (Exception e) {
            appendLog("[" + stamp() + "] [APP] 生成证书失败：" + e);
            return false;
        }
    }

    public synchronized void start(boolean useHttps, int port, int heapMb) {
        if (process != null && process.isAlive()) {
            appendLog("[" + stamp() + "] [APP] 服务已在运行中");
            return;
        }
        this.port = port;
        this.heapMb = heapMb;

        File java = RuntimeInstaller.javaBin(ctx);
        if (!java.exists()) {
            state = State.FAILED;
            lastError = "运行环境未安装完整，请先点『初始化/修复运行环境』";
            appendLog("[" + stamp() + "] [APP] " + lastError);
            return;
        }

        this.https = useHttps && ensureKeystore(false);
        if (!this.https) {
            fallbackTried = false;
        }
        RuntimeInstaller.ensureLayout(ctx);

        List<String> cmd = new ArrayList<>();
        cmd.add(java.getAbsolutePath());
        cmd.add("-Djava.awt.headless=true");
        cmd.add("-Dfile.encoding=UTF-8");
        cmd.add("-Dsun.jnu.encoding=UTF-8");
        cmd.add("-Duser.home=" + RuntimeInstaller.homeDir(ctx).getAbsolutePath());
        cmd.add("-Djava.io.tmpdir=" + RuntimeInstaller.tmpDir(ctx).getAbsolutePath());
        cmd.add("-Xms64m");
        cmd.add("-Xmx" + this.heapMb + "m");
        cmd.add("-XX:+UseSerialGC");
        cmd.add("-jar");
        cmd.add(RuntimeInstaller.jarFile(ctx).getAbsolutePath());
        cmd.add("--server.port=" + this.port);
        cmd.add("--spring.datasource.url=jdbc:h2:file:./data/werewolf;AUTO_SERVER=FALSE");
        if (this.https) {
            cmd.add("--spring.profiles.active=https");
        }

        appendLog("[" + stamp() + "] [APP] 启动：" + (this.https ? "HTTPS" : "HTTP") + " 端口 " + this.port
                + "，堆上限 " + this.heapMb + "MB");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(RuntimeInstaller.serverDir(ctx));
            pb.redirectErrorStream(true);
            applyRuntimeEnv(pb);
            process = pb.start();
            startedAt = System.currentTimeMillis();
            state = State.STARTING;
            lastError = "";
            startPump(process);
        } catch (IOException e) {
            state = State.FAILED;
            lastError = "启动失败：" + e.getMessage();
            appendLog("[" + stamp() + "] [APP] " + lastError);
        }
    }

    private void startPump(final Process p) {
        pump = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    appendLog(line);
                    if (state == State.STARTING && (line.contains("Tomcat started on port")
                            || line.contains("Started Werewolf") || line.contains("Started Wolf"))) {
                        state = State.RUNNING;
                    }
                }
            } catch (IOException ignore) {
                // 进程被终止时读流会抛异常，属正常
            } finally {
                int code = -1;
                try {
                    code = p.waitFor();
                } catch (InterruptedException ignore) {
                }
                if (state != State.STOPPING) {
                    state = code == 0 ? State.STOPPED : State.FAILED;
                    if (code != 0) {
                        lastError = "服务进程异常退出（退出码 " + code + "），请查看日志";
                    }
                } else {
                    state = State.STOPPED;
                }
                appendLog("[" + stamp() + "] [APP] 服务进程已退出（退出码 " + code + "）");

                // 内置 Android 运行时可能缺少 EC 加密库导致 HTTPS 起不来：自动降级为 HTTP 重试一次
                if (code != 0 && https && !fallbackTried) {
                    fallbackTried = true;
                    appendLog("[" + stamp() + "] [APP] HTTPS 启动失败，自动改用 HTTP 重试…");
                    final int fPort = port;
                    final int fHeap = heapMb;
                    new Thread(() -> start(false, fPort, fHeap), "ww-fallback").start();
                }
            }
        }, "ww-log-pump");
        pump.setDaemon(true);
        pump.start();
    }

    public synchronized void stop() {
        if (process == null) {
            state = State.STOPPED;
            return;
        }
        state = State.STOPPING;
        appendLog("[" + stamp() + "] [APP] 正在停止服务…");
        final Process p = process;
        process = null;
        try {
            p.destroy();
            for (int i = 0; i < 60 && p.isAlive(); i++) {
                Thread.sleep(100);
            }
            if (p.isAlive()) {
                p.destroyForcibly();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        appendLog("[" + stamp() + "] [APP] 服务已停止");
    }

    /** 供界面轮询：状态自愈（进程已死但状态未更新时）。 */
    public void refresh() {
        if ((state == State.STARTING || state == State.RUNNING) && process != null && !process.isAlive()) {
            state = State.FAILED;
        }
    }
}
