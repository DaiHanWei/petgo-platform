package com.tailtopia.shop.order.service;

import com.tailtopia.admin.alert.OpsAlertService;
import com.tailtopia.admin.alert.OpsChannel;
import org.springframework.stereotype.Component;

/**
 * 电商告警（v1.4.2 shop Story 3.3 AC4.3 / AC6.2 起；V1.4.2 shop-admin Story 1.3 · AD-5 起改走 {@link OpsAlertService}）。
 *
 * <p>🔴 <b>按调用点分流</b>：技术类（查单 / webhook 连续失败、重下运单激增、到账找不到订单）走 {@link OpsChannel#TECH}；
 * 运营类（用户确认收货清除异常、运单取消失败、物流异常 / 停滞、揽收失败处置）走 {@link OpsChannel#OPS}。
 * 不去重（dedupeKey 为空）：各调用点沿用自己的节流。
 *
 * <p>🔴 <b>发送失败只 WARN、绝不抛</b>；在活动事务内调用 → 提交后才发，回滚不发（{@link OpsAlertService} 负责）。
 * 群未配置（{@code LARK_SHOP_*_WEBHOOK_URL} 为空）只写 WARN —— 取代原来的 {@code order-notify.mode} 开关。
 *
 * <p>🔒 文案只含订单展示号 / 意图 id / 次数，不含手机号、地址、收款账户。
 */
@Component
public class ShopPaymentAlerts {

    private final OpsAlertService ops;

    public ShopPaymentAlerts(OpsAlertService ops) {
        this.ops = ops;
    }

    public void alert(OpsChannel channel, String text) {
        ops.send(channel, null, text);
    }
}
