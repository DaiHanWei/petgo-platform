package com.tailtopia.shop.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.SkuInventory;
import com.tailtopia.shop.domain.StockStatus;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 库存原语（Story 1.2，FR-95 / AD-6）。
 *
 * <p>🔒 <b>本类是整条电商链路防超卖的唯一防线。</b>四条原语全部委托给仓储的
 * <b>单条条件原子 UPDATE</b>，依影响行数判定成败——<b>本类内绝不出现「先查可售、再扣减」</b>，
 * 那两步之间的窗口就是超卖发生的地方。
 *
 * <p>🔴 禁分布式锁 / 禁 Redis 扣减 / 禁 {@code SELECT ... FOR UPDATE} / 禁任何新中间件
 * （NFR-1 · AD-6，范式照 V1.0 决策 F11 兽医抢单）。
 *
 * <p><b>本 Story 只提供原语，不实现调用它们的业务流程</b>：下单锁定属 3.4、支付出库属 3.8、
 * 超时释放属 3.4、采购入库属 1.4、退货入库属 5.4。
 */
@Service
public class InventoryService {

    private final SkuInventoryRepository inventory;
    private final long lowStockThreshold;

    public InventoryService(SkuInventoryRepository inventory,
            @Value("${petgo.shop.low-stock-threshold:5}") long lowStockThreshold) {
        this.inventory = inventory;
        this.lowStockThreshold = lowStockThreshold;
    }

    /**
     * 可售 → 锁定（3.4 提交订单）。
     *
     * @throws AppException 可售不足 → {@code conflict}「已售罄」。
     *     🔴 <b>明确失败，不静默、不重试、不排队</b>（FR-95）——并发下失败方必须拿到清楚的错误。
     */
    @Transactional
    public void lock(long skuId, long qty) {
        requirePositive(qty);
        if (inventory.lock(skuId, qty) == 0) {
            throw AppException.conflict("已售罄");
        }
    }

    /** 锁定 → 可售（3.4 支付超时 / 用户取消）。 */
    @Transactional
    public void release(long skuId, long qty) {
        requirePositive(qty);
        if (inventory.release(skuId, qty) == 0) {
            // 不静默吞掉：影响 0 行说明锁定量已被别处改动，属状态机不一致，必须暴露
            throw AppException.conflict("库存锁定量不足，无法释放");
        }
    }

    /** 锁定 → 扣减出库（3.8 支付成功）。同时减 actual 与 locked，保持不变式。 */
    @Transactional
    public void commit(long skuId, long qty) {
        requirePositive(qty);
        if (inventory.commit(skuId, qty) == 0) {
            throw AppException.conflict("库存锁定量或实际库存不足，无法出库");
        }
    }

    /** 增加可售（1.4 采购入库 / 5.4 退货质检通过入库）。 */
    @Transactional
    public void restock(long skuId, long qty) {
        requirePositive(qty);
        if (inventory.restock(skuId, qty) == 0) {
            throw AppException.notFound("库存记录不存在");
        }
    }

    /** 幂等建库存行（SKU 创建后调用，属 1.3；此处提供原语）。 */
    @Transactional
    public void ensureRow(long skuId) {
        inventory.ensureRow(skuId);
    }

    /**
     * 按可售库存计算展示状态（FR-95）。
     *
     * <p>🔴 <b>售罄不下架</b>——本方法只决定展示状态，不影响商品可见性。
     */
    public StockStatus statusOf(long available) {
        if (available <= 0) {
            return StockStatus.OUT_OF_STOCK;
        }
        return available <= lowStockThreshold ? StockStatus.LOW_STOCK : StockStatus.IN_STOCK;
    }

    /** 批量取可售库存，避免详情/列表出现 N+1。无库存行的 SKU 视为 0（售罄）。 */
    @Transactional(readOnly = true)
    public Map<Long, Long> availableBySkuId(List<Long> skuIds) {
        if (skuIds.isEmpty()) {
            return Map.of();
        }
        return inventory.findBySkuIdIn(skuIds).stream()
                .collect(Collectors.toMap(SkuInventory::getSkuId, SkuInventory::available,
                        (a, b) -> a));
    }

    public long lowStockThreshold() {
        return lowStockThreshold;
    }

    private static void requirePositive(long qty) {
        if (qty <= 0) {
            throw AppException.validation("数量必须为正");
        }
    }

    /** 便于调用方在同一处取到「可售 + 状态」。 */
    public Function<Long, StockStatus> statusFn() {
        return this::statusOf;
    }
}
