package com.tailtopia.tailsonality.service;

import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.domain.CreditUseKind;
import com.tailtopia.entitlement.dto.CreditUnlockResponse;
import com.tailtopia.entitlement.event.EntitlementUsedEvent;
import com.tailtopia.entitlement.service.ConsumeCommand;
import com.tailtopia.entitlement.service.ConsumeResult;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.profile.dto.PetIdentityView;
import com.tailtopia.profile.service.PetProfileQueryService;
import com.tailtopia.profile.service.PetWriteGuard;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import com.tailtopia.tailsonality.repository.TailsonalityResultRepository;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 性格测试结果用免费次数解锁（V1.5.0 gift-pack Story 4-3 · AC4 · D-19）。
 *
 * <p>结果不存在 / 非本人 / 宠物已删 → 404（与付费解锁同口径）；已解锁 → 成功不扣。扣 Tailsonality 1 次（不足 409
 * {@code entitlement-insufficient}，整事务回滚）→ 执行与 {@link TailsonalityKeepsakeGranter} <b>同一段</b> SQL（置
 * {@code unlocked_at} + 首次自动佩戴）。<b>不写 {@code keepsake_purchases}</b>（不进「我的订单」）、不发
 * {@code KeepsakeUnlockedEvent}；提交后报 {@code entitlement_used}。结果行按 token 加锁读，并发同结果串行。
 */
@Service
public class TailsonalityCreditUnlockService {

    private final TailsonalityResultRepository results;
    private final PetProfileQueryService pets;
    private final PetWriteGuard petWriteGuard;
    private final TailsonalityKeepsakeGranter granter;
    private final EntitlementCreditService credits;
    private final ApplicationEventPublisher events;

    public TailsonalityCreditUnlockService(TailsonalityResultRepository results, PetProfileQueryService pets,
            PetWriteGuard petWriteGuard, TailsonalityKeepsakeGranter granter, EntitlementCreditService credits,
            ApplicationEventPublisher events) {
        this.results = results;
        this.pets = pets;
        this.petWriteGuard = petWriteGuard;
        this.granter = granter;
        this.credits = credits;
        this.events = events;
    }

    @Transactional
    public CreditUnlockResponse unlock(long userId, String token) {
        TailsonalityResult row = results.findForUpdateByPublicToken(token)
                .filter(r -> r.getPetProfileId() != null && pets.ownsPet(userId, r.getPetProfileId()))
                .orElseThrow(() -> AppException.notFound("结果不存在"));
        if (row.getUnlockedAt() != null) {
            return CreditUnlockResponse.unlocked(credits.remainingFor(userId, CreditType.TAILSONALITY));
        }
        petWriteGuard.requireWritable(row.getPetProfileId()); // 同付费解锁：归属（404）之后、扣次数之前
        String petName = pets.findIdentityByPetId(row.getPetProfileId()).map(PetIdentityView::name).orElse(null);
        ConsumeResult used = credits.consume(new ConsumeCommand(userId, CreditUseKind.TAILSONALITY,
                CreditType.TAILSONALITY, 1, row.getId(), petName));
        if (!used.alreadyUsed()) {
            granter.unlock(row.getId());
            events.publishEvent(new EntitlementUsedEvent(userId, CreditUseKind.TAILSONALITY, used.firstSource()));
        }
        return CreditUnlockResponse.unlocked(credits.remainingFor(userId, CreditType.TAILSONALITY));
    }
}
