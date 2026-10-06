import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/**
 * M1 房间系统 WebSocket 验收：
 * 1) Alice WS 连接 → 建房间 → 收到 room.state
 * 2) Bob WS 连接 → 加入 → Alice 收到广播的 room.state（含 Bob）
 * 3) Bob 准备 → Alice 收到 ready=true 广播
 * 4) Alice 踢 Bob → Bob 收到 room.kicked，Alice 收到广播
 * 5) 断线 60s 内重连可续座（本用例仅测在线标记翻转）
 */
public class RoomWsTest {
    static final String BASE = "http://127.0.0.1:11111";
    static final AtomicReference<String> aliceLastState = new AtomicReference<>();
    static final AtomicReference<String> bobLastState = new AtomicReference<>();
    static final AtomicReference<String> bobKicked = new AtomicReference<>();
    static final AtomicInteger aliceStates = new AtomicInteger();
    static final AtomicInteger bobStates = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        String tokA = login("alice");
        String tokB = login("bob");
        System.out.println("TOK_A=" + tokA);
        System.out.println("TOK_B=" + tokB);

        WebSocket wsA = connect(tokA, aliceLastState, aliceStates);
        Thread.sleep(400);
        WebSocket wsB = connect(tokB, bobLastState, bobStates);
        Thread.sleep(400);

        // 1. Alice 建房间
        String create = post("/api/room/create", tokA, null);
        String roomNo = extract(create, "roomNo");
        System.out.println("[1] Alice 建房间: " + roomNo);
        Thread.sleep(300);
        System.out.println("    Alice 收到 room.state 数: " + aliceStates.get());
        System.out.println("    Alice state 含自己 online=true: " + (aliceLastState.get().contains("\"nickname\":\"阿尔法\"") && aliceLastState.get().contains("\"online\":true")));

        // 2. Bob 加入
        post("/api/room/join", tokB, "{\"roomNo\":\"" + roomNo + "\"}");
        Thread.sleep(400);
        System.out.println("[2] Bob 加入");
        System.out.println("    Alice 状态更新数: " + aliceStates.get());
        System.out.println("    Bob 状态更新数: " + bobStates.get());
        boolean bobOnlineTmp = aliceLastState.get().contains("鲍勃") && aliceLastState.get().contains("\"online\":true");
        System.out.println("    Alice 视角 Bob online=true: " + bobOnlineTmp);
        boolean bobOnline = bobOnlineTmp;
        System.out.println("    Bob 在线标记正确: " + bobOnline);

        // 3. Bob 准备
        post("/api/room/ready", tokB, "{\"ready\":true}");
        Thread.sleep(400);
        System.out.println("[3] Bob 准备 → Alice 收到 ready=true 广播");
        boolean aliceSeesBobReady = aliceLastState.get().contains("鲍勃") && aliceLastState.get().contains("\"ready\":true");
        System.out.println("    Alice 看到 Bob 已准备: " + aliceSeesBobReady);

        // 4. Alice 踢 Bob（Bob 的 userId 是 2，M0 里注册的）
        long bobId = 2L;
        post("/api/room/kick", tokA, "{\"userId\":" + bobId + "}");
        Thread.sleep(400);
        System.out.println("[4] Alice 踢 Bob");
        System.out.println("    Bob 收到 room.kicked: " + (bobKicked.get() != null));
        System.out.println("    Alice 状态只剩自己: " + (!aliceLastState.get().contains("鲍勃")));

        // 5. 清理
        post("/api/room/leave", tokA, null);
        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "end");
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "end");
        Thread.sleep(300);
        System.exit(0);
    }

    static long extractLong(String json, String key) {
        // 找 nickname=鲍勃 附近的 userId
        int idx = json.indexOf("鲍勃");
        String around = json.substring(Math.max(0, idx - 60), Math.min(json.length(), idx + 20));
        return Long.parseLong(extract(around, key));
    }

    static String login(String user) throws Exception {
        String body = "{\"username\":\"" + user + "\",\"password\":\"werewolf6\"}";
        String r = postRaw("/api/auth/login", body, null);
        return extract(r, "token");
    }

    static String post(String path, String token, String body) throws Exception {
        return postRaw(path, body == null ? "{}" : body, token);
    }

    static String postRaw(String path, String body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(BASE + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString());
        return r.body();
    }

    static String extract(String json, String key) {
        String pat = "\"" + key + "\":\"";
        int i = json.indexOf(pat);
        if (i < 0) {
            pat = "\"" + key + "\":";
            i = json.indexOf(pat);
            if (i < 0) return null;
            int j = i + pat.length();
            int k = j;
            while (k < json.length() && (Character.isDigit(json.charAt(k)) || json.charAt(k) == '-')) k++;
            return json.substring(j, k);
        }
        int j = i + pat.length();
        int k = json.indexOf('"', j);
        return json.substring(j, k);
    }

    static WebSocket connect(String token, AtomicReference<String> lastState, AtomicInteger counter) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:11111/ws?token=" + token), new Handler(lastState, counter, bobKicked))
                .join();
    }

    static class Handler implements WebSocket.Listener {
        final AtomicReference<String> lastState;
        final AtomicInteger counter;
        final AtomicReference<String> kickedRef;
        final StringBuilder sb = new StringBuilder();
        Handler(AtomicReference<String> s, AtomicInteger c, AtomicReference<String> k) { lastState = s; counter = c; kickedRef = k; }
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            sb.append(data);
            if (last) {
                String msg = sb.toString();
                sb.setLength(0);
                if (msg.startsWith("{\"type\":\"room.state\"")) { lastState.set(msg); counter.incrementAndGet(); }
                if (msg.startsWith("{\"type\":\"room.kicked\"")) kickedRef.set(msg);
            }
            ws.request(1);
            return null;
        }
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer p) { ws.request(1); return null; }
        public void onError(WebSocket ws, Throwable e) { System.out.println("[ws-err] " + e.getMessage()); }
    }
}
