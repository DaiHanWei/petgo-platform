package com.tailtopia.merchant.po.service;

import com.tailtopia.merchant.po.domain.PurchaseOrder;
import com.tailtopia.merchant.po.domain.PurchaseOrderLine;
import com.tailtopia.merchant.po.domain.PurchaseOrderStatus;
import com.tailtopia.merchant.po.repository.PurchaseOrderLineRepository;
import com.tailtopia.merchant.po.repository.PurchaseOrderRepository;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.event.PaymentIntentPaidEvent;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.pay.ChargeRequest;
import com.tailtopia.shared.pay.ChargeResult;
import com.tailtopia.shared.pay.PaymentGateway;
import com.tailtopia.shop.service.InventoryService;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 进货单 QRIS 支付（契约 5.5，FR-13）。支付用途 {@code MERCHANT_PO}，付款人 = 门店（{@link PurchaseOrderPayer}）。
 *
 * <p>🔴 <b>天然幂等</b>：意图幂等键绑进货单 token，同一单已有未过期意图就取回它（同一个二维码），否则新建；
 * 意图 TTL = 距付款截止的剩余时长，故 {@code expires_at} 恒不晚于 {@code payment_deadline}。
 *
 * <p>⚠️ 网关调用在事务之外（同 {@code ShopOrderPaymentService}）：建意图与回填各自短事务。
 */
@Service
public class PurchaseOrderPaymentService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseOrderPaymentService.class);
    private static final String CURRENCY = "IDR";

    private final PurchaseOrderRepository orders;
    private final PurchaseOrderLineRepository lines;
    private final PurchaseOrderService purchaseOrders;
    private final PaymentIntentService paymentIntents;
    private final PaymentGateway gateway;
    private final InventoryService inventory;
    /** 自引用代理：无事务的 {@link #pay} 调本类事务方法必须经它（{@code this.xxx()} 绕过代理，事务不生效）。 */
    private final ObjectProvider<PurchaseOrderPaymentService> selfProvider;

    public PurchaseOrderPaymentService(PurchaseOrderRepository orders, PurchaseOrderLineRepository lines,
            PurchaseOrderService purchaseOrders, PaymentIntentService paymentIntents, PaymentGateway gateway,
            InventoryService inventory, ObjectProvider<PurchaseOrderPaymentService> selfProvider) {
        this.orders = orders;
        this.lines = lines;
        this.purchaseOrders = purchaseOrders;
        this.paymentIntents = paymentIntents;
        this.gateway = gateway;
        this.inventory = inventory;
        this.selfProvider = selfProvider;
    }

    /**
     * 接口 2：发起支付。
     *
     * @throws MerchantApiException {@code not-found} / {@code store-disabled} / {@code not-payable}（附 {@code po_status}）
     */
    public PoPayment pay(long storeId, String poToken) {
        PurchaseOrderPaymentService self = selfProvider.getObject();
        Prepared p = self.prepare(storeId, poToken);
        if (p.notPayableStatus() != null) {
            // 在 prepare 的事务提交之后再抛：截止后的惰性取消必须落库，不能随异常一起回滚。
            throw new MerchantApiException(409, MerchantApiException.NOT_PAYABLE, "Purchase order is not payable",
                    Map.of("po_status", p.notPayableStatus().name()));
        }
        PaymentIntent intent = p.intent();
        String payload = payloadOf(intent);
        if (intent.getGatewayRef() == null) {
            ChargeResult charge;
            try {
                charge = gateway.createCharge(new ChargeRequest(intent.getPublicToken(), intent.getAmount(), CURRENCY,
                        PayChannel.QRIS.name(), PaymentPurpose.MERCHANT_PO.name()));
            } catch (RuntimeException e) {
                // 意图置 FAILED 留档（request_id 可对账），下次发起走新意图、新 request_id。
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
        if (payload == null || payload.isBlank()) {
            // 契约要求 qris_content 非空白：网关没给码就当不可用（商家平台按 503 显示），不发一个空码出去。
            throw new IllegalStateException("payment gateway returned no QRIS payload");
        }
        // 意图 TTL 按 prepare 时刻算，落库的 expires_at 会比截止晚几毫秒；对外按截止封顶（契约：不晚于 payment_deadline）。
        Instant expiresAt = intent.getExpiresAt() == null || intent.getExpiresAt().isAfter(p.paymentDeadline())
                ? p.paymentDeadline() : intent.getExpiresAt();
        return new PoPayment(poToken, payload, intent.getAmount(), expiresAt);
    }

    /** 校验 + 惰性取消 + 幂等取/建意图（同一事务）。 */
    @Transactional
    public Prepared prepare(long storeId, String poToken) {
        PurchaseOrder po = orders.findByPublicTokenAndMerchantStoreId(poToken, storeId)
                .orElseThrow(MerchantApiException::notFound);
        purchaseOrders.requireStoreActive(storeId);
        Instant now = Instant.now();
        purchaseOrders.expireIfOverdue(po, now);
        if (po.getStatus() != PurchaseOrderStatus.PENDING_PAYMENT) {
            return new Prepared(null, po.getStatus(), po.getPaymentDeadline());
        }
        Duration ttl = Duration.between(now, po.getPaymentDeadline());
        var response = paymentIntents.createIntent(PurchaseOrderPayer.intentUserIdFor(storeId),
                PaymentPurpose.MERCHANT_PO, PayChannel.QRIS, po.getGoodsAmount(), CURRENCY,
                "merchant-po-pay:" + po.getPublicToken(), ttl);
        PaymentIntent intent = paymentIntents.findByToken(response.token()).orElseThrow();
        po.attachPaymentIntent(intent.getPublicToken(), now);
        orders.save(po);
        return new Prepared(intent, null, po.getPaymentDeadline());
    }

    /**
     * 到账（{@code PENDING → PAID} 的同一事务，同 {@code ShopOrderPaidHandler} 范式：同步 + MANDATORY）。
     *
     * <p>扣减库存 + 写 {@code paid_at} + 转 {@code PAID} 要么一起提交要么一起回滚。门店停用不阻止到账（FR-40）。
     * 进货单已非待付款（超时已取消）→ 只记日志不抛：抛了会把意图的 markPaid 一起回滚，那才是真的丢账；
     * 截止后到账的款按支付侧既有规则人工处理（契约 5.2 末段）。
     */
    @EventListener
    @Transactional(propagation = Propagation.MANDATORY)
    public void onPaid(PaymentIntentPaidEvent event) {
        if (event.purpose() != PaymentPurpose.MERCHANT_PO) {
            return;
        }
        PurchaseOrder po = orders.findByPaymentIntentToken(event.publicToken()).orElse(null);
        if (po == null) {
            log.warn("进货单到账事件无匹配进货单，忽略 intent={}", event.publicToken());
            return;
        }
        if (po.getStatus() != PurchaseOrderStatus.PENDING_PAYMENT) {
            log.warn("进货单到账时已非待付款，需人工处理 po={} status={} intent={}", po.getPublicToken(),
                    po.getStatus(), event.publicToken());
            return;
        }
        for (PurchaseOrderLine l : lines.findByPurchaseOrderIdOrderByLineNoAsc(po.getId())) {
            inventory.commit(l.getSkuId(), l.getQty());
        }
        po.markPaid(Instant.now());
        orders.save(po);
        log.info("进货单到账 po={} intent={}", po.getPublicToken(), event.publicToken());
    }

    private static String payloadOf(PaymentIntent intent) {
        Map<String, Object> meta = intent.getGatewayMeta();
        return meta == null ? null : (String) meta.get("payload");
    }

    /** @param notPayableStatus 非空 = 不可支付（当前状态）；此时 {@code intent} 为 null */
    public record Prepared(PaymentIntent intent, PurchaseOrderStatus notPayableStatus, Instant paymentDeadline) {
    }

    /** 契约 5.5 {@code Payment}。🔒 {@code qrisContent} 不进日志。 */
    public record PoPayment(String poToken, String qrisContent, long amount, Instant expiresAt) {
    }
}
