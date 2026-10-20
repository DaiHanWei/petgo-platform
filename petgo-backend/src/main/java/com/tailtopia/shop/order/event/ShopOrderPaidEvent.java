package com.tailtopia.shop.order.event;

/**
 * 电商订单**真的**从待支付推进为待发货（v1.3.0 shop-v2 复审 #6 / #13）。
 *
 * <p>发点唯一：{@code ShopOrderPaymentService#fulfillPaid} 返回 {@code true} 时。
 * 那是两条支付路径唯一的交汇处 ——
 * <ul>
 *   <li><b>混合 / 纯现金</b>：网关回调 → {@code ShopOrderPaidHandler#onPaid}；</li>
 *   <li><b>纯 PawCoin</b>：{@code ShopOrderPaymentService#settlePureCoin}（<b>不产生支付意图、
 *       也不发 {@code PaymentIntentPaidEvent}</b>）。</li>
 * </ul>
 * 🔴 复审 #6 记的就是后一条：原先「待提醒」只挂在支付意图事件上，于是<b>整条纯币支付路径
 * 永不入队</b> —— 用户付了钱，运营永远收不到这批单的发货信号。挂在状态迁移上而不是
 * 支付方式上，才是与「该发货了」一一对应的那个事实。
 *
 * <p>🔴 消费方一律用 {@code @TransactionalEventListener}（默认 AFTER_COMMIT）：外层事务回滚时
 * 监听器根本不会跑，因此不会为一张最终没付成的单留下孤儿提醒行（复审 #13）。
 *
 * <p>V1.4.0 ① Story 1.3 末尾追加 {@code userId} / {@code paidTotal}（现金 + PawCoin 实付总额，IDR）：电商消费返成长值
 * 只经此一处（QRIS / MIXED / 纯币三种付法），MIXED 的币段与现金段不会各算一次。
 */
public record ShopOrderPaidEvent(long shopOrderId, String orderToken, long userId, long paidTotal) {
}
