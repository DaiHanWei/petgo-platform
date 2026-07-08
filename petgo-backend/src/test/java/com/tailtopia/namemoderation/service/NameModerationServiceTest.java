package com.tailtopia.namemoderation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.content.service.ContentModerationService;
import com.tailtopia.namemoderation.domain.NameDecision;
import com.tailtopia.namemoderation.domain.NameModerationRecord;
import com.tailtopia.namemoderation.domain.NameModerationStatus;
import com.tailtopia.namemoderation.domain.NamePriority;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.event.NameResetEvent;
import com.tailtopia.namemoderation.repository.NameModerationRecordRepository;
import com.tailtopia.namemoderation.service.NameModerationRouter.RoutingDecision;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

/**
 * L0（AC-B3 陈旧作废 + 处置状态机）：用 mock repo 覆盖评分结果落库前的 revision 陈旧校验与运营处置路径。无 DB。
 */
class NameModerationServiceTest {

    private final NameModerationRecordRepository records = mock(NameModerationRecordRepository.class);
    private final ContentModerationService moderation = mock(ContentModerationService.class);
    private final DefaultNameGenerator nameGenerator = mock(DefaultNameGenerator.class);
    private final UserRepository users = mock(UserRepository.class);
    private final PetProfileRepository petProfiles = mock(PetProfileRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);

    private final NameModerationService service = new NameModerationService(
            records, moderation, nameGenerator, users, petProfiles, events);

    private static NameModerationRecord scoringRecord(NameTargetType type, long refId, long revision) {
        return NameModerationRecord.scoring(type, refId, revision, "some-name", Instant.now());
    }

    // ---------- AC-B3：陈旧作废（A→B→C，B 的结果回来时已 SUPERSEDED → 静默丢弃） ----------

    @Test
    void applyScore_droppedWhenSuperseded() {
        NameModerationRecord superseded = scoringRecord(NameTargetType.NICKNAME, 7L, 2L);
        superseded.supersede(); // B 已被 C 取代
        when(records.findById(100L)).thenReturn(Optional.of(superseded));

        service.applyScoreOutcome(100L, new RoutingDecision(
                NameModerationStatus.MANUAL_PENDING, NamePriority.HIGH, BigDecimal.valueOf(0.9)), 0);

        // 不改状态、不改名、不入队、不发通知。
        assertThat(superseded.getStatus()).isEqualTo(NameModerationStatus.SUPERSEDED);
        verify(events, never()).publishEvent(any());
        verify(users, never()).save(any());
    }

    @Test
    void applyScore_appliedWhenStillScoring() {
        NameModerationRecord latest = scoringRecord(NameTargetType.NICKNAME, 7L, 3L); // C，仍最新
        when(records.findById(101L)).thenReturn(Optional.of(latest));

        service.applyScoreOutcome(101L, new RoutingDecision(
                NameModerationStatus.MANUAL_PENDING, NamePriority.HIGH, BigDecimal.valueOf(0.9)), 1);

        assertThat(latest.getStatus()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(latest.getPriority()).isEqualTo(NamePriority.HIGH);
        assertThat(latest.getRiskScore()).isEqualByComparingTo(BigDecimal.valueOf(0.9));
        assertThat(latest.getRetryCount()).isEqualTo(1);
    }

    // ---------- 运营处置：VIOLATION → 重置默认名 + 发 NameResetEvent ----------

    @Test
    void decide_violation_resetsNicknameAndPublishesEvent() {
        NameModerationRecord pending = scoringRecord(NameTargetType.NICKNAME, 42L, 1L);
        pending.applyScore(NameModerationStatus.MANUAL_PENDING, NamePriority.HIGH, BigDecimal.valueOf(0.95), 0);
        when(records.findById(200L)).thenReturn(Optional.of(pending));

        User user = User.newGoogleUser("sub-x", "e@x.id", "违规昵称", null);
        when(users.findById(42L)).thenReturn(Optional.of(user));
        when(nameGenerator.generate(any(), any())).thenReturn("user_ab12cd34");

        service.decide(200L, NameDecision.VIOLATION, 9L, "AD_SPAM");

        assertThat(user.getNickname()).isEqualTo("user_ab12cd34");
        assertThat(user.isSystemDefaultName()).isTrue();
        assertThat(pending.getStatus()).isEqualTo(NameModerationStatus.RESOLVED_VIOLATION);
        assertThat(pending.getDecidedBy()).isEqualTo(9L);
        verify(users).save(user);
        verify(events).publishEvent(any(NameResetEvent.class));
    }

    @Test
    void decide_pass_resolvesWithoutResetOrNotify() {
        NameModerationRecord pending = scoringRecord(NameTargetType.PET_NAME, 5L, 1L);
        pending.applyScore(NameModerationStatus.MANUAL_PENDING, NamePriority.NORMAL, BigDecimal.valueOf(0.7), 0);
        when(records.findById(201L)).thenReturn(Optional.of(pending));

        service.decide(201L, NameDecision.PASS, 9L, null);

        assertThat(pending.getStatus()).isEqualTo(NameModerationStatus.RESOLVED_PASS);
        verify(events, never()).publishEvent(any());
        verify(petProfiles, never()).save(any());
    }

    @Test
    void decide_rejectsNonPendingRecord() {
        NameModerationRecord autoPassed = scoringRecord(NameTargetType.NICKNAME, 1L, 1L);
        autoPassed.applyScore(NameModerationStatus.AUTO_PASSED, NamePriority.NORMAL, BigDecimal.valueOf(0.1), 0);
        when(records.findById(202L)).thenReturn(Optional.of(autoPassed));

        assertThatThrownBy(() -> service.decide(202L, NameDecision.VIOLATION, 9L, "X"))
                .isInstanceOf(AppException.class);
        verify(events, never()).publishEvent(any());
    }

    // ---------- 评分编排：DEGRADED 重试耗尽 → fail-closed 入队（AC-B7 的 L0 骨架） ----------

    @Test
    void scoreAndRoute_degradedThroughout_failsClosedToManualQueue() {
        when(moderation.evaluate(anyString(), any()))
                .thenReturn(com.tailtopia.content.moderation.ModerationOutcome.degraded(
                        com.tailtopia.content.moderation.DegradeReason.TIMEOUT));

        // scoreAndRoute 仅评分并返回路由决策（不落库；落库由监听器跨 bean 调 applyScoreOutcome）。
        NameModerationService.ScoredRouting scored = service.scoreAndRoute(300L, "some-name");

        assertThat(scored.decision().status()).isEqualTo(NameModerationStatus.MANUAL_PENDING);
        assertThat(scored.decision().riskScore()).isNull(); // 降级不落分
        assertThat(scored.retries()).isEqualTo(NameModerationService.MAX_ATTEMPTS - 1);
    }
}
