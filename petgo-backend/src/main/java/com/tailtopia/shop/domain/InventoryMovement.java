package com.tailtopia.shop.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 库存流水（Story 1.4，建 {@code inventory_movements} 表；AB-10C）。
 *
 * <p><b>append-only</b>：{@code actual} 的每一次变更都必须在此留一行，含前后值与操作人。
 * 一张表同时承担<b>入库单 / 报损单 / 盘点单 / 审计前后值</b>四个职责——三类操作要留存的是同一组
 * 字段，拆多表会让 AB-13D 对账（进货成本 → 销售收入 → 毛利）需要 union。
 *
 * <p>🔴 <b>本实体不落 {@code locked}</b>：{@code locked} 只由订单流程改动，与本 Story 的四条路径
 * 无关，冗余存储会产生第二个真相。
 *
 * <p>🔒 {@code costPrice} 商业敏感：需 {@code shop.cost_view} 权限，服务端按权限决定是否下发；
 * <b>审计详情不记数值</b>——审计日志页的可见范围与进货价权限不同，写进去等于绕过权限位。
 */
@Entity
@Table(name = "inventory_movements")
public class InventoryMovement {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "sku_id", nullable = false, updatable = false)
    private Long skuId;

    @Enumerated(EnumType.STRING)
    @Column(name = "movement_type", nullable = false, updatable = false, length = 32)
    private InventoryMovementType movementType;

    /** 🔴 带符号增量：入库为正、报损为负、盘点为差值。恒满足 after = before + delta。 */
    @Column(name = "qty_delta", nullable = false, updatable = false)
    private long qtyDelta;

    @Column(name = "actual_before", nullable = false, updatable = false)
    private long actualBefore;

    @Column(name = "actual_after", nullable = false, updatable = false)
    private long actualAfter;

    /** 报损 / 盘点必填；入库可空（DB CHECK 兜底）。 */
    @Column(name = "reason", updatable = false, length = 500)
    private String reason;

    /** 采购单号；退货入库时填<b>原订单号</b>（S-9），不得留空。 */
    @Column(name = "purchase_no", updatable = false, length = 64)
    private String purchaseNo;

    @Column(name = "supplier", updatable = false, length = 200)
    private String supplier;

    /** 🔒 进货单价（最小币种单位，IDR 无小数）。商业敏感。 */
    @Column(name = "cost_price", updatable = false)
    private Long costPrice;

    @Column(name = "inbound_date", updatable = false)
    private LocalDate inboundDate;

    /** 🔴 服务端从登录态取，绝不接受前端传入。 */
    @Column(name = "operator_account_id", nullable = false, updatable = false)
    private Long operatorAccountId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected InventoryMovement() {
    }

    private InventoryMovement(long skuId, InventoryMovementType type, long qtyDelta,
            long actualBefore, long actualAfter, long operatorAccountId) {
        this.skuId = skuId;
        this.movementType = type;
        this.qtyDelta = qtyDelta;
        this.actualBefore = actualBefore;
        this.actualAfter = actualAfter;
        this.operatorAccountId = operatorAccountId;
        this.createdAt = Instant.now();
    }

    /**
     * 入库流水（采购 / 退货批次）。
     *
     * <p>🔴 {@code purchaseNo} 与 {@code costPrice} 均<b>不允许留空</b>（S-9）——留空会让
     * 「钱已退、货已回、系统里不存在」，并污染 AB-13C 的资金占用读数。DB CHECK 同样看守。
     */
    public static InventoryMovement inbound(long skuId, InventoryMovementType type, long qty,
            long actualBefore, long actualAfter, String purchaseNo, String supplier,
            long costPrice, LocalDate inboundDate, long operatorAccountId) {
        InventoryMovement m = new InventoryMovement(skuId, type, qty, actualBefore, actualAfter,
                operatorAccountId);
        m.purchaseNo = purchaseNo;
        m.supplier = supplier;
        m.costPrice = costPrice;
        m.inboundDate = inboundDate;
        return m;
    }

    /** 报损流水。{@code qty} 为正数，落库 {@code qtyDelta} 取负。 */
    public static InventoryMovement damage(long skuId, long qty, long actualBefore,
            long actualAfter, String reason, long operatorAccountId) {
        InventoryMovement m = new InventoryMovement(skuId, InventoryMovementType.DAMAGE, -qty,
                actualBefore, actualAfter, operatorAccountId);
        m.reason = reason;
        return m;
    }

    /** 盘点流水。{@code qtyDelta} 为盘点值与原值之差，可正可负可为 0。 */
    public static InventoryMovement stocktake(long skuId, long actualBefore, long countedActual,
            String reason, long operatorAccountId) {
        InventoryMovement m = new InventoryMovement(skuId, InventoryMovementType.STOCKTAKE,
                countedActual - actualBefore, actualBefore, countedActual, operatorAccountId);
        m.reason = reason;
        return m;
    }

    public Long getId() {
        return id;
    }

    public Long getSkuId() {
        return skuId;
    }

    public InventoryMovementType getMovementType() {
        return movementType;
    }

    public long getQtyDelta() {
        return qtyDelta;
    }

    public long getActualBefore() {
        return actualBefore;
    }

    public long getActualAfter() {
        return actualAfter;
    }

    public String getReason() {
        return reason;
    }

    public String getPurchaseNo() {
        return purchaseNo;
    }

    public String getSupplier() {
        return supplier;
    }

    /** 🔒 商业敏感：调用方必须先校验 {@code shop.cost_view}，否则不得下发。 */
    public Long getCostPrice() {
        return costPrice;
    }

    public LocalDate getInboundDate() {
        return inboundDate;
    }

    public Long getOperatorAccountId() {
        return operatorAccountId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
