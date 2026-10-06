package com.werewolf.server;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

/**
 * 前台服务：持有 JVM 进程、唤醒锁与 WiFi 锁，尽量让服务端在后台长期存活。
 */
public class ServerService extends Service {

    private static final String TAG = "WWServer";

    public static final String ACTION_START = "com.werewolf.server.action.START";
    public static final String ACTION_STOP = "com.werewolf.server.action.STOP";
    public static final String EXTRA_HTTPS = "https";
    public static final String EXTRA_PORT = "port";
    public static final String EXTRA_HEAP = "heap";

    private static final String CHANNEL_ID = "werewolf_server";
    private static final int NOTIF_ID = 1001;

    private PowerManager.WakeLock wakeLock;
    private WifiManager.WifiLock wifiLock;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            ServerManager.get(this).stop();
            releaseLocks();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }

        // Android 12+ 对后台启动前台服务有限制；被系统按 START_STICKY 拉起时
        // startForeground 可能抛异常。此时降级为「无通知常驻」，服务端仍可运行。
        try {
            startForeground(NOTIF_ID, buildNotification());
        } catch (Throwable t) {
            Log.w(TAG, "startForeground failed: " + t);
        }
        acquireLocks();

        final boolean https = intent != null && intent.getBooleanExtra(EXTRA_HTTPS, false);
        final int port = intent == null ? ServerManager.DEFAULT_PORT
                : intent.getIntExtra(EXTRA_PORT, ServerManager.DEFAULT_PORT);
        final int heap = intent == null ? ServerManager.DEFAULT_HEAP_MB
                : intent.getIntExtra(EXTRA_HEAP, ServerManager.DEFAULT_HEAP_MB);

        new Thread(() -> {
            ServerManager.get(ServerService.this).start(https, port, heap);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) {
                nm.notify(NOTIF_ID, buildNotification());
            }
        }, "ww-server-start").start();

        return START_STICKY;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null && nm.getNotificationChannel(CHANNEL_ID) == null) {
                NotificationChannel ch = new NotificationChannel(CHANNEL_ID,
                        getString(R.string.notify_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                nm.createNotificationChannel(ch);
            }
        }
    }

    private Notification buildNotification() {
        ServerManager sm = ServerManager.get(this);
        String url = sm.getLanUrl();

        Intent open = new Intent(this, MainActivity.class);
        open.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        int piFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M
                ? PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
                : PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent contentPi = PendingIntent.getActivity(this, 0, open, piFlags);

        Intent stop = new Intent(this, ServerService.class);
        stop.setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop, piFlags);

        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            b = new Notification.Builder(this, CHANNEL_ID);
        } else {
            //noinspection deprecation
            b = new Notification.Builder(this);
        }
        b.setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(getString(R.string.notify_title))
                .setContentText(url)
                .setOngoing(true)
                .setContentIntent(contentPi)
                .addAction(new Notification.Action.Builder(null, "停止服务", stopPi).build());
        return b.build();
    }

    private void acquireLocks() {
        try {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (pm != null && (wakeLock == null || !wakeLock.isHeld())) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "werewolf:server");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Throwable ignore) {
            // 拿不到唤醒锁不影响主流程
        }
        try {
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && (wifiLock == null || !wifiLock.isHeld())) {
                wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "werewolf:server");
                wifiLock.setReferenceCounted(false);
                wifiLock.acquire();
            }
        } catch (Throwable ignore) {
            // 同上
        }
    }

    private void releaseLocks() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignore) {
        }
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                wifiLock.release();
            }
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onDestroy() {
        releaseLocks();
        ServerManager.get(this).stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
