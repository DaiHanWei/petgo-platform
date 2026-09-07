package com.tailtopia.shop.service;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.InventoryMovement;
import com.tailtopia.shop.domain.InventoryMovementType;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.domain.SkuInventory;
import com.tailtopia.shop.repository.InventoryMovementRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存增减的四条合法路径（Story 1.4，AB-10C）。
 *
 * <p>🔴 <b>不提供直接编辑库存数字的入口</b>——AB-10C 明令：直接改数会使库存与采购成本脱钩，
 * 令 AB-13A 毛利核算失真。每一次 {@code actual} 变更都在 {@code inventory_movements} 留一行。
 *
 * <p>每个入口的统一骨架，四步缺一不可：
 * <ol>
 *   <li><b>单条条件原子 UPDATE 判影响行数</b>（AD-6）——判定在 SQL 里，不在应用层</li>
 *   <li>同事务读回 {@code actual}（原语已带 {@code clearAutomatically}，读到的是新值不是缓存实体）</li>
 *   <li>落流水（含前后值、操作人、原因）</li>
 *   <li>写审计</li>
 * </ol>
 *
 * <p>🔒 <b>审计详情绝不写进货单价数值</b>：审计日志页的可见范围与 {@code shop.cost_view} 不同，
 * 写进去等于绕过权限位（沿用 Story 1.3 对 {@code SHOP_PRODUCT_COST_UPDATED} 的同一处置）。
 *
 * <p>⚠️ 决策 S-3：<b>真正的超卖来源是盘点/报损/退货入库撤销，不是并发。</b>并发那一半由 Story 1.2
 * 的条件原子写解决；本类打开的正是另一半，故每条路径都带不变式条件。
 */
@Service
public class InventoryMovementService {

    private final SkuInventoryRepository inventory;
    private final InventoryMovementRepository movements;
    private final ShopSkuRepository skus;
    private final AdminAuditService audit;

    public InventoryMovementService(SkuInventoryRepository inventory,
            InventoryMovementRepository movements, ShopSkuRepository skus,
            AdminAuditService audit) {
        this.inventory = inventory;
        this.movements = movements;
        this.skus = skus;
        this.audit = audit;
    }

    /**
     * 采购入库（{@code actual +}）。
     *
     * <p>🔒 调用方必须先校验 {@code shop.inventory_edit} <b>与</b> {@code shop.cost_edit} 双权限
     * （2026-08-17 产品确认）——进货单价按 S-9 不允许留空，而单价是商业敏感数据。
     */
    @Transactional
    public InventoryMovement receivePurchase(long skuId, long qty, String purchaseNo,
            String supplier, Long costPrice, LocalDate inboundDate, long actorAccountId) {
        requirePositive(qty);
        requireText(purchaseNo, "采购单号必填");
        requireCost(costPrice);
        return doInbound(skuId, InventoryMovementType.PURCHASE_INBOUND, qty, purchaseNo, supplier,
                costPrice, inboundDate, actorAccountId);
    }

    /**
     * 退货入库批次（{@code actual +}）—— 质检通过入库与拒收货入库都走这里（S-9 / SPEC-11）。
     *
     * <p>采购单号<b>填原订单号</b>；进货单价<b>由系统取该 SKU 最近一次采购入库单价</b>，
     * 因此本入口<b>只需 {@code shop.inventory_edit}</b>，不需 {@code shop.cost_edit}。
     *
     * @throws AppException 该 SKU 从无采购入库记录 → 无历史单价可取。
     *     🔴 <b>明确拒绝，不得静默以 0 入库</b>——0 成本会让 AB-13A 毛利虚高。
     */
    @Transactional
    public InventoryMovement receiveReturn(long skuId, long qty, String originalOrderNo,
            LocalDate inboundDate, long actorAccountId) {
        requirePositive(qty);
        requireText(originalOrderNo, "原订单号必填");
        long costPrice = lastPurchaseCostPrice(skuId);
        return doInbound(skuId, InventoryMovementType.RETURN_INBOUND, qty, originalOrderNo, null,
                costPrice, inboundDate, actorAccountId);
    }

    /**
     * 报损（{@code actual −}）。
     *
     * <p>🔴 上限是<b>可售库存</b>（{@code actual - locked}），不是 {@code actual}——判定在
     * {@link SkuInventoryRepository#damage} 的 WHERE 里。
     */
    @Transactional
    public InventoryMovement writeOff(long skuId, long qty, String reason, long actorAccountId) {
        requirePositive(qty);
        requireText(reason, "报损原因必填");

        if (inventory.damage(skuId, qty) == 0) {
            // 不静默降级为「能报多少报多少」：影响 0 行说明可售不足，运营必须重新核对实物
            throw AppException.conflict("可售库存不足，无法报损（报损上限是可售库存，已锁定的货不可报损）");
        }
        long after = readActual(skuId);
        InventoryMovement m = movements.save(
                InventoryMovement.damage(skuId, qty, after + qty, after, reason, actorAccountId));

        audit.record(actorAccountId, AuditActions.SHOP_INVENTORY_DAMAGED, "SHOP_SKU",
                skuToken(skuId),
                "报损 %d 件：%d → %d，原因：%s".formatted(qty, after + qty, after, reason));
        return m;
    }

    /**
     * 盘点调整（{@code actual} 设为盘点值，可增可减）。
     *
     * <p>🔴 下限是 {@code locked}；且带期望前值的 CAS —— 盘点期间库存被并发改动则本次盘点已过期，
     * 拒绝并要求重盘，而不是覆盖（详见 {@link SkuInventoryRepository#stocktakeTo}）。
     */
    @Transactional
    public InventoryMovement stocktake(long skuId, long countedActual, String reason,
            long actorAccountId) {
        if (countedActual < 0) {
            throw AppException.validation("盘点值不能为负");
        }
        requireText(reason, "盘点差异原因必填");

        long before = readActual(skuId);
        if (inventory.stocktakeTo(skuId, countedActual, before) == 0) {
            throw AppException.conflict(
                    "盘点未生效：盘点值低于锁定库存，或该 SKU 库存在盘点期间已被改动，请重新盘点");
        }
        InventoryMovement m = movements.save(InventoryMovement.stocktake(skuId, before,
                countedActual, reason, actorAccountId));

        audit.record(actorAccountId, AuditActions.SHOP_INVENTORY_STOCKTAKED, "SHOP_SKU",
                skuToken(skuId),
                "盘点调整：%d → %d，原因：%s".formatted(before, countedActual, reason));
        return m;
    }

    /** 某 SKU 的流水，最新在前。🔒 进货单价是否下发由调用方按 {@code shop.cost_view} 决定。 */
    @Transactional(readOnly = true)
    public List<InventoryMovement> recentMovements(long skuId, int limit) {
        return movements.findBySkuIdOrderByCreatedAtDescIdDesc(skuId, Limit.of(limit));
    }

    /**
     * S-9：该 SKU 最近一次<b>采购</b>入库的进货单价。
     *
     * @throws AppException 无采购记录 —— 🔴 不得回退为 0
     */
    @Transactional(readOnly = true)
    public long lastPurchaseCostPrice(long skuId) {
        return movements
                .findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                        skuId, InventoryMovementType.PURCHASE_INBOUND)
                .map(InventoryMovement::getCostPrice)
                .orElseThrow(() -> AppException.validation(
                        "该 SKU 尚无采购入库记录，取不到进货单价，无法登记退货入库。"
                                + "请先登记一次采购入库（0 成本入库会让毛利核算失真）"));
    }

    // ---------- 内部 ----------

    private InventoryMovement doInbound(long skuId, InventoryMovementType type, long qty,
            String purchaseNo, String supplier, long costPrice, LocalDate inboundDate,
            long actorAccountId) {
        if (inventory.restock(skuId, qty) == 0) {
            throw AppException.notFound("库存记录不存在，无法入库");
        }
        long after = readActual(skuId);
        InventoryMovement m = movements.save(InventoryMovement.inbound(skuId, type, qty,
                after - qty, after, purchaseNo, supplier, costPrice,
                inboundDate == null ? LocalDate.now() : inboundDate, actorAccountId));

        // 🔒 详情记数量与前后值，绝不写 costPrice 数值
        audit.record(actorAccountId, AuditActions.SHOP_INVENTORY_RECEIPT_CREATED, "SHOP_SKU",
                skuToken(skuId),
                "%s %d 件：%d → %d，单号：%s".formatted(
                        type == InventoryMovementType.PURCHASE_INBOUND ? "采购入库" : "退货入库",
                        qty, after - qty, after, purchaseNo));
        return m;
    }

    /**
     * 读回当前 {@code actual}。
     *
     * <p>安全性：条件 UPDATE 成功后该行持有写锁直到提交，同事务内再读拿到的是稳定值，
     * 其他事务改不动它。原语上的 {@code clearAutomatically} 保证读的是库不是持久化上下文缓存。
     */
    private long readActual(long skuId) {
        return inventory.findBySkuId(skuId)
                .map(SkuInventory::getActual)
                .orElseThrow(() -> AppException.notFound("库存记录不存在"));
    }

    private String skuToken(long skuId) {
        return skus.findById(skuId).map(ShopSku::getPublicToken).orElse(String.valueOf(skuId));
    }

    private static void requirePositive(long qty) {
        if (qty <= 0) {
            throw AppException.validation("数量必须为正");
        }
    }

    private static void requireText(String value, String message) {
        if (value == null || value.isBlank()) {
            throw AppException.validation(message);
        }
    }

    /** S-9：进货单价不允许留空。 */
    private static void requireCost(Long costPrice) {
        if (costPrice == null || costPrice < 0) {
            throw AppException.validation("进货单价必填且不能为负（S-9：入库单不允许留空进货单价）");
        }
    }
}
