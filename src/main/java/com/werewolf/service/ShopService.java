package com.werewolf.service;

import com.werewolf.model.GoldTransaction;
import com.werewolf.model.Inventory;
import com.werewolf.model.ItemDefinition;
import com.werewolf.model.User;
import com.werewolf.repo.GoldTransactionRepository;
import com.werewolf.repo.InventoryRepository;
import com.werewolf.repo.ItemDefinitionRepository;
import com.werewolf.repo.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
public class ShopService {

    private final ItemDefinitionRepository itemRepo;
    private final InventoryRepository invRepo;
    private final UserRepository userRepo;
    private final GoldTransactionRepository goldRepo;
    private final VipService vipService;

    public ShopService(ItemDefinitionRepository itemRepo, InventoryRepository invRepo,
                       UserRepository userRepo, GoldTransactionRepository goldRepo, VipService vipService) {
        this.itemRepo = itemRepo;
        this.invRepo = invRepo;
        this.userRepo = userRepo;
        this.goldRepo = goldRepo;
        this.vipService = vipService;
    }

    public List<Map<String, Object>> catalog(User user) {
        Map<Long, Inventory> invMap = new HashMap<>();
        invRepo.findByUserId(user.getId()).forEach(i -> invMap.put(i.getItemDefId(), i));
        List<Map<String, Object>> list = new ArrayList<>();
        for (ItemDefinition it : itemRepo.findByEnabledTrueOrderByTypeAscPriceAsc()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("type", it.getType());
            m.put("name", it.getName());
            m.put("description", it.getDescription());
            long finalPrice = vipService.priceFor(user, it.getPrice());
            m.put("price", finalPrice);
            if (finalPrice != it.getPrice()) { m.put("origPrice", it.getPrice()); m.put("discounted", true); }
            m.put("asset", it.getAsset());
            Inventory inv = invMap.get(it.getId());
            boolean owned;
            if (inv == null) owned = false;
            else if ("FUNCTION".equals(it.getType())) owned = !inv.isUsed(); // 功能道具：仅“未使用”才算已拥有
            else owned = true;                                               // 装饰道具：拥有即永久
            m.put("owned", owned);
            list.add(m);
        }
        return list;
    }

    @Transactional
    public String buy(User user, long itemDefId) {
        Optional<ItemDefinition> opt = itemRepo.findById(itemDefId);
        if (opt.isEmpty()) return "商品不存在";
        ItemDefinition item = opt.get();
        if (!item.isEnabled()) return "商品已下架";
        boolean isFunc = "FUNCTION".equals(item.getType());
        Optional<Inventory> ex = invRepo.findByUserIdAndItemDefId(user.getId(), itemDefId);
        if (ex.isPresent()) {
            if (!isFunc) return "你已拥有该物品";                  // 装饰类：一次性，不重复卖
            if (!ex.get().isUsed()) return "你已有未使用的该道具"; // 功能类：还有没用完的，先别买
            // 功能类且上次已用完：允许回购（下方把 used 复位）
        }
        long price = vipService.priceFor(user, item.getPrice());   // VIP 折扣价
        // 原子扣减金币：数据库层条件更新（gold >= price），返回 0 表示余额不足
        int deducted = userRepo.deductGold(user.getId(), price);
        if (deducted == 0) return "金币不足";
        user.setGold(user.getGold() - price); // 同步内存余额，供调用方正确展示（本方法不再 save 该实体的金币）

        if (ex.isPresent() && isFunc) {
            Inventory inv = ex.get();
            inv.setUsed(false);              // 回购充值：重新可用
            invRepo.save(inv);
        } else {
            Inventory inv = new Inventory();
            inv.setUserId(user.getId());
            inv.setItemDefId(itemDefId);
            invRepo.save(inv);
        }

        long balanceAfter = userRepo.findById(user.getId()).map(User::getGold).orElse(0L);
        GoldTransaction gt = new GoldTransaction();
        gt.setUserId(user.getId());
        gt.setDelta(-price);
        gt.setBalanceAfter(balanceAfter);
        gt.setReason("购买 " + item.getName() + (price != item.getPrice() ? "（VIP折扣）" : ""));
        goldRepo.save(gt);
        return null;
    }

    public List<Map<String, Object>> inventory(User user) {
        Map<Long, ItemDefinition> defs = new HashMap<>();
        itemRepo.findAll().forEach(d -> defs.put(d.getId(), d));
        List<Map<String, Object>> list = new ArrayList<>();
        for (Inventory inv : invRepo.findByUserId(user.getId())) {
            if (inv.isUsed()) continue; // 已消耗的功能道具不显示
            ItemDefinition d = defs.get(inv.getItemDefId());
            if (d == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("itemDefId", d.getId());
            m.put("type", d.getType());
            m.put("name", d.getName());
            m.put("asset", d.getAsset());
            list.add(m);
        }
        return list;
    }
}
