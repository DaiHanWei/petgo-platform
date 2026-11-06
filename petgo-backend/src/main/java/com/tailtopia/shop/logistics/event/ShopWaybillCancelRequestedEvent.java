package com.tailtopia.shop.logistics.event;

/**
 * 平台原因整单取消后取消 Biteship 运单的请求（v1.4.2 shop Story 5.1，实现 4.1 {@code ShopWaybillCanceller} 钩子，决策 C-7）。
 *
 * <p>🔴 由取消事务内发布、{@code AFTER_COMMIT} 后异步处理：取消事务回滚（如 {@code @Version} 冲突）则不取消运单，
 * 避免「订单未取消而运单已取消」；Biteship 调用不在任何事务内。
 */
public record ShopWaybillCancelRequestedEvent(long orderId, String orderDisplayNo, Long actorAdminId) {
}
