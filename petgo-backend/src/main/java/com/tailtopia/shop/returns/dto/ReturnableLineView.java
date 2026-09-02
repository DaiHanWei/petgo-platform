package com.tailtopia.shop.returns.dto;

/**
 * 退货申请页的可退行（Story 5.7，FR-104A / FR-104）。
 *
 * <p>🔴 <b>「开封不退」的行保留可见但置灰不可勾选</b>，并直接标注原因 ——
 * 比提交后再驳回体验好得多。故本视图<b>不过滤掉</b>不可退的行，而是带上
 * {@code selectable} 与 {@code blockedCode} 让前端渲染成置灰态（文案由端上按码取，见该字段说明）。
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
        /**
         * 不可退的**原因码**，不是文案（D-9，2026-09-02 stag，P1）。
         *
         * <p>此前这里下发的是中文串（`开封后不支持退货（若是破损/临期/错发，请选「质量问题」）`），
         * 而 App **没有中文包**、这句也不经 i18n ⇒ 印尼用户在退货申请页**必现**中文。
         *
         * <p>🔴 修法不是「把它搬进后端 messages.properties」：`AdminLocaleConfig` 的注释写明
         * 「api 链返 JSON，**文案固定，不经此**」，默认 locale 是 zh_CN ——
         * 搬过去照样解析成中文。**展示文案属于端上**，后端只负责说「为什么不可退」。
         *
         * <p>取值：{@code ALL_RETURNED} / {@code NON_RETURNABLE} / {@code NO_RETURN_AFTER_OPEN}；
         * 可退时为 null。⚠️ 端上必须对未知码留兜底（新增码时老版本 App 不至于显示空白）。
         */
        String blockedCode) {
}
