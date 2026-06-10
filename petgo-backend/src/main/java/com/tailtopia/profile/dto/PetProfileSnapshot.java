package com.tailtopia.profile.dto;

import java.time.LocalDate;

/**
 * 宠物档案只读快照（Story 6.7 定时推送扫描用）。
 *
 * <p>仅暴露定时扫描所需的最小只读字段（id/ownerId/name/birthday/建档日期），避免跨模块直访
 * {@code PetProfileRepository} 或 JPA 实体（保持模块边界 —— notify 经 profile 域只读端口取）。
 * {@code createdDate} 为建档时间按 UTC 折算的日期（纪念日按 {@code now::date − created_at::date} 判定）。
 */
public record PetProfileSnapshot(
        long id,
        long ownerId,
        String name,
        LocalDate birthday,
        LocalDate createdDate) {
}
