package com.tailtopia.namemoderation.service;

import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService.Verdict;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NamePriority;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 名称评分路由（内容审核 story 4，§5.2）。把 {@link ModerationOutcome} 映射为名称审核落库结论。
 * <b>纯函数、无 DB / 无副作用</b>（便于 L0 边界单测，AC-B2）。
 *
 * <p>名称采「先放行、无 L1 硬拦截」模型：{@code TEXT_BLOCKED}（L1 命中）<b>不 throw</b>，视为 riskScore=1.0
 * 入人工队列标 HIGH（spec §5.2）。分档以 riskScore（非 verdict）为准：
 * <ul>
 *   <li>{@code DEGRADED} 或 {@code degraded()} → fail-closed：{@code MANUAL_PENDING}（risk=NULL），优先级 NORMAL。</li>
 *   <li>{@code TEXT_BLOCKED} → {@code MANUAL_PENDING/HIGH}（risk=1.0）。</li>
 *   <li>riskScore &lt; 0.6 → {@code AUTO_PASSED}（终态静默）。</li>
 *   <li>0.6 ≤ riskScore &lt; 0.8 → {@code MANUAL_PENDING/NORMAL}。</li>
 *   <li>riskScore ≥ 0.8 → {@code MANUAL_PENDING/HIGH}。</li>
 * </ul>
 */
public final class NameModerationRouter {

    /** 入队下限（含）：≥ 此分入人工队列，&lt; 此分自动通过。 */
    static final double MANUAL_THRESHOLD = 0.6;
    /** 高优先级下限（含）：≥ 此分标 HIGH。 */
    static final double HIGH_THRESHOLD = 0.8;

    private NameModerationRouter() {
    }

    /** 路由结论：目标状态 + 优先级 + 落库风险分（降级为 null）。 */
    public record RoutingDecision(NameModerationStatus status, NamePriority priority, BigDecimal riskScore) {
    }

    public static RoutingDecision route(ModerationOutcome outcome) {
        // 1) fail-closed 降级：绝不自动判过 → 入队（risk 未知留 NULL）。
        if (outcome.degraded() || outcome.verdict() == Verdict.DEGRADED) {
            return new RoutingDecision(NameModerationStatus.MANUAL_PENDING, NamePriority.NORMAL, null);
        }
        // 2) L1 硬命中（TEXT_BLOCKED）：名称无硬拦截，视为满分入队标 HIGH（不 throw）。
        if (outcome.verdict() == Verdict.TEXT_BLOCKED) {
            return new RoutingDecision(NameModerationStatus.MANUAL_PENDING, NamePriority.HIGH, score(1.0));
        }
        // 3) 纯评分分档。
        double risk = outcome.riskScore();
        if (risk < MANUAL_THRESHOLD) {
            return new RoutingDecision(NameModerationStatus.AUTO_PASSED, NamePriority.NORMAL, score(risk));
        }
        NamePriority priority = risk >= HIGH_THRESHOLD ? NamePriority.HIGH : NamePriority.NORMAL;
        return new RoutingDecision(NameModerationStatus.MANUAL_PENDING, priority, score(risk));
    }

    private static BigDecimal score(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
