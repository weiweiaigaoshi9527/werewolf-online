package com.werewolf.api;

import com.werewolf.repo.AppSettingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/** 客户端可匿名读取的显示设置（网页最大高度等）。只暴露白名单字段，不泄露任何敏感配置。 */
@RestController
public class DisplayConfigController {

    private final AppSettingRepository settingRepo;

    public DisplayConfigController(AppSettingRepository settingRepo) {
        this.settingRepo = settingRepo;
    }

    /** 公共功能开关：返回被关闭的功能 id 列表（客户端据此隐藏对应入口）。 */
    @GetMapping("/api/config/features")
    public Map<String, Object> features() {
        String v = settingRepo.findByKey("featureFlags").map(a -> a.getValue()).orElse("{}");
        java.util.List<String> disabled = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.ObjectMapper om = new com.fasterxml.jackson.databind.ObjectMapper();
            Map<String, Object> m = om.readValue(v == null || v.isBlank() ? "{}" : v, Map.class);
            for (Map.Entry<String, Object> e : m.entrySet())
                if (!Boolean.TRUE.equals(e.getValue())) disabled.add(e.getKey());
        } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("disabled", disabled);
        return out;
    }

    @GetMapping("/api/config/display")
    public Map<String, Object> display() {
        String v = settingRepo.findByKey("webMaxHeight").map(a -> a.getValue()).orElse("0");
        int h = 0;
        try { h = Integer.parseInt(v.trim()); } catch (Exception ignored) {}
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("webMaxHeight", Math.max(0, Math.min(h, 100)));   // 百分比 0-100
        return out;
    }
}
