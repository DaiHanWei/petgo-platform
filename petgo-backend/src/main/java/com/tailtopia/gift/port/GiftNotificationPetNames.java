package com.tailtopia.gift.port;

import com.tailtopia.notify.port.NotificationPetNames;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 通知列表取宠物名端口 {@link NotificationPetNames} 的实现（V1.4.0 gift Story 1.2 · AC5.4）。
 *
 * <p>逐个经 {@link GiftPetDirectory#pet} 取（一页 ≤ 20 行，可接受）；取宠物只经送礼自有端口（P-3）。
 * 🔴 对齐 story 4.1 时随端口换实现（改接 ② 的批量读口），{@link NotificationPetNames} 签名不变。
 */
@Component
public class GiftNotificationPetNames implements NotificationPetNames {

    private final GiftPetDirectory pets;

    public GiftNotificationPetNames(GiftPetDirectory pets) {
        this.pets = pets;
    }

    @Override
    public Map<Long, String> namesOf(Collection<Long> petIds) {
        Map<Long, String> out = new LinkedHashMap<>();
        for (Long id : petIds) {
            if (id == null || out.containsKey(id)) {
                continue;
            }
            pets.pet(id).ifPresent(p -> out.put(id, p.name()));
        }
        return out;
    }
}
