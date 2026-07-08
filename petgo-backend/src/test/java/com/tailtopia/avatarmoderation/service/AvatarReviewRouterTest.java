package com.tailtopia.avatarmoderation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.avatarmoderation.domain.AvatarPriority;
import com.tailtopia.avatarmoderation.domain.AvatarReviewStatus;
import com.tailtopia.avatarmoderation.domain.AvatarReviewVerdict;
import com.tailtopia.avatarmoderation.service.AvatarReviewRouter.RoutingDecision;
import com.tailtopia.content.moderation.DegradeReason;
import com.tailtopia.content.moderation.ModerationOutcome;
import com.tailtopia.content.service.ContentModerationService.Verdict;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * L0：头像图像评分路由分档（内容审核 story 5，AC-B5~B8）。纯函数、无 DB。
 * 覆盖 cm-1 图像二值口径（IMAGE_BLOCKED→HIGH 不 throw / PASS→AUTO_PASSED）+ fail-closed +
 * 为 story 1 未来非二值预留的中间档。
 */
class AvatarReviewRouterTest {

    @Test
    void imageBlockedRoutesToHighManualNotThrown() {
        // cm-1 图像高置信违规（色情≥0.85 等）→ IMAGE_BLOCKED（risk 1.0）→ 不 throw、入队标 HIGH（§5.1 P1）。
        RoutingDecision d = AvatarReviewRouter.route(ModerationOutcome.imageBlocked("PORN"));
        assertThat(d.status()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(AvatarPriority.HIGH);
        assertThat(d.verdict()).isEqualTo(AvatarReviewVerdict.PENDING_REVIEW);
        assertThat(d.riskScore()).isEqualByComparingTo(BigDecimal.valueOf(1.0));
    }

    @Test
    void degradedFailsClosedToManualQueue() {
        // 三方降级 → 绝不自动放行，入队（risk NULL，verdict DEGRADED_QUEUED，D-CM5）。
        RoutingDecision d = AvatarReviewRouter.route(ModerationOutcome.degraded(DegradeReason.TIMEOUT));
        assertThat(d.status()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(AvatarPriority.NORMAL);
        assertThat(d.verdict()).isEqualTo(AvatarReviewVerdict.DEGRADED_QUEUED);
        assertThat(d.riskScore()).isNull();
    }

    @Test
    void passImageAutoPasses() {
        // cm-1 正常图（纯图无文）→ PASS，risk 0.0 → 自动通过（终态静默，不推送）。
        RoutingDecision d = AvatarReviewRouter.route(ModerationOutcome.pass(0.0, null));
        assertThat(d.status()).isEqualTo(AvatarReviewStatus.AUTO_PASSED);
        assertThat(d.verdict()).isEqualTo(AvatarReviewVerdict.PASS);
        assertThat(d.priority()).isEqualTo(AvatarPriority.NORMAL);
    }

    @Test
    void midBandRoutesToNormalManual_reservedForNonBinaryFuture() {
        // story 1 未来若给图像返回中间置信度 [0.6,0.8) → 入队 NORMAL（今 stub 二值下不可达）。
        RoutingDecision d = AvatarReviewRouter.route(new ModerationOutcome(Verdict.RISKY, 0.7, null, false, null));
        assertThat(d.status()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(AvatarPriority.NORMAL);
        assertThat(d.verdict()).isEqualTo(AvatarReviewVerdict.PENDING_REVIEW);
    }

    @Test
    void highBandRoutesToHighManual_reservedForNonBinaryFuture() {
        RoutingDecision d = AvatarReviewRouter.route(new ModerationOutcome(Verdict.RISKY, 0.85, null, false, null));
        assertThat(d.status()).isEqualTo(AvatarReviewStatus.MANUAL_PENDING);
        assertThat(d.priority()).isEqualTo(AvatarPriority.HIGH);
    }
}
