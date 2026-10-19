package com.tailtopia.gift.service;

import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.gift.domain.GiftCatalogItem;
import com.tailtopia.gift.domain.GiftKind;
import com.tailtopia.gift.domain.GiftOrder;
import com.tailtopia.gift.domain.GiftOrderStatus;
import com.tailtopia.gift.domain.GiftSource;
import com.tailtopia.gift.dto.GiftSendRequest;
import com.tailtopia.gift.dto.GiftSendResponse;
import com.tailtopia.gift.port.GiftPetDirectory.GiftPet;
import com.tailtopia.gift.repository.GiftCatalogRepository;
import com.tailtopia.gift.repository.GiftOrderRepository;
import com.tailtopia.growth.domain.GrowthLevel;
import com.tailtopia.growth.service.GrowthQueryService;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.dto.PaymentIntentResponse;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
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
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 发起送礼（V1.4.0 gift Story 1.1 · AC5 ~ AC7 · AD-6）：{@code POST /api/v1/pets/{petId}/gifts}。
 *
 * <h2>校验顺序（全部在任何写库之前，失败即抛，不留任何行）</h2>
 * ① 宠物可送达（{@link GiftRecipientGuard}：不存在 / 主人注销 404，封号放行）→ ② 非自己的宠物 422 →
 * ③ 送礼人未拉黑主人 403（只拦单向）→ ④ 礼物 ONLINE，否则 409 → ⑤ 专属礼物等级（{@link GrowthQueryService}）403 →
 * ⑥ 数量 1~99 → ⑦ 渠道（MIXED 显式拒绝）→ ⑧ QRIS 总额 ≥ {@code pricing.giftQrisMinAmount} 422 → ⑨ {@code source} 三值。
 *
 * <h2>幂等</h2>
 * 订单 {@code idem_key = GiftIdemKeys.send(sender, requestId)}。已有订单 → <b>不再校验、不再扣款</b>，按订单当前状态返回
 * （QRIS PENDING 重走出码取回同一意图与二维码）；并发同键插入撞唯一约束由控制器捕获后经 {@link #replayExisting} 新事务重读。
 *
 * <h2>两个渠道</h2>
 * <ul>
 *   <li>PawCoin：同一事务 插订单 → {@code wallet.debit(SPEND, "GIFT", orderId, GIFT_COIN:{token})} →
 *       {@link GiftDeliveryService#deliver}；余额不足 409 {@code pawcoin-insufficient} 整事务回滚（订单行不留）。
 *       只对送礼人扣币，收礼方不入任何币。</li>
 *   <li>QRIS：插订单 → {@code createIntent(GIFT, QRIS, total, GIFT_QRIS:{token}, PAY_WINDOW)} → 回填意图 id →
 *       {@link #ensureCharge}；到账由 {@code GiftPaidHandler} 交付。</li>
 * </ul>
 */
@Service
public class GiftSendService {

    /** QRIS 付款窗（照 Keepsake）。 */
    public static final Duration PAY_WINDOW = Duration.ofMinutes(60);

    /**
     * 扣币流水 {@code refType}：必须是字面量 {@code "GIFT"}（= {@code PaymentPurpose.GIFT.name()}）——
     * ① 的消费返成长值监听按 refType 排除送礼，写错会被重复计成长值。
     */
    static final String COIN_REF_TYPE = "GIFT";

    static final int MIN_QUANTITY = 1;
    static final int MAX_QUANTITY = 99;
    private static final String CURRENCY = "IDR";

    private final GiftOrderRepository orders;
    private final GiftCatalogRepository catalog;
    private final GiftRecipientGuard recipients;
    private final GrowthQueryService growth;
    private final PlatformConfigService platformConfig;
    private final PawCoinWalletService wallet;
    private final PaymentIntentService paymentIntents;
    private final PaymentGateway gateway;
    private final GiftDeliveryService delivery;
    private final GiftTokenGenerator tokens;
    private final Clock clock;

    @Autowired
    public GiftSendService(GiftOrderRepository orders, GiftCatalogRepository catalog, GiftRecipientGuard recipients,
            GrowthQueryService growth, PlatformConfigService platformConfig, PawCoinWalletService wallet,
            PaymentIntentService paymentIntents, PaymentGateway gateway, GiftDeliveryService delivery,
            GiftTokenGenerator tokens) {
        this(orders, catalog, recipients, growth, platformConfig, wallet, paymentIntents, gateway, delivery, tokens,
                Clock.systemUTC());
    }

    GiftSendService(GiftOrderRepository orders, GiftCatalogRepository catalog, GiftRecipientGuard recipients,
            GrowthQueryService growth, PlatformConfigService platformConfig, PawCoinWalletService wallet,
            PaymentIntentService paymentIntents, PaymentGateway gateway, GiftDeliveryService delivery,
            GiftTokenGenerator tokens, Clock clock) {
        this.orders = orders;
        this.catalog = catalog;
        this.recipients = recipients;
        this.growth = growth;
        this.platformConfig = platformConfig;
        this.wallet = wallet;
        this.paymentIntents = paymentIntents;
        this.gateway = gateway;
        this.delivery = delivery;
        this.tokens = tokens;
        this.clock = clock;
    }

    // noRollbackFor：网关下单失败时 failChargeAttempt 已把意图置 FAILED（同步失败监听把订单置 FAILED），
    // 这两行必须随事务提交留档（同 KeepsakePurchaseService.start）。
    @Transactional(noRollbackFor = PayException.class)
    public GiftSendResponse send(long senderId, long petId, GiftSendRequest req) {
        Optional<GiftOrder> existing = orders.findByIdemKey(GiftIdemKeys.send(senderId, req.requestId()));
        if (existing.isPresent()) {
            return replay(existing.get());
        }

        // ① ~ ③ 收礼宠物
        GiftPet pet = recipients.requireRecipient(senderId, petId);
        // ④ 礼物上架
        GiftCatalogItem gift = catalog.findByPublicToken(req.giftToken())
                .filter(GiftCatalogItem::isOnline)
                .orElseThrow(() -> AppException.giftUnavailable("该礼物已下架"));
        // ⑤ 专属礼物等级（只经 GrowthQueryService；不参与成长体系一律不给）
        if (gift.getKind() == GiftKind.EXCLUSIVE) {
            GrowthLevel level = growth.getLevel(senderId);
            if (level == null || !level.participating() || gift.getMinLevel() == null
                    || level.level() < gift.getMinLevel()) {
                throw AppException.giftLocked("等级不足，暂不能送这件礼物");
            }
        }
        // ⑥ 数量
        int quantity = req.quantity() == null ? 0 : req.quantity();
        if (quantity < MIN_QUANTITY || quantity > MAX_QUANTITY) {
            throw AppException.validation("数量须在 1 ~ 99 之间");
        }
        // ⑦ 渠道：🔴 礼物恒单渠道，MIXED 只用于电商实物订单。显式拒绝，不落 default。
        PayChannel channel = req.channel();
        switch (channel) {
            case PAWCOIN, QRIS -> {
            }
            case MIXED -> throw AppException.validation("礼物不支持混合支付");
        }
        // ⑧ QRIS 最低金额（只在这里校验，不进通用 createIntent）
        long total = Math.multiplyExact(gift.getPrice(), (long) quantity);
        if (channel == PayChannel.QRIS && total < platformConfig.pricing().getGiftQrisMinAmount()) {
            throw AppException.giftQrisBelowMin("QRIS 单笔金额不足，请改用 PawCoin");
        }
        // ⑨ 入口
        GiftSource source = GiftSource.fromWire(req.source())
                .orElseThrow(() -> AppException.validation("未知的送礼入口"));

        GiftOrder order = orders.saveAndFlush(GiftOrder.pending(tokens.generate(), senderId, pet.petId(),
                pet.ownerId(), pet.name(), gift, quantity, channel, source,
                GiftIdemKeys.send(senderId, req.requestId()), Instant.now(clock)));
        return switch (channel) {
            case PAWCOIN -> payWithCoins(order);
            case QRIS -> payWithQris(order);
            case MIXED -> throw new IllegalStateException("MIXED 已在校验 ⑦ 拒绝");
        };
    }

    /**
     * 并发同键插入撞 {@code uq_gift_orders_idem_key} 后的新事务重读（控制器调用）：按已有订单返回；
     * 查不到（撞的是别的约束）→ 空，由调用方原样抛出原异常。
     */
    @Transactional(noRollbackFor = PayException.class)
    public Optional<GiftSendResponse> replayExisting(long senderId, String requestId) {
        return orders.findByIdemKey(GiftIdemKeys.send(senderId, requestId)).map(this::replay);
    }

    /** 幂等重放：不再校验、不再扣款。 */
    private GiftSendResponse replay(GiftOrder order) {
        if (order.getStatus() == GiftOrderStatus.PENDING && order.getChannel() == PayChannel.QRIS) {
            return replayQris(order);
        }
        return GiftSendResponse.ofStatus(order.getPublicToken(), order.getStatus());
    }

    private GiftSendResponse payWithCoins(GiftOrder order) {
        // 余额不足 → debit 抛 pawcoin-insufficient（409）→ 整事务回滚（订单行一起撤销）。
        wallet.debit(order.getSenderId(), order.getTotal(), PawCoinTxnType.SPEND, COIN_REF_TYPE, order.getId(),
                GiftIdemKeys.coin(order.getPublicToken()));
        if (delivery.deliver(order) != GiftDeliveryService.Result.DELIVERED) {
            // PawCoin 同步成交：刚校验过可送达，走到这里只可能是并发删宠物 / 拉黑。整笔回滚（扣币、订单一起撤销）。
            throw AppException.conflict("礼物未能送达，本次未扣费，请重试");
        }
        return GiftSendResponse.delivered(order.getPublicToken());
    }

    private GiftSendResponse payWithQris(GiftOrder order) {
        PaymentIntent intent = createQrisIntent(order);
        order.attachPaymentIntent(intent.getId());
        orders.saveAndFlush(order);
        String payload = ensureCharge(intent);
        return GiftSendResponse.paymentRequired(PaymentIntentResponse.of(intent), payload, order.getPublicToken());
    }

    /**
     * 幂等重放取回（AC7.3）：同键再 {@code createIntent}。返回的意图就是订单那笔 → 返回它与已存载荷；
     * 不是（旧意图已过窗被懒过期，且懒过期不发失败事件）→ 订单置 EXPIRED 并返回 EXPIRED，<b>不把新意图挂到旧订单上</b>
     * （App 换新 requestId 重发）。
     */
    private GiftSendResponse replayQris(GiftOrder order) {
        PaymentIntent intent = createQrisIntent(order);
        if (!Long.valueOf(intent.getId()).equals(order.getPaymentIntentId())) {
            order.markExpired();
            orders.saveAndFlush(order);
            return GiftSendResponse.ofStatus(order.getPublicToken(), GiftOrderStatus.EXPIRED);
        }
        if (intent.getStatus() == PaymentStatus.PAID) {
            // 已到账：交付由到账监听在回调事务里完成，这里只回报订单当前状态，不再出码。
            return new GiftSendResponse(false, PaymentIntentResponse.of(intent), null, order.getPublicToken(),
                    order.getStatus().name());
        }
        String payload = ensureCharge(intent);
        return GiftSendResponse.paymentRequired(PaymentIntentResponse.of(intent), payload, order.getPublicToken());
    }

    private PaymentIntent createQrisIntent(GiftOrder order) {
        PaymentIntentResponse resp = paymentIntents.createIntent(order.getSenderId(), PaymentPurpose.GIFT,
                PayChannel.QRIS, order.getTotal(), CURRENCY, GiftIdemKeys.qris(order.getPublicToken()), PAY_WINDOW);
        return paymentIntents.findByToken(resp.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
    }

    /**
     * 首次下单向网关取二维码载荷；幂等重放返回既有载荷。同 {@code KeepsakePurchaseService.ensureCharge} 的逻辑
     * （那边不改，这里是 gift 包私有副本）。失败时 {@code failChargeAttempt} 留档后原样抛出。
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
