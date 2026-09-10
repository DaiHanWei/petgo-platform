package com.tailtopia.admin.comment.dto;

/**
 * B2 评论巡查摘要条三格（V1.3.0 Story 7.2 · AC2）：随当前筛选联动，单条 {@code COUNT(*) FILTER} 聚合。
 *
 * @param todayNew  今日新增按 <b>WIB 自然日</b>
 * @param takenDown 已下架 = 可见性态 {@code TAKEN_DOWN} 且未软删
 *                  （🔴 与「已删除」不是一回事，别混成一个状态——Dev Notes）
 */
public record CommentSummary(long total, long todayNew, long takenDown) {
}
