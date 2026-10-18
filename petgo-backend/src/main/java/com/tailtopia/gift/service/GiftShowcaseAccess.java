package com.tailtopia.gift.service;

import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.stereotype.Component;

/**
 * 礼物页可见性（V1.4.0 gift Story 2.1 · AC3 · AD-10「可见性沿用访客视图的拉黑规则」），礼物页与粉丝榜两个端点共用。
 *
 * <p>规则逐条对齐 {@code InAppVisitorPetController.requireVisible}（同状态码、同 detail 文案）：
 * <ol>
 *   <li>宠物不存在或主人已注销 → 404「链接已失效」；</li>
 *   <li>主人本人 → 放行，{@code viewerIsOwner = true}；</li>
 *   <li>查看者主动拉黑了主人（只认 BLOCK）→ 403 {@code blockedUser}；</li>
 *   <li>主人主动拉黑了查看者 → <b>同一个 404</b>（不可与「宠物不存在」区分，不暴露被拉黑）。</li>
 * </ol>
 *
 * <p>⚠️ 与访客视图<b>唯一的差别</b>：主人被封号（{@code ownerActive = false} 且未注销）<b>放行</b>——
 * 访客 Diary Tab 对封号主人 404，但 FR-148.1「封号仍可收礼」、发起送礼也放行封号主人（拍板 P-6）。
 * 若产品改为与访客视图一致，在这里加一条 {@code ownerActive} 判断即可。
 */
@Component
public class GiftShowcaseAccess {

    /** 与访客视图同一句（{@code InAppVisitorPetController.GONE_DETAIL}）：几种失效不可区分。 */
    static final String GONE_DETAIL = "链接已失效";

    private final GiftPetDirectory pets;
    private final UserHideRelationReader hideRelations;

    public GiftShowcaseAccess(GiftPetDirectory pets, UserHideRelationReader hideRelations) {
        this.pets = pets;
        this.hideRelations = hideRelations;
    }

    /**
     * 取可见的宠物，否则抛 404 / 403。
     *
     * @param viewerId 当前登录用户 id（只取 JWT {@code sub}，端点只对 {@code role=USER} 开放）
     */
    public Visible requireVisible(long viewerId, long petId) {
        GiftPet pet = pets.pet(petId)
                .filter(p -> !p.ownerDeleted())
                .orElseThrow(() -> AppException.notFound(GONE_DETAIL));
        if (pet.ownerId() == viewerId) {
            return new Visible(pet, true);
        }
        if (hideRelations.isBlocked(viewerId, pet.ownerId())) {
            throw AppException.blockedUser("你已拉黑该用户");
        }
        // 主人拉黑了我 → 落进同一句 404；抛 403 等于明白告诉他「你被拉黑了」（FR-118.5）。
        if (hideRelations.isBlocked(pet.ownerId(), viewerId)) {
            throw AppException.notFound(GONE_DETAIL);
        }
        return new Visible(pet, false);
    }

    /** 可见的宠物与查看者是否主人。 */
    public record Visible(GiftPet pet, boolean viewerIsOwner) {
    }
}
