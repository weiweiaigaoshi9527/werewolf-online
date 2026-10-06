package com.werewolf.service;

import com.werewolf.model.AppSetting;
import com.werewolf.repo.AppSettingRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/** 键值设置读写（后台持久化配置）。 */
@Service
public class SettingsService {

    private final AppSettingRepository repo;

    public SettingsService(AppSettingRepository repo) {
        this.repo = repo;
    }

    public Optional<String> get(String key) {
        return repo.findByKey(key).map(AppSetting::getValue);
    }

    @Transactional
    public void put(String key, String value) {
        AppSetting s = repo.findByKey(key).orElseGet(() -> {
            AppSetting n = new AppSetting();
            n.setKey(key);
            return n;
        });
        s.setValue(value);
        repo.save(s);
    }
}
