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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
    private static final Logger log = LoggerFactory.getLogger(GiftSendService.class);

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
        return chargeOrFailed(order, intent);
    }

    /**
     * 出码；网关下单失败（{@link PayException}）且意图已被 {@code failChargeAttempt} 置 FAILED → 订单随之 FAILED，
     * 返回正常响应 {@code {delivered:false, status:FAILED}}（与幂等重放旧 FAILED 单同形），<b>不再 500</b>。
     *
     * <p>stag 复验：原先原样抛出 → 500 → App 视为结果未知、保留 requestId → 第 2 次点击重放旧单才拿到 FAILED、
     * 第 3 次才真正重下（用户必白点一次）。现在首次就回 FAILED，App（OCR #4 逻辑）当场清 requestId，下次即换新单。
     * 事务语义不变：{@code noRollbackFor = PayException} 本就让意图 FAILED + 订单 FAILED 提交；这里吞掉异常后正常提交，
     * 同样两行都留档、没有半成品。
     *
     * <p>只接 {@link PayException}：其它异常（程序错误等）照旧抛出走 500。意图没被置 FAILED（{@code failChargeAttempt}
     * no-op：并发的另一请求已拿到网关单号）→ 也照旧抛出，绝不把可能已出码的单标成 FAILED。
     * 日志 WARN 只记订单 / 意图 id 与安全原因（网关错误码已由网关适配层按 request_id 记过），不含 PII。
     */
    private GiftSendResponse chargeOrFailed(GiftOrder order, PaymentIntent intent) {
        String payload;
        try {
            payload = ensureCharge(intent);
        } catch (PayException e) {
            if (intent.getStatus() != PaymentStatus.FAILED) {
                throw e;
            }
            order.markFailed(); // 同步失败监听通常已置 FAILED；非 PENDING 时 no-op
            orders.saveAndFlush(order);
            log.warn("gift qris charge failed orderId={} intentId={} reason={}", order.getId(), intent.getId(),
                    e.getMessage());
            return GiftSendResponse.ofStatus(order.getPublicToken(), order.getStatus());
        }
        return GiftSendResponse.paymentRequired(PaymentIntentResponse.of(intent), payload, order.getPublicToken());
    }

    /**
     * 幂等重放取回（AC7.3）：认订单<b>自己挂着的</b>那笔意图（{@code payment_intent_id}），不再同键 {@code createIntent}。
     *
     * <p>OCR #7：原先靠幂等键映射「取回」意图，取回的不是订单那笔（映射过期 / 丢失，或旧意图已过窗被懒过期）就把订单置
     * EXPIRED —— 但映射丢失时旧意图仍是 PENDING、旧码仍可付，付了之后到账监听看到订单已 EXPIRED 直接丢弃：收了钱不送达也不退。
     * 现在：
     * <ul>
     *   <li>意图状态先经支付模块 {@code statusOf}（PENDING 且过窗 → 就地置 EXPIRED，走意图状态机，不绕过）；</li>
     *   <li>PENDING（窗内）→ 返回同一笔的已存载荷（没有就补下单），旧码继续有效、到账照常交付；</li>
     *   <li>PAID → 交付由到账监听在回调事务里完成，这里只回报订单当前状态，不再出码；</li>
     *   <li>EXPIRED / FAILED（意图已终态、订单却还 PENDING：漏了失败事件，如 {@code statusOf} 懒过期不发事件）→
     *       订单随意图置 EXPIRED / FAILED。意图先终态、订单后标，之后该码的回调在支付层即被「已终态」拦下。</li>
     * </ul>
     * 不再建第二笔意图，也就不会再有「孤儿意图 / 订单与意图对不上」。App 收到 EXPIRED / FAILED 换新 requestId 重发。
     */
    private GiftSendResponse replayQris(GiftOrder order) {
        PaymentIntent intent = order.getPaymentIntentId() == null ? null
                : paymentIntents.findById(order.getPaymentIntentId()).orElse(null);
        if (intent == null) {
            // 防御：QRIS 订单与意图同事务落库，不应发生。按过期收口，让 App 换新 requestId。
            log.warn("gift qris replay without intent orderId={}", order.getId());
            order.markExpired();
            orders.saveAndFlush(order);
            return GiftSendResponse.ofStatus(order.getPublicToken(), order.getStatus());
        }
        PaymentStatus status = paymentIntents.statusOf(order.getSenderId(), intent.getPublicToken());
        switch (status) {
            case PAID -> {
                // 已到账：交付由到账监听在回调事务里完成，这里只回报订单当前状态，不再出码。
                return new GiftSendResponse(false, PaymentIntentResponse.of(intent), null, order.getPublicToken(),
                        order.getStatus().name());
            }
            case PENDING -> {
                // 意图窗内但还没网关单号（首单出码前异常中断）→ 补下单；补下单再失败同样回 FAILED、不 500。
                return chargeOrFailed(order, intent);
            }
            case EXPIRED, FAILED -> {
                if (status == PaymentStatus.FAILED) {
                    order.markFailed();
                } else {
                    order.markExpired();
                }
                orders.saveAndFlush(order);
                return GiftSendResponse.ofStatus(order.getPublicToken(), order.getStatus());
            }
            default -> throw new IllegalStateException("未知支付状态 " + status);
        }
    }

    private PaymentIntent createQrisIntent(GiftOrder order) {
        PaymentIntentResponse resp = paymentIntents.createIntent(order.getSenderId(), PaymentPurpose.GIFT,
                PayChannel.QRIS, order.getTotal(), CURRENCY, GiftIdemKeys.qris(order.getPublicToken()), PAY_WINDOW);
        return paymentIntents.findByToken(resp.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
    }

    /**
     * 首次下单向网关取二维码载荷；幂等重放返回既有载荷。同 {@code KeepsakePurchaseService.ensureCharge} 的逻辑
     * （那边不改，这里是 gift 包私有副本）。失败时 {@code failChargeAttempt} 留档后原样抛出，由
     * {@link #chargeOrFailed} 收口。
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
