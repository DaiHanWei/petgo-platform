package com.tailtopia.content.domain;

/**
 * 内容状态（落库 varchar）。
 * <ul>
 *   <li>{@link #PUBLISHED}：发布即全平台公开（口径 {@code deleted_at IS NULL AND status=PUBLISHED}）。</li>
 *   <li>{@link #UNDER_REVIEW}：人工审核挂起（Story 4.3，仅 {@code manual_review_enabled=true} 时产生）——
 *       已落库但不进任何公开口径；运营通过→转 PUBLISHED，拒绝/超时→软删丢弃。</li>
 * </ul>
 * Epic 3 删除（3.6）用软删 {@code deleted_at}。列 varchar(16) 无 CHECK 约束，新增值无需迁移。
 */
public enum PostStatus {
    PUBLISHED,
    UNDER_REVIEW
}
