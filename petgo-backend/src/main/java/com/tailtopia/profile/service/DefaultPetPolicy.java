package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetStatus;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 「默认宠物」的唯一定义（V1.4.0 multi-pet AD-2）：旧版 App 不带 petId、按账号取一只宠物的地方都落到这里。
 *
 * <p>口径（Story 2.1 起，AD-2 原文）= 最早创建的 {@code ACTIVE}，没有 {@code ACTIVE} 再取最早创建
 * （{@code created_at} 相同按 {@code id} 升序）。全仓不得自写 {@code get(0)} / {@code min(createdAt)}。
 * 默认名额恒 ≥ 1 ⇒ 有宠物的账号恒有一只 ACTIVE，故默认宠物恒可写（单宠物账号恒 ACTIVE，行为与改造前一致）。
 */
public final class DefaultPetPolicy {

    private DefaultPetPolicy() {
    }

    /** @param orderedByCreatedAsc 已按 {@code created_at, id} 升序排好的账号全部宠物 */
    public static Optional<PetProfile> pick(List<PetProfile> orderedByCreatedAsc) {
        if (orderedByCreatedAsc == null || orderedByCreatedAsc.isEmpty()) {
            return Optional.empty();
        }
        for (PetProfile p : orderedByCreatedAsc) {
            if (p.getStatus() != PetStatus.READ_ONLY) {
                return Optional.of(p);
            }
        }
        return Optional.of(orderedByCreatedAsc.get(0));
    }

    /**
     * 多账号批量取默认宠物（V1.4.0 multi-pet Story 1.2）：每个 owner 按 {@link #pick} 同一口径取
     * （Story 2.1 起最早的 ACTIVE，没有再取最早）。
     *
     * @param orderedByCreatedAsc 已按 {@code created_at, id} 升序排好的多账号宠物（如
     *     {@code findAllByOwnerIdInOrderByCreatedAtAscIdAsc}）
     * @return {@code ownerId → 默认宠物}；无宠物的账号不入 map
     */
    public static Map<Long, PetProfile> pickPerOwner(List<PetProfile> orderedByCreatedAsc) {
        Map<Long, PetProfile> out = new LinkedHashMap<>();
        if (orderedByCreatedAsc == null) {
            return out;
        }
        Map<Long, List<PetProfile>> byOwner = new LinkedHashMap<>();
        for (PetProfile p : orderedByCreatedAsc) {
            byOwner.computeIfAbsent(p.getOwnerId(), k -> new ArrayList<>()).add(p);
        }
        byOwner.forEach((owner, pets) -> pick(pets).ifPresent(p -> out.put(owner, p)));
        return out;
    }
}
