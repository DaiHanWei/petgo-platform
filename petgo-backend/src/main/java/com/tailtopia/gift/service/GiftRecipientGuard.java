package com.tailtopia.gift.service;

import com.tailtopia.gift.port.GiftPetDirectory;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.social.read.UserHideRelationReader;
import org.springframework.stereotype.Component;

/**
 * 收礼宠物校验（V1.4.0 gift Story 1.1 · AC5.1 第 1 ~ 3 条）：发起送礼与拉取目录<b>共用同一口径</b>，
 * 让 App 打开面板时就能显示与发起时相同的错误。
 *
 * <ol>
 *   <li>宠物不存在或主人已注销 → 404 {@code gift-pet-gone}。封号主人（{@code ownerActive = false} 但未注销）<b>放行</b>；
 *       只读宠物不校验（PRD AC10：只读可收）。</li>
 *   <li>自己的宠物 → 422 {@code gift-own-pet}。</li>
 *   <li>送礼人拉黑了主人 {@code isBlocked(sender, owner)} → 403 {@code gift-blocked}。<b>只拦单向</b>：
 *       主人拉黑送礼人不拦（PRD AC9，不暴露被拉黑）。</li>
 * </ol>
 */
@Component
public class GiftRecipientGuard {

    private final GiftPetDirectory pets;
    private final UserHideRelationReader hides;

    public GiftRecipientGuard(GiftPetDirectory pets, UserHideRelationReader hides) {
        this.pets = pets;
        this.hides = hides;
    }

    /** 按宠物 id 取收礼宠物并校验。 */
    public GiftPet requireRecipient(long senderId, long petId) {
        GiftPet pet = pets.pet(petId)
                .filter(p -> !p.ownerDeleted())
                .orElseThrow(() -> AppException.giftPetGone("该宠物已不存在"));
        return check(senderId, pet);
    }

    /**
     * 按内容 id 取收礼宠物（取<b>第一只</b>，= 宠物创建顺序）并校验；内容不可送礼（非公开 Diary / 无宠物）同 404。
     * 只服务已弃用的目录 {@code ?postId=}（gift Story 4.1 · AC3.4，兼容 1.4.0 早期包）。
     */
    public GiftPet requireRecipientOfPost(long senderId, long postId) {
        GiftPet pet = pets.petsOfPost(postId).stream()
                .findFirst()
                .filter(p -> !p.ownerDeleted())
                .orElseThrow(() -> AppException.giftPetGone("该宠物已不存在"));
        return check(senderId, pet);
    }

    private GiftPet check(long senderId, GiftPet pet) {
        if (pet.ownerId() == senderId) {
            throw AppException.giftOwnPet("不能给自己的宠物送礼");
        }
        if (hides.isBlocked(senderId, pet.ownerId())) {
            throw AppException.giftBlocked("你已拉黑该用户，无法送礼");
        }
        return pet;
    }
}
