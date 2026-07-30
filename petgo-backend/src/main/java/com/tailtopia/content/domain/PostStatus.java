package com.tailtopia.content.domain;

/**
 * 内容状态（落库 varchar）。
 * <ul>
 *   <li>{@link #PUBLISHED}：发布即全平台公开（口径 {@code deleted_at IS NULL AND status=PUBLISHED}）。</li>
 *   <li>{@link #UNDER_REVIEW}：人工审核挂起（Story 4.3，仅 {@code manual_review_enabled=true} 时产生）——
 *       已落库但不进任何公开口径；运营通过→转 PUBLISHED，拒绝/超时→软删丢弃。</li>
 *   <li>{@link #AUTHOR_DEACTIVATED}：作者注销 → 内容对他人隐藏（内容审核 story 9，§5.5）——不进公开口径、
 *       内容保留（{@code deleted_at IS NULL}，与"已注销用户"匿名化并存，可见性层≠显示层）。</li>
 * </ul>
 * Epic 3 删除（3.6）用软删 {@code deleted_at}。列 varchar(16) 无 CHECK 约束，新增值无需迁移。
 */
public enum PostStatus {
    PUBLISHED,
    UNDER_REVIEW,
    AUTHOR_DEACTIVATED
}
