package com.tailtopia.shop.order.service;

import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.service.InventoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

    /**
     * ⚠️ 自引用代理（照 {@code AccountDisposalService} 范式）。
     *
     * <p><b>无事务方法（{@link #pay} / {@link #cancelOverdue}）调本类事务方法必须经它</b>，
     * 不能 {@code this.xxx(...)} —— 后者绕过 Spring 代理，{@code @Transactional} 直接不生效：
     * 纯 PawCoin 的「扣币 + 出库 + 状态迁移同一事务」会退化成三步各自提交的假原子，
     * 超时扫描的「逐笔独立事务」同样名存实亡。
     */
    private final org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider;

    public ShopOrderPaymentService(ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            InventoryService inventory, CheckoutService checkout,
            PaymentIntentService paymentIntents, PaymentGateway gateway,
            org.springframework.beans.factory.ObjectProvider<ShopOrderPaymentService> selfProvider) {
        this.orders = orders;
        this.orderLines = orderLines;
        this.inventory = inventory;
        this.checkout = checkout;
        this.paymentIntents = paymentIntents;
        this.gateway = gateway;
        this.selfProvider = selfProvider;
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
            ChargeResult charge = gateway.createCharge(new ChargeRequest(
                    intent.getPublicToken(), cash, CURRENCY, PayChannel.QRIS.name(),
                    PaymentPurpose.SHOP_ORDER.name()));
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
        managed.attachPaymentIntent(entity.getPublicToken());
        orders.save(managed);
        return entity;
    }

    /**
     * 纯 PawCoin 单当场结清。
     *
     * <p>🔴 扣币与状态迁移、库存扣减在<b>同一事务</b>：任何一步失败都必须整体回滚 ——
     * 扣了币却没出货，或出了货没扣币，两种都是资损。
     */
    @Transactional
    public void settlePureCoin(long userId, String orderToken) {
        ShopOrder order = orders.findByPublicTokenAndUserId(orderToken, userId)
                .orElseThrow(() -> AppException.notFound("订单不存在"));
        requirePayable(order);
        // 🔴 先状态迁移再扣币（与 ShopOrderPaidHandler#onPaid 同序）：fulfillPaid 对非待支付
        //    静默跳过，若先扣币，竞态下会留下「币已扣、单已取消」的无补偿态。
        //    同一事务内，扣币失败（余额被别处花掉）整体回滚，出库与扣币仍是原子的。
        if (fulfillPaid(order)) {
            checkout.settlePawCoinSegment(order);
        }
    }

    /**
     * 支付成功后的收尾：<b>状态迁移 + 库存扣减出库</b>。
     *
     * <p>🔴 双重守卫：<b>非待支付状态直接返回 {@code false}</b>（重复回调 / 轮询二次到达、
     * 或订单已被取消时的内存守卫）；而「回调 vs 取消/懒过期」两个事务<b>同时</b>读到
     * {@code PENDING_PAYMENT} 的真并发，由 {@code shop_orders.version}（{@code @Version} 乐观锁）
     * 裁决 —— 后提交者在同一行上撞版本号整体回滚，于是 {@code inventory.commit} 与
     * {@code inventory.release} 不会双双落库，一件库存也不会被扣第二次。
     *
     * @return 本次是否真的把订单从待支付推进为待发货；调用方据此决定<b>是否扣 PawCoin 段</b>
     *     —— 非 {@code PENDING_PAYMENT} 时绝不扣币
     */
    @Transactional
    public boolean fulfillPaid(ShopOrder order) {
        if (order.getStatus() != ShopOrderStatus.PENDING_PAYMENT) {
            return false;   // 已处理过（重复回调 / 轮询二次到达）或已被取消
        }
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            inventory.commit(line.getSkuId(), line.getQty());
        }
        order.transitionTo(ShopOrderStatus.PENDING_SHIPMENT);
        orders.save(order);
        return true;
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
        releaseAndCancel(order, "USER_CANCEL");
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
            releaseAndCancel(order, "TIMEOUT");
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
        releaseAndCancel(order, "TIMEOUT");
    }

    /**
     * 🔴 <b>释放库存与状态迁移在同一事务内</b>（AD-8）。
     * 分开做的话，中间崩一次就会留下「已取消但库存还锁着」的幽灵占用 ——
     * 而这种占用没有任何后续流程会来清理它。
     */
    private void releaseAndCancel(ShopOrder order, String reason) {
        for (ShopOrderLine line : orderLines.findByOrderIdOrderByIdAsc(order.getId())) {
            inventory.release(line.getSkuId(), line.getQty());
        }
        order.transitionTo(ShopOrderStatus.CANCELLED);
        orders.save(order);
        // 支付意图一并作废，免得它继续挂在后台支付列表里等一个不会到的付款
        String intentToken = order.getPaymentIntentToken();
        if (intentToken != null) {
            paymentIntents.failByToken(intentToken, reason);
        }
        log.info("电商订单取消 token={} reason={}", order.getPublicToken(), reason);
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
