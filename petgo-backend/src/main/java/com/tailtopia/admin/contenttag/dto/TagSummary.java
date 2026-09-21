package com.tailtopia.admin.contenttag.dto;

/**
 * B4 内容标签摘要条两格（V1.3.0 Story 7.4 · AC1）：生效标签数 · 生效中分配数。
 *
 * @param activeTags        未下线的标签数（下线的标签不可再分配，但已分配的照旧生效到各自 ends_at）
 * @param activeAssignments 当前生效中的分配总数（按 {@code [starts_at, ends_at)} 判，{@code ends_at} 空 = 永久）
 */
public record TagSummary(long activeTags, long activeAssignments) {
}
