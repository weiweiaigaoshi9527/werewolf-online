package com.werewolf.service;

import com.werewolf.model.ItemDefinition;
import com.werewolf.model.User;
import com.werewolf.repo.ItemDefinitionRepository;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** 解析用户穿戴的装饰为可显示信息（头像框颜色、称号文本、昵称颜色）。 */
@Service
public class DecorationService {

    private final ItemDefinitionRepository itemRepo;

    public DecorationService(ItemDefinitionRepository itemRepo) {
        this.itemRepo = itemRepo;
    }

    public Map<String, Object> of(User u) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("avatarId", u.getAvatarId());
        m.put("avatarUrl", u.getAvatarUrl());
        m.put("nickColor", u.getNickColor());
        m.put("frameId", u.getFrameId());
        m.put("titleId", u.getTitleId());
        ItemDefinition frame = u.getFrameId() > 0 ? itemRepo.findById((long) u.getFrameId()).orElse(null) : null;
        ItemDefinition title = u.getTitleId() > 0 ? itemRepo.findById((long) u.getTitleId()).orElse(null) : null;
        m.put("frameColor", frame != null ? frame.getAsset() : null);
        m.put("frameName", frame != null ? frame.getName() : null);
        m.put("title", title != null ? title.getAsset() : null);
        return m;
    }

    private String assetOf(long itemId) {
        return itemRepo.findById(itemId).map(ItemDefinition::getAsset).orElse(null);
    }

    /**
     * 批量解析多个用户穿戴的装饰：先汇总所有头像框/称号 id，一次 findAllById 查出，
     * 再逐个用户组装，避免在好友列表等场景中逐条查询造成 N+1。
     * 返回 userId -> 装饰信息 Map。
     */
    public Map<Long, Map<String, Object>> ofAll(Collection<User> userList) {
        Map<Long, Map<String, Object>> out = new HashMap<>();
        if (userList == null || userList.isEmpty()) return out;
        // 汇总所有需要用到的商品 id（头像框 + 称号）
        List<Long> itemIds = new ArrayList<>();
        for (User u : userList) {
            if (u == null) continue;
            if (u.getFrameId() > 0) itemIds.add((long) u.getFrameId());
            if (u.getTitleId() > 0) itemIds.add((long) u.getTitleId());
        }
        Map<Long, ItemDefinition> items = new HashMap<>();
        if (!itemIds.isEmpty()) {
            for (ItemDefinition it : itemRepo.findAllById(itemIds)) items.put(it.getId(), it);
        }
        for (User u : userList) {
            if (u == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("avatarId", u.getAvatarId());
            m.put("avatarUrl", u.getAvatarUrl());
            m.put("nickColor", u.getNickColor());
            m.put("frameId", u.getFrameId());
            m.put("titleId", u.getTitleId());
            ItemDefinition frame = u.getFrameId() > 0 ? items.get((long) u.getFrameId()) : null;
            ItemDefinition title = u.getTitleId() > 0 ? items.get((long) u.getTitleId()) : null;
            m.put("frameColor", frame != null ? frame.getAsset() : null);
            m.put("frameName", frame != null ? frame.getName() : null);
            m.put("title", title != null ? title.getAsset() : null);
            out.put(u.getId(), m);
        }
        return out;
    }
}
