package com.tailtopia.content.autolike;

/** 一条计划的自动赞的结果（{@code auto_like_logs.status}，含义见迁移 V20261115_0520 头注释）。 */
public enum AutoLikeLogStatus {
    PLANNED,
    MISSED,
    LIKED,
    REACHED_TARGET,
    SKIPPED_REVIEW,
    POST_GONE,
    NO_IDENTITY,
    FAILED
}
