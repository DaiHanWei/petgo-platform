package com.tailtopia.shop.order.service;

import com.tailtopia.shop.order.notify.LarkMessageClient;
import com.tailtopia.shop.order.notify.ShopOrderNotifyProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 电商支付资金告警（v1.4.2 shop Story 3.3 AC4.3 / AC6.2）：复用 {@link LarkMessageClient#sendText} 发飞书机器人。
 *
 * <p>🔴 <b>发送失败只 WARN、绝不抛</b>：告警是「有人知道」，不能反过来把关单 / 到账链路打断。
 *
 * <p>🔴 <b>不在事务里出网</b>：在活动事务内调用（如到账回调事务里的 {@code ShopOrderPaidHandler}）→ 挂到
 * {@code afterCommit} 再发，避免一次 Lark 往返（最长数秒）拖着意图 / 订单行锁；事务回滚则不发（那笔到账本就没落库）。
 *
 * <p>收件配置沿用新订单提醒（{@code petgo.shop.order-notify.*}）：{@code mode=off} 或收件人未配 → 零出网，只剩调用方的 ERROR 日志。
 *
 * <p>🔒 文案只含订单展示号 / 意图 id / 次数，不含 token、金额以外的用户信息。
 */
@Component
public class ShopPaymentAlerts {

    private static final Logger log = LoggerFactory.getLogger(ShopPaymentAlerts.class);

    private final LarkMessageClient lark;
    private final ShopOrderNotifyProperties props;

    public ShopPaymentAlerts(LarkMessageClient lark, ShopOrderNotifyProperties props) {
        this.lark = lark;
        this.props = props;
    }

    public void alert(String text) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    send(text);
                }
            });
            return;
        }
        send(text);
    }

    private void send(String text) {
        try {
            if (!props.isLive()) {
                return;
            }
            lark.sendText(text);
        } catch (RuntimeException e) {
            log.warn("电商支付告警发送失败 cause={}", e.getClass().getSimpleName());
        }
    }
}
