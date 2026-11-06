package com.tailtopia.shop.order.refund;

import com.tailtopia.shop.order.domain.ShopOrder;

/**
 * 平台原因整单取消前取消 Biteship 运单的钩子（v1.4.2 shop Story 4.1，决策 C-7）。
 *
 * <p>Biteship 运单服务（{@code BiteshipShipmentService.cancelWaybill}）由 Story 5.1 提供；在它落地前容器里没有本接口的实现，
 * {@code ShopOrderRefundService} 经 {@code ObjectProvider} 取不到即跳过（此时也不存在 Biteship 运单）。
 *
 * <p>🔴 约定：
 * <ul>
 *   <li>实现方对「无可取消运单」幂等返回；取消失败（运单已揽收不可取消 / 网关未知）<b>抛异常</b>，
 *       由调用方记 WARN 并写运营提醒，<b>不阻断取消与退款</b>（C-7）；</li>
 *   <li>调用方在<b>读写订单与加锁之前</b>调用本钩子，外部调用不在持锁区间内；实现方自行保证不在持锁事务里出网。</li>
 *   <li>🔴 调用方事务仍可能在之后回滚（如 {@code @Version} 冲突）：实现方（5.1）应只登记意图，
 *       <b>在事务提交后（{@code TransactionSynchronization#afterCommit} / {@code AFTER_COMMIT}）</b>再真正调 Biteship 取消，
 *       避免订单未取消而运单已取消。</li>
 * </ul>
 */
public interface ShopWaybillCanceller {

    /** 取消该单可取消状态的 Biteship 运单（无则什么都不做）。 */
    void cancelForPlatformCancel(ShopOrder order, Long actorAdminId);
}
