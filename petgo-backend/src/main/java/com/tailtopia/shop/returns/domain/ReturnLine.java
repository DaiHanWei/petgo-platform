package com.tailtopia.shop.returns.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * 退货行（Story 5.1，FR-104A 行级部分退货）。
 *
 * <p>🔴 <b>指向订单行而不是 SKU</b>：同一个 SKU 可能在一张订单里出现两行
 * （不同时间加购、不同价格快照）。指向 SKU 就说不清退的是哪一行的钱。
 */
@Entity
@Table(name = "return_lines")
public class ReturnLine {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "return_request_id", nullable = false, updatable = false)
    private Long returnRequestId;

    @Column(name = "order_line_id", nullable = false, updatable = false)
    private Long orderLineId;

    @Column(name = "qty", nullable = false)
    private int qty;

    /** 该行应退的商品金额（不含运费），按下单时的行单价快照算。 */
    @Column(name = "line_refund_amount", nullable = false)
    private long lineRefundAmount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ReturnLine() {
    }

    public static ReturnLine of(long returnRequestId, long orderLineId, int qty,
            long lineRefundAmount) {
        ReturnLine l = new ReturnLine();
        l.returnRequestId = returnRequestId;
        l.orderLineId = orderLineId;
        l.qty = qty;
        l.lineRefundAmount = lineRefundAmount;
        l.createdAt = Instant.now();
        return l;
    }

    public Long getId() {
        return id;
    }

    public Long getReturnRequestId() {
        return returnRequestId;
    }

    public Long getOrderLineId() {
        return orderLineId;
    }

    public int getQty() {
        return qty;
    }

    public long getLineRefundAmount() {
        return lineRefundAmount;
    }
}
