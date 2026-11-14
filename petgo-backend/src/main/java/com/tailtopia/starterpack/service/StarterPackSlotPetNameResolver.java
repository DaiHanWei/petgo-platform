package com.tailtopia.starterpack.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.slot.PetSlotService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 「Slot {宠物} diperpanjang 30 hari」的宠物名（V1.5.0 gift-pack Story 4-2 · AC7 · 待拍板 Q-5 默认规则）：
 * 取「该名额到期后会变只读的那只宠物」——按 reconcile 的保留顺序（{@code keep_active} 优先，其后建档先后），
 * 名额少 1 个时第一只会被转只读的正常宠物；算不出（正常宠物数 &lt; 名额）则取最新建的一只；没有宠物 → null。
 * 🔒 宠物名只进响应，不写日志。
 */
@Component
public class StarterPackSlotPetNameResolver {

    private static final Comparator<PetProfile> KEEP_ACTIVE_FIRST =
            Comparator.comparing(PetProfile::isKeepActive).reversed();

    private final PetProfileRepository profiles;
    private final PetSlotService slots;

    public StarterPackSlotPetNameResolver(PetProfileRepository profiles, PetSlotService slots) {
        this.profiles = profiles;
        this.slots = slots;
    }

    public String resolve(long userId) {
        List<PetProfile> pets = profiles.findAllByOwnerIdOrderByCreatedAtAscIdAsc(userId);
        if (pets.isEmpty()) {
            return null;
        }
        return pick(pets, slots.quotaOf(userId).total());
    }

    /** 纯函数：{@code pets} 按建档先后（created_at ASC, id ASC）。 */
    static String pick(List<PetProfile> pets, int quota) {
        List<PetProfile> ordered = new ArrayList<>(pets);
        ordered.sort(KEEP_ACTIVE_FIRST); // 稳定排序：同组内保持建档先后（同 PetSlotStateService）
        List<PetProfile> active = ordered.stream().filter(p -> p.getStatus() != PetStatus.READ_ONLY).toList();
        int keep = Math.max(0, quota - 1);
        if (active.size() > keep) {
            return active.get(keep).getName();
        }
        return pets.get(pets.size() - 1).getName();
    }
}
