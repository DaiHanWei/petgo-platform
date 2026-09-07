package com.tailtopia.auth.dto;

import java.time.LocalDate;

/**
 * 用户生命周期只读快照（留存运营作战手册 · 抓手 1 日扫用）。
 *
 * <p>仅暴露「算得出该给哪一层用户推什么」所需的最小只读字段，避免 notify 模块直访
 * {@code UserRepository} 或 JPA 实体（架构 §Architectural Boundaries —— 跨模块一律经只读端口）。
 *
 * <p>🔒 不含 email / 昵称 / 头像等任何 PII：日扫只需要「注册多久了、最近来过吗、发过内容吗」，
 * 拿不到也不该拿到用户是谁。
 *
 * @param userId         用户 id。
 * @param registeredDate 注册日（{@code created_at} 折 UTC 日期）；D1/D3/D7 以此为锚。
 * @param lastActiveDate 最后活跃日（UTC 日期）；{@code null} 视为「从未记录过活跃」，
 *                       流失判定退化为按注册日算（老数据回填前的兜底）。
 * @param publishedCount 已发布内容数 —— 唯一的强行为信号（手册：发布 30.9% 是全站最高的行为转化）。
 */
public record UserLifecycleSnapshot(
        long userId,
        LocalDate registeredDate,
        LocalDate lastActiveDate,
        int publishedCount) {

    /** 是否已发生过强行为（发过至少一条内容）。 */
    public boolean hasPublished() {
        return publishedCount > 0;
    }
}
