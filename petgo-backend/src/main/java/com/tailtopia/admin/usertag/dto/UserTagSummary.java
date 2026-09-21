package com.tailtopia.admin.usertag.dto;

/**
 * B8 用户标签摘要条两格（V1.3.0 Story 8.2 · AC1）：生效标签数 · 生效中分配数。
 *
 * <p>与内容标签的 {@code contenttag.dto.TagSummary} 同形状 —— 两页动线刻意做成同构，
 * 摘要条也不该一个两格一个三格。
 *
 * @param activeTags        未下线的标签数（下线只挡「再分配」，已分配的照旧生效到各自 ends_at）
 * @param activeAssignments 当前生效中的分配总数（按 {@code [starts_at, ends_at)} 判，{@code ends_at} 空 = 永久）
 */
public record UserTagSummary(long activeTags, long activeAssignments) {
}
