package com.tailtopia.profile.event;

/**
 * 宠物类型已修改（V1.5.0 pet-species Story 1.2 · §8）。仅供 AFTER_COMMIT 埋点 {@code pet_type_changed}；
 * 🔒 不带宠物名 / 品种 / 修改原因。
 *
 * @param ownerId      主人用户 id（埋点 distinctId 由它哈希）
 * @param petProfileId 宠物档案 id
 * @param fromSpecies  原细分类型名
 * @param toSpecies    新细分类型名
 * @param groupChanged 大类是否改变
 * @param source       {@code user} / {@code admin}
 */
public record PetSpeciesChangedEvent(long ownerId, long petProfileId, String fromSpecies, String toSpecies,
        boolean groupChanged, String source) {

    public static final String SOURCE_USER = "user";
    public static final String SOURCE_ADMIN = "admin";
}
