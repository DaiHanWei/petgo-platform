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
 */
@Service
public class TailsonalityUnlockService {

    private final TailsonalityResultRepository results;
    private final PetProfileQueryService pets;
    private final KeepsakePurchaseService purchases;

    public TailsonalityUnlockService(TailsonalityResultRepository results, PetProfileQueryService pets,
            KeepsakePurchaseService purchases) {
        this.results = results;
        this.pets = pets;
        this.purchases = purchases;
    }

    /** token 不存在 / 非本人 / 宠物已删 → 同一个 404（与 {@code GET …/results/{token}} 同口径）。 */
    // noRollbackFor 与 start 一致：网关下单失败时意图 FAILED 行要随事务提交留档（外层不跟着就会整笔回滚）。
    @Transactional(noRollbackFor = PayException.class)
    public KeepsakePurchaseResponse unlock(long userId, String token, PayChannel channel) {
        OwnedPetRef pet = pets.findOwnedPet(userId).orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
        TailsonalityResult row = results.findForUpdateByPublicTokenAndPetProfileId(token, pet.petId())
                .orElseThrow(() -> AppException.notFound("结果不存在"));
        KeepsakeRef ref = new KeepsakeRef(KeepsakeSku.TAILSONALITY, row.getId(), row.getPublicToken(),
                row.getPetProfileId(), row.getUnlockedAt() != null);
        return purchases.start(userId, ref, channel);
    }
}
