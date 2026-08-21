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
     * 入库：增加可售（1.4 采购入库 / 退货入库 / 5.4 退货质检通过）。
     *
     * <p>🔴 {@code clearAutomatically/flushAutomatically} 与 {@link #damage} / {@link #stocktakeTo}
     * 同理且必须对齐：{@code InventoryMovementService.doInbound} 在本方法之后<b>同事务读回</b>
     * {@code actual} 写进流水的前后值。若本事务此前已 load 过同一 {@code SkuInventory} 实体，
     * JPQL 批量 UPDATE 绕过持久化上下文，读回的会是<b>一级缓存里的旧实体</b>——
     * 前后值同步错位一个 offset，而 {@code ck_inventory_movements_delta_consistent}
     * <b>拦不住</b>（after = before + delta 在错位后仍然自洽），且<b>不报任何错</b>。
     *
     * <p>当前调用路径下事务内无前置 load，尚不会触发；补齐是为了让这三条原语的安全模式对称，
     * 不留「下次谁把它并进更大的事务就静默出错」的地雷。
     *
     * @return 1 = 成功；0 = 无该 SKU 库存行
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SkuInventory i set i.actual = i.actual + :qty, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP where i.skuId = :skuId")
    int restock(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 报损：减少 {@code actual}（1.4 后台报损，AB-10C）。
     *
     * <p>🔴 <b>条件是「可售 ≥ qty」，不是「actual ≥ qty」。</b>写成后者会允许把已被 {@code locked}
     * 的货报损掉——那等于把已卖给用户的东西销账，直接破坏 {@code locked <= actual} 不变式。
     * <b>条件形状与 {@link #lock} 相同，不要照抄 {@link #commit} 的</b>（commit 减 locked，报损不减）。
     *
     * <p>🔴 {@code clearAutomatically/flushAutomatically} 是必须的：调用方要在同事务内读回
     * {@code actual} 写进流水的前后值，不清持久化上下文会读到旧实体，且<b>不报任何错</b>。
     *
     * @return 1 = 成功；0 = 可售不足或无该 SKU 库存行 → 调用方须拒绝，不得降级为「尽力而为」
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SkuInventory i set i.actual = i.actual - :qty, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.skuId = :skuId and i.actual - i.locked >= :qty")
    int damage(@Param("skuId") long skuId, @Param("qty") long qty);

    /**
     * 盘点调整：把 {@code actual} 直接设为盘点值（1.4 后台盘点，AB-10C）。可增可减。
     *
     * <p>🔴 条件一 {@code locked <= :counted}：盘点值低于锁定量会使 {@code locked > actual}，
     * 等于把已卖出的货抹掉。⚠️ 决策 S-3：<b>真正的超卖来源是盘点/报损，不是并发</b>——本方法就是那个来源。
     *
     * <p>🔴 条件二 {@code i.actual = :expectedBefore}（CAS）：入库/报损是<b>增量</b>操作，前值可由
     * {@code after ∓ qty} 精确反推；<b>盘点是赋值操作，反推不出前值</b>。若改为「先读前值、再赋值」，
     * 两步之间的并发改动会把<b>错误的前值</b>写进审计流水，而且 DB 的
     * {@code ck_inventory_movements_delta_consistent} 查不出来——流水行自身仍然自洽。
     *
     * <p>把期望前值放进 WHERE 后，并发改动使影响行数为 0，<b>失败是响亮的</b>。
     * 这不是被禁止的「先查后改」：判定仍在同一条 SQL 内，应用层的读只用来提出期望值。
     * 语义上也更正确——盘点是「我数出 N、系统当时是 M」，M 变了就说明这次盘点已过期，应当拒绝重盘。
     *
     * @return 1 = 成功；0 = 盘点值低于锁定量 / 前值已被并发改动 / 无该 SKU 库存行
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update SkuInventory i set i.actual = :counted, i.version = i.version + 1, "
            + "i.updatedAt = CURRENT_TIMESTAMP "
            + "where i.skuId = :skuId and i.actual = :expectedBefore and i.locked <= :counted")
    int stocktakeTo(@Param("skuId") long skuId, @Param("counted") long counted,
            @Param("expectedBefore") long expectedBefore);

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
