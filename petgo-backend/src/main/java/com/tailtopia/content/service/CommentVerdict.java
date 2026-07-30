package com.tailtopia.content.service;

/**
 * 评论文字审核结论（内容审核补充规范 story 3 定义/消费；story 1 落地真实评分与降级）。
 *
 * <p>{@link com.tailtopia.content.service.ContentModerationService#moderateComment(String)} 内部由
 * {@code evaluate(text, List.of())} 的 {@link com.tailtopia.content.moderation.ModerationOutcome}
 * 映射而来（纯文字、无图审）：
 * <ul>
 *   <li>{@link #PASS}：风险 &lt; 0.8 且未命中 L1 → 通过，立即对他人可见。</li>
 *   <li>{@link #L1_BLOCKED}：命中 L1 强制拦截词库 → 即时失败、不入队（F13）。</li>
 *   <li>{@link #HIGH_RISK}：未命中 L1 但风险评分 ≥ 0.8 → 同步拦截、从未发布。</li>
 *   <li>{@link #DEGRADED}：三方超时 / 4xx-5xx / 配额耗尽 / 熔断 → fail-closed，不自动放行 → 转人工队列。</li>
 * </ul>
 */
public enum CommentVerdict {
    PASS,
    L1_BLOCKED,
    HIGH_RISK,
    DEGRADED
}
