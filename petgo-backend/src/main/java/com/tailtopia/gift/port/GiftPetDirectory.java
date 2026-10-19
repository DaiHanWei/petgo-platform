package com.tailtopia.gift.port;

import java.util.List;
import java.util.Optional;

/**
 * 送礼模块取宠物的<b>自有端口</b>（V1.4.0 gift Story 1.1 · AD-5 / P-3）。
 *
 * <p>🔴 与 ①② 并行开发：② 的多宠接口在本分支不存在，送礼的服务与控制器<b>只依赖本接口</b>。
 * 签名是对齐 story 4.1 的契约 —— 对齐时只换实现（改接 ② 的读口），<b>不改签名、业务代码不动</b>。
 */
public interface GiftPetDirectory {

    /** 按 id 取宠物（含主人状态）；宠物不存在 → 空。 */
    Optional<GiftPet> pet(long petId);

    /**
     * 某条内容关联的宠物（单宠过渡：只有公开、已发布、未下架、未删除的 Diary 帖且带宠物时返回一只，其余一律空表）。
     * 多宠（一帖多宠）在 story 4.1 对齐。
     */
    List<GiftPet> petsOfPost(long postId);

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
