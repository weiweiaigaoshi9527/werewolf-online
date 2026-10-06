package com.werewolf.server;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

public class MainActivity extends Activity {

    private static final int REQ_NOTIF = 100;

    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvState;
    private TextView tvUrl;
    private TextView tvLogs;
    private TextView tvProgress;
    private ProgressBar progress;
    private Button btnToggle;
    private Button btnOpen;
    private Button btnInstall;
    private CheckBox cbHttps;
    private ScrollView logScroll;
    private boolean installing = false;
    private boolean autoScroll = true;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            refreshUi();
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(buildLayout());
        requestNotifPermissionIfNeeded();

        if (!RuntimeInstaller.isReady(this)) {
            new AlertDialog.Builder(this)
                    .setTitle("首次使用")
                    .setMessage("需要先解包内置的 Android 版 JDK 运行时（约 400MB，耗时 1-3 分钟，请保持屏幕常亮并插电）。\n\n"
                            + "建议在 WiFi 环境下操作。解包完成后本机即可作为狼人杀服务器。")
                    .setCancelable(false)
                    .setPositiveButton("开始初始化", (d, w) -> doInstall())
                    .show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        ui.removeCallbacks(tick);
        ui.post(tick);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ui.removeCallbacks(tick);
    }

    // ------------------------------------------------------------------- UI

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private View buildLayout() {
        ScrollView root = new ScrollView(this);

        LinearLayout ll = new LinearLayout(this);
        ll.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        ll.setPadding(p, p, p, p);
        root.addView(ll, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("🐺 狼人杀服务端");
        title.setTextSize(22);
        ll.addView(title);

        TextView sub = new TextView(this);
        sub.setText("把手机变成游戏服务器：同一局的所有玩家连到这台手机即可开黑。");
        sub.setTextSize(12);
        sub.setPadding(0, dp(4), 0, dp(12));
        ll.addView(sub);

        tvState = new TextView(this);
        tvState.setTextSize(16);
        tvState.setPadding(0, 0, 0, dp(6));
        ll.addView(tvState);

        tvUrl = new TextView(this);
        tvUrl.setTextSize(15);
        tvUrl.setTextIsSelectable(true);
        tvUrl.setPadding(0, 0, 0, dp(12));
        ll.addView(tvUrl);

        LinearLayout row1 = new LinearLayout(this);
        row1.setOrientation(LinearLayout.HORIZONTAL);
        btnToggle = mkButton("启动服务");
        btnToggle.setOnClickListener(v -> toggleServer());
        row1.addView(btnToggle, weight());
        btnOpen = mkButton("打开控制台");
        btnOpen.setOnClickListener(v -> {
            Intent i = new Intent(this, ConsoleActivity.class);
            i.putExtra("url", "http://127.0.0.1:" + ServerManager.get(this).getPort() + "/");
            startActivity(i);
        });
        row1.addView(btnOpen, weight());
        ll.addView(row1);

        LinearLayout row2 = new LinearLayout(this);
        row2.setOrientation(LinearLayout.HORIZONTAL);
        btnInstall = mkButton("修复运行环境");
        btnInstall.setOnClickListener(v -> confirmInstall());
        row2.addView(btnInstall, weight());
        Button btnCert = mkButton("重生成证书");
        btnCert.setOnClickListener(v -> regenCert());
        row2.addView(btnCert, weight());
        ll.addView(row2);

        LinearLayout row3 = new LinearLayout(this);
        row3.setOrientation(LinearLayout.HORIZONTAL);
        Button btnCopy = mkButton("复制地址");
        btnCopy.setOnClickListener(v -> {
            String url = ServerManager.get(this).getLanUrl();
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("url", url));
                toast("已复制：" + url);
            }
        });
        row3.addView(btnCopy, weight());
        Button btnShare = mkButton("分享地址");
        btnShare.setOnClickListener(v -> {
            Intent i = new Intent(Intent.ACTION_SEND);
            i.setType("text/plain");
            i.putExtra(Intent.EXTRA_SUBJECT, "狼人杀服务器地址");
            i.putExtra(Intent.EXTRA_TEXT, "狼人杀开黑，浏览器打开：" + ServerManager.get(this).getLanUrl());
            startActivity(Intent.createChooser(i, "分享服务器地址"));
        });
        row3.addView(btnShare, weight());
        ll.addView(row3);

        LinearLayout row4 = new LinearLayout(this);
        row4.setOrientation(LinearLayout.HORIZONTAL);
        Button btnBattery = mkButton("后台运行设置");
        btnBattery.setOnClickListener(v -> openBatterySettings());
        row4.addView(btnBattery, weight());
        Button btnSettings = mkButton("端口/内存");
        btnSettings.setOnClickListener(v -> editSettings());
        row4.addView(btnSettings, weight());
        ll.addView(row4);

        cbHttps = new CheckBox(this);
        cbHttps.setText("启用 HTTPS（语音麦克风需要；部分设备运行时缺少 EC 加密库，开启后可能连不上）");
        cbHttps.setTextSize(12);
        cbHttps.setChecked(getSharedPreferences("ww", MODE_PRIVATE).getBoolean("https", false));
        cbHttps.setPadding(0, dp(10), 0, 0);
        ll.addView(cbHttps);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setVisibility(View.GONE);
        ll.addView(progress);
        tvProgress = new TextView(this);
        tvProgress.setTextSize(12);
        tvProgress.setVisibility(View.GONE);
        ll.addView(tvProgress);

        TextView logTitle = new TextView(this);
        logTitle.setText("\n运行日志（点击可暂停/恢复自动滚动）");
        logTitle.setTextSize(13);
        ll.addView(logTitle);

        logScroll = new ScrollView(this);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(260));
        lp.topMargin = dp(6);
        logScroll.setLayoutParams(lp);
        logScroll.setBackgroundColor(0x11000000);
        logScroll.setOnClickListener(v -> autoScroll = !autoScroll);
        tvLogs = new TextView(this);
        tvLogs.setTextSize(10);
        tvLogs.setPadding(dp(8), dp(8), dp(8), dp(8));
        tvLogs.setTextIsSelectable(true);
        logScroll.addView(tvLogs);
        ll.addView(logScroll);

        TextView tip = new TextView(this);
        tip.setText("\n提示：\n"
                + "• 首次启动较慢（要初始化数据库），约 20-60 秒。\n"
                + "• 手机需与玩家在同一 WiFi；玩家用浏览器打开上面的地址即可。\n"
                + "• 默认用 HTTP，兼容性最好。浏览器里打字发言、AI 对局、房间、战绩等功能全部可用。\n"
                + "• 只有『语音麦克风』需要 HTTPS：勾选上面的 HTTPS 试试，若客户端连不上就取消勾选（内置运行时可能缺少 EC 加密库）。\n"
                + "• 想让服务端在后台长期运行，请在上方『后台运行设置』里允许后台运行并关闭电池优化。\n"
                + "• 若与官方 Termux 冲突导致装不上，先卸载 Termux（本 App 复用其运行时目录前缀）。");
        tip.setTextSize(12);
        ll.addView(tip);
        return root;
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp.rightMargin = dp(6);
        return lp;
    }

    private Button mkButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(13);
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // -------------------------------------------------------------- actions

    private void toggleServer() {
        ServerManager sm = ServerManager.get(this);
        if (sm.isRunning()) {
            Intent i = new Intent(this, ServerService.class);
            i.setAction(ServerService.ACTION_STOP);
            startService(i);
            return;
        }
        if (!RuntimeInstaller.isReady(this)) {
            toast("请先初始化运行环境");
            confirmInstall();
            return;
        }
        final boolean https = cbHttps.isChecked();
        final int port = getSharedPreferences("ww", MODE_PRIVATE).getInt("port", ServerManager.DEFAULT_PORT);
        getSharedPreferences("ww", MODE_PRIVATE).edit()
                .putBoolean("https", https).putInt("port", port).apply();
        if (https) {
            sm.ensureKeystore(false);
        }
        Intent i = new Intent(this, ServerService.class);
        i.setAction(ServerService.ACTION_START);
        i.putExtra(ServerService.EXTRA_HTTPS, https);
        i.putExtra(ServerService.EXTRA_PORT, port);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(i);
        } else {
            startService(i);
        }
        toast("正在启动，请稍候…");
    }

    private void confirmInstall() {
        if (installing) {
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("重新解包运行环境")
                .setMessage("将重新解包内置 JDK 运行时（约 400MB）。服务器数据（账号/战绩）不会被删除。继续？")
                .setNegativeButton("取消", null)
                .setPositiveButton("继续", (d, w) -> doInstall())
                .show();
    }

    private void doInstall() {
        if (installing) {
            return;
        }
        installing = true;
        progress.setVisibility(View.VISIBLE);
        tvProgress.setVisibility(View.VISIBLE);
        btnInstall.setEnabled(false);
        btnToggle.setEnabled(false);
        new Thread(() -> {
            try {
                RuntimeInstaller.install(MainActivity.this, (msg, pct) -> ui.post(() -> {
                    progress.setProgress(pct);
                    tvProgress.setText(msg);
                }));
                ui.post(() -> {
                    installing = false;
                    progress.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.VISIBLE);
                    tvProgress.setText("运行环境就绪，可以启动服务了。");
                    btnInstall.setEnabled(true);
                    btnToggle.setEnabled(true);
                    toast("初始化完成");
                });
            } catch (Throwable e) {
                ui.post(() -> {
                    installing = false;
                    progress.setVisibility(View.GONE);
                    tvProgress.setVisibility(View.VISIBLE);
                    tvProgress.setText("初始化失败：" + e);
                    btnInstall.setEnabled(true);
                    btnToggle.setEnabled(true);
                    toast("初始化失败，请检查存储空间");
                });
            }
        }, "ww-install").start();
    }

    private void regenCert() {
        ServerManager sm = ServerManager.get(this);
        new Thread(() -> {
            boolean ok = sm.ensureKeystore(true);
            ui.post(() -> toast(ok ? "证书已重新生成（下次启动生效）" : "生成失败，请查看日志"));
        }, "ww-cert").start();
    }

    private void openBatterySettings() {
        try {
            Intent i = new Intent("android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS");
            i.setData(Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Throwable t) {
            try {
                startActivity(new Intent(Settings.ACTION_SETTINGS));
            } catch (Throwable ignore) {
                toast("无法打开设置");
            }
        }
    }

    private void editSettings() {
        ServerManager sm = ServerManager.get(this);
        final EditText et = new EditText(this);
        et.setHint("端口（默认 11111）");
        et.setText(String.valueOf(sm.getPort()));
        new AlertDialog.Builder(this)
                .setTitle("端口设置")
                .setView(et)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        int np = Integer.parseInt(et.getText().toString().trim());
                        if (np < 1 || np > 65535) {
                            throw new NumberFormatException();
                        }
                        getSharedPreferences("ww", MODE_PRIVATE).edit().putInt("port", np).apply();
                        sm.stop();
                        toast("端口已改为 " + np + "，重启服务后生效");
                    } catch (Exception e) {
                        toast("端口不合法");
                    }
                })
                .show();
    }

    private void requestNotifPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, REQ_NOTIF);
            }
        }
    }

    // --------------------------------------------------------------- render

    private void refreshUi() {
        ServerManager sm = ServerManager.get(this);
        sm.refresh();

        String stateText;
        switch (sm.getState()) {
            case RUNNING:
                stateText = "🟢 运行中";
                break;
            case STARTING:
                stateText = "🟡 启动中（初始化数据库，请稍候）";
                break;
            case STOPPING:
                stateText = "🟠 正在停止";
                break;
            case FAILED:
                stateText = "🔴 启动失败：" + sm.getLastError();
                break;
            default:
                stateText = "⚪ 未运行";
        }
        tvState.setText("状态：" + stateText);

        boolean https = sm.isHttps();
        tvUrl.setText("本机：" + sm.getLocalUrl() + "\n"
                + "局域网（发给玩家）：" + sm.getLanUrl()
                + "\n协议：" + (https ? "HTTPS（自签，支持语音）" : "HTTP（兼容性最好）")
                + (sm.isPortOpen() ? "" : "\n（端口尚未监听）"));

        boolean ready = RuntimeInstaller.isReady(this);
        btnToggle.setText(sm.isRunning() ? "停止服务" : "启动服务");
        btnOpen.setEnabled(sm.isRunning());
        btnInstall.setEnabled(!installing);
        if (!ready && !installing) {
            tvProgress.setVisibility(View.VISIBLE);
            tvProgress.setText("运行环境未就绪，点『修复运行环境』开始解包。");
        }

        List<String> lines = sm.getLogs();
        if (!lines.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (String l : lines) {
                sb.append(l).append('\n');
            }
            tvLogs.setText(sb.toString());
            if (autoScroll) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
    }
}
