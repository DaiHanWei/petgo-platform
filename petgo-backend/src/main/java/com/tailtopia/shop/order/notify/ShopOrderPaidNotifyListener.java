package com.tailtopia.shop.order.notify;

import com.tailtopia.shop.order.event.ShopOrderPaidEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 订单转「待发货」→ 登记新订单提醒（v1.3.0 shop-v2 复审 #6 / #13）。
 *
 * <p><b>为什么从 {@code ShopOrderPaidHandler} 里挪出来</b>：
 * <ul>
 *   <li><b>#6</b>：原先登记挂在 {@code PaymentIntentPaidEvent} 上，而<b>纯 PawCoin 单
 *       根本不产生支付意图、也不发那个事件</b>（{@code settlePureCoin} 直接结清）。
 *       于是整条纯币路径永不入队 —— 用户付了钱，运营永远收不到这批单的发货信号。
 *       改挂 {@link ShopOrderPaidEvent}（发点是状态迁移本身），两条路径自然都覆盖。</li>
 *   <li><b>#13</b>：原先在支付回调事务<b>内部</b>用 {@code REQUIRES_NEW} 抢先提交，
 *       外层一旦回滚就留下一行指向「从未付款成功」的订单的孤儿提醒。
 *       {@code @TransactionalEventListener} 默认 AFTER_COMMIT，外层回滚则本监听器
 *       <b>根本不会跑</b>，孤儿行从机制上消失。</li>
 * </ul>
 *
 * <p>🔴 <b>本监听器绝不放异常出去</b>：AFTER_COMMIT 阶段抛出虽已不能回滚支付事务
 * （那时已经提交），但会污染调用栈与日志，而「一条运维提醒没登记上」远不值得那样。
 * 失败只记 warn —— 队列是尽力而为的运维信号，不是账。
 */
@Component
public class ShopOrderPaidNotifyListener {

    private static final Logger log = LoggerFactory.getLogger(ShopOrderPaidNotifyListener.class);

    private final ShopOrderNotifyService notify;

    public ShopOrderPaidNotifyListener(ShopOrderNotifyService notify) {
        this.notify = notify;
    }

    /**
     * 🔴 {@code REQUIRES_NEW}：AFTER_COMMIT 阶段原事务已提交，此处需要自己的事务才能写库。
     * 这与 #13 要消除的「抢先提交」不冲突 —— 那时问题在于<b>外层还没提交就先落行</b>，
     * 现在外层已经提交了，落行才开始。
     */
    @TransactionalEventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onPaid(ShopOrderPaidEvent event) {
        try {
            notify.enqueue(event.shopOrderId());
        } catch (RuntimeException e) {
            log.warn("新订单提醒登记失败（不影响订单） order={} reason={}",
                    event.orderToken(), e.getClass().getSimpleName());
        }
    }
}
