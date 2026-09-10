package com.tailtopia.admin.moderation.dto;

/**
 * B1 内容管理摘要条五格（V1.3.0 Story 7.1 · AC2）：随当前筛选联动，单条 {@code COUNT(*) FILTER} 聚合。
 *
 * @param total      当前筛选命中的帖数
 * @param todayNew   今日新增，按 <b>WIB 自然日</b>（后台全站按雅加达解释时间）
 * @param underReview 人工审核挂起（{@code status=UNDER_REVIEW} 且未删）
 * @param throttled  当前存在<b>生效中</b>限流的帖数（内容级或作者账号级，与列表限流标记同口径）
 * @param takenDown  已下架 = {@code deleted_at IS NOT NULL}（与列表状态列同口径）
 */
public record ContentSummary(long total, long todayNew, long underReview, long throttled,
        long takenDown) {
}
