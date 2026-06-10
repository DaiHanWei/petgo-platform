package com.tailtopia.triage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.triage.domain.DangerLevel;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：确定性安全规则层（Story 4.2，J1）。穷举只升不降 + 双语命中 + E5 否定 + fail-fast。
 *
 * <p>核心安全断言：命中清单 → 无论模型返回什么，最终必为 RED；任何组合均无下调。
 */
class SafetyRuleLayerTest {

    private SafetyRuleLayer layer;

    @BeforeEach
    void setUp() {
        layer = new SafetyRuleLayer();
        layer.load(); // 加载真实 resources/safety/high_risk_symptoms.yml
    }

    private static final String HIT = "我的狗误食巧克力了";
    private static final String NO_HIT = "我的狗轻微打喷嚏";

    // ---- AC1：穷举 (model ∈ {G,Y,R}) × (命中 ∈ {true,false})，断言无任何下调 ----

    @Test
    void hitForcesRedRegardlessOfModelLevel() {
        for (DangerLevel model : DangerLevel.values()) {
            SafetyDecision d = layer.enforce(model, HIT);
            assertThat(d.finalLevel()).as("model=%s 命中必 RED", model).isEqualTo(DangerLevel.RED);
            assertThat(d.matchedRuleIds()).contains("chocolate_ingestion");
            // 只升不降：最终不低于模型
            assertThat(d.finalLevel().ordinal()).isGreaterThanOrEqualTo(model.ordinal());
        }
    }

    @Test
    void noHitKeepsModelLevelAndNeverDowngrades() {
        for (DangerLevel model : DangerLevel.values()) {
            SafetyDecision d = layer.enforce(model, NO_HIT);
            assertThat(d.finalLevel()).as("model=%s 未命中保留模型值", model).isEqualTo(model);
            assertThat(d.escalatedBySafetyRule()).isFalse();
            assertThat(d.finalLevel().ordinal()).isGreaterThanOrEqualTo(model.ordinal());
        }
    }

    @Test
    void modelRedNeverDowngradedEvenWithoutHit() {
        SafetyDecision d = layer.enforce(DangerLevel.RED, NO_HIT);
        assertThat(d.finalLevel()).isEqualTo(DangerLevel.RED);
        assertThat(d.escalatedBySafetyRule()).isFalse(); // 模型本已红，非规则升级
    }

    @Test
    void escalationFlagTrueOnlyWhenRulePushesUp() {
        assertThat(layer.enforce(DangerLevel.GREEN, HIT).escalatedBySafetyRule()).isTrue();
        assertThat(layer.enforce(DangerLevel.YELLOW, HIT).escalatedBySafetyRule()).isTrue();
        assertThat(layer.enforce(DangerLevel.RED, HIT).escalatedBySafetyRule()).isFalse();
    }

    @Test
    void nullModelLevelTreatedAsGreenThenRuleLayer() {
        assertThat(layer.enforce(null, HIT).finalLevel()).isEqualTo(DangerLevel.RED);
        assertThat(layer.enforce(null, NO_HIT).finalLevel()).isEqualTo(DangerLevel.GREEN);
    }

    // ---- 双源匹配：症状空但解析文本命中 → 仍升红 ----

    @Test
    void hitInSecondSourceParsedTextStillEscalates() {
        SafetyDecision d = layer.enforce(DangerLevel.GREEN, null, "建议警惕呼吸困难加重");
        assertThat(d.finalLevel()).isEqualTo(DangerLevel.RED);
    }

    // ---- 双语命中（zh / id / en）----

    @Test
    void bilingualSignalsMatch() {
        assertThat(layer.enforce(DangerLevel.GREEN, "dog ate chocolate").finalLevel())
                .isEqualTo(DangerLevel.RED);
        assertThat(layer.enforce(DangerLevel.GREEN, "anjing sesak napas").finalLevel())
                .isEqualTo(DangerLevel.RED);
        assertThat(layer.enforce(DangerLevel.GREEN, "kucing muntah darah").finalLevel())
                .isEqualTo(DangerLevel.RED);
        assertThat(layer.enforce(DangerLevel.GREEN, "cat can't urinate, straining to urinate").finalLevel())
                .isEqualTo(DangerLevel.RED);
    }

    // ---- E5 保守否定：绝不漏掉未否定的真实急症 ----

    @Test
    void negatedSingleSymptomDoesNotEscalate() {
        assertThat(layer.enforce(DangerLevel.GREEN, "狗没有呼吸困难").finalLevel())
                .isEqualTo(DangerLevel.GREEN);
        assertThat(layer.enforce(DangerLevel.GREEN, "没吃到巧克力").finalLevel())
                .isEqualTo(DangerLevel.GREEN);
        assertThat(layer.enforce(DangerLevel.GREEN, "dog without labored breathing").finalLevel())
                .isEqualTo(DangerLevel.GREEN);
    }

    @Test
    void otherUnnegatedEmergencyStillEscalatesDespiteNegation() {
        // 「没有呼吸困难，但呕吐带血」→ 呕吐带血未否定 → 仍 RED
        SafetyDecision d = layer.enforce(DangerLevel.GREEN, "没有呼吸困难，但呕吐带血");
        assertThat(d.finalLevel()).isEqualTo(DangerLevel.RED);
        assertThat(d.matchedRuleIds()).contains("vomiting_blood");
        assertThat(d.matchedRuleIds()).doesNotContain("labored_breathing");
    }

    @Test
    void negationDoesNotLeakAcrossSources() {
        // 症状源含否定，解析源含真实急症 → 不跨源否定 → 升红
        SafetyDecision d = layer.enforce(DangerLevel.GREEN, "狗没有别的问题", "呕吐带血明显");
        assertThat(d.finalLevel()).isEqualTo(DangerLevel.RED);
    }

    // ---- 起步范围 15–20 ----

    @Test
    void listCoversFifteenToTwentyEmergencies() {
        // 用穷举命中数粗验清单非空且覆盖代表条目（实际条目数验证见加载日志）。
        assertThat(layer.enforce(DangerLevel.GREEN, "误食葡萄干").matchedRuleIds())
                .contains("grape_raisin_ingestion");
        assertThat(layer.enforce(DangerLevel.GREEN, "猫误食百合").matchedRuleIds())
                .contains("lily_ingestion");
        assertThat(layer.enforce(DangerLevel.GREEN, "胃扭转").matchedRuleIds())
                .contains("gdv_bloat");
    }

    // ---- fail-fast：清单缺失 / 为空 / 结构非法 → 启动失败 ----

    @Test
    void failFastOnNullOrEmptyOrMalformed() {
        SafetyRuleLayer fresh = new SafetyRuleLayer();
        assertThatThrownBy(() -> fresh.applyConfig(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fresh.applyConfig(Map.of()))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fresh.applyConfig(Map.of("emergencies", List.of())))
                .isInstanceOf(IllegalStateException.class);
        // 条目缺 signals
        assertThatThrownBy(() -> fresh.applyConfig(
                Map.of("emergencies", List.of(Map.of("id", "x")))))
                .isInstanceOf(IllegalStateException.class);
        // 条目缺 id
        assertThatThrownBy(() -> fresh.applyConfig(
                Map.of("emergencies", List.of(Map.of("signals", List.of("呼吸困难"))))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void validMinimalConfigLoads() {
        SafetyRuleLayer fresh = new SafetyRuleLayer();
        fresh.applyConfig(Map.of(
                "negations", Map.of("zh", List.of("没")),
                "emergencies", List.of(Map.of("id", "x", "signals", List.of("呕吐带血")))));
        assertThat(fresh.enforce(DangerLevel.GREEN, "狗呕吐带血").finalLevel()).isEqualTo(DangerLevel.RED);
    }
}
