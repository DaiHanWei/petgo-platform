package com.tailtopia.profile.service;

import com.tailtopia.profile.domain.PetProfile;
import java.util.List;
import java.util.Optional;

/**
 * 「默认宠物」的唯一定义（V1.4.0 multi-pet AD-2）：旧版 App 不带 petId、按账号取一只宠物的地方都落到这里。
 *
 * <p>当前口径 = 最早创建的一只（{@code created_at} 相同按 {@code id} 升序）。Story 2.1 加 {@code status} 后改为
 * 「最早创建的 ACTIVE，没有 ACTIVE 再取最早创建」——<b>只改本方法</b>，全仓不得自写 {@code get(0)} / {@code min(createdAt)}。
 */
public final class DefaultPetPolicy {

    private DefaultPetPolicy() {
    }

    /** @param orderedByCreatedAsc 已按 {@code created_at, id} 升序排好的账号全部宠物 */
    public static Optional<PetProfile> pick(List<PetProfile> orderedByCreatedAsc) {
        if (orderedByCreatedAsc == null || orderedByCreatedAsc.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(orderedByCreatedAsc.get(0));
    }
}
