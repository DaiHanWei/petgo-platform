package com.tailtopia.social.dto;

import com.tailtopia.profile.domain.PetType;

/**
 * 「某用户追更的宠物」列表行（V1.4.2 social-topic · Story 1.1 · AC8 · D-8 / G-1 / R-2）。
 *
 * @param petId         宠物 id —— 与站内访客路由 {@code /pets/{petId}} 同一标识（现有访客 / 推荐卡已以 petId 寻址）
 * @param name          宠物名
 * @param petType       物种
 * @param avatarUrl     头像（服务端缩放 + 去 EXIF）；无头像省略
 * @param ownerNickname 主人昵称；查不到省略
 * @param ownerToken    主人公开 token（封号也给，供埋点 {@code owner_id}，R-2）
 * @param iFollow       <b>查看者</b>是否追更这只宠物（游客恒 false）
 * @param iFollowOwner  查看者是否关注这只宠物的主人（游客恒 false）
 */
public record FollowedPetItem(
        long petId,
        String name,
        PetType petType,
        String avatarUrl,
        String ownerNickname,
        String ownerToken,
        boolean iFollow,
        boolean iFollowOwner,
        /** 细分类型（V1.5.0 pet-species · D-10，PetSpecies 名）；{@code petType} 仍是大类三值。 */
        String species) {

    /** 便捷构造：V1.5.0 前的形态（不带细分类型，species = null，App 按 petType 兜底）。 */
    public FollowedPetItem(long petId, String name, PetType petType, String avatarUrl, String ownerNickname,
            String ownerToken, boolean iFollow, boolean iFollowOwner) {
        this(petId, name, petType, avatarUrl, ownerNickname, ownerToken, iFollow, iFollowOwner, null);
    }
}
