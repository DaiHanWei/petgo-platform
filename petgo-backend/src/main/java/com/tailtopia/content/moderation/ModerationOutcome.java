package com.tailtopia.content.moderation;

import com.tailtopia.content.service.ContentModerationService.Verdict;

/**
 * 富审核结果（内容审核 Story 1 · §5.2）。{@code ContentModerationService.evaluate} 的返回契约，
 * 供 story 2/3/4/5 按 {@code verdict} + {@code degraded} 决定放行 / 硬拦截失败 / 转人工队列。
 *
 * @param verdict       PASS / TEXT_BLOCKED / IMAGE_BLOCKED / RISKY / DEGRADED
 * @param riskScore     0.0–1.0；DEGRADED 时 -1 表未知；*_BLOCKED 时 1.0
 * @param topCategory   命中的最高风险类别（DRUGS/PORN/...），无则 null
 * @param degraded      true = fail-closed 触发（超时/4xx/5xx/配额/熔断）
 * @param degradeReason 降级原因枚举串（TIMEOUT/HTTP_4XX/HTTP_5XX/QUOTA/CIRCUIT_OPEN），非降级为 null
 */
public record ModerationOutcome(
        Verdict verdict,
        double riskScore,
        String topCategory,
        boolean degraded,
        String degradeReason) {

    public static ModerationOutcome pass(double riskScore, String topCategory) {
        return new ModerationOutcome(Verdict.PASS, riskScore, topCategory, false, null);
    }

    public static ModerationOutcome risky(double riskScore, String topCategory) {
        return new ModerationOutcome(Verdict.RISKY, riskScore, topCategory, false, null);
    }

    public static ModerationOutcome textBlocked(String category) {
        return new ModerationOutcome(Verdict.TEXT_BLOCKED, 1.0, category, false, null);
    }

    public static ModerationOutcome imageBlocked(String category) {
        return new ModerationOutcome(Verdict.IMAGE_BLOCKED, 1.0, category, false, null);
    }

    public static ModerationOutcome degraded(DegradeReason reason) {
        return new ModerationOutcome(Verdict.DEGRADED, -1.0, null, true, reason.name());
    }
}
