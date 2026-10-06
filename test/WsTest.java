import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

public class WsTest {
    static CompletableFuture<Void> done = new CompletableFuture<>();
    static AtomicInteger pongCount = new AtomicInteger();
    static AtomicReference<String> aliceWelcome = new AtomicReference<>();
    static AtomicReference<String> bobWelcome = new AtomicReference<>();
    static AtomicReference<String> aliceBroadcast = new AtomicReference<>();
    static StringBuilder buf = new StringBuilder();

    public static void main(String[] args) throws Exception {
        String tokA = System.getenv("TOK_A");
        String tokB = System.getenv("TOK_B");

        WebSocket wsA = connect("ws://127.0.0.1:11111/ws?token=" + tokA, aliceWelcome, aliceBroadcast);
        Thread.sleep(300);
        WebSocket wsB = connect("ws://127.0.0.1:11111/ws?token=" + tokB, bobWelcome, null);
        Thread.sleep(500);

        // 心跳：应用层 ping → 服务端 pong
        wsA.sendText("{\"type\":\"ping\"}", true);
        Thread.sleep(400);
        wsA.sendText("{\"type\":\"ping\"}", true);
        Thread.sleep(400);

        // 无效 token 应被拒绝
        try {
            WebSocket bad = HttpClient.newHttpClient().newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:11111/ws?token=INVALID"), new Handler(new AtomicReference<>(), null)).join();
            bad.sendClose(WebSocket.NORMAL_CLOSURE, "x");
        } catch (Exception e) {
            System.out.println("BAD_TOKEN_REJECTED");
        }

        // 断开 Bob，观察 Alice 是否收到离开广播
        wsB.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
        Thread.sleep(600);

        System.out.println("--- 结果 ---");
        System.out.println("Alice welcome: " + aliceWelcome.get());
        System.out.println("Bob   welcome: " + bobWelcome.get());
        System.out.println("Alice 收到 Bob 进入广播: " + (aliceBroadcast.get() != null ? aliceBroadcast.get() : "(未收到)"));
        System.out.println("Pong 计数: " + pongCount.get());

        wsA.sendClose(WebSocket.NORMAL_CLOSURE, "end");
        Thread.sleep(200);
        System.exit(0);
    }

    static WebSocket connect(String url, AtomicReference<String> welcomeRef, AtomicReference<String> broadcastRef) throws Exception {
        return HttpClient.newHttpClient().newWebSocketBuilder()
            .buildAsync(URI.create(url), new Handler(welcomeRef, broadcastRef)).join();
    }

    static class Handler implements WebSocket.Listener {
        final AtomicReference<String> welcomeRef;
        final AtomicReference<String> broadcastRef;
        final StringBuilder sb = new StringBuilder();
        Handler(AtomicReference<String> w, AtomicReference<String> b) { welcomeRef = w; broadcastRef = b; }
        public void onOpen(WebSocket ws) { ws.request(1); }
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            sb.append(data);
            if (last) {
                String msg = sb.toString();
                sb.setLength(0);
                if (msg.contains("\"type\":\"welcome\"")) welcomeRef.set(msg);
                if (broadcastRef != null && msg.contains("\"type\":\"system\"")) broadcastRef.set(msg);
                if (msg.contains("\"type\":\"pong\"")) pongCount.incrementAndGet();
                System.out.println("[recv] " + msg);
            }
            ws.request(1);
            return null;
        }
        public CompletionStage<?> onPing(WebSocket ws, java.nio.ByteBuffer p) { ws.request(1); return null; }
        public CompletionStage<?> onClose(WebSocket ws, int code, String reason) { return null; }
        public void onError(WebSocket ws, Throwable e) { System.out.println("[ws-err] " + e.getMessage()); }
    }
}
