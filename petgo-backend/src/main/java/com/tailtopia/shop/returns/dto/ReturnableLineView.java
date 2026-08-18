package com.tailtopia.shop.returns.dto;

/**
 * 退货申请页的可退行（Story 5.7，FR-104A / FR-104）。
 *
 * <p>🔴 <b>「开封不退」的行保留可见但置灰不可勾选</b>，并直接标注原因 ——
 * 比提交后再驳回体验好得多。故本视图<b>不过滤掉</b>不可退的行，而是带上
 * {@code selectable} 与 {@code blockedReason} 让前端渲染成置灰态。
 *
 * <p>🔴 这里是<b>「开封不退」三处明示的第 3 处</b>
 * （第 1 处商品详情页 Story 1.7，第 2 处结算页 Story 3.7）。
 */
public record ReturnableLineView(
        long orderLineId,
        String productName,
        String specName,
        long unitPrice,
        int qty,
        /** 已退数量（多次部分退货时累加）。 */
        int refundedQty,
        /** 本次最多还能退几件。 */
        int returnableQty,
        String returnPolicy,
        /** 非质量问题下是否可勾选。质量问题另有口径（破损与是否开封无关）。 */
        boolean selectable,
        /** 不可勾选的原因（前端直接展示，不用自己拼）。 */
        String blockedReason) {
}
