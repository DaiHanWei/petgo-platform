package com.tailtopia.shop.order.service;

import com.tailtopia.account.repository.AccountDeletionRepository;
import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.coupon.service.CouponLedgerService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.event.ShopPaymentIntentCreatedEvent;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
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
 * <p>🔴 <b>支付窗到期即取消并释放库存</b>（AD-8）。用户可见的正确性由<b>懒过期</b>承担
 * （读详情 / 点支付时就地判定），定时扫描只是兜底 —— 无人查看的订单不至于永远锁着库存。
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
     * <p><b>无事务方法（{@link #pay} / {@link #cancelOverdue}）调本类事务方法必须经它</b>，
     * 不能 {@code this.xxx(...)} —— 后者绕过 Spring 代理，{@code @Transactional} 直接不生效：
     * 纯 PawCoin 的「扣币 + 出库 + 状态迁移同一事务」会退化成三步各自提交的假原子，
     * 超时扫描的「逐笔独立事务」同样名存实亡。
     */
    private final org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider;

    /** 领域事件发布（Story 1-2：支付漏斗入口）。Spring 原生，不引任何 MQ。 */
    private final ApplicationEventPublisher events;

    public ShopOrderPaymentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            InventoryService inventory, CheckoutService checkout,
            PaymentIntentService paymentIntents, PaymentGateway gateway,
            org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider,
            ApplicationEventPublisher events, ShopOrderPaidTransition paidTransition,
            CouponLedgerService coupons, PawCoinWalletService wallet,
            AccountDeletionRepository accountDeletions) {
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
    }

    // ---------- 读 ----------

    /**
     * 读本人订单（懒过期）。
     *
     * <p>🔴 越权与不存在<b>同为 404</b>：403 会泄漏「这个 token 确实存在」，而 token 本就是防枚举的。
     */
    @Transactional
    public ShopOrder requireOwn(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        expireIfOverdue(order);
        return order;
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

        long cash = order.getCashAmount() == null ? order.getTotalAmount() : order.getCashAmount();
        if (cash <= 0) {
            // 纯 PawCoin：站内即时扣 + 当场结清（无网关往返，无意图）
            self.settlePureCoin(userId, orderToken);
            ShopOrder settled = self.requireOwn(userId, orderToken);
            return new PayResult(settled.getStatus().name(), null, null, null);
        }

        // 有现金段：QRIS（纯现金）或 MIXED（两段都有）
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
            paymentIntents.attachCharge(intent.getPublicToken(), charge.gatewayRef(), meta);
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
        Duration ttl = remainingWindow(order);

        var response = coin > 0
                ? paymentIntents.createMixedIntent(userId, PaymentPurpose.SHOP_ORDER,
                        order.getTotalAmount(), coin, cash, CURRENCY, key, ttl)
                : paymentIntents.createIntent(userId, PaymentPurpose.SHOP_ORDER, PayChannel.QRIS,
                        order.getTotalAmount(), CURRENCY, key, ttl);

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

    /** 用户主动取消（仅待支付可取消）。释放库存 + 作废支付意图。 */
    @Transactional
    public void cancel(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            throw AppException.conflict("该订单已不能取消");
        }
        releaseUnpaid(order, CancelReason.USER_UNPAID_CANCEL);
    }

    /**
     * 懒过期：读到一个已过窗的待支付订单就地取消。
     *
     * <p>🔴 用户看到的正确性靠这里，不靠定时扫描 —— 扫描有最长一分钟的延迟，
     * 而用户会在窗口刚过的那一秒点「去支付」。
     */
    @Transactional
    public void expireIfOverdue(ShopOrder order) {
        if (order.getStatus() == ShopOrderStatus.PENDING_PAYMENT
                && order.isPaymentExpiredAt(Instant.now())) {
            releaseUnpaid(order, CancelReason.PAYMENT_TIMEOUT);
        }
    }

    /**
     * 定时扫描兜底：批量取消过窗待支付订单并释放库存。
     *
     * <p>逐笔独立事务，单笔失败不阻断其余（一个坏订单不该让整批库存都释放不了）。
     *
     * @return 实际取消的笔数
     */
    public int cancelOverdue(int limit) {
        // 🔴 经 self 代理逐笔调用：this.cancelOneOverdue() 自调用会绕过代理，
        //    @Transactional 不生效 → 「逐笔独立事务、单笔失败不阻断」就没了。
        ShopOrderPaymentService self = selfProvider.getObject();
        int cancelled = 0;
        for (ShopOrder order : orders.findOverduePendingPayment(Instant.now(),
                org.springframework.data.domain.PageRequest.of(0, Math.max(1, limit)))) {
            try {
                self.cancelOneOverdue(order.getId());
                cancelled++;
            } catch (RuntimeException e) {
                log.warn("超时订单取消失败 orderId={} cause={}", order.getId(),
                        e.getClass().getSimpleName());
            }
        }
        return cancelled;
    }

    @Transactional
    public void cancelOneOverdue(long orderId) {
        ShopOrder order = orders.findById(orderId).orElse(null);
        if (order == null || order.getStatus() != ShopOrderStatus.PENDING_PAYMENT
                || !order.isPaymentExpiredAt(Instant.now())) {
            return;     // 并发已推进
        }
        releaseUnpaid(order, CancelReason.PAYMENT_TIMEOUT);
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
        // 支付意图一并作废，免得它继续挂在后台支付列表里等一个不会到的付款（纯 DB 写，无网关调用）
        String intentToken = order.getPaymentIntentToken();
        if (intentToken != null) {
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

    /** 意图付款窗跟着订单走：订单一过窗，二维码也不该还能付。 */
    private static Duration remainingWindow(ShopOrder order) {
        if (order.getExpiresAt() == null) {
            return ShopOrder.PAYMENT_WINDOW;
        }
        Duration left = Duration.between(Instant.now(), order.getExpiresAt());
        return left.isNegative() ? Duration.ZERO : left;
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
