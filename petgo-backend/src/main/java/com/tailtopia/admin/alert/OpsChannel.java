package com.tailtopia.admin.alert;

/**
 * 电商运营提醒的三个飞书群（V1.4.2 shop-admin Story 1.3 · AD-5 / 决策 O-10）。
 * <ul>
 *   <li>{@link #CS} 客服：取消后到账、打款失败、超 7 天未选退款去向；</li>
 *   <li>{@link #OPS} 运营：物流停滞 / 异常、确认收货清除异常、预估余额低、待索赔、运单取消失败；</li>
 *   <li>{@link #TECH} 技术：查单 / webhook 连续失败、重下运单激增、到账找不到订单等。</li>
 * </ul>
 */
public enum OpsChannel {
    CS, OPS, TECH
}
