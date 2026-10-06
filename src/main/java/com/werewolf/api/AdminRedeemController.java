package com.werewolf.api;

import com.werewolf.model.RedeemCode;
import com.werewolf.service.RedeemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/** 后台·兑换码管理（仅管理员）。 */
@RestController
@RequestMapping("/api/admin/redeem")
public class AdminRedeemController {

    private final RedeemService redeemService;

    public AdminRedeemController(RedeemService redeemService) {
        this.redeemService = redeemService;
    }

    public record CreateBody(String code, Long gold, Long exp, Integer itemDefId, Integer maxUses, String expireAt, String note) {}
    public record FlagBody(boolean value) {}

    @GetMapping
    public ResponseEntity<?> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (RedeemCode c : redeemService.list()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", c.getId()); m.put("code", c.getCode()); m.put("gold", c.getGold()); m.put("exp", c.getExp());
            m.put("itemDefId", c.getItemDefId()); m.put("maxUses", c.getMaxUses()); m.put("usedCount", c.getUsedCount());
            m.put("active", c.isActive()); m.put("expireAt", c.getExpireAt() == null ? null : c.getExpireAt().toString());
            m.put("note", c.getNote());
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> -((Number) m.get("id")).longValue()));
        return ResponseEntity.ok(out);
    }

    @PostMapping
    public ResponseEntity<?> create(@RequestBody CreateBody body) {
        try {
            LocalDateTime exp = (body.expireAt() == null || body.expireAt().isBlank()) ? null : LocalDateTime.parse(body.expireAt());
            RedeemCode c = redeemService.create(body.code(),
                    body.gold() == null ? 0 : body.gold(),
                    body.exp() == null ? 0 : body.exp(),
                    body.itemDefId() == null ? 0 : body.itemDefId(),
                    body.maxUses() == null ? 1 : body.maxUses(),
                    exp, body.note());
            return ResponseEntity.ok(Map.of("ok", true, "code", c.getCode()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @PostMapping("/{id}/active")
    public ResponseEntity<?> active(@PathVariable long id, @RequestBody FlagBody body) {
        redeemService.setActive(id, body.value());
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @PostMapping("/{id}/delete")
    public ResponseEntity<?> delete(@PathVariable long id) {
        redeemService.delete(id);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}
