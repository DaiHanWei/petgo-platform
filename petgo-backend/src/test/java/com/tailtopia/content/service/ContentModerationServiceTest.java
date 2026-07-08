package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ModerationKeywordRule;
import com.tailtopia.content.moderation.ContentSafetyClient;
import com.tailtopia.content.moderation.ModerationCircuitBreaker;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.moderation.ModerationProperties;
import com.tailtopia.content.moderation.KeywordRuleEngine;
import com.tailtopia.content.moderation.StubContentSafetyClient;
import com.tailtopia.content.service.ContentModerationService.Verdict;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * L0（AC2/AC3/AC4/AC5）：审核门面 —— 兼容 shim 行为不变 + evaluate 富语义 + 白名单优先 + fail-closed。
 * stub 模式，无 DB / 无凭证。
 */
class ContentModerationServiceTest {

    private ContentModerationService serviceWith(ContentSafetyClient client) {
        KeywordRuleEngine engine = new KeywordRuleEngine(null);
        engine.apply(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "judi", "GAMBLING", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "narkoba", "DRUGS", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "anjing", "HARASSMENT", "id", true),
                ModerationKeywordRule.of("L3_WHITELIST", "EXACT", "anjing", "PET_SAFE", "id", true),
                ModerationKeywordRule.of("L2_ADJUSTABLE", "SUBSTRING", "wa.me/", "AD_SPAM", "ALL", true)));
        return new ContentModerationService(engine, client, new ModerationProperties(),
                new ModerationCircuitBreaker());
    }

    private ContentModerationService service() {
        return serviceWith(new StubContentSafetyClient());
    }

    // ---------- AC2：moderate() 兼容 shim（publish 行为不变） ----------

    @Test
    void shim_cleanContentPasses() {
        assertThat(service().moderate("Hari ini jalan-jalan ke taman bareng anabul",
                List.of("https://cdn.petgo.test/a.jpg")))
                .isEqualTo(Verdict.PASS);
    }

    @Test
    void shim_nullContentPasses() {
        assertThat(service().moderate(null, null)).isEqualTo(Verdict.PASS);
    }

    @Test
    void shim_l1KeywordReturnsTextBlocked() {
        assertThat(service().moderate("ayo main judi online", null)).isEqualTo(Verdict.TEXT_BLOCKED);
    }

    @Test
    void shim_violatingImageReturnsImageBlocked() {
        assertThat(service().moderate("teks normal",
                List.of("https://cdn.petgo.test/moderation-blocked-x.jpg")))
                .isEqualTo(Verdict.IMAGE_BLOCKED);
    }

    @Test
    void shim_highScoreRiskyStillPasses() {
        // evaluate 会判 RISKY，但 shim 令 publish 行为不变（返回 PASS）。
        assertThat(service().moderate("stub-high promo", null)).isEqualTo(Verdict.PASS);
    }

    @Test
    void shim_degradedStillPasses() {
        // 三方降级时 shim 仍返 PASS（过渡态；真实拦截由 story 2/3 采纳 evaluate）。
        assertThat(service().moderate("stub-timeout content", null)).isEqualTo(Verdict.PASS);
    }

    // ---------- AC3：evaluate() 富语义 ----------

    @Test
    void evaluate_l1HitTextBlockedWithScoreOne() {
        ModerationOutcome o = service().evaluate("ayo main judi online", null);
        assertThat(o.verdict()).isEqualTo(Verdict.TEXT_BLOCKED);
        assertThat(o.riskScore()).isEqualTo(1.0);
        assertThat(o.topCategory()).isEqualTo("GAMBLING");
        assertThat(o.degraded()).isFalse();
    }

    @Test
    void evaluate_scoreAboveThresholdRisky() {
        ModerationOutcome o = service().evaluate("stub-high promotional content", null);
        assertThat(o.verdict()).isEqualTo(Verdict.RISKY);
        assertThat(o.riskScore()).isGreaterThanOrEqualTo(0.8);
        assertThat(o.degraded()).isFalse();
    }

    @Test
    void evaluate_scoreBelowThresholdPasses() {
        ModerationOutcome o = service().evaluate("cerita santai soal kucing", null);
        assertThat(o.verdict()).isEqualTo(Verdict.PASS);
        assertThat(o.riskScore()).isLessThan(0.8);
    }

    @Test
    void evaluate_violatingImageBlocked() {
        ModerationOutcome o = service().evaluate("teks normal",
                List.of("https://cdn.petgo.test/stub-porn.jpg"));
        assertThat(o.verdict()).isEqualTo(Verdict.IMAGE_BLOCKED);
        assertThat(o.topCategory()).isEqualTo("PORN");
    }

    // ---------- AC4：白名单优先级（§9.3） ----------

    @Test
    void evaluate_whitelistExemptsL1() {
        // "anjing" 同时是 L1 黑名单外观与 L3 白名单词（宠物语境）→ 不触发硬拦截。
        ModerationOutcome o = service().evaluate("anjing lucu banget hari ini", null);
        assertThat(o.verdict()).isNotEqualTo(Verdict.TEXT_BLOCKED);
        assertThat(o.verdict()).isIn(Verdict.PASS, Verdict.RISKY);
    }

    @Test
    void evaluate_whitelistDoesNotExemptOtherL1() {
        // 白名单只豁免其覆盖的词；narkoba 仍应硬拦截。
        ModerationOutcome o = service().evaluate("anjing lucu tapi jual narkoba", null);
        assertThat(o.verdict()).isEqualTo(Verdict.TEXT_BLOCKED);
        assertThat(o.topCategory()).isEqualTo("DRUGS");
    }

    // ---------- AC5：fail-closed（绝不 PASS） ----------

    @Test
    void evaluate_timeoutDegradesNeverPass() {
        assertDegraded(service().evaluate("stub-timeout here", null), "TIMEOUT");
    }

    @Test
    void evaluate_http4xxDegradesNeverPass() {
        assertDegraded(service().evaluate("stub-4xx here", null), "HTTP_4XX");
    }

    @Test
    void evaluate_http5xxDegradesNeverPass() {
        assertDegraded(service().evaluate("stub-5xx here", null), "HTTP_5XX");
    }

    @Test
    void evaluate_quotaDegradesNeverPass() {
        assertDegraded(service().evaluate("stub-quota here", null), "QUOTA");
    }

    @Test
    void evaluate_imageTimeoutDegradesNeverPass() {
        ModerationOutcome o = service().evaluate("teks normal",
                List.of("https://cdn.petgo.test/stub-img-timeout.jpg"));
        assertDegraded(o, "TIMEOUT");
    }

    @Test
    void evaluate_circuitOpenDegradesNeverPass() {
        // 熔断器构造为「1 次失败即打开、窗口极长」，第二次调用短路为 CIRCUIT_OPEN。
        KeywordRuleEngine engine = new KeywordRuleEngine(null);
        engine.apply(List.of());
        ModerationCircuitBreaker breaker = new ModerationCircuitBreaker(1, 10_000L, () -> 0L);
        ContentModerationService svc = new ContentModerationService(
                engine, new StubContentSafetyClient(), new ModerationProperties(), breaker);
        // 第 1 次：真失败（TIMEOUT）→ 打开熔断。
        assertDegraded(svc.evaluate("stub-timeout", null), "TIMEOUT");
        // 第 2 次：干净文本，但熔断已开 → 不打三方，短路 CIRCUIT_OPEN。
        assertDegraded(svc.evaluate("teks bersih tanpa masalah", null), "CIRCUIT_OPEN");
    }

    private void assertDegraded(ModerationOutcome o, String reason) {
        assertThat(o.verdict()).isEqualTo(Verdict.DEGRADED);
        assertThat(o.verdict()).isNotEqualTo(Verdict.PASS);
        assertThat(o.degraded()).isTrue();
        assertThat(o.degradeReason()).isEqualTo(reason);
        assertThat(o.riskScore()).isEqualTo(-1.0);
    }
}
