package com.werewolf.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AdminInterceptor adminInterceptor;

    public WebMvcConfig(AdminInterceptor adminInterceptor) {
        this.adminInterceptor = adminInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/api/admin/**")
                // 仅限 127.0.0.1 的本地管理端点（服务管理 GUI 用）不走后台鉴权，其安全性由 AdminLocalController 的本机 IP 校验保证
                .excludePathPatterns("/api/admin/local/**");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // 独立后台页面：/admin 与 /admin/ 都转发到静态 admin.html
        registry.addViewController("/admin").setViewName("forward:/admin.html");
        registry.addViewController("/admin/").setViewName("forward:/admin.html");

        // 服务端安装指导页面：/install 与 /install/ 都转发到静态 install.html
        registry.addViewController("/install").setViewName("forward:/install.html");
        registry.addViewController("/install/").setViewName("forward:/install.html");
    }
}
