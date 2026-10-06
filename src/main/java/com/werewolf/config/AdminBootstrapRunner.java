package com.werewolf.config;

import com.werewolf.model.User;
import com.werewolf.repo.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/**
 * 启动引导管理员：把 admin.bootstrap-usernames 里已存在的用户置为管理员；
 * 兜底：若系统已有用户却一个管理员都没有，则把最早注册的用户提为管理员，保证后台永远进得去。
 */
@Component
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);
    private final AdminProperties props;
    private final UserRepository users;

    public AdminBootstrapRunner(AdminProperties props, UserRepository users) {
        this.props = props;
        this.users = users;
    }

    @Override
    public void run(ApplicationArguments args) {
        for (String name : props.getBootstrapUsernames()) {
            if (name == null || name.isBlank()) continue;
            users.findByUsername(name.trim()).ifPresent(u -> {
                if (!u.isAdmin()) {
                    u.setAdmin(true);
                    users.save(u);
                    log.info("引导管理员：{} 已设为管理员", u.getUsername());
                }
            });
        }
        List<User> all = users.findAll();
        boolean anyAdmin = all.stream().anyMatch(User::isAdmin);
        if (!anyAdmin && !all.isEmpty()) {
            User first = all.stream().min(Comparator.comparingLong(User::getId)).orElse(null);
            if (first != null) {
                first.setAdmin(true);
                users.save(first);
                log.info("系统无管理员，已把最早注册的用户 {} 设为管理员", first.getUsername());
            }
        }
    }
}
