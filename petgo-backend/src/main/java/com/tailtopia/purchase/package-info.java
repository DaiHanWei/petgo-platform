/**
 * 一次性解锁购买链路（V1.3.2 batch-a Story 3.1 · 架构 delta AD-1）：Tailsonality 结果 / 护照快照 / 登机牌三类共用。
 *
 * <p>依赖方向：purchase → pay、config（读价）；SKU 模块（tailsonality / passport）→ purchase（实现 {@code KeepsakeGranter}、
 * 在加锁读业务行之后调 {@code KeepsakePurchaseService.start}）。<b>purchase 不得反向依赖任何 SKU 模块</b>，也不认识任何业务表。
 *
 * <p>三个新 purpose 的到账**只有一个消费者**：{@code KeepsakePaidHandler}；下游（埋点、订单）一律订阅
 * {@code KeepsakeUnlockedEvent}（AFTER_COMMIT），不再订阅 {@code PaymentIntentPaidEvent}。
 */
package com.tailtopia.purchase;
