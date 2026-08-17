package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.SkuInventory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * SKU 库存仓储（Story 1.2）。<b>防超卖核心</b>：四条原语全部是<b>单行原子条件 UPDATE</b>，
 * 自带行锁、天然串行化同一 SKU 的并发操作，<b>返回影响行数</b>（0 = 条件不满足 → 拒绝）。
 *
 * <p>🔴 <b>禁应用层读改写</b>（并发丢更新 / 超卖）。范式照 {@code PawCoinWalletRepository.applyDelta}
 * 与 V1.0 决策 F11（兽医抢单 {@code WHERE status='WAITING'} 判影响行数）。
 *
 * <p>🔴 <b>禁 SELECT ... FOR UPDATE / Redis 扣减 / 分布式锁 / 任何新中间件</b>（NFR-1 · AD-6）。
 */
public interface SkuInventoryRepository extends JpaRepository<SkuInventory, Long> {

    Optional<SkuInventory> findBySkuId(long skuId);

    List<SkuInventory> findBySkuIdIn(List<Long> skuIds);

    /**
     * 锁定：可售 → 锁定（3.4 提交订单）。
     * <b>条件与写入在同一条 SQL 内原子完成</b>，{@code (actual - locked) >= qty} 保证不超卖。
     *
     * @return 1 = 锁定成功；0 = 可售不足或无该 SKU 库存行 → 调用方须当作「已售罄」拒绝
     */
    @Modifying
    @Query("update SkuInventory i set i.locked = i.locked + :qty, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.skuId = :skuId and i.actual - i.locked >= :qty")
    int lock(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 释放：锁定 → 可售（3.4 支付超时 / 用户取消）。
     *
     * @return 1 = 成功；0 = 锁定量不足 → 说明状态机已被别处推进过，调用方须显式处理而非忽略
     */
    @Modifying
    @Query("update SkuInventory i set i.locked = i.locked - :qty, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.skuId = :skuId and i.locked >= :qty")
    int release(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 出库：锁定 → 扣减（3.8 支付成功）。同时减 {@code actual} 与 {@code locked}，
     * 保证 {@code locked <= actual} 不变式在中间态也成立。
     *
     * @return 1 = 成功；0 = 锁定量或实际库存不足
     */
    @Modifying
    @Query("update SkuInventory i set i.actual = i.actual - :qty, i.locked = i.locked - :qty, "
            + "i.version = i.version + 1, i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.skuId = :skuId and i.locked >= :qty and i.actual >= :qty")
    int commit(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 入库：增加可售（1.4 采购入库 / 报损盘点 / 5.4 退货质检通过）。
     *
     * @return 1 = 成功；0 = 无该 SKU 库存行
     */
    @Modifying
    @Query("update SkuInventory i set i.actual = i.actual + :qty, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP where i.skuId = :skuId")
    int restock(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 首次使用前幂等建库存行（并发安全）：{@code ON CONFLICT DO NOTHING} 靠
     * {@code uq_sku_inventory_sku} 兜住并发建。返回 1=新建 / 0=已存在。
     * 范式照 {@code PawCoinWalletRepository} 的建钱包。
     */
    @Modifying
    @Query(value = "INSERT INTO sku_inventory (sku_id, actual, locked, version, created_at, updated_at) "
            + "VALUES (:skuId, 0, 0, 0, now(), now()) ON CONFLICT (sku_id) DO NOTHING",
            nativeQuery = true)
    int ensureRow(@Param("skuId") long skuId);
}
