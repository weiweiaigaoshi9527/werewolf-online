import java.net.URI;
import java.net.http.*;
import java.net.http.WebSocket;
import java.util.concurrent.*;
public class ReconnectTest {
  public static void main(String[] a) throws Exception {
    String tok = System.getenv("TOK");
    WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
      .buildAsync(URI.create("ws://127.0.0.1:11111/ws?token=" + tok), new WebSocket.Listener(){
        public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean l){return null;}
      }).join();
    Thread.sleep(500);
    System.out.println("首次连接 OK，房间号: " + myRoom(tok));
    ws.sendClose(WebSocket.NORMAL_CLOSURE, "x");
    Thread.sleep(2000);
    System.out.println("断开 2s 后仍在房间: " + (myRoom(tok) != null));
    // 重连
    WebSocket ws2 = HttpClient.newHttpClient().newWebSocketBuilder()
      .buildAsync(URI.create("ws://127.0.0.1:11111/ws?token=" + tok), new WebSocket.Listener(){
        public CompletionStage<?> onText(WebSocket w, CharSequence d, boolean l){return null;}
      }).join();
    Thread.sleep(500);
    System.out.println("重连后仍在房间: " + (myRoom(tok) != null));
    ws2.sendClose(WebSocket.NORMAL_CLOSURE, "y");
    System.exit(0);
  }
  static String myRoom(String tok) throws Exception {
    HttpResponse<String> r = HttpClient.newHttpClient().send(
      HttpRequest.newBuilder().uri(URI.create("http://127.0.0.1:11111/api/room/my"))
        .header("Authorization","Bearer "+tok).GET().build(),
      HttpResponse.BodyHandlers.ofString());
    String b = r.body();
    int i = b.indexOf("\"roomNo\":\"");
    return i < 0 ? null : b.substring(i+10, i+16);
  }
}
