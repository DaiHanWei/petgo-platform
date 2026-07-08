package com.tailtopia.content.domain;

/**
 * 评论审核可见性态（内容审核补充规范 story 3，落库 varchar UPPER_SNAKE）。
 *
 * <ul>
 *   <li>{@link #VISIBLE}：正常通过（<0.8），对他人可见（存量评论 grandfather 到此态）。</li>
 *   <li>{@link #UNDER_REVIEW}：三方降级挂起（fail-closed）——仅作者可见、无标签，待人工队列判定。</li>
 *   <li>{@link #TAKEN_DOWN}：FR-55A 巡查下架——仅作者可见 + 「违规下架」标签。</li>
 *   <li>{@link #REJECTED}：降级队列被拒 / 超时丢弃（终态）——仍仅作者可见。</li>
 * </ul>
 */
public enum CommentModerationStatus {
    VISIBLE,
    UNDER_REVIEW,
    TAKEN_DOWN,
    REJECTED
}
