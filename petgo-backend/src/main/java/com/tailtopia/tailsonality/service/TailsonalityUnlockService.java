package com.tailtopia.tailsonality.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.profile.dto.OwnedPetRef;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.service.KeepsakePurchaseService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tailsonality 结果解锁发起（V1.3.2 Story 3.2 · AC1）。加行锁读结果 → 交给 purchase 包的
 * {@link KeepsakePurchaseService#start}；已解锁 / MIXED / 余额不足都由那边抛，本类不另造。
 *
 * <p>2026-10-09 配型改回付费：{@link #unlockMatch} 单独解锁配型；完整解读包含配型，已单独买过配型的结果再买完整解读
 * 只补差价（{@link TailsonalityUpgradePricing}，在行锁内算，与成交同一时刻）。
 */
@Service
public class TailsonalityUnlockService {

    private final TailsonalityResultRepository results;
    private final PetProfileQueryService pets;
    private final KeepsakePurchaseService purchases;
    private final TailsonalityUpgradePricing upgradePricing;

    public TailsonalityUnlockService(TailsonalityResultRepository results, PetProfileQueryService pets,
            KeepsakePurchaseService purchases, TailsonalityUpgradePricing upgradePricing) {
        this.results = results;
        this.pets = pets;
        this.purchases = purchases;
        this.upgradePricing = upgradePricing;
    }

    /** token 不存在 / 非本人 / 宠物已删 → 同一个 404（与 {@code GET …/results/{token}} 同口径）。 */
    // noRollbackFor 与 start 一致：网关下单失败时意图 FAILED 行要随事务提交留档（外层不跟着就会整笔回滚）。
    @Transactional(noRollbackFor = PayException.class)
    public KeepsakePurchaseResponse unlock(long userId, String token, PayChannel channel) {
        TailsonalityResult row = lockOwned(userId, token);
        KeepsakeRef ref = new KeepsakeRef(KeepsakeSku.TAILSONALITY, row.getId(), row.getPublicToken(),
                row.getPetProfileId(), row.getUnlockedAt() != null, upgradePricing.upgradePriceOrNull(row));
        return purchases.start(userId, ref, channel);
    }

    /** 配型单独解锁：完整解读已解锁（已含配型）或已单独买过 → 409 {@code keepsake-already-unlocked}。 */
    @Transactional(noRollbackFor = PayException.class)
    public KeepsakePurchaseResponse unlockMatch(long userId, String token, PayChannel channel) {
        TailsonalityResult row = lockOwned(userId, token);
        KeepsakeRef ref = new KeepsakeRef(KeepsakeSku.TS_MATCH, row.getId(), row.getPublicToken(),
                row.getPetProfileId(), row.matchUnlocked());
        return purchases.start(userId, ref, channel);
    }

    private TailsonalityResult lockOwned(long userId, String token) {
        OwnedPetRef pet = pets.findOwnedPet(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        return results.findForUpdateByPublicTokenAndPetProfileId(token, pet.petId())
                .orElseThrow(() -> AppException.notFound("结果不存在"));
    }
}
