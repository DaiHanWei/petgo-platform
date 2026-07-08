package com.tailtopia.content.domain;

/**
 * 评论审核可见性态（内容审核补充规范 story 3，落库 varchar UPPER_SNAKE）。
 *
 * <ul>
 *   <li>{@link #VISIBLE}：正常通过（<0.8），对他人可见（存量评论 grandfather 到此态）。</li>
 *   <li>{@link #UNDER_REVIEW}：三方降级挂起（fail-closed）——仅作者可见、无标签，待人工队列判定。</li>
 *   <li>{@link #TAKEN_DOWN}：FR-55A 巡查下架——仅作者可见 + 「违规下架」标签。</li>
 *   <li>{@link #REJECTED}：降级队列被拒 / 超时丢弃（终态）——仍仅作者可见。</li>
 *   <li>{@link #AUTHOR_DEACTIVATED}：作者注销 → 评论对他人隐藏（内容审核 story 9，§5.5）——非 VISIBLE 即对他人不可见，
 *       内容保留（{@code deleted_at IS NULL}，与匿名化并存）。CHECK 约束经 V56 放宽容纳。</li>
 * </ul>
 */
public enum CommentModerationStatus {
    VISIBLE,
    UNDER_REVIEW,
    TAKEN_DOWN,
    REJECTED,
    AUTHOR_DEACTIVATED
}
