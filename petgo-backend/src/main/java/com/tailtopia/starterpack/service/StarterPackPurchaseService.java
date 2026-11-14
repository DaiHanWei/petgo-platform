package com.tailtopia.starterpack.service;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.starterpack.domain.StarterPackPurchase;
import com.tailtopia.starterpack.domain.StarterPackPurchaseStatus;
import com.tailtopia.starterpack.domain.StarterPackSlotOutcome;
import com.tailtopia.starterpack.domain.StarterPackSlotState;
import com.tailtopia.starterpack.domain.StarterPackTier;
import com.tailtopia.starterpack.dto.StarterPackPurchaseRequest;
import com.tailtopia.starterpack.dto.StarterPackPurchaseResponse;
import com.tailtopia.starterpack.dto.StarterPackPurchaseStatusResponse;
import com.tailtopia.starterpack.dto.StarterPackView;
import com.tailtopia.starterpack.repository.StarterPackPurchaseRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 超值礼包查询 / 下单 / 状态（V1.5.0 gift-pack Story 4-2 · AC3 / AC4 / AC7 · D-20 ~ D-24）。
 *
 * <p>下单：同一用户 {@code pg_advisory_xact_lock(hashtext('starter_pack:' || userId))} 串行；幂等键
 * {@code STARTER_PACK:{userId}:{requestId}} 已有单 → 不再校验、按当前状态返回；否则按 D-22 顺序校验
 * （停售 → 虚拟号 → 未过窗 PENDING → 冷却 → 档 2 / 3 名额 FULL）。
 * <ul>
 *   <li>PawCoin：同一事务 扣币 → PAID → 发放 → 返回 PAID；任何异常整笔回滚（不扣钱、不留单）。</li>
 *   <li>QRIS：建意图（60 分钟窗）→ 出码；出码失败 → 购买 FAILED、返回 FAILED（不 500）。到账由
 *       {@code StarterPackPaidHandler} 置 PAID，发放由 {@code StarterPackFulfillmentListener} 执行。</li>
 * </ul>
 * 不退款：本服务不提供任何退款路径。日志只记 userId / purchaseId / tier / channel。
 */
@Service
public class StarterPackPurchaseService {

    /** QRIS 付款窗。 */
    static final Duration PAY_WINDOW = Duration.ofMinutes(60);
    /** PawCoin 流水 {@code refType}（D-20）；{@code refId} = 购买 id。 */
    public static final String COIN_REF_TYPE = "STARTER_PACK";
    /** 客户端 {@code requestId} 最大长度。 */
    static final int MAX_REQUEST_ID = 64;
    private static final String CURRENCY = "IDR";
    private static final String LOCK_SQL = "SELECT pg_advisory_xact_lock(hashtext(:k))";
    private static final Logger log = LoggerFactory.getLogger(StarterPackPurchaseService.class);

    private final StarterPackPurchaseRepository purchases;
    private final StarterPackFulfillmentService fulfillment;
    private final StarterPackSlotPetNameResolver petNames;
    private final StarterPackTokenGenerator tokens;
    private final PlatformConfigService platformConfig;
    private final AccountQueryService accounts;
    private final PawCoinWalletService wallet;
    private final PaymentIntentService paymentIntents;
    private final PaymentGateway gateway;
    private final PetSlotService slots;
    private final NamedParameterJdbcTemplate jdbc;
    private final Clock clock;

    @PersistenceContext
    private EntityManager entityManager;

    @Autowired
    public StarterPackPurchaseService(StarterPackPurchaseRepository purchases, StarterPackFulfillmentService fulfillment,
            StarterPackSlotPetNameResolver petNames, StarterPackTokenGenerator tokens,
            PlatformConfigService platformConfig, AccountQueryService accounts, PawCoinWalletService wallet,
            PaymentIntentService paymentIntents, PaymentGateway gateway, PetSlotService slots,
            NamedParameterJdbcTemplate jdbc) {
        this(purchases, fulfillment, petNames, tokens, platformConfig, accounts, wallet, paymentIntents, gateway, slots,
                jdbc, Clock.systemUTC());
    }

    StarterPackPurchaseService(StarterPackPurchaseRepository purchases, StarterPackFulfillmentService fulfillment,
            StarterPackSlotPetNameResolver petNames, StarterPackTokenGenerator tokens,
            PlatformConfigService platformConfig, AccountQueryService accounts, PawCoinWalletService wallet,
            PaymentIntentService paymentIntents, PaymentGateway gateway, PetSlotService slots,
            NamedParameterJdbcTemplate jdbc, Clock clock) {
        this.purchases = purchases;
        this.fulfillment = fulfillment;
        this.petNames = petNames;
        this.tokens = tokens;
        this.platformConfig = platformConfig;
        this.accounts = accounts;
        this.wallet = wallet;
        this.paymentIntents = paymentIntents;
        this.gateway = gateway;
        this.slots = slots;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** 幂等键（D-22）。 */
    static String idemKey(long userId, String requestId) {
        return "STARTER_PACK:" + userId + ":" + requestId.trim();
    }

    // ── 查询 ───────────────────────────────────────────────────────────────

    /** {@code GET /api/v1/starter-pack}：过窗的 PENDING 就地置 EXPIRED（写库，故非只读事务）。 */
    @Transactional
    public StarterPackView view(long userId) {
        Instant now = clock.instant();
        PricingConfig pricing = platformConfig.pricing();
        StarterPackView.Pending pending = openPending(userId, now)
                .map(p -> new StarterPackView.Pending(p.getPublicToken(), p.getTier(), p.getPrice(),
                        p.getPayExpiresAt()))
                .orElse(null);
        Instant lastPaidAt = purchases.findFirstByUserIdAndStatusOrderByPaidAtDesc(userId,
                StarterPackPurchaseStatus.PAID).map(StarterPackPurchase::getPaidAt).orElse(null);
        return StarterPackViewAssembler.assemble(pricing, accounts.isVirtual(userId), wallet.balanceOf(userId),
                slots.quotaOf(userId), lastPaidAt, pending, now);
    }

    // ── 下单 ───────────────────────────────────────────────────────────────

    // noRollbackFor：出码失败时 failChargeAttempt 已把意图置 FAILED、购买置 FAILED，须随事务提交留档（同 GiftSendService）。
    @Transactional(noRollbackFor = PayException.class)
    public StarterPackPurchaseResponse purchase(long userId, StarterPackPurchaseRequest req) {
        StarterPackTier tier = StarterPackTier.of(req == null ? null : req.tier())
                .orElseThrow(() -> AppException.validation("未知的礼包档位"));
        PayChannel channel = req.channel();
        if (channel != PayChannel.PAWCOIN && channel != PayChannel.QRIS) {
            throw AppException.validation("礼包只支持 PawCoin 或 QRIS");
        }
        String requestId = req.requestId() == null ? "" : req.requestId().trim();
        if (requestId.isEmpty() || requestId.length() > MAX_REQUEST_ID) {
            throw AppException.validation("requestId 为空或超长");
        }
        lockUser(userId);
        String key = idemKey(userId, requestId);
        Optional<StarterPackPurchase> existing = purchases.findByIdemKey(key);
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        Instant now = clock.instant();
        PricingConfig pricing = platformConfig.pricing();
        // ① 停售
        if (!pricing.isStarterPackEnabled()) {
            throw AppException.starterPackDisabled("礼包已停售");
        }
        // ⑤ 虚拟号（AC4.2 顺序：紧随停售）
        if (accounts.isVirtual(userId)) {
            throw AppException.forbidden("该账号不能购买礼包");
        }
        // ② 未过窗 PENDING（过窗的就地置 EXPIRED 后继续）
        Optional<StarterPackPurchase> open = openPending(userId, now);
        if (open.isPresent()) {
            throw AppException.starterPackPending(open.get().getPublicToken());
        }
        // ③ 冷却
        Instant until = StarterPackViewAssembler.cooldownUntil(purchases.findFirstByUserIdAndStatusOrderByPaidAtDesc(
                userId, StarterPackPurchaseStatus.PAID).map(StarterPackPurchase::getPaidAt).orElse(null), now);
        if (until != null) {
            throw AppException.starterPackCooldown(until);
        }
        // ④ 档 2 / 3 名额 FULL
        if (tier.hasSlot()
                && StarterPackViewAssembler.slotState(slots.quotaOf(userId)) == StarterPackSlotState.FULL) {
            throw AppException.starterPackSlotsFull("宠物名额已满，无法购买含名额的礼包");
        }

        StarterPackPurchase p = purchases.saveAndFlush(StarterPackPurchase.pending(tokens.generate(), userId, tier,
                tier.price(pricing), tier.separateTotal(pricing), channel, key, now));
        log.info("starter pack order created userId={} purchaseId={} tier={} channel={}", userId, p.getId(),
                tier.number(), channel);
        return channel == PayChannel.PAWCOIN ? payWithCoins(p, now) : payWithQris(p);
    }

    /**
     * 并发同键撞 {@code uq_starter_pack_purchases_idem_key} 后的新事务重读（控制器调用，照 {@code GiftController}）：
     * 按已有单返回；查不到 → 空，由调用方原样抛出原异常。
     */
    @Transactional(noRollbackFor = PayException.class)
    public Optional<StarterPackPurchaseResponse> replayExisting(long userId, String requestId) {
        if (requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return purchases.findByIdemKey(idemKey(userId, requestId)).map(this::replay);
    }

    private StarterPackPurchaseResponse payWithCoins(StarterPackPurchase p, Instant now) {
        // 余额不足 → debit 抛 pawcoin-insufficient（409）→ 整事务回滚（购买行一起撤销）。
        wallet.debit(p.getUserId(), p.getPrice(), PawCoinTxnType.SPEND, COIN_REF_TYPE, p.getId(),
                "STARTER_PACK_COIN:" + p.getPublicToken());
        p.markPaid(now, nextPurchaseIndex(p.getUserId()));
        purchases.saveAndFlush(p);
        fulfillment.fulfill(p.getId());
        return StarterPackPurchaseResponse.ofStatus(StarterPackPurchaseStatus.PAID.name(), p.getPublicToken());
    }

    private StarterPackPurchaseResponse payWithQris(StarterPackPurchase p) {
        PaymentIntentResponse resp = paymentIntents.createIntent(p.getUserId(), PaymentPurpose.STARTER_PACK,
                PayChannel.QRIS, p.getPrice(), CURRENCY, "STARTER_PACK_QRIS:" + p.getPublicToken(), PAY_WINDOW);
        PaymentIntent intent = paymentIntents.findByToken(resp.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        p.attachPaymentIntent(intent.getId(), intent.getExpiresAt());
        purchases.saveAndFlush(p);
        return chargeOrFailed(p, intent);
    }

    /** 幂等重放：不再校验、不再扣款；PENDING 带回同一意图与二维码。 */
    private StarterPackPurchaseResponse replay(StarterPackPurchase p) {
        if (p.isPending()) {
            return replayPending(p);
        }
        return StarterPackPurchaseResponse.ofStatus(p.getStatus().name(), p.getPublicToken());
    }

    private StarterPackPurchaseResponse replayPending(StarterPackPurchase p) {
        PaymentIntent intent = intentOf(p);
        if (intent == null) {
            log.warn("starter pack pending without intent purchaseId={}", p.getId());
            if (lockAndRefresh(p)) {
                p.markExpired();
                purchases.saveAndFlush(p);
            }
            return StarterPackPurchaseResponse.ofStatus(p.getStatus().name(), p.getPublicToken());
        }
        PaymentStatus status = paymentIntents.statusOf(p.getUserId(), intent.getPublicToken());
        switch (status) {
            case PENDING -> {
                return chargeOrFailed(p, intent);
            }
            case PAID -> {
                // 已到账：置 PAID 与发放由到账监听完成，这里只回报当前状态。
                return new StarterPackPurchaseResponse(p.getStatus().name(), p.getPublicToken(),
                        PaymentIntentResponse.of(intent), null);
            }
            case EXPIRED, FAILED -> {
                // 锁行重读后再关单：并发到账已置 PAID 时不得覆盖（4-2 复审 #1）
                if (lockAndRefresh(p)) {
                    if (status == PaymentStatus.FAILED) {
                        p.markFailed();
                    } else {
                        p.markExpired();
                    }
                    purchases.saveAndFlush(p);
                }
                return StarterPackPurchaseResponse.ofStatus(p.getStatus().name(), p.getPublicToken());
            }
            default -> throw new IllegalStateException("未知支付状态 " + status);
        }
    }

    /** 出码；网关下单失败且意图已被置 FAILED → 购买 FAILED、返回 FAILED（不 500，照 {@code GiftSendService}）。 */
    private StarterPackPurchaseResponse chargeOrFailed(StarterPackPurchase p, PaymentIntent intent) {
        String payload;
        try {
            payload = ensureCharge(intent);
        } catch (PayException e) {
            if (intent.getStatus() != PaymentStatus.FAILED) {
                throw e;
            }
            if (lockAndRefresh(p)) {
                p.markFailed();
                purchases.saveAndFlush(p);
            }
            // 只记异常类名：网关异常消息可能带返回体（4-2 复审 #6）
            log.warn("starter pack qris charge failed purchaseId={} intentId={} cause={}", p.getId(),
                    intent.getId(), e.getClass().getSimpleName());
            return StarterPackPurchaseResponse.ofStatus(p.getStatus().name(), p.getPublicToken());
        }
        return new StarterPackPurchaseResponse(p.getStatus().name(), p.getPublicToken(),
                PaymentIntentResponse.of(intent), payload);
    }

    /**
     * 首次向网关取二维码载荷；已出码返回既有载荷。{@code GiftSendService.ensureCharge} 的 starterpack 包私有副本。
     */
    private String ensureCharge(PaymentIntent entity) {
        if (entity.getGatewayRef() == null) {
            ChargeResult charge;
            try {
                charge = gateway.createCharge(new ChargeRequest(entity.getPublicToken(), entity.getAmount(), CURRENCY,
                        PayChannel.QRIS.name(), entity.getPurpose().name()));
            } catch (RuntimeException e) {
                paymentIntents.failChargeAttempt(entity.getPublicToken());
                throw e;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            if (charge.rawMeta() != null) {
                meta.putAll(charge.rawMeta());
            }
            if (charge.payload() != null) {
                meta.put("payload", charge.payload());
            }
            paymentIntents.attachCharge(entity.getPublicToken(), charge.gatewayRef(), meta);
            return charge.payload();
        }
        Map<String, Object> savedMeta = entity.getGatewayMeta();
        return savedMeta == null ? null : (String) savedMeta.get("payload");
    }

    // ── 状态 ───────────────────────────────────────────────────────────────

    /** {@code GET /api/v1/me/starter-pack-purchases/{token}}（AC7）：非本人 → 404；过窗 PENDING 就地 EXPIRED。 */
    @Transactional(noRollbackFor = PayException.class)
    public StarterPackPurchaseStatusResponse status(long userId, String token) {
        StarterPackPurchase p = purchases.findByPublicTokenAndUserId(token, userId)
                .orElseThrow(() -> AppException.notFound("礼包订单不存在"));
        PaymentIntentResponse intent = null;
        String qrPayload = null;
        if (p.isPending()) {
            if (p.isPendingPastWindow(clock.instant())) {
                expirePending(p);
            } else {
                StarterPackPurchaseResponse r = replayPending(p);
                intent = r.intent();
                qrPayload = r.qrPayload();
            }
        }
        StarterPackPurchaseStatusResponse.Slot slot = slotOf(p);
        return new StarterPackPurchaseStatusResponse(p.getPublicToken(), p.getTier(), p.getPrice(),
                p.getPayChannel().name(), p.getStatus().name(), p.getFulfilledAt() != null, p.getPaidAt(),
                p.getCreditsExpireAt(), slot, p.tierEnum().contents(),
                p.isPending() ? intent : null, p.isPending() ? qrPayload : null);
    }

    private StarterPackPurchaseStatusResponse.Slot slotOf(StarterPackPurchase p) {
        StarterPackSlotOutcome outcome = p.getSlotOutcome();
        if (outcome == null || outcome == StarterPackSlotOutcome.NONE) {
            return null;
        }
        String petName = outcome == StarterPackSlotOutcome.EXTENDED ? petNames.resolve(p.getUserId()) : null;
        return new StarterPackPurchaseStatusResponse.Slot(outcome, p.getSlotExpiresAt(), petName);
    }

    // ── 内部 ───────────────────────────────────────────────────────────────

    private void lockUser(long userId) {
        jdbc.query(LOCK_SQL, new MapSqlParameterSource("k", "starter_pack:" + userId),
                (RowCallbackHandler) rs -> { });
    }

    /** 未过窗的 PENDING；过窗的就地置 EXPIRED（并让意图走自己的过期）后视为没有。 */
    private Optional<StarterPackPurchase> openPending(long userId, Instant now) {
        Optional<StarterPackPurchase> pending = purchases.findFirstByUserIdAndStatus(userId,
                StarterPackPurchaseStatus.PENDING);
        if (pending.isPresent() && pending.get().isPendingPastWindow(now)) {
            expirePending(pending.get());
            return Optional.empty();
        }
        return pending;
    }

    /** 过窗 PENDING → EXPIRED；意图经 {@code statusOf} 走支付模块自己的懒过期（不绕过意图状态机）。 */
    private void expirePending(StarterPackPurchase p) {
        if (!lockAndRefresh(p)) {
            return; // 并发到账已改状态（如 PAID）：不覆盖（4-2 复审 #1）
        }
        p.markExpired();
        purchases.saveAndFlush(p);
        PaymentIntent intent = intentOf(p);
        if (intent != null) {
            paymentIntents.statusOf(p.getUserId(), intent.getPublicToken());
        }
    }

    /**
     * 关单前锁行并按库内现值刷新实体（{@code SELECT … FOR UPDATE}）：实体无 {@code @Version}，按旧快照整行写回会把并发到账写入的
     * PAID / paid_at 覆盖成 EXPIRED（收了钱不发货，4-2 复审 #1）。返回刷新后是否仍 PENDING。
     */
    private boolean lockAndRefresh(StarterPackPurchase p) {
        entityManager.refresh(p, LockModeType.PESSIMISTIC_WRITE);
        return p.isPending();
    }

    private PaymentIntent intentOf(StarterPackPurchase p) {
        return p.getPaymentIntentId() == null ? null
                : paymentIntents.findById(p.getPaymentIntentId()).orElse(null);
    }

    /** 第几次成功购买（含本次）：PAID 前在用户锁内数。 */
    private int nextPurchaseIndex(long userId) {
        return (int) purchases.countByUserIdAndStatus(userId, StarterPackPurchaseStatus.PAID) + 1;
    }
}
