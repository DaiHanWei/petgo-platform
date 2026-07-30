package com.tailtopia.admin.moderation.domain;

/**
 * 人工审核队列项处理优先级（内容审核补充规范 §5.1，story 8，落库 varchar length=8）。
 *
 * <p><b>语义 = 通用「处理紧急度」（CM6 定论 R3）</b>，与举报处置的 P0/P1/P2（举报唯一用户数≥10=P0…那条轴，story 6）
 * 是<b>不同轴</b>。队列排序：{@code P0 < P1 < P2}（字典序与优先级序天然一致 → {@code ORDER BY priority, submitted_at}
 * 无需数值映射）。入队项到 P0/P1/P2 的映射由生产者（story 2/3/6）定义；未显式标注默认 {@link #P1}（不沉底）。
 *
 * <p>长度>1 规避 Hibernate6 {@code CHAR(1)} → {@code validate} 全红 坑（历史教训）。
 */
public enum ReviewPriority {
    /** 紧急（最高，队列置顶）。 */
    P0,
    /** 高（默认；未显式标注的历史/降级入队项归此，避免沉底）。 */
    P1,
    /** 普通（最低）。 */
    P2
}
