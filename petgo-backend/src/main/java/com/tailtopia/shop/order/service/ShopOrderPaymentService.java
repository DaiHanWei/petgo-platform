package com.tailtopia.shop.order.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.pay.service.PaymentReconciliationService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeQueryResult;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentCallback;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.PaymentCheckView;
import com.tailtopia.shop.order.event.ShopPaymentIntentCreatedEvent;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 电商订单支付执行（Story 3.8，FR-100 / AD-1 / AD-8 / AD-9）。
 *
 * <p>三条路径，语义各不相同：
 * <ul>
 *   <li><b>纯 PawCoin</b>（现金段为 0）：站内即时扣，<b>不建支付意图</b>
 *       —— 没有真钱环节就没有网关意图（与 {@code ConsultPayService} / {@code IdCardHdService} 同范式）。</li>
 *   <li><b>纯 QRIS</b>：建 QRIS 意图 → 网关下单 → 到账事件推进。</li>
 *   <li><b>混合</b>：建 MIXED 意图（三列写入，库级不变式 coin + cash = amount），
 *       网关只收现金段，Coin 段在到账时扣。</li>
 * </ul>
 *
 * <p>🔴 v1.4.2 shop Story 3.3（S-1 / S-7 / B1 / I3）—— <b>等码失效并最后查单才关单</b>：
 * <ul>
 *   <li>订单窗 {@code expires_at} = 建单 + 60 分钟（App 倒计时，不变）；GemPay 码失效 = 出码 + 65 分钟
 *       （{@link #PROVIDER_CODE_TTL}，固定不可调）；两者之间是<b>宽限期</b>：显示已取消、不可支付，期间到账转待发货。</li>
 *   <li>关单判据唯一实现 {@link #isClosable}：订单窗已过 <b>且</b>该单每个已出码意图的码都已失效；关单前对仍未付的
 *       意图逐个查单，<b>查询失败不关单</b>（{@link #closeOverdue}）。读单 / 点支付 / 用户取消一律不关单。</li>
 *   <li>出码只在建单后 {@link #QR_ISSUE_WINDOW}（10 分钟）内 ⇒ <b>关单最晚 = 建单 + 10 + 65 = 建单 + 75 分钟</b>
 *       （防第 55 分钟出码把库存与券占到 120 分钟）。</li>
 * </ul>
 *
 * <p>🔴 <b>仅 QRIS 一种真钱渠道</b>（FR-100）：不支持 GoPay/OVO 直连，不支持银行转账/VA。
 *
 * <p>🔴 v1.4.2 shop Story 3.2（S-3）：<b>PawCoin 在提交订单时扣</b>（{@code CheckoutService#place}），
 * 纯币 / 零元单提交即结清；本类的到账与 {@code /pay} 路径对新单（{@code checkout_source} 非空）<b>不再扣币</b>，
 * 未付款取消经 {@link #releaseUnpaid} 退回已扣的币与券。存量单（{@code checkout_source IS NULL}）保留付款时扣的
 * 旧规则（过渡，{@code TODO(v1.4.3)} 删除）。
 */
@Service
public class ShopOrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderPaymentService.class);

    private static final String CURRENCY = "IDR";

    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;
    private final InventoryService inventory;
    private final CheckoutService checkout;
    private final PaymentIntentService paymentIntents;
    private final PaymentGateway gateway;
    /** v1.4.2 shop Story 3.2：统一「已付款」入口。 */
    private final ShopOrderPaidTransition paidTransition;
    /** v1.4.2 shop Story 3.2：未付款取消退券（P-2 券状态只经账本）。 */
    private final CouponLedgerService coupons;
    /** v1.4.2 shop Story 3.2：未付款取消退回提交时已扣的币（S-3）。 */
    private final PawCoinWalletService wallet;
    /** 复审修复（D1/D2）：已受理注销的账号，常规取消路径不再退币退券（避免给已注销账号重建钱包 / 复活券）。 */
    private final AccountDeletionRepository accountDeletions;

    /**
     * ⚠️ 自引用代理（照 {@code AccountDisposalService} 范式）。
     *
     * <p><b>无事务方法（{@link #pay} / {@link #closeOverdue} / {@link #paymentCheck}）调本类事务方法必须经它</b>，
     * 不能 {@code this.xxx(...)} —— 后者绕过 Spring 代理，{@code @Transactional} 直接不生效：
     * 纯 PawCoin 的「扣币 + 出库 + 状态迁移同一事务」会退化成三步各自提交的假原子，
     * 超时扫描的「逐笔独立事务」同样名存实亡。
     */
    private final org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider;

    /** 领域事件发布（Story 1-2：支付漏斗入口）。Spring 原生，不引任何 MQ。 */
    private final ApplicationEventPublisher events;

    /** v1.4.2 shop Story 3.3：事务外查单（三态）。 */
    private final PaymentReconciliationService reconciliation;
    /** v1.4.2 shop Story 3.3：关单查单失败计数、「我已付款」节流（沿用现有 Redis，与 IdempotencyService 同级用法）。 */
    private final StringRedisTemplate redis;
    /** v1.4.2 shop Story 3.3：连续查单失败的飞书告警。 */
    private final ShopPaymentAlerts alerts;

    /** GemPay 码固定有效期（出码 + 65 分钟）。🔴 <b>不做成可配</b>：误配短于 65 分钟就会在码仍有效时关单。 */
    public static final Duration PROVIDER_CODE_TTL = PaymentIntent.PROVIDER_CODE_TTL;
    /** 出码窗口（I3）：建单后 10 分钟内才可出新码；之后只能复用仍有效的码。 */
    public static final Duration QR_ISSUE_WINDOW = Duration.ofMinutes(10);
    /** 关单批量上限：每单可能一次网关往返，由 200 降到 50。 */
    public static final int CLOSE_BATCH_LIMIT = 50;
    /** 连续查单失败计数键前缀（24 小时 TTL）。 */
    static final String CLOSE_FAIL_KEY_PREFIX = "shop:close-query-fail:";
    static final Duration CLOSE_FAIL_TTL = Duration.ofHours(24);
    /** 「我已付款」节流键前缀（10 秒）。 */
    static final String PAYMENT_CHECK_KEY_PREFIX = "shop:payment-check:";
    static final Duration PAYMENT_CHECK_THROTTLE = Duration.ofSeconds(10);

    public ShopOrderPaymentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            InventoryService inventory, CheckoutService checkout,
            PaymentIntentService paymentIntents, PaymentGateway gateway,
            org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider,
            ApplicationEventPublisher events, ShopOrderPaidTransition paidTransition,
            CouponLedgerService coupons, PawCoinWalletService wallet,
            AccountDeletionRepository accountDeletions, PaymentReconciliationService reconciliation,
            StringRedisTemplate redis, ShopPaymentAlerts alerts) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.inventory = inventory;
        this.checkout = checkout;
        this.paymentIntents = paymentIntents;
        this.gateway = gateway;
        this.selfProvider = selfProvider;
        this.events = events;
        this.paidTransition = paidTransition;
        this.coupons = coupons;
        this.wallet = wallet;
        this.accountDeletions = accountDeletions;
        this.reconciliation = reconciliation;
        this.redis = redis;
        this.alerts = alerts;
    }

    // ---------- 读 ----------

    /**
     * 读本人订单（<b>只读</b>）。
     *
     * <p>🔴 越权与不存在<b>同为 404</b>：403 会泄漏「这个 token 确实存在」，而 token 本就是防枚举的。
     *
     * <p>🔴 v1.4.2 shop Story 3.3（B1 路径④）：<b>读单不再懒关单</b>。原先用户在第 60 分 01 秒打开详情或点支付就把单关了、
     * 意图置 FAILED，而码要到 65 分钟才失效 —— 那 5 分钟内的付款全成了迟到款。宽限期改由
     * {@link ShopOrder#displayStatusAt} 显示已取消；关单<b>只</b>由 {@link #closeOverdue} 执行。
     */
    @Transactional(readOnly = true)
    public ShopOrder requireOwn(long userId, String orderToken) {
        return orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
    }

    @Transactional(readOnly = true)
    public List<ShopOrderLine> linesOf(ShopOrder order) {
        return orderLines.findByOrderIdOrderByIdAsc(order.getId());
    }

    /**
     * 取该订单的支付意图（Story 1-1，供详情装配下发支付状态与失败类别）。
     *
     * <p>🔴 <b>电商侧访问 pay 模块一律经本 service</b>——让 controller 直接注入
     * {@code PaymentIntentService} 等于在表现层开第二条跨模块口子。
     *
     * @return 纯 PawCoin 单（无 {@code payment_intent_token}）返回 {@link Optional#empty()}
     */
    @Transactional(readOnly = true)
    public Optional<PaymentIntent> intentOf(ShopOrder order) {
        String token = order.getPaymentIntentToken();
        return token == null ? Optional.empty() : paymentIntents.findByToken(token);
    }

    /**
     * 批量取订单当前意图（v1.4.2 shop Story 6.2 订单列表 {@code canPay}，一次查询；同样经本 service 访问 pay 模块）。
     *
     * @return 意图 token → 意图；无 token 的订单不查
     */
    @Transactional(readOnly = true)
    public java.util.Map<String, PaymentIntent> intentsOf(java.util.Collection<ShopOrder> orders) {
        List<String> tokens = orders.stream().map(ShopOrder::getPaymentIntentToken)
                .filter(java.util.Objects::nonNull).distinct().toList();
        java.util.Map<String, PaymentIntent> out = new java.util.HashMap<>();
        for (PaymentIntent pi : paymentIntents.findByTokens(tokens)) {
            out.put(pi.getPublicToken(), pi);
        }
        return out;
    }

    // ---------- 支付 ----------

    /**
     * 发起支付。
     *
     * <p>⚠️ <b>网关调用刻意放在事务之外</b>（照 {@code PawCoinTopupService} 范式）：
     * 建意图与回填各自短事务，避免把一次网络往返关在数据库事务里。
     *
     * @return 付款载荷；纯 PawCoin 单当场结清，返回的 {@code payload} 为 null
     */
    public PayResult pay(long userId, String orderToken, String idempotencyKey) {
        // 🔴 本方法刻意无事务（网关往返不入库事务），故事务方法一律经 self 代理调 ——
        //    this.xxx() 自调用会绕过代理让 @Transactional 失效。
        ShopOrderPaymentService self = selfProvider.getObject();
        ShopOrder order = self.requireOwn(userId, orderToken);
        // v1.4.2 shop Story 3.2 AC5.5：纯币 / 零元单提交时已结清 —— 旧版 App 下单后仍会点「去支付」，
        // 直接回当前状态（无意图、无码），不再 409。
        if (order.getStatus() == ShopOrderStatus.PENDING_SHIPMENT
                && order.getCashAmount() != null && order.getCashAmount() == 0L) {
            return new PayResult(order.getStatus().name(), null, null, null);
        }
        requirePayable(order);

        // v1.4.2 shop Story 3.3 AC2.4 新判序（与详情 canPay 同一判定 payGate，不复制）
        Instant now = Instant.now();
        PaymentIntent current = self.intentOf(order).orElse(null);
        PayGate gate = payGate(order, current, now);
        switch (gate) {
            case WINDOW_CLOSED -> throw AppException.shopOrderNotPayable("订单已超过付款时间");
            case ISSUE_CLOSED -> throw AppException.shopOrderNotPayable("订单已无法支付");
            case NOT_PENDING -> throw AppException.conflict("该订单已不在待支付状态");
            case REUSE -> {
                // 码仍有效 → 直接返回同一个码，不向网关再下单
                return new PayResult(order.getStatus().name(), current.getPublicToken(), payloadOf(current),
                        order.getExpiresAt());
            }
            default -> { /* PURE_COIN / ISSUE_NEW 往下走 */ }
        }

        if (gate == PayGate.PURE_COIN) {
            // 纯 PawCoin：站内即时扣 + 当场结清（无网关往返，无意图）
            self.settlePureCoin(userId, orderToken);
            ShopOrder settled = self.requireOwn(userId, orderToken);
            return new PayResult(settled.getStatus().name(), null, null, null);
        }
        long cash = cashOf(order);

        // 有现金段：QRIS（纯现金）或 MIXED（两段都有）。首次出码失败（E7）在 10 分钟内可重试。
        PaymentIntent intent = self.ensureIntent(userId, order, idempotencyKey);
        String payload = payloadOf(intent);
        if (intent.getGatewayRef() == null) {
            // 🔴 网关只收【现金段】：Coin 段是站内余额，不经网关。
            //    而意图的 amount 是订单总额（库级不变式 coin + cash = amount 要求如此）——
            //    两者不同不是笔误，混同会让用户被真收走本该用币抵掉的那部分。
            ChargeResult charge;
            try {
                charge = gateway.createCharge(new ChargeRequest(
                        intent.getPublicToken(), cash, CURRENCY, PayChannel.QRIS.name(),
                        PaymentPurpose.SHOP_ORDER.name()));
            } catch (RuntimeException e) {
                // 下单失败：意图置 FAILED 留档（request_id 可对账），下次发起走新意图、新 request_id。
                paymentIntents.failChargeAttempt(intent.getPublicToken());
                throw e;
            }
            Map<String, Object> meta = new LinkedHashMap<>();
            if (charge.rawMeta() != null) {
                meta.putAll(charge.rawMeta());
            }
            if (charge.payload() != null) {
                meta.put("payload", charge.payload());
            }
            // 🔴 意图有效期 = 码失效时刻（出码 + 65 分钟），不再跟订单剩余窗（B1 路径①）
            paymentIntents.attachCharge(intent.getPublicToken(), charge.gatewayRef(), meta, PROVIDER_CODE_TTL);
            payload = charge.payload();
        }
        return new PayResult(order.getStatus().name(), intent.getPublicToken(), payload,
                order.getExpiresAt());
    }

    /** 幂等取/建意图，并把 token 记到订单上（到账事件据此回找订单）。 */
    @Transactional
    public PaymentIntent ensureIntent(long userId, ShopOrder order, String idempotencyKey) {
        long coin = order.getCoinAmount() == null ? 0L : order.getCoinAmount();
        long cash = order.getCashAmount() == null ? order.getTotalAmount() : order.getCashAmount();
        // 🔴 幂等键绑定订单 token：重复点「去支付」取回同一个意图、同一个二维码，
        //    不会每点一次就向网关多下一单。
        //    🔴 客户端 key 一律【不采信】：订单 token 已唯一标识「这个用户的这一单」，
        //    采信任意字符串会让 B 用 A 的 key 取回 A 的意图并挂到 B 单上（回调按 token 回找订单命中两行）。
        String key = "shop-order-pay:" + order.getPublicToken();
        // v1.4.2 shop Story 3.3（B1 路径①）：TTL = 码有效期 65 分钟；出码成功时 attachCharge 再按网关返回时刻校准。
        Duration ttl = PROVIDER_CODE_TTL;

        var response = coin > 0
                ? paymentIntents.createMixedIntent(userId, PaymentPurpose.SHOP_ORDER,
                        order.getTotalAmount(), coin, cash, CURRENCY, key, ttl)
                : paymentIntents.createIntent(userId, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS,
                        order.getTotalAmount(), CURRENCY, key, ttl);

        // 一单多意图关联（关单判据「所有意图码已失效」与迟到款找回订单都靠它）；仅当为空时写
        paymentIntents.bindShopOrder(response.token(), order.getId());
        PaymentIntent entity = paymentIntents.findByToken(response.token())
                .orElseThrow(() -> AppException.notFound("支付意图不存在"));
        ShopOrder managed = orders.findById(order.getId()).orElseThrow();
        // 🔴 判据是「换了新意图」而不是「调了 ensureIntent」（Story 1-2 AC9）：本方法幂等，
        //    连点三次「去支付」拿回的是同一个意图，照调用次数发事件会把漏斗入口灌水，
        //    转化率被用户自己的重试稀释。比对必须在 attachPaymentIntent 之前做。
        boolean newIntent = !java.util.Objects.equals(
                managed.getPaymentIntentToken(), entity.getPublicToken());
        managed.attachPaymentIntent(entity.getPublicToken());
        orders.save(managed);
        if (newIntent) {
            // 本方法是 @Transactional，监听方是 AFTER_COMMIT —— 本事务回滚则不上报。
            // ⚠️ 但**只覆盖到本事务**：调用方 pay(...) 随后才调 gateway.createCharge()，
            //    那一步刻意在事务外（网关往返不入库事务）。网关故障时用户拿到 500、没有二维码，
            //    而本事件已经提交发出 ⇒ GemPay 宕机期间漏斗入口会被抬高。
            //    语义上「意图确实创建了」没错（payment_intents 行真的在），但分析时要知道
            //    intent_created → paid 的转化率在网关故障窗口内会失真，不是用户放弃了。
            events.publishEvent(new ShopPaymentIntentCreatedEvent(userId,
                    managed.getPayChannel() == null ? null : managed.getPayChannel().name(),
                    managed.getTotalAmount(),
                    managed.getCoinAmount() != null && managed.getCoinAmount() > 0));
        }
        return entity;
    }

    /**
     * 纯 PawCoin 单当场结清（经 {@code /pay}）。
     *
     * <p>v1.4.2 shop Story 3.2：新单在提交时已结清、到不了这里；只剩<b>存量</b>纯币待付款单
     * （{@code checkout_source IS NULL}，币尚未扣）走它，按旧规则付款时扣币。
     *
     * <p>🔴 扣币与状态迁移、库存扣减在<b>同一事务</b>：任何一步失败都必须整体回滚 ——
     * 扣了币却没出货，或出了货没扣币，两种都是资损。
     */
    @Transactional
    public void settlePureCoin(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        requirePayable(order);
        // 🔴 先状态迁移再扣币（与 ShopOrderPaidHandler#onPaid 同序）：markPaid 对非待支付
        //    静默跳过，若先扣币，竞态下会留下「币已扣、单已取消」的无补偿态。
        //    同一事务内，扣币失败（余额被别处花掉）整体回滚，出库与扣币仍是原子的。
        if (paidTransition.markPaid(order, PaidSource.PURE_COIN) && !order.isCoinDebitedAtSubmit()) {
            // TODO(v1.4.3)：上线过渡规则（存量待付款单币未扣），过渡期 ≤ 75 分钟，下一版本删除
            checkout.settlePawCoinSegment(order);
        }
    }

    /**
     * 统一「已付款」入口（v1.4.2 shop Story 3.2 AC4；原 {@code fulfillPaid} 改名扩参）。委托
     * {@link ShopOrderPaidTransition#markPaid}：逐行出库 → 写 {@code paid_at} → 券 USED → 发 {@code ShopOrderPaidEvent}。
     *
     * @return 本次是否真的把订单从待支付推进为待发货；非 {@code PENDING_PAYMENT} 返回 {@code false}
     */
    @Transactional
    public boolean markPaid(ShopOrder order, PaidSource source) {
        return paidTransition.markPaid(order, source);
    }

    // ---------- 取消 / 超时 ----------

    /**
     * 用户主动取消（仅待支付可取消）。释放库存 + 作废支付意图。
     *
     * <p>🔴 v1.4.2 shop Story 3.3（拍板④）：<b>宽限期（订单窗已过）内 no-op</b>，关单交给扫描 —— 否则用户在宽限期点取消，
     * 会把一笔即将正常到账的付款人为变成迟到款。详情此时已显示已取消。
     */
    @Transactional
    public void cancel(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            throw AppException.conflict("该订单已不能取消");
        }
        if (order.isPaymentExpiredAt(Instant.now())) {
            return;
        }
        releaseUnpaid(order, CancelReason.USER_UNPAID_CANCEL);
    }

    // ---------- 关单（v1.4.2 shop Story 3.3 AC3 / AC4） ----------

    /**
     * 关单判据的<b>唯一实现</b>（B1）：{@code PENDING_PAYMENT} 且订单窗已过，且该单每一个已出码（{@code gateway_ref}
     * 非空）的意图都已过码失效时刻（{@code COALESCE(provider_expires_at, created_at + 65 分钟)}）。未出码的意图从未
     * 把码交到用户手上，不参与判据。
     */
    public static boolean isClosable(ShopOrder order, List<PaymentIntent> intents, Instant now) {
        if (order == null || order.getStatus() != ShopOrderStatus.PENDING_PAYMENT
                || !order.isPaymentExpiredAt(now)) {
            return false;
        }
        for (PaymentIntent pi : intents) {
            if (pi.getGatewayRef() != null && !PaymentIntentService.isProviderExpired(pi, now)) {
                return false;
            }
        }
        return true;
    }

    /** 关单上下文：订单 + 其全部意图（含 {@code payment_intent_token} 指向但未绑定的存量意图）。 */
    public record CloseContext(ShopOrder order, List<PaymentIntent> intents) {

        /** 已出码且未付的意图：关单前要逐个查单的那些。 */
        public List<PaymentIntent> chargedUnpaidIntents() {
            return intents.stream()
                    .filter(pi -> pi.getGatewayRef() != null && pi.getStatus() != PaymentStatus.PAID)
                    .toList();
        }
    }

    private enum Verdict { CLOSE, PAID, HOLD }

    /**
     * 定时关单（取代 {@code cancelOverdue}，{@link ShopOrderExpiryScanner} 调）。
     *
     * <p>🔴 <b>方法本身无事务</b>，事务段全经 {@code self}：网关查单必须在事务外（P-7）。对每个候选订单：
     * <ol>
     *   <li>{@link #closeContext}（只读）：重读订单与全部意图，已推进 / 不可关 → 跳过；</li>
     *   <li>已出码未付的意图一个都没有 → 直接 {@link #closeOne}；</li>
     *   <li>否则逐个<b>事务外</b>查单：任一 PAID → {@code applyResult}（订单此时仍待支付，正常转待发货），本单不关；
     *       全部网关明确 FAILED / EXPIRED → 先逐个 {@code applyResult} 推进意图终态，再 {@link #closeOne}；
     *       任一 UNKNOWN 或网关仍 PENDING → <b>本轮不关</b>（S-1：查询失败不关单），失败计数 +1，每满 10 次告警。</li>
     * </ol>
     * 单笔异常不阻断其余。
     *
     * @return 实际关单笔数
     */
    public int closeOverdue(int limit) {
        ShopOrderPaymentService self = selfProvider.getObject();
        int closed = 0;
        int batchLimit = Math.max(1, limit);
        int held = 0;
        List<Long> candidateIds = orders.findClosableCandidateIds(Instant.now(), batchLimit);
        for (Long orderId : candidateIds) {
            try {
                CloseContext ctx = self.closeContext(orderId);
                if (ctx == null) {
                    continue;   // 已推进 / 不可关
                }
                if (ctx.intents().stream().anyMatch(pi -> pi.getStatus() == PaymentStatus.PAID)) {
                    // 意图已 PAID 而订单仍待支付：到账没能推进订单（不应出现）。宁可不关、交人工，绝不把付了钱的单关掉。
                    log.error("电商订单有已到账意图但仍待支付，跳过关单 displayNo={}", ctx.order().getDisplayNo());
                    bumpCloseFail(ctx.order());
                    held++;
                    continue;
                }
                Verdict v = Verdict.CLOSE;
                List<PaymentCallback> pendingTerminal = new ArrayList<>();
                for (PaymentIntent pi : ctx.chargedUnpaidIntents()) {
                    ChargeQueryResult r = reconciliation.query(pi);        // 事务外
                    if (r.outcome() == ChargeQueryResult.Outcome.PAID) {
                        paymentIntents.applyResult(r.callback());
                        v = Verdict.PAID;
                        break;
                    }
                    if (!r.isTerminalNotPaid()) {
                        v = Verdict.HOLD;                                   // UNKNOWN / 网关仍 PENDING
                    } else if (v == Verdict.CLOSE) {
                        pendingTerminal.add(r.callback());
                    }
                }
                switch (v) {
                    case PAID -> clearCloseFail(orderId);
                    case HOLD -> {
                        bumpCloseFail(ctx.order());
                        held++;
                    }
                    case CLOSE -> {
                        pendingTerminal.forEach(paymentIntents::applyResult);
                        if (self.closeOne(orderId)) {
                            closed++;
                            clearCloseFail(orderId);
                        }
                    }
                }
            } catch (RuntimeException e) {
                log.warn("关单失败 orderId={} cause={}", orderId, e.getClass().getSimpleName());
            }
        }
        // 复审修复②：候选 SQL 按 expires_at 升序取前 limit 单，若这一批全部查单未决（HOLD），后面可关的单本轮
        // 一张也轮不到 —— 关单队列可能被长期未决的单堵住。额外告警一次（是否加退避待规划层定）。
        if (held > 0 && held == batchLimit && candidateIds.size() == batchLimit) {
            log.error("电商关单队列可能被堵：本批 {} 单全部查单未决", held);
            alerts.alert("电商关单队列可能被堵：本批 " + held + " 单全部查单未决（GemPay 查单失败 / 仍 Pending），"
                    + "后续可关订单本轮未处理，请检查 GemPay");
        }
        return closed;
    }

    /** 关单上下文（只读事务）：订单已非待支付或不可关 → null。 */
    @Transactional(readOnly = true)
    public CloseContext closeContext(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return null;
        }
        List<PaymentIntent> intents = intentsOfOrder(order);
        if (!isClosable(order, intents, Instant.now())) {
            return null;
        }
        return new CloseContext(order, intents);
    }

    /**
     * 关一单（独立事务）：重读订单，仍待支付且 {@link #isClosable} → {@link #releaseUnpaid}（{@code PAYMENT_TIMEOUT}：
     * 回补库存、退币、退券、写原因、作废该单全部未终态意图）。并发已推进 → no-op（{@code @Version} 兜底）。
     *
     * @return 是否真的关了
     */
    @Transactional
    public boolean closeOne(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null) {
            return false;
        }
        List<PaymentIntent> intents = intentsOfOrder(order);
        if (intents.stream().anyMatch(pi -> pi.getStatus() == PaymentStatus.PAID)) {
            // 复审修复③：事务内重读到已 PAID 的意图（查单与关单之间到账）→ 绝不关单，交到账链路推进
            return false;
        }
        if (!isClosable(order, intents, Instant.now())) {
            return false;   // 并发已推进 / 码仍有效
        }
        releaseUnpaid(order, CancelReason.PAYMENT_TIMEOUT);
        return true;
    }

    /** 该单全部意图：{@code shop_order_id} 绑定的 + {@code payment_intent_token} 指向的（存量未回填时兜底）。 */
    private List<PaymentIntent> intentsOfOrder(ShopOrder order) {
        List<PaymentIntent> list = new ArrayList<>(paymentIntents.findByShopOrderId(order.getId()));
        String token = order.getPaymentIntentToken();
        if (token != null && list.stream().noneMatch(pi -> token.equals(pi.getPublicToken()))) {
            paymentIntents.findByToken(token).ifPresent(list::add);
        }
        return list;
    }

    /** 查单未决：失败计数 +1（首次设 24 小时 TTL）；恰为 10 及其后每满 10 → ERROR + 飞书。Redis 异常不影响关单流程。 */
    private void bumpCloseFail(ShopOrder order) {
        try {
            Long n = redis.opsForValue().increment(CLOSE_FAIL_KEY_PREFIX + order.getId());
            if (n == null) {
                return;
            }
            if (n == 1L) {
                redis.expire(CLOSE_FAIL_KEY_PREFIX + order.getId(), CLOSE_FAIL_TTL);
            }
            if (n % 10 == 0) {
                log.error("电商订单关单前查单连续失败 displayNo={} count={}（多单同时出现时关单队列可能被堵）",
                        order.getDisplayNo(), n);
                alerts.alert("电商订单关单前查单连续失败 " + n + " 次：" + order.getDisplayNo()
                        + "，请检查 GemPay（多单同时出现时关单队列可能被堵）");
            }
        } catch (RuntimeException e) {
            log.warn("关单查单失败计数异常 orderId={} cause={}", order.getId(), e.getClass().getSimpleName());
        }
    }

    private void clearCloseFail(long orderId) {
        try {
            redis.delete(CLOSE_FAIL_KEY_PREFIX + orderId);
        } catch (RuntimeException e) {
            log.warn("关单查单失败计数清理异常 orderId={} cause={}", orderId, e.getClass().getSimpleName());
        }
    }

    // ---------- 「我已付款」（v1.4.2 shop Story 3.3 AC9） ----------

    /**
     * 「我已付款」主动查单。非本人 404；非待支付 → 不查网关直接返回；10 秒节流内 → 不查网关直接返回当前状态（200，不报 429）；
     * 否则对该单已出码且未付的意图逐个对账（事务外查、{@code applyResult} 落），再重读订单。查单失败不报错（{@code paid=false}）。
     */
    public PaymentCheckView paymentCheck(long userId, String orderToken) {
        ShopOrderPaymentService self = selfProvider.getObject();
        ShopOrder order = self.requireOwn(userId, orderToken);
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return PaymentCheckView.of(order, Instant.now());
        }
        Boolean first;
        try {
            first = redis.opsForValue().setIfAbsent(PAYMENT_CHECK_KEY_PREFIX + orderToken, "1",
                    PAYMENT_CHECK_THROTTLE);
        } catch (RuntimeException e) {
            first = Boolean.FALSE;  // Redis 不可用：宁可不查网关，也不让用户狂点打爆网关
        }
        if (!Boolean.TRUE.equals(first)) {
            return PaymentCheckView.of(order, Instant.now());
        }
        for (PaymentIntent pi : self.chargedUnpaidIntentsOf(order.getId())) {
            try {
                reconciliation.reconcile(pi.getPublicToken());
            } catch (RuntimeException e) {
                log.warn("我已付款查单失败 orderId={} cause={}", order.getId(), e.getClass().getSimpleName());
            }
        }
        return PaymentCheckView.of(self.requireOwn(userId, orderToken), Instant.now());
    }

    /** 某单已出码且未付的意图（只读）。 */
    @Transactional(readOnly = true)
    public List<PaymentIntent> chargedUnpaidIntentsOf(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null) {
            return List.of();
        }
        return new CloseContext(order, intentsOfOrder(order)).chargedUnpaidIntents();
    }

    // ---------- 支付判定（pay 与详情 canPay 共用） ----------

    /** {@link #payGate} 的结果。 */
    public enum PayGate {
        /** 非待支付。 */
        NOT_PENDING,
        /** 订单窗已过（含宽限期，S-7）。 */
        WINDOW_CLOSED,
        /** 存量纯币待付款单：站内结清。 */
        PURE_COIN,
        /** 当前意图的码仍有效：复用。 */
        REUSE,
        /** 建单 10 分钟内：可出新码。 */
        ISSUE_NEW,
        /** 建单已超 10 分钟且无可复用的码（I3）。 */
        ISSUE_CLOSED;

        public boolean canPay() {
            return this == PURE_COIN || this == REUSE || this == ISSUE_NEW;
        }
    }

    /**
     * 支付判定（AC2.4 / AC7.2 同一方法）：{@code /pay} 的放行与详情 {@code canPay} 都据此，不复制。
     *
     * @param current 订单当前挂着的意图（{@code payment_intent_token}），无则 null
     */
    public static PayGate payGate(ShopOrder order, PaymentIntent current, Instant now) {
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return PayGate.NOT_PENDING;
        }
        if (order.isPaymentExpiredAt(now)) {
            return PayGate.WINDOW_CLOSED;
        }
        if (cashOf(order) <= 0) {
            return PayGate.PURE_COIN;
        }
        if (isReusable(current, now)) {
            return PayGate.REUSE;
        }
        if (order.getCreatedAt() != null && now.isAfter(order.getCreatedAt().plus(QR_ISSUE_WINDOW))) {
            return PayGate.ISSUE_CLOSED;
        }
        return PayGate.ISSUE_NEW;
    }

    /** 当前意图可复用 = PENDING 且已出码且码未失效。 */
    static boolean isReusable(PaymentIntent intent, Instant now) {
        return intent != null && intent.getStatus() == PaymentStatus.PENDING && intent.getGatewayRef() != null
                && !PaymentIntentService.isProviderExpired(intent, now);
    }

    /** 详情 {@code canPay}（AC7.2）：与 {@link #pay} 放行同一判定。 */
    public static boolean canPay(ShopOrder order, PaymentIntent current, Instant now) {
        return payGate(order, current, now).canPay();
    }

    private static long cashOf(ShopOrder order) {
        return order.getCashAmount() == null ? order.getTotalAmount() : order.getCashAmount();
    }

    /**
     * 未付款取消的唯一收口（v1.4.2 shop Story 3.2 AC6.3，取代 {@code releaseAndCancel}）：用户取消
     * （{@code USER_UNPAID_CANCEL}）与超时关单（{@code PAYMENT_TIMEOUT}，含 3.3 的关单路径）都经这里。
     * 已付款订单的取消退币走 4.1 退款单，不经这里。
     *
     * <p>🔴 <b>同一事务</b>（AD-8）：释放库存、退币、退券、状态迁移、作废意图要么全有要么全无 ——
     * 分开做的话，中间崩一次就会留下「已取消但库存还锁着 / 币没退」的幽灵态。
     *
     * <p>🔴 退币条件 {@code checkout_source IS NOT NULL && coin_amount > 0}：存量单（过渡规则）币从未扣过，
     * 退了就是白送。幂等键 {@code shop-order-void:{token}} 保证重复取消不重复退。<b>不</b>调
     * {@code recordRefund}（{@code refunded_total} 只记已付款订单的退款，财务按它算现金净流入），
     * <b>不</b>发 {@code RefundSucceededEvent}（未付款单从未入账成长值）。
     */
    @Transactional
    public void releaseUnpaid(ShopOrder order, CancelReason reason) {
        // 🔴 复审修复（D1/D2）：账号已受理注销（account_deletions 有行，无冷静期、不可撤回）→ 常规路径
        //    （超时扫描 / 懒过期 / 用户取消竞态）不退币、不退券：注销作业的 PawCoin 作废可能已执行，
        //    这时 credit 会给已注销账号重建钱包、写流水与负债分录；退券会把 LOCKED 券复活成 AVAILABLE。
        //    注销作业自己经 releaseUnpaidForAccountDeletion 在 PawCoin 作废【之前】先退，资产随后一并作废。
        boolean returnAssets = accountDeletions.findByUserId(order.getUserId()).isEmpty();
        releaseUnpaid(order, reason, returnAssets);
    }

    /**
     * 注销联动（复审修复，D1/D2）：{@code ShopAccountDeletionService.deleteByUserId} 在 PawCoin 作废与可用券作废
     * <b>之前</b>对该用户每张待付款单调用 —— 币先退回再随钱包一并作废（FORFEITURE），券先回 AVAILABLE 再被作废，
     * 库存释放、意图作废。原因取 {@code USER_UNPAID_CANCEL}（现有枚举里最贴切：用户主动行为导致的未付款取消）。
     */
    @Transactional
    public void releaseUnpaidForAccountDeletion(ShopOrder order) {
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return;     // 并发已推进
        }
        releaseUnpaid(order, CancelReason.USER_UNPAID_CANCEL, true);
    }

    private void releaseUnpaid(ShopOrder order, CancelReason reason, boolean returnAssets) {
        Instant now = Instant.now();
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            inventory.release(line.getSkuId(), line.getQty());
        }
        long coin = order.getCoinAmount() == null ? 0L : order.getCoinAmount();
        if (returnAssets && order.isCoinDebitedAtSubmit() && coin > 0) {
            wallet.credit(order.getUserId(), coin, PawCoinTxnType.REFUND, REF_TYPE_VOID,
                    order.getId(), "shop-order-void:" + order.getPublicToken());
        }
        if (returnAssets) {
            coupons.releaseOnCancel(order.getId(), reason.couponReleaseMode(), now);
        }
        order.markCancelled(reason, now);
        orders.save(order);
        // 支付意图一并作废，免得它继续挂在后台支付列表里等一个不会到的付款（纯 DB 写，无网关调用）。
        // v1.4.2 shop Story 3.3 AC4.2：作废该单【全部】未终态意图（shop_order_id 绑定的 + 当前 token 指向的），
        // 不只 payment_intent_token 那一个；failByToken 对已终态 no-op。
        LinkedHashSet<String> tokens = new LinkedHashSet<>();
        for (PaymentIntent pi : paymentIntents.findByShopOrderId(order.getId())) {
            if (pi.getStatus() == PaymentStatus.PENDING) {
                tokens.add(pi.getPublicToken());
            }
        }
        if (order.getPaymentIntentToken() != null) {
            tokens.add(order.getPaymentIntentToken());
        }
        for (String intentToken : tokens) {
            paymentIntents.failByToken(intentToken, intentFailReason(reason));
        }
        log.info("电商订单取消 token={} reason={} assetsReturned={}", order.getPublicToken(), reason,
                returnAssets);
    }

    /** 未付款作废退币的 {@code ref_type}：与已付款退款（{@code SHOP_ORDER}）区分，财务 {@code coinSpent} 据此扣除往返。 */
    public static final String REF_TYPE_VOID = "SHOP_ORDER_VOID";

    /**
     * 意图作废原因沿用旧值：{@code PaymentFailureCategory} 按 {@code TIMEOUT} / {@code USER_CANCEL} 归类
     * （{@code shop_payment_expired} / {@code shop_payment_user_cancelled}），改写成枚举名会让漏斗把两类全记成网关拒付。
     */
    static String intentFailReason(CancelReason reason) {
        return reason == CancelReason.USER_UNPAID_CANCEL ? "USER_CANCEL" : "TIMEOUT";
    }

    // ---------- 内部 ----------

    private static void requirePayable(ShopOrder order) {
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            throw AppException.conflict("该订单已不在待支付状态");
        }
    }

    private static String payloadOf(PaymentIntent intent) {
        Map<String, Object> meta = intent.getGatewayMeta();
        return meta == null ? null : (String) meta.get("payload");
    }

    /**
     * 发起支付的结果。
     *
     * @param payload 二维码/deeplink 载荷；纯 PawCoin 单为 null（当场结清，没有可扫的码）
     */
    public record PayResult(String orderStatus, String paymentIntentToken, String payload,
            Instant expiresAt) {
    }
}
