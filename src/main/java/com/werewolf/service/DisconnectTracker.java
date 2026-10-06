package com.werewolf.service;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 断线宽限：WS 断开后 60s 内重连可续座，超时触发清理回调。
 */
@Service
public class DisconnectTracker {

    private final Map<Long, ScheduledFuture<?>> pending = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ww-disconnect");
        t.setDaemon(true);
        return t;
    });

    public void schedule(long userId, long graceSeconds, Runnable onExpire) {
        cancel(userId);
        ScheduledFuture<?> f = scheduler.schedule(() -> {
            pending.remove(userId);
            try { onExpire.run(); } catch (Exception ignored) {}
        }, graceSeconds, TimeUnit.SECONDS);
        pending.put(userId, f);
    }

    public void cancel(long userId) {
        ScheduledFuture<?> f = pending.remove(userId);
        if (f != null) f.cancel(false);
    }
}
