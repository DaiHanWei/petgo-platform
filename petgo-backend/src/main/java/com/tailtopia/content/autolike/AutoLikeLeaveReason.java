package com.tailtopia.content.autolike;

/** 帖子离开自动点赞目标集的原因（{@code auto_like_targets.leave_reason}，V1.5.0）。 */
public enum AutoLikeLeaveReason {
    /** 检查时赞数已达目标，不进集合。 */
    AT_TARGET_ON_CHECK,
    /** 作者在对照组，不进集合（FR-223）。 */
    HOLDOUT,
    /** 赞数达到目标。 */
    REACHED_TARGET,
    /** 帖子超过最长年龄（7 天）。 */
    MAX_AGE,
    /** 帖子不可见 / 已删 / 作者状态不再符合。 */
    POST_GONE,
    /** 没有可用的虚拟号（全都赞过 / 被作者隐藏）。 */
    NO_IDENTITY,
    /** 失败次数达到上限。 */
    TOO_MANY_FAILURES
}
