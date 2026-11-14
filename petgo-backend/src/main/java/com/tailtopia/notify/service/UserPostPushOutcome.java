package com.tailtopia.notify.service;

/**
 * {@code user_post_notify_log.push_outcome} 取值（V1.5.0 onboarding-recall Story 5.1 · OR-13）。落库为枚举名；表上不加 CHECK，
 * 取值只在这里收口。推送决策按声明顺序判定、任一命中即停（{@link #PUSHED} / {@link #FAILED} 是终点）。
 */
public enum UserPostPushOutcome {
    /** 收件人是虚拟号 / 官方号（没有 App 端）。 */
    NO_PUSH_ACCT,
    /** 关了「Yang kamu ikuti」（{@code PET_DIARY}）分类；也是写入时的初值。 */
    MUTED,
    /** App 最后上报版本 &lt; 1.5.0（只写通知中心，FR-185）。 */
    OLD_APP,
    /** 今天（WIB）已推过这位作者。 */
    AUTHOR_DUP,
    /** 两类合计当日名额已满。 */
    CAP_REACHED,
    /** 推送投递失败（不重推）。 */
    FAILED,
    /** 已推送。 */
    PUSHED
}
