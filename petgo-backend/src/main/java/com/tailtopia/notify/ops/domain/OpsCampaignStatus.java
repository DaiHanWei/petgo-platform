package com.tailtopia.notify.ops.domain;

/**
 * 运营通知发送活动状态（V1.3.4 Story 6.1 · AD-17）。落库 varchar + CHECK
 * {@code ck_ops_notification_campaigns_status}。
 *
 * <p>状态机 {@code PENDING → SENDING → DONE | PARTIAL_FAILED}，{@code PENDING} 可 → {@code CANCELED}；
 * 迁移由 Story 6.2 以条件更新实现，本 story 只建枚举。
 * 🔴 加值须同步迁移 CHECK。
 */
public enum OpsCampaignStatus {
    PENDING,
    SENDING,
    DONE,
    PARTIAL_FAILED,
    CANCELED
}
