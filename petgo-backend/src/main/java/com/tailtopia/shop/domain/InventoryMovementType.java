package com.tailtopia.shop.domain;

/**
 * 库存流水类型（Story 1.4，AB-10C）—— {@code actual} 增减的四条合法路径，补齐 SPEC-7 的缺口。
 *
 * <p>🔴 <b>不存在第五条路径。</b>AB-10C 明令不提供直接编辑库存数字的入口：直接改数会使库存
 * 与采购成本脱钩，令 AB-13A 毛利核算失真。任何 {@code actual} 变更都必须落一条
 * {@code inventory_movements}。
 *
 * <p>⚠️ 注意本枚举<b>不含</b>订单侧的锁定/释放/出库——那三者只动 {@code locked}
 * （出库同时动 {@code actual}，但由 Story 3.8 的 {@code commit} 原语负责，属订单流程）。
 */
public enum InventoryMovementType {

    /**
     * 采购入库（{@code actual +}）。
     *
     * <p>🔒 需 {@code shop.inventory_edit} <b>与</b> {@code shop.cost_edit} 双权限——
     * 进货单价按 S-9 不允许留空，而单价是商业敏感数据（2026-08-17 产品确认）。
     */
    PURCHASE_INBOUND,

    /**
     * 退货入库批次（{@code actual +}）—— 质检通过入库与拒收货入库都走这里。
     *
     * <p>🔴 <b>与采购入库分开是有意的</b>：二次销售风险不同（AB-10C）。
     *
     * <p>S-9：采购单号<b>填原订单号</b>、进货单价<b>取该 SKU 最近一次采购入库单价</b>，两者均不得留空。
     * 因单价由系统带出而非人工填写，本类型<b>只需 {@code shop.inventory_edit}</b>。
     */
    RETURN_INBOUND,

    /**
     * 报损（{@code actual −}）。
     *
     * <p>🔴 合法上限是<b>可售库存</b>（{@code actual - locked}），<b>不是</b> {@code actual}——
     * 报损已被锁定的货等于把卖给用户的东西销账，会直接破坏 {@code locked <= actual} 不变式。
     */
    DAMAGE,

    /**
     * 盘点调整（{@code actual} 设为盘点值，可增可减）。
     *
     * <p>🔴 合法下限是 {@code locked}：盘点值低于锁定量同样破坏不变式。
     *
     * <p>⚠️ 决策 S-3：<b>真正的超卖来源是盘点/报损/退货入库撤销，不是并发。</b>本类型是其中之一。
     */
    STOCKTAKE
}
