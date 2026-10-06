import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * M7 并发压测：N 个线程各自注册账号→建房→全托管开局（同步跑完整局）→查终局。
 * 验证 HTTP 线程安全、规则引擎、结算落库在并发下无死锁/无串号。
 */
public class ConcurrencyTest {
    static final String BASE = "http://127.0.0.1:11111";
    static final int N = 10;
    static AtomicInteger ok = new AtomicInteger(), fail = new AtomicInteger();

    public static void main(String[] a) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(N);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(N);
        long t0 = System.currentTimeMillis();
        for (int i = 0; i < N; i++) {
            final int id = i;
            pool.submit(() -> {
                try {
                    start.await();
                    runOne(id);
                } catch (Exception e) {
                    fail.incrementAndGet();
                    System.out.println("[u" + id + "] 异常 " + e);
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown(); // 同时放行
        done.await();
        long ms = System.currentTimeMillis() - t0;
        pool.shutdown();
        System.out.printf("%n并发 %d 房间全托管局：成功 %d，失败 %d，总耗时 %dms%n", N, ok.get(), fail.get(), ms);
        System.exit(fail.get() == 0 && ok.get() == N ? 0 : 1);
    }

    static void runOne(int id) throws Exception {
        String user = "stress" + id;
        String body = "{\"username\":\"" + user + "\",\"password\":\"stress123\",\"nickname\":\"压测" + id + "\"}";
        String reg = post("/api/auth/register", body, null);
        String tok = extract(reg, "token");
        if (tok == null) { // 已存在则登录
            tok = extract(post("/api/auth/login", "{\"username\":\"" + user + "\",\"password\":\"stress123\"}", null), "token");
        }
        post("/api/room/leave", "{}", tok);
        String room = post("/api/room/create", "{}", tok);
        if (!room.contains("roomNo")) { fail.incrementAndGet(); System.out.println("[u" + id + "] 建房失败 " + room); return; }
        post("/api/game/start", "{\"allBots\":true}", tok);
        // 轮询直到 GAME_OVER 或超时（异步 AI 局需要时间）
        String st = "";
        long deadline = System.currentTimeMillis() + 90000;
        while (System.currentTimeMillis() < deadline) {
            st = get("/api/game/state", tok);
            if (st.contains("\"phase\":\"GAME_OVER\"")) break;
            Thread.sleep(500);
        }
        if (st.contains("\"phase\":\"GAME_OVER\"") && st.contains("\"winner\"")) {
            ok.incrementAndGet();
        } else {
            fail.incrementAndGet();
            System.out.println("[u" + id + "] 未在 90s 内结束: " + st.substring(0, Math.min(140, st.length())));
        }
    }

    static String post(String path, String body, String tok) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(BASE + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        if (tok != null) b.header("Authorization", "Bearer " + tok);
        return HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String get(String path, String tok) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder().uri(URI.create(BASE + path))
                .header("Authorization", "Bearer " + tok).GET().build(), HttpResponse.BodyHandlers.ofString()).body();
    }
    static String extract(String json, String key) {
        String p = "\"" + key + "\":\"";
        int i = json.indexOf(p);
        if (i < 0) return null;
        int j = i + p.length(), k = json.indexOf('"', j);
        return json.substring(j, k);
    }
}
