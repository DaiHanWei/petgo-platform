package com.tailtopia.content.moderation;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ModerationKeywordRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/** L0（AC4/§5.4）：分层词库匹配 —— 白名单优先豁免、L1 硬拦截、L2 加权、EXACT/REGEX、enabled 过滤。 */
class KeywordRuleEngineTest {

    private KeywordRuleEngine engine(List<ModerationKeywordRule> rules) {
        KeywordRuleEngine e = new KeywordRuleEngine(null);
        e.apply(rules);
        return e;
    }

    @Test
    void l1SubstringBlocks() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "narkoba", "DRUGS", "id", true)));
        KeywordClassification c = e.classify("jual narkoba murah");
        assertThat(c.l1Blocked()).isTrue();
        assertThat(c.l1Category()).isEqualTo("DRUGS");
    }

    @Test
    void whitelistExemptsOverlappingL1() {
        // anjing 既是 L1 外观又是 L3 白名单 → 豁免（§9.3）。
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "anjing", "HARASSMENT", "id", true),
                ModerationKeywordRule.of("L3_WHITELIST", "EXACT", "anjing", "PET_SAFE", "id", true)));
        assertThat(e.classify("anjing lucu").l1Blocked()).isFalse();
    }

    @Test
    void whitelistDoesNotExemptUnrelatedL1() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "narkoba", "DRUGS", "id", true),
                ModerationKeywordRule.of("L3_WHITELIST", "EXACT", "anjing", "PET_SAFE", "id", true)));
        assertThat(e.classify("anjing dan narkoba").l1Blocked()).isTrue();
    }

    @Test
    void exactMatchIsTokenBounded() {
        // EXACT anjing 不应命中 anjingan（整词边界）。
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L3_WHITELIST", "EXACT", "anjing", "PET_SAFE", "id", true),
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "anjingan", "HARASSMENT", "id", true)));
        // 白名单 EXACT 未命中 anjingan → L1 substring 命中且不豁免。
        assertThat(e.classify("dasar anjingan").l1Blocked()).isTrue();
    }

    @Test
    void l2AddsWeightNotBlock() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L2_ADJUSTABLE", "SUBSTRING", "wa.me/", "AD_SPAM", "ALL", true)));
        KeywordClassification c = e.classify("chat saya di wa.me/628123");
        assertThat(c.l1Blocked()).isFalse();
        assertThat(c.l2Weight()).isGreaterThan(0.0);
        assertThat(c.l2Category()).isEqualTo("AD_SPAM");
    }

    @Test
    void regexL2Matches() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L2_ADJUSTABLE", "REGEX",
                        "\\b08[0-9]{2}[-\\s]?[0-9]{4}[-\\s]?[0-9]{4}\\b", "AD_SPAM", "id", true)));
        assertThat(e.classify("hubungi 0812 3456 7890 ya").l2Weight()).isGreaterThan(0.0);
        assertThat(e.classify("tidak ada nomor").l2Weight()).isEqualTo(0.0);
    }

    @Test
    void disabledRuleIsIgnored() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "judi", "GAMBLING", "id", false)));
        assertThat(e.classify("ayo judi").l1Blocked()).isFalse();
    }

    @Test
    void blankTextIsClean() {
        KeywordRuleEngine e = engine(List.of(
                ModerationKeywordRule.of("L1_BLOCK", "SUBSTRING", "judi", "GAMBLING", "id", true)));
        assertThat(e.classify(null).l1Blocked()).isFalse();
        assertThat(e.classify("   ").l1Blocked()).isFalse();
    }
}
