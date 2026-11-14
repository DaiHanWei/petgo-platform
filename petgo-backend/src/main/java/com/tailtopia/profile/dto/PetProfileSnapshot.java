package com.tailtopia.profile.dto;

import java.time.Instant;
import java.time.LocalDate;

/**
 * 宠物档案只读快照（Story 6.7 定时推送扫描用）。
 *
 * <p>仅暴露定时扫描所需的最小只读字段，避免跨模块直访 {@code PetProfileRepository} 或 JPA 实体
 * （保持模块边界 —— notify 经 profile 域只读端口取）。
 *
 * @param createdDate    建档时间按 <b>UTC</b> 折算的日期（旧口径，保留供既有调用方 / 5 参构造）
 * @param createdDateWib 建档时间按 <b>WIB</b> 折算的日期（V1.5.0 onboarding-recall Story 5.2 · OR-16：纪念日天数口径）
 * @param status         宠物状态 {@code ACTIVE} / {@code READ_ONLY}（V1.5.0 Story 5.2：只读宠物不发纪念日）
 * @param ownerActive    主人是否 {@code isActive}（未注销 + 未封号；V1.5.0 Story 5.2：非 active 不发纪念日）
 * @param createdAt      建档时刻（UTC；同主人同天多只纪念日按 {@code created_at, id} 取最早）。5 参构造为 null
 */
public record PetProfileSnapshot(
        long id,
        long ownerId,
        String name,
        LocalDate birthday,
        LocalDate createdDate,
        LocalDate createdDateWib,
        String status,
        boolean ownerActive,
        Instant createdAt) {

    /** 宠物状态：正常。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    /**
     * 5 参兼容构造器（V1.5.0 前的形态；健康提醒线测试直接 new 它）：{@code createdDateWib = createdDate}、
     * {@code status = ACTIVE}、{@code ownerActive = true}、{@code createdAt = null}。
     */
    public PetProfileSnapshot(long id, long ownerId, String name, LocalDate birthday, LocalDate createdDate) {
        this(id, ownerId, name, birthday, createdDate, createdDate, STATUS_ACTIVE, true, null);
    }

    /** 正常状态（status 为空按 ACTIVE）。 */
    public boolean isActivePet() {
        return status == null || STATUS_ACTIVE.equals(status);
    }
}
