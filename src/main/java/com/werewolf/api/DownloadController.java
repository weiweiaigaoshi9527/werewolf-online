package com.werewolf.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 下载中心（服务端驱动）：
 *  - GET /api/downloads   读取 downloads/manifest.json，动态补全每个安装包的实际大小与“是否已就绪”，返回给前端渲染。
 *  - GET /download/{file} 从 downloads 目录流式下载对应安装包。
 *
 * 上架/更新一个客户端 = 往 downloads 目录丢文件 + 改 manifest.json，无需改前端、更无需更新已安装的 App 客户端。
 */
@RestController
public class DownloadController {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path baseDir;

    public DownloadController(@Value("${app.downloads-dir:downloads}") String dir) {
        this.baseDir = Paths.get(dir).toAbsolutePath().normalize();
    }

    @GetMapping("/api/downloads")
    public ResponseEntity<?> list() {
        ObjectNode out = mapper.createObjectNode();
        out.put("updated", "");
        out.put("banner", "");
        ArrayNode items = out.putArray("items");

        Path manifest = baseDir.resolve("manifest.json");
        if (Files.isRegularFile(manifest)) {
            try {
                JsonNode root = mapper.readTree(Files.readString(manifest));
                if (root.hasNonNull("updated")) out.put("updated", root.get("updated").asText());
                if (root.hasNonNull("banner")) out.put("banner", root.get("banner").asText());
                JsonNode list = root.path("items");
                if (list.isArray()) {
                    for (JsonNode it : list) {
                        ObjectNode e = items.addObject();
                        e.put("platform", text(it, "platform", "other"));
                        e.put("label", text(it, "label", "客户端"));
                        e.put("icon", text(it, "icon", "📦"));
                        e.put("os", text(it, "os", ""));
                        e.put("arch", text(it, "arch", ""));
                        e.put("version", text(it, "version", ""));
                        e.put("note", text(it, "note", ""));
                        String file = text(it, "file", "");
                        e.put("file", file);
                        Path fp = safeResolve(file);
                        boolean ok = fp != null && Files.isRegularFile(fp);
                        if (ok) {
                            e.put("available", true);
                            e.put("size", Files.size(fp));
                            e.put("url", "/download/" + file);
                        } else {
                            // 无文件但 manifest 自带 url（如 PWA 的“打开本站”）：直接跳转而非“即将提供”
                            String mu = it.hasNonNull("url") ? it.get("url").asText() : "";
                            e.put("available", mu != null && !mu.isBlank());
                            e.put("size", 0);
                            e.put("url", (mu == null || mu.isBlank()) ? null : mu);
                        }
                    }
                }
            } catch (Exception e) {
                out.put("error", "manifest 解析失败: " + e.getMessage());
            }
        }
        return ResponseEntity.ok(out);
    }

    @GetMapping("/download/{file}")
    public ResponseEntity<Resource> download(@PathVariable("file") String file) {
        Path fp = safeResolve(file);
        if (fp == null || !Files.isRegularFile(fp)) {
            return ResponseEntity.notFound().build();
        }
        File f = fp.toFile();
        String encoded = java.net.URLEncoder.encode(f.getName(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + f.getName() + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(f.length())
                .body(new FileSystemResource(f));
    }

    /** 防目录穿越：只允许 downloads 目录内的纯文件名。 */
    private Path safeResolve(String file) {
        if (file == null || file.isBlank()) return null;
        if (file.contains("/") || file.contains("\\") || file.contains("..")) return null;
        Path p = baseDir.resolve(file).normalize();
        return p.startsWith(baseDir) ? p : null;
    }

    private static String text(JsonNode n, String k, String def) {
        return n.hasNonNull(k) ? n.get(k).asText() : def;
    }
}
