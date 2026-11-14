package com.tailtopia.gift.port;

import java.util.List;
import java.util.Optional;

/**
 * 送礼模块取宠物的<b>自有端口</b>（V1.4.0 gift Story 1.1 · AD-5 / P-3）。
 *
 * <p>送礼的服务与控制器<b>只依赖本接口</b>；唯一实现是接 ② 读口的 {@link MultiPetGiftPetDirectory}
 * （gift Story 4.1 · AC1 换掉 1.1 的单宠过渡实现，签名未改）。
 */
public interface GiftPetDirectory {

    /** 按 id 取宠物（含主人状态）；宠物不存在 → 空。 */
    Optional<GiftPet> pet(long petId);

    /**
     * 某条内容关联的全部宠物，按宠物创建顺序（与详情 {@code pets} 同序）：只有公开、已发布、未下架、未删除的 Diary 帖
     * 才有，其余一律空表；只读宠物照列，已删宠物不在。
     */
    List<GiftPet> petsOfPost(long postId);

    /**
     * V1.5.0 gift-pack Story 1-4：某账号名下宠物名（含只读、不含已删），按建档时间，最多 {@code limit} 个
     * （礼物详情「送礼人的宠物」）。默认空表——只有多宠实现给真值。
     */
    default List<String> petNamesOfOwner(long ownerId, int limit) {
        return List.of();
    }

    /** 批量版（一条查询，不 N+1）：{@code ownerId → 宠物名}；没有宠物的账号不在 Map 里。默认逐个调单个版本。 */
    default java.util.Map<Long, List<String>> petNamesOfOwners(java.util.Collection<Long> ownerIds, int limit) {
        java.util.Map<Long, List<String>> out = new java.util.HashMap<>();
        for (Long id : ownerIds) {
            List<String> names = petNamesOfOwner(id, limit);
            if (!names.isEmpty()) {
                out.put(id, names);
            }
        }
        return out;
    }

    /**
     * 收礼宠物视图。
     *
     * @param petId        宠物 id
     * @param ownerId      主人 users.id
     * @param name         宠物名（下单时快照进订单）
     * @param avatarUrl    宠物头像（可空）
     * @param petType      {@code CAT} / {@code DOG} / {@code OTHER}（目录物种排序用）
     * @param breed        品种（可空）
     * @param ownerActive  主人未注销且 {@code status = ACTIVE}（与 {@code AccountQueryService.isActive} 同口径；封号为 false）
     * @param ownerDeleted 主人已注销（{@code users.deleted_at IS NOT NULL}）。🔴 送礼只拦注销，封号主人可收礼（PRD / P-6）
     */
    record GiftPet(long petId, long ownerId, String name, String avatarUrl, String petType, String breed,
            boolean ownerActive, boolean ownerDeleted) {
    }
}
