package com.tailtopia.profile.domain;

/**
 * 健康记录来源（V1.5.0 健康记录提醒 Story 1.1，架构增量 §1.1）。落库 {@code health_records.record_origin}。
 *
 * <ul>
 *   <li>{@link #APP}：1.5.0 及以上客户端新建（列默认值）；</li>
 *   <li>{@link #LEGACY_APP}：旧版客户端（请求头 &lt; 1.5.0 或缺头）新建；</li>
 *   <li>{@link #BACKFILL}：V1.5.0 迁移前的存量行（回填静默的判据）。</li>
 * </ul>
 */
public enum RecordOrigin {
    APP,
    LEGACY_APP,
    BACKFILL
}
