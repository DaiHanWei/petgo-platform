package com.tailtopia.shared.logistics.biteship.dto;

import java.time.Instant;

/**
 * Biteship 运单状态快照（v1.4.2 shop Story 1.1 定形，Story 5.1 真实现）。
 *
 * <p>🔒 driver 姓名 / 电话、收发件人信息一律不读入本对象。
 */
public record BiteshipOrderSnapshot(String id, String status, String waybillId, String trackingId,
        String courierCompany, String courierType, Long price, String link, Instant updatedAt) {
}
