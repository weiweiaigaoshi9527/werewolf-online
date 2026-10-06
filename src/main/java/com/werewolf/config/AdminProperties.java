package com.werewolf.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/** 后台管理员相关配置：启动引导的管理员用户名列表（这些已存在的用户会被置为管理员）。 */
@Component
@ConfigurationProperties(prefix = "admin")
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdminProperties {

    /** 逗号分隔或 YAML 列表；对应 app_user.username，启动时标记为管理员。 */
    private List<String> bootstrapUsernames = new ArrayList<>();

    public List<String> getBootstrapUsernames() { return bootstrapUsernames; }
    public void setBootstrapUsernames(List<String> v) { this.bootstrapUsernames = v; }
}
