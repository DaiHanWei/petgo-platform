package com.tailtopia.content.autocomment;

/** 自动评论留档状态。取值与 {@code ck_auto_comment_logs_status} 一一对应，增值必须同步迁移。 */
public enum AutoCommentStatus {
    /** 已发出（评论本身仍走正常审核链路）。 */
    POSTED,
    /** AI 判定不宜评论。 */
    AI_SKIPPED,
    /** 帖子无图（只读首图）。 */
    NO_IMAGE,
    /** 没有可用的虚拟账号。 */
    NO_IDENTITY,
    /** 生成文字命中 L1 敏感词。 */
    BLOCKED,
    /** 处理期间帖子被删 / 下架 / 转私密。 */
    POST_GONE,
    /** AI 调用或其他异常；未达上限时下一轮重试。 */
    FAILED
}
