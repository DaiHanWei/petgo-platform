package com.tailtopia.shop.order.domain;

import com.tailtopia.shop.domain.ReturnPolicy;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 订单行（Story 3.2）。
 *
 * <p>🔴 <b>商品名/规格/单价/退货规则全部快照</b>：商品改名改价、SKU 下架都不得改写历史订单。
 * 退货规则尤其重要 —— <b>下单时承诺的是什么，退货时就按什么算</b>（FR-104）；
 * 若读当前 SKU 的规则，运营事后把「可退」改成「开封不退」就会溯及既往地毁约。
 */
@Entity
@Table(name = "shop_order_lines")
public class ShopOrderLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, updatable = false)
    private Long orderId;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Column(name = "product_name", nullable = false, updatable = false, length = 120)
    private String productName;

    @Column(name = "spec_name", nullable = false, updatable = false, length = 60)
    private String specName;

    @Column(name = "unit_price", nullable = false, updatable = false)
    private long unitPrice;

    @Column(name = "qty", nullable = false, updatable = false)
    private int qty;

    @Column(name = "line_total", nullable = false, updatable = false)
    private long lineTotal;

    @Enumerated(EnumType.STRING)
    @Column(name = "return_policy", nullable = false, updatable = false, length = 32)
    private ReturnPolicy returnPolicy;

    /** Epic 5 用。 */
    @Column(name = "refunded_qty", nullable = false)
    private int refundedQty;

    /**
     * 🔴 下单入口来源（Story 3.4，IR 前移）。AB-13B 算转化率的<b>服务端权威依据</b> ——
     * 只靠客户端埋点会被广告拦截与事件丢失打穿分母，而这个数字是裁决 A-16 的唯一依据。
     */
    @Column(name = "entry_source", updatable = false, length = 32)
    private String entrySource;

    /** 若来自复购触发卡，记其类型；非触发来源为 NULL。 */
    @Column(name = "trigger_type", updatable = false, length = 32)
    private String triggerType;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ShopOrderLine() {
    }

    public static ShopOrderLine of(long orderId, long skuId, String productName, String specName,
            long unitPrice, int qty, ReturnPolicy returnPolicy) {
        ShopOrderLine l = new ShopOrderLine();
        l.orderId = orderId;
        l.skuId = skuId;
        l.productName = productName;
        l.specName = specName;
        l.unitPrice = unitPrice;
        l.qty = qty;
        l.lineTotal = unitPrice * qty;
        l.returnPolicy = returnPolicy;
        l.createdAt = Instant.now();
        return l;
    }

    /** 归因随建行落库。null 表示来源未知（如后台代下单），不是错误。 */
    public void attributeTo(String entrySource, String triggerType) {
        this.entrySource = entrySource;
        this.triggerType = triggerType;
    }

    public String getEntrySource() {
        return entrySource;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public Long getId() {
        return id;
    }

    public Long getOrderId() {
        return orderId;
    }

    public Long getSkuId() {
        return skuId;
    }

    public String getProductName() {
        return productName;
    }

    public String getSpecName() {
        return specName;
    }

    public long getUnitPrice() {
        return unitPrice;
    }

    public int getQty() {
        return qty;
    }

    public long getLineTotal() {
        return lineTotal;
    }

    /** 🔴 下单时承诺的规则，退货时按它算（FR-104）。 */
    public ReturnPolicy getReturnPolicy() {
        return returnPolicy;
    }

    public int getRefundedQty() {
        return refundedQty;
    }
}
