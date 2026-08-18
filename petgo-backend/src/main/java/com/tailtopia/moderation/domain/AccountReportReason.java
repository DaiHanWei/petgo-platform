package com.tailtopia.moderation.domain;

/**
 * 账号举报理由（Story 2.1，FR-58 · AD-12）。落库 varchar + UPPER_SNAKE。
 *
 * <p>⚠️ <b>这是账号维度的五类，与内容维度的 {@link ReportReason} 是两套东西，不要互相复用</b>：
 * 内容维度是 {@code ILLEGAL / MISINFO / INAPPROPRIATE / HARASSMENT / OTHER}，
 * 两边只有「骚扰」和「其他」勉强对得上。举报一个<b>账号</b>说的是「这个人怎么了」，
 * 举报一条<b>内容</b>说的是「这条东西怎么了」，混用会让运营在工单里看到风马牛不相及的理由。
 */
public enum AccountReportReason {

    /** 垃圾信息 / 恶意营销。 */
    SPAM,

    /** 仿冒他人。 */
    IMPERSONATION,

    /** 持续骚扰。 */
    HARASSMENT,

    /** 发布违规内容。 */
    VIOLATING_CONTENT,

    /** 其他 —— <b>只有这一类要求填补充说明</b>（≤200 字）。 */
    OTHER
}
