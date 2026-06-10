package com.tailtopia.content.domain;

/**
 * 内容状态（落库 varchar）。本 Story 仅 {@link #PUBLISHED}（发布即全平台公开）；
 * Epic 3 删除（3.6）用软删 {@code deleted_at}，必要时扩展状态。
 */
public enum PostStatus {
    PUBLISHED
}
