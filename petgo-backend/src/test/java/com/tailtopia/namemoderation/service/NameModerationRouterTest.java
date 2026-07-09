package com.tailtopia.namemoderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.moderation.DegradeReason;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NamePriority;
import com.tailtopia.namemoderation.service.NameModerationRouter.RoutingDecision;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * L0（AC-B2 / TEXT_BLOCKED / DEGRADED）：评分路由分档边界（含闭开区间）。纯函数，无 DB / 无三方。
 */
class NameModerationRouterTest {

    // ---------- AC-B2：评分分档边界 ----------

    @Test
    void score059_autoPassed() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.pass(0.59, null));
        assertThat(d.status()).isEqualTo(NameModerationStatus.AUTO_PASSED);
        assertThat(d.riskScore()).isEqualByComparingTo(BigDecimal.valueOf(0.590));
    }

    @Test
    void score060_manualNormal() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.pass(0.6, null));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.NORMAL);
    }

    @Test
    void score079_manualNormal() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.pass(0.79, null));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.NORMAL);
    }

    @Test
    void score080_manualHigh() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.risky(0.8, "AD_SPAM"));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.HIGH);
    }

    @Test
    void score0999_manualHigh() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.risky(0.999, "PORN"));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.HIGH);
        assertThat(d.riskScore()).isEqualByComparingTo(BigDecimal.valueOf(0.999));
    }

    // ---------- L1 硬命中：不 throw，视为满分入队标 HIGH ----------

    @Test
    void textBlocked_manualHigh_notThrown() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.textBlocked("GAMBLING"));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.HIGH);
        assertThat(d.riskScore()).isEqualByComparingTo(BigDecimal.valueOf(1.000));
    }

    // ---------- fail-closed 降级：入队 risk=NULL，绝不自动判过 ----------

    @Test
    void degraded_failClosedManual_riskNull() {
        RoutingDecision d = NameModerationRouter.route(ModerationOutcome.degraded(DegradeReason.TIMEOUT));
        assertThat(d.status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(NamePriority.NORMAL);
        assertThat(d.riskScore()).isNull();
    }
}
