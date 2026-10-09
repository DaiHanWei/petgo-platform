package com.tailtopia.purchase.service;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.purchase.domain.GrantOutcome;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakePurchaseStatus;
import com.tailtopia.purchase.domain.KeepsakeRef;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.dto.KeepsakePurchaseResponse;
import com.tailtopia.purchase.event.KeepsakeUnlockedEvent;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PayException;
import com.tailtopia.shared.pay.PaymentGateway;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 一次性解锁发起购买（V1.3.2 Story 3.1 · AC4 · AD-1）。SKU 模块在加锁读业务行之后调 {@link #start}。
 *
 * <h2>🔴 幂等键按渠道加后缀</h2>
 * 基键 {@code {sku}:{refToken}}，PawCoin 用 {@code :PAWCOIN}、QRIS 用 {@code :QRIS}。{@code IdempotencyService} 是全局
 * {@code idem:} 命名空间：同一串给 {@code createIntent} 与 {@code debit}，「先开过 QRIS 再改用 PawCoin」会被 {@code debit}
 * 判成重放、不扣币，业务行却被解锁 = 免费拿到。
 *
 * <p>价格每次发起按 SKU 实时读 {@code pricing_config}；改价只影响新发起，已有 PENDING 行价不变。
 * SKU 模块给了 {@link KeepsakeRef#priceOverride()}（补差价）则用它，幂等键另加 {@code :UPG} 后缀 —— 否则会复用
 * 补差前按原价开出的 QRIS 意图，让用户照原价付。
 */
@Service
public class KeepsakePurchaseService {

    static final Duration PAY_WINDOW = Duration.ofMinutes(60);
    private static final String CURRENCY = "IDR";

    private final KeepsakePurchaseRepository purchases;
    private final PaymentIntentService paymentIntents;
    private final PawCoinWalletService wallet;
    private final PlatformConfigService platformConfig;
    private final PaymentGateway gateway;
    private final KeepsakeGrantRunner grantRunner;
    private final KeepsakeTokenGenerator tokens;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Autowired
    public KeepsakePurchaseService(KeepsakePurchaseRepository purchases, PaymentIntentService paymentIntents,
            PawCoinWalletService wallet, PlatformConfigService platformConfig, PaymentGateway gateway,
            KeepsakeGrantRunner grantRunner, KeepsakeTokenGenerator tokens, ApplicationEventPublisher events) {
        this(purchases, paymentIntents, wallet, platformConfig, gateway, grantRunner, tokens, events, Clock.systemUTC());
    }

    KeepsakePurchaseService(KeepsakePurchaseRepository purchases, PaymentIntentService paymentIntents,
            PawCoinWalletService wallet, PlatformConfigService platformConfig, PaymentGateway gateway,
            KeepsakeGrantRunner grantRunner, KeepsakeTokenGenerator tokens, ApplicationEventPublisher events,
            Clock clock) {
        this.purchases = purchases;
        this.paymentIntents = paymentIntents;
        this.wallet = wallet;
        this.platformConfig = platformConfig;
        this.gateway = gateway;
        this.grantRunner = grantRunner;
        this.tokens = tokens;
        this.events = events;
        this.clock = clock;
    }

    /** 幂等键：基键 + 渠道后缀（见类注释）。 */
    static String idempotencyKey(KeepsakeRef ref, PayChannel channel) {
        String base = ref.sku().name() + ":" + ref.refToken() + ":" + channel.name();
        return ref.priceOverride() == null ? base : base + ":UPG";
    }

    /** 本次成交价：SKU 模块给的补差价优先，否则实时读定价表。 */
    private long priceFor(KeepsakeRef ref) {
        return ref.priceOverride() != null ? ref.priceOverride() : priceOf(platformConfig.pricing(), ref.sku());
    }

    /** 按 SKU 取当前价（每次发起实时读）。 */
    static long priceOf(PricingConfig p, KeepsakeSku sku) {
        return switch (sku) {
            case TAILSONALITY -> p.getTailsonalityUnlockPrice();
            case PASSPORT_SNAP -> p.getPassportPageUnlockPrice();
            case BOARDING_PASS -> p.getPassportBoardingUnlockPrice();
            case TS_MATCH -> p.getTailsonalityMatchUnlockPrice();
        };
    }

    // noRollbackFor：GemPay 下单失败时 failChargeAttempt 已把意图置 FAILED，这一行必须随事务提交留档
    // （request_id 可对账、重试走新单）；整笔回滚正是 2026-09-21 超时事故里 request_id 全丢的原因。
    @Transactional(noRollbackFor = PayException.class)
    public KeepsakePurchaseResponse start(long userId, KeepsakeRef ref, PayChannel channel) {
        if (ref.alreadyUnlocked()) {
            throw AppException.keepsakeAlreadyUnlocked("已解锁，无需再次购买");
        }
        return switch (channel) {
            case PAWCOIN -> startPawcoin(userId, ref);
            case QRIS -> startQris(userId, ref);
            // 🔴 虚拟商品【恒单渠道】（AD-3）：MIXED 只用于电商实物订单。显式拒绝，不落 default。
            case MIXED -> throw AppException.validation("该商品不支持混合支付");
        };
    }

    private KeepsakePurchaseResponse startPawcoin(long userId, KeepsakeRef ref) {
        long price = priceFor(ref);
        // 余额不足 → debit 抛 pawcoin-insufficient（409）→ 整事务回滚。
        wallet.debit(userId, price, PawCoinTxnType.SPEND, ref.sku().name(), ref.refId(),
                idempotencyKey(ref, PayChannel.PAWCOIN));
        KeepsakePurchase row = purchases.saveAndFlush(
                KeepsakePurchase.paidPawcoin(tokens.generate(), ref, userId, price, Instant.now(clock)));
        GrantOutcome outcome = grantRunner.grantOrNull(row);
        if (outcome == null || outcome == GrantOutcome.REF_MISSING) {
            // PawCoin 是同步成交：没发出去就整笔回滚（扣币、购买行一起撤销），不留「扣了币、没解锁、却回复成功」的单。
            // （QRIS 到账不能回滚，那条路径才记 ORPHAN_PAID 交人工。）
            throw AppException.conflict("解锁未完成，本次未扣费，请重试");
        }
        row.applyGrantOutcome(outcome); // GRANTED → PAID；ALREADY_UNLOCKED → DUPLICATE_PAID（确已解锁）
        purchases.save(row);
        if (outcome == GrantOutcome.GRANTED) {
            events.publishEvent(new KeepsakeUnlockedEvent(userId, ref.sku(), ref.refId(), price, PayChannel.PAWCOIN));
        }
        return KeepsakePurchaseResponse.unlocked(row.getPublicToken());
    }

    private KeepsakePurchaseResponse startQris(long userId, KeepsakeRef ref) {
        long price = priceFor(ref);
        PaymentIntentResponse intentResp = paymentIntents.createIntent(userId, ref.sku().toPurpose(), PayChannel.QRIS,
                price, CURRENCY, idempotencyKey(ref, PayChannel.QRIS), PAY_WINDOW);
        PaymentIntent intent = paymentIntents.findByToken(intentResp.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));

        if (intent.getStatus() == PaymentStatus.PAID) {
            // createIntent 对 PAID 直接返回既有意图：这笔已付过。发放以购买行为准 —— 已发放 → 已解锁；否则交人工。
            return purchases.findByPaymentIntentId(intent.getId())
                    .filter(p -> p.getStatus() == KeepsakePurchaseStatus.PAID)
                    .map(p -> KeepsakePurchaseResponse.unlocked(p.getPublicToken()))
                    .orElseThrow(() -> AppException.keepsakeAlreadyUnlocked("该商品已付款，正在处理"));
        }

        KeepsakePurchase row = purchases.findFirstBySkuAndRefIdAndStatus(ref.sku(), ref.refId(),
                KeepsakePurchaseStatus.PENDING).orElse(null);
        if (row != null && !Long.valueOf(intent.getId()).equals(row.getPaymentIntentId())) {
            // 旧意图已过窗被 createIntent 懒过期（不发失败事件）→ 旧行跟着置 EXPIRED，再建新行。
            row.markStatus(KeepsakePurchaseStatus.EXPIRED);
            purchases.saveAndFlush(row);
            row = null;
        }
        if (row == null) {
            row = purchases.saveAndFlush(KeepsakePurchase.pendingQris(tokens.generate(), ref, userId,
                    intent.getAmount(), intent.getId(), Instant.now(clock)));
        }
        String payload = ensureCharge(intent);
        return KeepsakePurchaseResponse.paymentRequired(PaymentIntentResponse.of(intent), payload,
                row.getPublicToken());
    }

    /**
     * 首次下单向网关取二维码载荷；幂等重放返回既有载荷。同 {@code IdCardHdService.ensureCharge} 的逻辑
     * （那边不改，这里是 purchase 包私有副本）。失败时 {@code failChargeAttempt} 留档后原样抛出。
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
}
