import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.regex.*;

/**
 * M3 真人回合验收：alice 作为唯一真人 + 5 托管开局。
 * 循环：拉取状态 → 若轮到自己则按 actionKind 提交合法动作 → 直到游戏结束。
 * 证明 pump 会在真人回合停下、REST 提交后继续、整局可完成。
 */
public class M3HumanTest {
    static final String BASE = "http://127.0.0.1:11111";
    static String tok;

    public static void main(String[] a) throws Exception {
        tok = post("/api/auth/login", "{\"username\":\"alice\",\"password\":\"werewolf6\"}", null, "token");
        post("/api/room/leave", "{}", null, null);
        post("/api/room/create", "{}", null, null);
        String startResp = post("/api/game/start", "{\"allBots\":false}", null, null);
        System.out.println("开局响应: " + startResp);

        int rounds = 0;
        String lastKind = "";
        int sameKindCount = 0;
        while (rounds++ < 4000) {
            String st = get("/api/game/state");
            String phase = extract(st, "phase");
            if ("GAME_OVER".equals(phase)) {
                System.out.println("[OK] 真人回合驱动到游戏结束，phase=GAME_OVER, winner=" + extract(st, "winner"));
                break;
            }
            boolean myTurn = st.contains("\"myTurn\":true");
            String kind = extract(st, "actionKind");
            int mySeat = Integer.parseInt(extract(st, "mySeat"));
            if (myTurn) {
                String body = buildAction(st, kind, mySeat);
                String resp = post("/api/game/action", body, null, null);
                System.out.println("  第" + rounds + "轮 我(" + mySeat + "号)行动 kind=" + kind + " -> " + resp);
                if (kind.equals(lastKind)) { if (++sameKindCount > 8) { System.out.println("[FAIL] 同类动作反复卡住"); break; } }
                else { sameKindCount = 0; }
                lastKind = kind;
            } else {
                // 未到我的回合（可能服务端同步跑完托管后停在别的真人？此处仅 alice 一真人，理论不会长时间停）
                Thread.sleep(300);
            }
        }
        if (rounds >= 4000) System.out.println("[FAIL] 未在 4000 轮内结束");
    }

    static String buildAction(String st, String kind, int mySeat) {
        int t = firstAliveOther(st, mySeat);
        switch (kind) {
            case "SEER_CHECK": return "{\"target\":" + t + "}";
            case "SPEECH": case "PK_SPEECH": case "LAST_WORDS": return "{\"text\":\"我是好人，过。\"}";
            case "SHERIFF_SIGNUP": return "{\"signup\":false}";
            case "WITCH": return "{\"target\":0}";
            default: return "{\"target\":0}"; // 夜晚/投票类：放弃/弃票
        }
    }

    static int firstAliveOther(String st, int mySeat) {
        // 在 seats 数组里找第一个 alive:true 且 seat!=mySeat
        Matcher m = Pattern.compile("\\{[^{}]*\"seat\":(\\d+)[^{}]*\"alive\":true[^{}]*\\}").matcher(st);
        while (m.find()) {
            int s = Integer.parseInt(m.group(1));
            if (s != mySeat) return s;
        }
        return 0;
    }

    static String extract(String json, String key) {
        Matcher m = Pattern.compile("\"" + key + "\":\"?([A-Za-z_0-9\\u4e00-\\u9fa5]+)\"?").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String post(String path, String body, String tokOverride, String extractKey) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder().uri(URI.create(BASE + path))
                .header("Content-Type", "application/json; charset=utf-8")
                .POST(HttpRequest.BodyPublishers.ofString(body, java.nio.charset.StandardCharsets.UTF_8));
        String t = tokOverride != null ? tokOverride : tok;
        if (t != null) b.header("Authorization", "Bearer " + t);
        String r = HttpClient.newHttpClient().send(b.build(), HttpResponse.BodyHandlers.ofString()).body();
        return extractKey != null ? extract(r, extractKey) : r;
    }

    static String get(String path) throws Exception {
        return HttpClient.newHttpClient().send(
                HttpRequest.newBuilder().uri(URI.create(BASE + path)).header("Authorization", "Bearer " + tok).GET().build(),
                HttpResponse.BodyHandlers.ofString()).body();
    }
}
