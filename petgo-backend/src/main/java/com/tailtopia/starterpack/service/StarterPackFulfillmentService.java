package com.tailtopia.starterpack.service;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import com.tailtopia.entitlement.service.EntitlementCreditService;
import com.tailtopia.entitlement.service.GrantCommand;
import com.tailtopia.entitlement.service.GrantResult;
import com.tailtopia.profile.slot.PetSlotGranter;
import com.tailtopia.profile.slot.PetSlotLock;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.profile.slot.PetSlotStateService;
import com.tailtopia.profile.slot.PetSlotTokenGenerator;
import com.tailtopia.profile.slot.domain.PetSlotPurchase;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseKind;
import com.tailtopia.profile.slot.domain.PetSlotPurchaseStatus;
import com.tailtopia.profile.slot.domain.PetSlotRental;
import com.tailtopia.profile.slot.domain.PetSlotRentalStatus;
import com.tailtopia.profile.slot.repository.PetSlotPurchaseRepository;
import com.tailtopia.profile.slot.repository.PetSlotRentalRepository;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackPurchaseStatus;
import com.tailtopia.starterpack.domain.StarterPackSlotOutcome;
import com.tailtopia.starterpack.domain.StarterPackTier;
import com.tailtopia.starterpack.event.StarterPackFulfilledEvent;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 礼包发放（V1.5.0 gift-pack Story 4-2 · AC6 · D-23 / D-24）：次数 + 名额 → {@code fulfilled_at}。
 *
 * <p>幂等：锁购买行（{@code FOR UPDATE}）后 {@code fulfilled_at} 非空直接返回；次数键 {@code PACK:{purchaseId}}
 * （(idemKey, type) 唯一）、名额键 {@code PACK_SLOT:{purchaseId}}（{@code pet_slot_purchases.idem_key}）。
 * 事务 REQUIRED：PawCoin 下单并入下单事务（任何失败整笔回滚、不扣钱）；QRIS 由发放监听 / 重试任务在 REQUIRES_NEW 里调。
 *
 * <p>不调钱包、不建意图、不发 {@code PawCoinSpentEvent}（名额发放不产生 EXP）。日志只记 userId / purchaseId / tier。
 */
@Service
public class StarterPackFulfillmentService {

    /** 次数幂等键前缀。 */
    public static final String CREDIT_KEY_PREFIX = "PACK:";
    /** 名额幂等键前缀。 */
    public static final String SLOT_KEY_PREFIX = "PACK_SLOT:";

    private static final Logger log = LoggerFactory.getLogger(StarterPackFulfillmentService.class);

    private final StarterPackPurchaseRepository purchases;
    private final EntitlementCreditService credits;
    private final PetSlotLock slotLock;
    private final PetSlotService slots;
    private final PetSlotPurchaseRepository slotPurchases;
    private final PetSlotRentalRepository rentals;
    private final PetSlotGranter granter;
    private final PetSlotStateService slotState;
    private final PetSlotTokenGenerator slotTokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public StarterPackFulfillmentService(StarterPackPurchaseRepository purchases, EntitlementCreditService credits,
            PetSlotLock slotLock, PetSlotService slots, PetSlotPurchaseRepository slotPurchases,
            PetSlotRentalRepository rentals, PetSlotGranter granter, PetSlotStateService slotState,
            PetSlotTokenGenerator slotTokens, ApplicationEventPublisher events) {
        this(purchases, credits, slotLock, slots, slotPurchases, rentals, granter, slotState, slotTokens, events,
                Clock.systemUTC());
    }

    StarterPackFulfillmentService(StarterPackPurchaseRepository purchases, EntitlementCreditService credits,
            PetSlotLock slotLock, PetSlotService slots, PetSlotPurchaseRepository slotPurchases,
            PetSlotRentalRepository rentals, PetSlotGranter granter, PetSlotStateService slotState,
            PetSlotTokenGenerator slotTokens, ApplicationEventPublisher events, Clock clock) {
        this.purchases = purchases;
        this.credits = credits;
        this.slotLock = slotLock;
        this.slots = slots;
        this.slotPurchases = slotPurchases;
        this.rentals = rentals;
        this.granter = granter;
        this.slotState = slotState;
        this.slotTokens = slotTokens;
        this.events = events;
        this.clock = clock;
    }

    /** 本档要发的次数：8 款 KTP 各 1、Tailsonality 1、档 3 另 1.000 档礼物 5（AC6.1）。 */
    static List<CreditGrant> creditGrants(StarterPackTier tier) {
        List<CreditGrant> out = new ArrayList<>();
        for (CreditType t : CreditType.ktpTemplates()) {
            out.add(new CreditGrant(t, 1));
        }
        out.add(new CreditGrant(CreditType.TAILSONALITY, 1));
        if (tier.giftCredits() > 0) {
            out.add(new CreditGrant(CreditType.GIFT_1K, tier.giftCredits()));
        }
        return out;
    }

    /** 一类次数。 */
    record CreditGrant(CreditType type, int quantity) {
    }

    /** 发放（见类注释）；购买不存在 / 非 PAID → 记 WARN 不发。 */
    @Transactional
    public void fulfill(long purchaseId) {
        StarterPackPurchase p = purchases.findByIdForUpdate(purchaseId).orElse(null);
        if (p == null) {
            log.warn("starter pack fulfill without purchase purchaseId={}", purchaseId);
            return;
        }
        if (p.getFulfilledAt() != null) {
            return; // 重放幂等
        }
        if (p.getStatus() != StarterPackPurchaseStatus.PAID) {
            log.warn("starter pack fulfill on unpaid purchase purchaseId={} status={}", purchaseId, p.getStatus());
            return;
        }
        long userId = p.getUserId();
        StarterPackTier tier = p.tierEnum();
        Instant creditsExpireAt = null;
        for (CreditGrant g : creditGrants(tier)) {
            GrantResult r = credits.grant(new GrantCommand(userId, g.type(), g.quantity(), CreditSource.PACK,
                    String.valueOf(purchaseId), CREDIT_KEY_PREFIX + purchaseId, null, null, null, null));
            if (creditsExpireAt == null) {
                creditsExpireAt = r.expiresAt();
            }
        }
        SlotResult slot = tier.hasSlot() ? grantSlot(p) : new SlotResult(StarterPackSlotOutcome.NONE, null, null);
        Instant now = clock.instant();
        p.markFulfilled(now, creditsExpireAt, slot.outcome(), slot.rentalId(), slot.expiresAt());
        purchases.saveAndFlush(p);
        log.info("starter pack fulfilled userId={} purchaseId={} tier={} slot={}", userId, purchaseId,
                tier.number(), slot.outcome());
        events.publishEvent(new StarterPackFulfilledEvent(userId, tier.number(), p.getPayChannel().name(),
                p.getPurchaseIndex() == null ? 0 : p.getPurchaseIndex()));
    }

    /** 名额发放结果。 */
    record SlotResult(StarterPackSlotOutcome outcome, Long rentalId, Instant expiresAt) {
    }

    /**
     * 名额（AC6.2 · D-23）：账号锁内 → 已有 {@code PACK_SLOT:{id}} 行（重放）→ 照原结果；名额 &lt; 5 → 新建
     * {@code PACK_GRANT}；否则有生效租用名额 → 最早到期的 +30 天（{@code PACK_EXTEND}）；都没有 → SKIPPED + WARN。
     */
    private SlotResult grantSlot(StarterPackPurchase p) {
        long userId = p.getUserId();
        long purchaseId = p.getId();
        String key = SLOT_KEY_PREFIX + purchaseId;
        slotLock.lockUser(userId);
        Optional<PetSlotPurchase> prior = slotPurchases.findByIdemKey(key);
        if (prior.isPresent()) {
            return replaySlot(prior.get());
        }
        Instant now = clock.instant();
        if (slots.quotaOf(userId).total() < PetSlotService.MAX_PETS) {
            PetSlotPurchase row = slotPurchases.saveAndFlush(PetSlotPurchase.pack(slotTokens.generate(), userId,
                    PetSlotPurchaseKind.PACK_GRANT, key, purchaseId, null, now));
            PetSlotGranter.Result result = granter.grant(row, now);
            if (!result.granted() || result.rentalIds().isEmpty()) {
                // 锁内已按 quotaOf 判过封顶，正常不可达；抛错让本次发放整体回滚、交重试任务。
                throw new IllegalStateException("pack slot grant over cap purchaseId=" + purchaseId);
            }
            row.settle(PetSlotPurchaseStatus.PAID, now);
            row.setRentalIds(result.rentalIds());
            slotPurchases.save(row);
            slotState.reconcile(userId, result.trigger());
            return new SlotResult(StarterPackSlotOutcome.GRANTED, result.rentalIds().get(0),
                    now.plus(Duration.ofDays(PetSlotPurchase.RENTAL_DAYS)));
        }
        Optional<PetSlotRental> earliest = rentals
                .findFirstByUserIdAndStatusAndExpiresAtAfterOrderByExpiresAtAscIdAsc(userId,
                        PetSlotRentalStatus.ACTIVE, now);
        if (earliest.isPresent()) {
            PetSlotRental r = earliest.get();
            PetSlotPurchase row = slotPurchases.saveAndFlush(PetSlotPurchase.pack(slotTokens.generate(), userId,
                    PetSlotPurchaseKind.PACK_EXTEND, key, purchaseId, List.of(r.getId()), now));
            if (granter.extend(userId, r.getId(), PetSlotPurchase.RENTAL_DAYS) != 1) {
                throw new IllegalStateException("pack slot extend affected no row purchaseId=" + purchaseId);
            }
            row.settle(PetSlotPurchaseStatus.PAID, now);
            slotPurchases.save(row);
            return new SlotResult(StarterPackSlotOutcome.EXTENDED, r.getId(),
                    r.getExpiresAt().plus(Duration.ofDays(PetSlotPurchase.RENTAL_DAYS)));
        }
        // 待拍板 Q-10：下单时已拦，只有「下单后升级」的竞态会到这里；次数照发，名额交人工。
        log.warn("starter pack slot skipped (slots full, no rental) userId={} purchaseId={}", userId, purchaseId);
        return new SlotResult(StarterPackSlotOutcome.SKIPPED, null, null);
    }

    private SlotResult replaySlot(PetSlotPurchase row) {
        List<Long> ids = row.rentalIdList();
        Long rentalId = ids.isEmpty() ? null : ids.get(0);
        Instant expiresAt = rentalId == null ? null : rentals.findById(rentalId).map(PetSlotRental::getExpiresAt)
                .orElse(null);
        StarterPackSlotOutcome outcome = row.getKind() == PetSlotPurchaseKind.PACK_EXTEND
                ? StarterPackSlotOutcome.EXTENDED : StarterPackSlotOutcome.GRANTED;
        return new SlotResult(outcome, rentalId, expiresAt);
    }
}
