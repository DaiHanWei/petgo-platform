package com.tailtopia.moderation.domain;

/**
 * 账号级处置类型（Story 3.1 建表 / Story 3.2 写入）。落库 varchar + UPPER_SNAKE。
 *
 * <p>⚠️ 与「注销」无关：{@link #SUSPEND} 是运营侧的封号（可逆，落 {@code users.status=DEACTIVATED}），
 * 用户自己注销走的是 {@code users.deleted_at}，两者要求常常相反（架构 S3 / 高风险点 R5）。
 */
public enum AccountDisposalType {

    /** 警告：只发通知、不动账号状态。**每一次警告都要留痕**——工单里的「历史处置次数」含警告。 */
    WARNING,

    /** 封号：账号停用（可逆）。 */
    SUSPEND
}
