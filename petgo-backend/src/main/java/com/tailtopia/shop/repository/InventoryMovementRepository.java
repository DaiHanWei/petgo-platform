package com.tailtopia.shop.repository;

import com.tailtopia.shop.domain.InventoryMovement;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * 库存流水仓储（Story 1.4，AB-10C）。
 *
 * <p><b>append-only</b>：本仓储<b>只有写入与查询，不提供任何更新或删除</b>——流水一旦落下就是
 * 审计事实。要撤销一笔操作，应再落一笔反向流水，而不是改历史。
 */
public interface InventoryMovementRepository extends JpaRepository<InventoryMovement, Long> {

    /** 某 SKU 的流水，最新在前（后台流水区）。 */
    List<InventoryMovement> findBySkuIdOrderByCreatedAtDescIdDesc(long skuId, Limit limit);

    /**
     * S-9：取该 SKU <b>最近一次采购入库</b>的进货单价，用于退货入库自动带出。
     *
     * <p>🔴 只看 {@code PURCHASE_INBOUND}，<b>不看 {@code RETURN_INBOUND}</b>——否则退货入库会
     * 抄上一次退货入库的价，一旦第一次抄错就会沿着退货链一路传播下去。
     *
     * <p>返回空 = 该 SKU 从无采购记录 → 调用方必须<b>明确报错拒绝</b>，
     * 🔴 <b>不得静默以 0 入库</b>（0 成本会让 AB-13A 毛利虚高）。
     */
    Optional<InventoryMovement>
            findFirstBySkuIdAndMovementTypeOrderByCreatedAtDescIdDesc(
                    long skuId, com.tailtopia.shop.domain.InventoryMovementType movementType);
}
