package com.tailtopia.admin.moderation.domain;

/**
 * 人工审核队列项内容多态类型（内容审核补充规范 story 3，落库 varchar UPPER_SNAKE）。
 * 队列由帖子扩为帖子 + 评论共用（决策 D-CM-C1：扩列 content_type，不另建评论专用队列表）。
 *
 * <ul>
 *   <li>{@link #CONTENT_POST}：帖子降级/高风险入队（story 2 语义，存量项 grandfather 到此）。</li>
 *   <li>{@link #COMMENT}：评论三方降级 fail-closed 入队（本 story）。</li>
 * </ul>
 */
public enum ReviewContentType {
    CONTENT_POST,
    COMMENT
}
