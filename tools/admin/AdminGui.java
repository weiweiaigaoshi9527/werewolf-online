import javax.swing.*;
import javax.swing.text.DefaultCaret;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.prefs.Preferences;
import java.util.concurrent.*;

/**
 * 狼人杀 Online · 服务端管理 GUI（Swing，零第三方依赖）。
 * 功能：启动/停止服务进程、实时日志、运行统计、打开浏览器/数据目录。
 * 打包：jpackage app-image 自带运行时，可 --autostart 自动拉起服务。
 */
public class AdminGui {

    private static final String JAR_PREFIX = "werewolf-online-";
    private static final String LOG_FILE = "logs/admin-gui.log";
    private static final SimpleDateFormat TS = new SimpleDateFormat("MM-dd HH:mm:ss");

    private final Preferences prefs = Preferences.userNodeForPackage(AdminGui.class);
    private final HttpClient http = buildClient();
    private volatile String activeScheme = "http"; // 探活后确定服务实际协议
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "gui-poll"); t.setDaemon(true); return t;
    });

    /** 信任自签证书（服务端默认 HTTPS + keystore.p12），用于本机探活/统计。 */
    private static HttpClient buildClient() {
        try {
            javax.net.ssl.TrustManager[] tm = new javax.net.ssl.TrustManager[]{ new javax.net.ssl.X509TrustManager() {
                public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {}
                public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            }};
            javax.net.ssl.SSLContext sc = javax.net.ssl.SSLContext.getInstance("TLS");
            sc.init(null, tm, new java.security.SecureRandom());
            return HttpClient.newBuilder().sslContext(sc).connectTimeout(java.time.Duration.ofSeconds(2)).build();
        } catch (Exception e) {
            return HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(2)).build();
        }
    }

    private Process serverProc;          // 由本 GUI 启动的服务进程
    private boolean spawnedByGui = false;
    private File projectRoot;            // G:\狼人杀
    private File serverJar;
    private File javaExe;
    private Thread readerThread;

    // 语音微服务（G:\werewolf-voice：SenseVoice ASR @5001 + Kokoro TTS @5002）
    private static final int ASR_PORT = 5001, TTS_PORT = 5002;
    private static final long VOICE_WARMUP_MS = 150_000; // 模型加载可达 1-2 分钟，期间端口未监听属正常
    private File voiceHome;
    private volatile long asrPid, ttsPid;        // 经 PowerShell -PassThru 拿到的进程号
    private volatile long lastAsrStart, lastTtsStart;
    private JLabel asrLed, ttsLed;
    private JButton asrStart, asrStop, ttsStart, ttsStop;
    private JCheckBox guardBox;

    // UI
    private JFrame frame;
    private JLabel led;
    private JLabel[] statValues = new JLabel[8];
    private JTextArea logArea;
    private JButton btnStart, btnStop;
    private JLabel pathLabel;

    public static void main(String[] args) {
        setDarkTheme();
        SwingUtilities.invokeLater(() -> new AdminGui().show(args));
    }

    /* ================= UI ================= */

    private void show(String[] args) {
        frame = new JFrame("狼人杀 Online · 服务管理");
        frame.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
        frame.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { onClosing(); }
        });

        resolvePaths();

        // 顶部控制条
        led = new JLabel("● 检测中…");
        led.setFont(led.getFont().deriveFont(Font.BOLD, 15f));
        btnStart = bigBtn("▶ 启动服务");
        btnStop = bigBtn("■ 停止服务");
        btnStart.addActionListener(e -> startServer());
        btnStop.addActionListener(e -> stopServer(true));
        JButton btnBrowser = bigBtn("🌐 浏览器");
        btnBrowser.addActionListener(e -> openBrowser());
        JButton btnData = bigBtn("📁 数据目录");
        btnData.addActionListener(e -> openDataDir());
        JButton btnClear = bigBtn("清空日志");
        btnClear.addActionListener(e -> logArea.setText(""));

        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 10));
        top.add(led);
        top.add(btnStart); top.add(btnStop); top.add(btnBrowser); top.add(btnData); top.add(btnClear);

        // 统计卡片
        String[] titles = {"在线玩家", "等待中房间", "游戏中房间", "进行中对局", "注册用户", "累计完成对局", "运行时长", "内存占用"};
        JPanel stats = new JPanel(new GridLayout(2, 4, 10, 10));
        stats.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        stats.setOpaque(false);
        for (int i = 0; i < titles.length; i++) {
            JPanel card = new JPanel(new GridLayout(2, 1));
            card.setBackground(new Color(20, 26, 46));
            card.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(42, 51, 80)),
                    BorderFactory.createEmptyBorder(8, 10, 8, 10)));
            JLabel t = new JLabel(titles[i]);
            t.setForeground(new Color(123, 133, 168));
            t.setFont(t.getFont().deriveFont(12f));
            statValues[i] = new JLabel("—");
            statValues[i].setForeground(new Color(232, 163, 61));
            statValues[i].setFont(statValues[i].getFont().deriveFont(Font.BOLD, 18f));
            card.add(t); card.add(statValues[i]);
            stats.add(card);
        }

        // 日志
        logArea = new JTextArea();
        logArea.setEditable(false);
        logArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        logArea.setBackground(new Color(10, 14, 26));
        logArea.setForeground(new Color(205, 214, 244));
        DefaultCaret caret = (DefaultCaret) logArea.getCaret();
        caret.setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(42, 51, 80)), "服务日志",
                0, 0, null, new Color(232, 163, 61)));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, stats, logScroll);
        split.setResizeWeight(0.32);
        split.setDividerSize(6);
        split.setBorder(null);

        pathLabel = new JLabel();
        pathLabel.setForeground(new Color(123, 133, 168));
        pathLabel.setFont(pathLabel.getFont().deriveFont(11f));
        JButton btnPick = new JButton("重新定位");
        btnPick.addActionListener(e -> pickJar());
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.add(pathLabel, BorderLayout.CENTER);
        bottom.add(btnPick, BorderLayout.EAST);

        frame.setLayout(new BorderLayout());
        JPanel northWrap = new JPanel(new BorderLayout());
        northWrap.add(top, BorderLayout.NORTH);
        northWrap.add(buildVoicePanel(), BorderLayout.SOUTH);
        frame.add(northWrap, BorderLayout.NORTH);
        frame.add(split, BorderLayout.CENTER);
        frame.add(bottom, BorderLayout.SOUTH);

        frame.setSize(760, 580);
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        refreshPathLabel();

        appendLog("管理器已启动，项目根=" + (projectRoot == null ? "未找到" : projectRoot.getPath()));

        poller.scheduleWithFixedDelay(this::pollOnce, 1, 3, TimeUnit.SECONDS);

        if (Arrays.asList(args).contains("--autostart")) {
            SwingUtilities.invokeLater(() -> SwingUtilities.invokeLater(this::startServer));
        }
    }

    private JButton bigBtn(String text) {
        JButton b = new JButton(text);
        b.setFont(b.getFont().deriveFont(Font.BOLD, 13f));
        b.setFocusPainted(false);
        return b;
    }

    private void onClosing() {
        if (spawnedByGui && serverProc != null && serverProc.isAlive()) {
            int r = JOptionPane.showConfirmDialog(frame, "退出管理器时同时停止服务端？", "确认",
                    JOptionPane.YES_NO_CANCEL_OPTION);
            if (r == JOptionPane.CANCEL_OPTION || r == JOptionPane.CLOSED_OPTION) return;
            if (r == JOptionPane.YES_OPTION) stopServer(false);
        }
        poller.shutdownNow();
        frame.dispose();
        System.exit(0);
    }

    /* ================= 路径解析 ================= */

    private void resolvePaths() {
        List<File> candidates = new ArrayList<>();
        String pref = prefs.get("projectRoot", null);
        if (pref != null) candidates.add(new File(pref));
        String appPath = System.getProperty("jpackage.app.path");
        if (appPath != null) {
            File f = new File(appPath).getParentFile();
            for (int i = 0; i < 3 && f != null; i++, f = f.getParentFile()) candidates.add(f);
        }
        File wd = new File(System.getProperty("user.dir", "."));
        for (int i = 0; i < 3; i++, wd = wd.getParentFile()) {
            if (wd == null) break;
            candidates.add(wd);
        }
        for (File c : candidates) {
            File jar = findJar(c);
            if (jar != null) { applyRoot(c, jar); return; }
        }
        // 未找到：弹选择器
        JOptionPane.showMessageDialog(null, "未自动定位到服务端工程（ werewolf-online-*.jar ），请手动选择 jar 文件。", "定位",
                JOptionPane.INFORMATION_MESSAGE);
        pickJar();
    }

    private void pickJar() {
        JFileChooser fc = new JFileChooser(projectRoot == null ? new File(".") : projectRoot);
        fc.setDialogTitle("选择服务端 jar（werewolf-online-*.jar）");
        if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return;
        File jar = fc.getSelectedFile();
        File root = jar.getParentFile(); // target/
        if (root != null && root.getParentFile() != null) root = root.getParentFile();
        applyRoot(root, jar);
        if (projectRoot != null) prefs.put("projectRoot", projectRoot.getPath());
        refreshPathLabel();
    }

    private File findJar(File root) {
        if (root == null) return null;
        File target = new File(root, "target");
        File[] jars = target.listFiles((d, n) -> n.startsWith(JAR_PREFIX) && n.endsWith(".jar") && !n.endsWith(".original"));
        return (jars != null && jars.length > 0) ? jars[0] : null;
    }

    private void applyRoot(File root, File jar) {
        this.projectRoot = root;
        this.serverJar = jar;
        // java 启动器：优先当前运行时（完整 JDK/完整 runtime-image），否则项目内 tools/jdk-21，否则 PATH
        File je = new File(System.getProperty("java.home"), "bin/java.exe");
        if (!je.exists() && projectRoot != null) {
            je = new File(projectRoot, "tools/jdk-21/bin/java.exe");
        }
        this.javaExe = je.exists() ? je : new File("java");
        refreshPathLabel();
    }

    private void refreshPathLabel() {
        if (pathLabel == null) return; // UI 未建好时仅记录
        pathLabel.setText(" 工程: " + (projectRoot == null ? "?" : projectRoot.getPath())
                + "   |   jar: " + (serverJar == null ? "?" : serverJar.getName())
                + "   |   java: " + javaExe.getPath());
    }

    /* ================= 语音微服务托管 ================= */

    /** 定位 werewolf-voice 目录（项目同级或项目内）。 */
    private void resolveVoiceHome() {
        voiceHome = null;
        if (projectRoot == null) return;
        File[] candidates = {
                new File(projectRoot.getParentFile(), "werewolf-voice"),
                new File(projectRoot, "werewolf-voice")
        };
        for (File c : candidates) {
            if (new File(c, "asr_service.py").exists() && new File(c, "tts_service.py").exists()) {
                voiceHome = c;
                break;
            }
        }
        appendLog(voiceHome == null
                ? "· 未找到 werewolf-voice 目录（ASR/TTS），语音托管停用"
                : "· 语音微服务目录: " + voiceHome.getPath());
    }

    private JPanel buildVoicePanel() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 6));
        p.add(new JLabel("语音微服务:"));
        asrLed = ledLabel("ASR"); ttsLed = ledLabel("TTS");
        p.add(asrLed);
        asrStart = smallBtn("启动"); asrStop = smallBtn("停止");
        asrStart.addActionListener(e -> new Thread(() -> startVoiceService(true), "gui-voice").start());
        asrStop.addActionListener(e -> new Thread(() -> stopVoiceService(true), "gui-voice").start());
        p.add(asrStart); p.add(asrStop);
        p.add(ttsLed);
        ttsStart = smallBtn("启动"); ttsStop = smallBtn("停止");
        ttsStart.addActionListener(e -> new Thread(() -> startVoiceService(false), "gui-voice").start());
        ttsStop.addActionListener(e -> new Thread(() -> stopVoiceService(false), "gui-voice").start());
        p.add(ttsStart); p.add(ttsStop);
        guardBox = new JCheckBox("掉线自动重拉（守护）");
        guardBox.setSelected(prefs.getBoolean("voiceGuard", true));
        guardBox.addItemListener(e -> prefs.putBoolean("voiceGuard", guardBox.isSelected()));
        p.add(guardBox);
        resolveVoiceHome();
        boolean has = voiceHome != null;
        asrStart.setEnabled(has); asrStop.setEnabled(has);
        ttsStart.setEnabled(has); ttsStop.setEnabled(has);
        return p;
    }

    private JLabel ledLabel(String name) {
        JLabel l = new JLabel("● " + name + " 探测中…");
        l.setForeground(new Color(123, 133, 168));
        return l;
    }

    private JButton smallBtn(String t) {
        JButton b = new JButton(t);
        b.setFont(b.getFont().deriveFont(12f));
        b.setFocusPainted(false);
        return b;
    }

    private boolean voiceUp(int port) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/health"))
                    .timeout(java.time.Duration.ofMillis(900)).GET().build();
            return http.send(req, HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private void startVoiceService(boolean asr) {
        String name = asr ? "ASR" : "TTS";
        int port = asr ? ASR_PORT : TTS_PORT;
        String task = asr ? "WW-Voice-ASR2" : "WW-Voice-TTS";
        if (voiceHome == null) { appendLog("✗ 语音托管未启用（未找到 werewolf-voice）"); return; }
        if (voiceUp(port)) { appendLog("· 语音服务(" + name + ")已在线，无需启动"); return; }
        try {
            ensureVoiceTask(asr);
            // 关键：经 Task Scheduler 启动——任务计划从用户注册表构建全新环境，
            // 彻底脱离 GUI 进程的继承链（继承链里的 python 会 torch DLL 1114 崩溃）
            new ProcessBuilder("cmd.exe", "/c", "schtasks /Run /TN " + task).start();
            if (asr) { asrPid = 0; lastAsrStart = System.currentTimeMillis(); }
            else { ttsPid = 0; lastTtsStart = System.currentTimeMillis(); }
            appendLog("▶ 语音服务(" + name + ")已请求启动（任务 " + task + "），模型加载需 1-2 分钟");
        } catch (Exception e) {
            appendLog("✗ 语音服务(" + name + ")启动失败: " + e.getMessage());
        }
    }

    /** 确保计划任务已注册（缺失则按已验证的语法创建）。 */
    private void ensureVoiceTask(boolean asr) throws Exception {
        String task = asr ? "WW-Voice-ASR2" : "WW-Voice-TTS";
        ProcessBuilder q = new ProcessBuilder("cmd.exe", "/c", "schtasks /Query /TN " + task);
        boolean exists = q.start().waitFor(5, TimeUnit.SECONDS);
        if (exists) return;
        String tr = "cmd /c cd /d " + voiceHome.getPath() + " && "
                + (asr ? "venv-asr\\Scripts\\python.exe asr_service.py" : "venv-tts\\Scripts\\python.exe tts_service.py");
        new ProcessBuilder("cmd.exe", "/c", "schtasks /Create /F /SC ONCE /ST 23:59 /TN " + task + " /TR \"" + tr + "\"")
                .start().waitFor(10, TimeUnit.SECONDS);
        appendLog("· 已注册语音计划任务 " + task);
    }

    private void stopVoiceService(boolean asr) {
        String name = asr ? "ASR" : "TTS";
        int port = asr ? ASR_PORT : TTS_PORT;
        String task = asr ? "WW-Voice-ASR2" : "WW-Voice-TTS";
        boolean stopped = false;
        try {
            new ProcessBuilder("cmd.exe", "/c", "schtasks /End /TN " + task).start().waitFor(5, TimeUnit.SECONDS);
            stopped = true;
        } catch (Exception ignored) {}
        if (voiceUp(port)) { killByPort(port); stopped = true; }
        if (asr) asrPid = 0; else ttsPid = 0;
        appendLog(stopped ? "■ 语音服务(" + name + ")已停止" : "· 语音服务(" + name + ")本就未在运行");
    }

    /** 按监听端口找到 PID 并强杀（处理非本 GUI 启动的实例）。 */
    private void killByPort(int port) {
        try {
            Process p = new ProcessBuilder("cmd.exe", "/c", "netstat -ano -p tcp | findstr :" + port).start();
            Set<String> pids = new HashSet<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (!line.contains("LISTENING")) continue;
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 5 && parts[1].endsWith(":" + port)) pids.add(parts[4]);
                }
            }
            p.waitFor(3, TimeUnit.SECONDS);
            for (String pid : pids) {
                new ProcessBuilder("cmd.exe", "/c", "taskkill /F /PID " + pid).start();
                appendLog("■ 已结束占用端口 " + port + " 的进程 PID=" + pid);
            }
        } catch (Exception e) {
            appendLog("✗ 按端口停止失败(" + port + "): " + e.getMessage());
        }
    }

    /** 守护：勾选时检测双服务，离线自动拉起。
     *  拉起后 150s 内不重复（模型加载 1-2 分钟期间端口未监听属正常）；超时仍离线视为崩溃，按窗口循环重拉。 */
    private void guardTick() {
        if (guardBox == null || !guardBox.isSelected() || voiceHome == null) return;
        long now = System.currentTimeMillis();
        if (!voiceUp(ASR_PORT) && now - lastAsrStart > VOICE_WARMUP_MS) {
            appendLog("🛡 守护：ASR 离线，自动拉起…");
            startVoiceService(true);
        }
        if (!voiceUp(TTS_PORT) && now - lastTtsStart > VOICE_WARMUP_MS) {
            appendLog("🛡 守护：TTS 离线，自动拉起…");
            startVoiceService(false);
        }
    }

    private void updateVoiceLeds(boolean asrUp, boolean ttsUp) {
        if (asrLed == null) return;
        asrLed.setText(asrUp ? "● ASR 运行中" : "● ASR 已停止");
        asrLed.setForeground(asrUp ? new Color(127, 212, 154) : new Color(123, 133, 168));
        ttsLed.setText(ttsUp ? "● TTS 运行中" : "● TTS 已停止");
        ttsLed.setForeground(ttsUp ? new Color(127, 212, 154) : new Color(123, 133, 168));
    }

    /* ================= 服务控制 ================= */

    private void startServer() {
        if (serverProc != null && serverProc.isAlive()) {
            appendLog("服务已在运行（PID " + serverProc.pid() + "）");
            return;
        }
        if (serverJar == null || !serverJar.exists()) {
            appendLog("✗ 未找到服务端 jar，请点右下角[重新定位]");
            return;
        }
        try {
            java.util.List<String> cmd = new ArrayList<>(List.of(
                    javaExe.getPath(), "-Xms128m", "-Xmx512m", "-jar", serverJar.getAbsolutePath()));
            // 与 start.bat 一致：存在自签证书则启用 HTTPS（语音麦克风需要安全上下文）
            if (new File(projectRoot, "config/keystore.p12").exists()) {
                cmd.add("--spring.profiles.active=https");
                activeScheme = "https";
            } else {
                activeScheme = "http";
            }
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(projectRoot);
            pb.redirectErrorStream(true);
            serverProc = pb.start();
            spawnedByGui = true;
            appendLog("▶ 服务进程已启动 PID=" + serverProc.pid());
            readerThread = new Thread(this::pumpOutput, "gui-log-reader");
            readerThread.setDaemon(true);
            readerThread.start();
            setLed(true);
        } catch (Exception e) {
            appendLog("✗ 启动失败: " + e.getMessage());
        }
    }

    private void pumpOutput() {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(serverProc.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while (serverProc.isAlive() && (line = r.readLine()) != null) {
                appendLog(line);
            }
        } catch (IOException ignored) {
        }
        appendLog("· 日志流结束（进程退出码 " + (serverProc == null ? "?" : serverProc.exitValue()) + "）");
        SwingUtilities.invokeLater(() -> setLed(serverProc != null && serverProc.isAlive()));
    }

    private void stopServer(boolean confirm) {
        if (confirm && !spawnedByGui && (serverProc == null || !serverProc.isAlive())) {
            appendLog("· 本管理器未启动过服务，尝试通过本地接口关停…");
        }
        // 1) 优雅：HTTP 关停
        try {
            String code = curlCode(activeScheme + "://127.0.0.1:11111/api/admin/local/shutdown", "POST");
            appendLog(code.startsWith("2") ? "■ 已发送优雅关停指令" : "· 关停指令响应 " + code);
        } catch (Exception e) {
            appendLog("· HTTP 关停不可达（服务可能未运行）");
        }
        // 2) 兜底：杀本 GUI 启动的进程
        if (serverProc != null && serverProc.isAlive()) {
            try { Thread.sleep(700); } catch (InterruptedException ignored) {}
            if (serverProc.isAlive()) {
                serverProc.destroy();
                try { serverProc.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
                if (serverProc.isAlive()) serverProc.destroyForcibly();
                appendLog("■ 服务进程已终止");
            }
        }
        setLed(false);
    }

    private void openBrowser() {
        try {
            Desktop.getDesktop().browse(new URI(activeScheme + "://localhost:11111"));
        } catch (Exception e) {
            appendLog("✗ 打开浏览器失败: " + e.getMessage());
        }
    }

    private void openDataDir() {
        try {
            File d = new File(projectRoot, "data");
            if (!d.exists()) d = projectRoot;
            Desktop.getDesktop().open(d);
        } catch (Exception e) {
            appendLog("✗ 打开目录失败: " + e.getMessage());
        }
    }

    /* ================= 状态轮询 ================= */

    private void pollOnce() {
        boolean up = httpUp();
        SwingUtilities.invokeLater(() -> setLed(up));
        if (!up) {
            SwingUtilities.invokeLater(() -> setStats(null));
        } else {
            String body = fetchStats();
            SwingUtilities.invokeLater(() -> setStats(body));
        }
        // 语音微服务：探活 + LED + 守护自动重拉
        try {
            boolean asrUp = voiceUp(ASR_PORT), ttsUp = voiceUp(TTS_PORT);
            SwingUtilities.invokeLater(() -> updateVoiceLeds(asrUp, ttsUp));
            if (!asrUp || !ttsUp) guardTick();
        } catch (Exception ignored) {
        }
    }

    private String fetchStats() {
        return curlBody(activeScheme + "://127.0.0.1:11111/api/admin/local/stats");
    }

    private boolean httpUp() {
        // 服务可能以 https（默认）或 http 运行，依次探测并记住。
        // 用系统自带 curl.exe 探活：jpackage 精简运行时的 Java SSL 对自签证书不可靠。
        for (String scheme : new String[]{activeScheme, "https", "http"}) {
            if ("200".equals(curlCode(scheme + "://127.0.0.1:11111/", null))) {
                activeScheme = scheme;
                return true;
            }
        }
        return false;
    }

    /** curl 探测，返回 http_code（失败返回 000）。method 为 null 表示 GET。
     *  必须带 X-WW-Local: 1 标记头（本地端点的 CSRF 校验），并直连 curl.exe（不经 cmd 以防引号损耗）。 */
    private String curlCode(String url, String method) {
        try {
            List<String> cmd = new ArrayList<>(List.of(
                    "curl.exe", "-sk", "-o", "NUL", "-w", "%{http_code}", "--max-time", "3",
                    "-H", "X-WW-Local: 1"));
            if (method != null) { cmd.add("-X"); cmd.add(method); }
            cmd.add(url);
            Process p = new ProcessBuilder(cmd).start();
            String out;
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                out = r.readLine();
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return out == null ? "000" : out.trim();
        } catch (Exception e) {
            return "000";
        }
    }

    private String curlBody(String url) {
        try {
            Process p = new ProcessBuilder("curl.exe", "-sk", "-H", "X-WW-Local: 1", "--max-time", "3", url).start();
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), "UTF-8"))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            p.waitFor(5, TimeUnit.SECONDS);
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void setLed(boolean up) {
        led.setText(up ? "● 服务运行中" : "● 服务已停止");
        led.setForeground(up ? new Color(127, 212, 154) : new Color(123, 133, 168));
        btnStart.setEnabled(!up);
        btnStop.setEnabled(up || (serverProc != null && serverProc.isAlive()));
    }

    private void setStats(String json) {
        if (json == null) {
            for (JLabel l : statValues) l.setText("—");
            return;
        }
        statValues[0].setText(str(json, "online"));
        statValues[1].setText(str(json, "roomsWaiting"));
        statValues[2].setText(str(json, "roomsPlaying"));
        statValues[3].setText(str(json, "liveGames"));
        statValues[4].setText(str(json, "users"));
        statValues[5].setText(str(json, "gamesFinished"));
        long up = num(json, "uptimeSec");
        statValues[6].setText(up >= 3600 ? String.format("%dh%02dm", up / 3600, up % 3600 / 60)
                : up >= 60 ? String.format("%dm%02ds", up / 60, up % 60) : up + "s");
        statValues[7].setText(str(json, "memUsedMb") + " MB");
    }

    private static String str(String json, String key) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\"" + key + "\":\"?([A-Za-z0-9_.\\-]+)\"?").matcher(json);
        return m.find() ? m.group(1) : "—";
    }
    private static long num(String json, String key) {
        try { return Long.parseLong(str(json, key)); } catch (Exception e) { return 0; }
    }

    /* ================= 日志 ================= */

    private synchronized void appendLog(String line) {
        String full = TS.format(new Date()) + "  " + line + "\n";
        SwingUtilities.invokeLater(() -> {
            if (logArea != null) {
                logArea.append(full);
                if (logArea.getDocument().getLength() > 300_000) {
                    try {
                        logArea.getDocument().remove(0, 150_000);
                    } catch (Exception ignored) {}
                }
                logArea.setCaretPosition(logArea.getDocument().getLength());
            }
        });
        try {
            File base = projectRoot != null ? projectRoot : new File(System.getProperty("user.dir"));
            File f = new File(base, LOG_FILE);
            f.getParentFile().mkdirs();
            try (FileWriter w = new FileWriter(f, true)) { w.write(full); }
        } catch (IOException ignored) {
        }
    }

    /* ================= 主题 ================= */

    private static void setDarkTheme() {
        try {
            UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            UIManager.put("nimbusBase", new Color(18, 24, 44));
            UIManager.put("nimbusBlueGrey", new Color(42, 51, 80));
            UIManager.put("nimbusFocus", new Color(232, 163, 61));
            UIManager.put("control", new Color(14, 18, 34));
            UIManager.put("nimbusLightBackground", new Color(16, 21, 40));
            UIManager.put("text", new Color(205, 214, 244));
            UIManager.put("nimbusSelectionBackground", new Color(169, 120, 44));
            UIManager.put("defaultFont", new Font("Microsoft YaHei", Font.PLAIN, 13));
        } catch (Exception ignored) {
        }
    }
}
