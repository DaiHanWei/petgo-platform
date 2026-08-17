package com.tailtopia.admin.shop.dto;

import java.time.Instant;

/**
 * 后台订单列表行（Story 4.2 / 4.3，AB-11A）。
 *
 * <p>🔒 <b>列表不带任何 PII</b>：不出现收件人姓名 / 电话 / 详细地址 ——
 * 一屏几十行 PII 是最容易被截图外传的形态。要看收件信息请进详情页（那里有单独的权限判定）。
 * 列表只给到 <b>Kecamatan</b>（区级），够运营判断配送范围，又不足以定位到人。
 */
public record AdminShopOrderRow(
        String orderToken,
        String status,
        long totalAmount,
        String payChannel,
        Long coinAmount,
        Long cashAmount,
        /** 区级行政区。非 PII —— 一个区里有几万人。 */
        String kecamatan,
        int packageCount,
        Instant createdAt,
        Instant shippedAt,
        Instant deliveredAt) {
}
