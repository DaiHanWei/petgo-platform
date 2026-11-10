package com.tailtopia.shop.order.pricing;

/**
 * 计价器输入：运费（Story 1.2）。整体为 null = 运费未知（无地址 / 询价失败）。
 *
 * @param selectedFee 用户所选快递的运费
 * @param lowestFee   本单最低报价（免运减免封顶的基准）
 */
public record ShippingChoice(long selectedFee, long lowestFee) {
}
