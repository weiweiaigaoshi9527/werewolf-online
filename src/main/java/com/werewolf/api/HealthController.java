package com.werewolf.api;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 公开健康/版本探针：供各端 App 在“设置服务器地址”时校验连通性与版本兼容。
 * 无需鉴权，保持极轻。
 */
@RestController
@RequestMapping("/api")
public class HealthController {

    private final DataSource dataSource;

    public HealthController(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        m.put("app", "werewolf-online");
        m.put("version", "0.1.0");
        m.put("api", 1);                 // 协议版本，客户端据此判断兼容性
        m.put("time", Instant.now().toString());
        // 数据库探活：执行 SELECT 1，任何异常都只反映为 db=down，接口本身仍返回 200
        m.put("db", pingDatabase());
        return m;
    }

    /** 数据库探活：成功返回 "up"，失败（含连接/查询异常）返回 "down"，绝不抛异常。 */
    private String pingDatabase() {
        try (Connection conn = dataSource.getConnection();
             Statement st = conn.createStatement()) {
            st.execute("SELECT 1");
            return "up";
        } catch (Exception e) {
            return "down";
        }
    }
}
