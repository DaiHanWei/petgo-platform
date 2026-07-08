package com.tailtopia.avatarmoderation.service;

import com.tailtopia.avatarmoderation.domain.AvatarPriority;
import com.tailtopia.avatarmoderation.domain.AvatarReviewStatus;
import com.tailtopia.avatarmoderation.domain.AvatarReviewVerdict;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService.Verdict;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 头像图像评分路由（内容审核 story 5，§5.2）。把 story 1 图像审核的 {@link ModerationOutcome}
 * （由 {@code evaluate("", List.of(avatarUrl))} 产出）映射为头像审核落库结论。
 * <b>纯函数、无 DB / 无副作用</b>（便于 L0 边界单测，AC-B5~B8）。
 *
 * <p><b>cm-1 图像 riskScore 口径（本 story 关键对齐）</b>：story 1 图像审核目前为<b>二值</b>——
 * 命中高置信违规（§4.2 色情≥0.85 / 暴力≥0.80 / 违禁≥0.75）→ {@code IMAGE_BLOCKED}（riskScore=1.0）；
 * 否则 {@code PASS}（纯图无文，riskScore=0.0）；三方故障 → {@code DEGRADED}。故实战只走三分支：
 * <ul>
 *   <li>{@code DEGRADED} 或 {@code degraded()} → fail-closed：{@code MANUAL_PENDING/NORMAL}（risk=NULL，verdict=DEGRADED_QUEUED，D-CM5）。</li>
 *   <li>{@code IMAGE_BLOCKED} → <b>不 throw</b>（头像先放行，D-CM2），视为高置信违规入队标 HIGH（risk=1.0，verdict=PENDING_REVIEW，§5.1 P1）。</li>
 *   <li>{@code PASS}（risk=0.0）→ {@code AUTO_PASSED}（verdict=PASS，终态静默，不推送）。</li>
 * </ul>
 * 中间档 [0.6,0.8)NORMAL / [0.8,1.0)HIGH 的分档逻辑<b>一并实现</b>以向前兼容——若 story 1 后续给图像返回
 * 中间置信度（非二值），本路由无需改动即按分档入队；今 stub 二值下这两档不可达。名称侧 TEXT_BLOCKED 对头像不可达
 * （送审文本恒为空）。
 */
public final class AvatarReviewRouter {

    /** 入队下限（含）：≥ 此分入人工队列，&lt; 此分自动通过。 */
    static final double MANUAL_THRESHOLD = 0.6;
    /** 高优先级下限（含）：≥ 此分标 HIGH。 */
    static final double HIGH_THRESHOLD = 0.8;

    private AvatarReviewRouter() {
    }

    /** 路由结论：目标状态 + 优先级 + 落库风险分（降级为 null）+ verdict。 */
    public record RoutingDecision(AvatarReviewStatus status, AvatarPriority priority, BigDecimal riskScore,
            AvatarReviewVerdict verdict) {
    }

    public static RoutingDecision route(ModerationOutcome outcome) {
        // 1) fail-closed 降级：绝不自动放行 → 入队（risk 未知留 NULL，D-CM5）。
        if (outcome.degraded() || outcome.verdict() == Verdict.DEGRADED) {
            return new RoutingDecision(AvatarReviewStatus.MANUAL_PENDING, AvatarPriority.NORMAL, null,
                    AvatarReviewVerdict.DEGRADED_QUEUED);
        }
        // 2) 图像高置信违规（IMAGE_BLOCKED）：头像不硬拦截、先放行（D-CM2），视为满分入队标 HIGH（不 throw）。
        if (outcome.verdict() == Verdict.IMAGE_BLOCKED) {
            return new RoutingDecision(AvatarReviewStatus.MANUAL_PENDING, AvatarPriority.HIGH, score(1.0),
                    AvatarReviewVerdict.PENDING_REVIEW);
        }
        // 3) 纯评分分档（今 stub 二值下 PASS→risk 0.0；中间档为 story 1 未来非二值预留）。
        double risk = Math.max(0.0, outcome.riskScore());
        if (risk < MANUAL_THRESHOLD) {
            return new RoutingDecision(AvatarReviewStatus.AUTO_PASSED, AvatarPriority.NORMAL, score(risk),
                    AvatarReviewVerdict.PASS);
        }
        AvatarPriority priority = risk >= HIGH_THRESHOLD ? AvatarPriority.HIGH : AvatarPriority.NORMAL;
        return new RoutingDecision(AvatarReviewStatus.MANUAL_PENDING, priority, score(risk),
                AvatarReviewVerdict.PENDING_REVIEW);
    }

    private static BigDecimal score(double v) {
        return BigDecimal.valueOf(v).setScale(3, RoundingMode.HALF_UP);
    }
}
