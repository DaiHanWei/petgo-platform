package com.tailtopia.admin.moderation.read;

/**
 * 账号违规计数类型（内容审核补充规范 §5.4，story 9 拥有数据、story 8 只读展示）。
 * 按内容维度分列累计<b>人工判定</b>违规（评论自动拦截不计，见 story 9）。
 */
public enum ViolationType {
    /** 帖子人工审核判违规。 */
    POST,
    /** 评论人工判违规（自动拦截不计入）。 */
    COMMENT,
    /** 昵称/宠物名人工判违规重置。 */
    NAME,
    /** 头像人工判违规重置。 */
    AVATAR
}
