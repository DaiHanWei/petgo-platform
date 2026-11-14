package com.tailtopia.content.autolike;

/** 自动点赞轮次状态（{@code auto_like_runs.status}）。 */
public enum AutoLikeRunStatus {
    /** 已排期、窗口未结束。 */
    ACTIVE,
    /** 窗口结束。 */
    DONE,
    /** 被叫停（{@code stop_reason}：NO_POOL / CONSECUTIVE_FAILURES）。 */
    STOPPED
}
