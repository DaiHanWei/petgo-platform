package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignDraft;
import com.tailtopia.notify.ops.domain.OpsCampaignReadyEvent;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsNotificationCampaignService;
import com.tailtopia.ops.OpsTokenGenerator;
import com.tailtopia.ops.target.OpsTargetPolicy;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：运营通知活动新建 / 取消（V1.3.4 Story 6.2 · AC2）。跳转目标只断言「调了 5.1 的
 * {@link OpsTargetPolicy#isAvailable} 且其否决原样生效」，具体目标合法性归 {@code OpsTargetPolicyTest}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsNotificationCampaignServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");

    @Mock
    OpsNotificationCampaignRepository repo;
    @Mock
    OpsCampaignTargetDao targets;
    @Mock
    OpsTargetPolicy policy;
    @Mock
    OpsTokenGenerator tokens;
    @Mock
    ApplicationEventPublisher events;

    private OpsNotificationCampaignService service;

    @BeforeEach
    void setUp() {
        service = new OpsNotificationCampaignService(repo, targets, policy, tokens, events,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(tokens.generate()).thenReturn("tok22charsAbcdefghijkl");
        when(policy.isAvailable(any(), any())).thenReturn(true);
        when(repo.save(any())).thenAnswer(inv -> {
            OpsNotificationCampaign c = inv.getArgument(0);
            ReflectionTestUtils.setField(c, "id", 99L);
            return c;
        });
    }

    private static OpsCampaignDraft draft(String titleId, String bodyId, AudienceSpec audience, List<Long> ids,
            Instant scheduledAt) {
        return new OpsCampaignDraft(titleId, "Weekend promo", bodyId, "Body EN", OpsTargetType.APP_PAGE, "HOME",
                audience, ids, scheduledAt);
    }

    private static OpsCampaignDraft ok() {
        return draft("Promo akhir pekan", "Isi ID", AudienceSpec.all(), null, null);
    }

    private void assertRejected(OpsCampaignDraft d, String code) {
        assertThatThrownBy(() -> service.create(d, 1L))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode())
                .isEqualTo(code);
        verify(repo, never()).save(any());
    }

    @Test
    void blankTextRejected() {
        assertRejected(draft("  ", "Isi", AudienceSpec.all(), null, null), "admin.err.ops-notify.text.blank");
        assertRejected(draft("Judul", null, AudienceSpec.all(), null, null), "admin.err.ops-notify.text.blank");
    }

    @Test
    void tooLongTextRejected() {
        assertRejected(draft("x".repeat(121), "Isi", AudienceSpec.all(), null, null),
                "admin.err.ops-notify.text.too-long");
        assertRejected(draft("Judul", "y".repeat(256), AudienceSpec.all(), null, null),
                "admin.err.ops-notify.text.too-long");
    }

    @Test
    void boundaryLengthsAccepted() {
        service.create(draft("x".repeat(120), "y".repeat(255), AudienceSpec.all(), null, null), 1L);
        verify(repo).save(any());
    }

    @Test
    void nullAudienceRejected() {
        assertRejected(draft("Judul", "Isi", null, null, null), "admin.err.ops-notify.audience.invalid");
    }

    @Test
    void idsWithEmptyListRejected() {
        assertRejected(draft("Judul", "Isi", AudienceSpec.ids(), List.of(), null),
                "admin.err.ops-notify.audience.ids-empty");
        assertRejected(draft("Judul", "Isi", AudienceSpec.ids(), null, null),
                "admin.err.ops-notify.audience.ids-empty");
    }

    @Test
    void pastScheduleRejected() {
        assertRejected(draft("Judul", "Isi", AudienceSpec.all(), null, NOW.minusSeconds(1)),
                "admin.err.ops-notify.schedule.past");
    }

    @Test
    void targetCheckedViaOpsTargetPolicyAndItsVetoApplies() {
        when(policy.isAvailable(OpsTargetType.APP_PAGE, "HOME")).thenReturn(false);
        assertThatThrownBy(() -> service.create(ok(), 1L)).isInstanceOf(AppException.class);
        verify(policy).isAvailable(OpsTargetType.APP_PAGE, "HOME");
        verify(repo, never()).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void targetPolicyExceptionPropagatesWithoutSaving() {
        when(policy.isAvailable(any(), any())).thenThrow(new IllegalStateException("db down"));
        assertThatThrownBy(() -> service.create(ok(), 1L)).isInstanceOf(IllegalStateException.class);
        verify(repo, never()).save(any());
    }

    @Test
    void immediateCreatePersistsPendingAndPublishesReadyEvent() {
        String token = service.create(ok(), 7L);

        assertThat(token).isEqualTo("tok22charsAbcdefghijkl");
        ArgumentCaptor<OpsNotificationCampaign> saved = ArgumentCaptor.forClass(OpsNotificationCampaign.class);
        verify(repo).save(saved.capture());
        OpsNotificationCampaign c = saved.getValue();
        assertThat(c.getPublicToken()).isEqualTo("tok22charsAbcdefghijkl");
        assertThat(c.getStatus()).isEqualTo(OpsCampaignStatus.PENDING);
        assertThat(c.getCreatedBy()).isEqualTo(7L);
        assertThat(c.getAudienceSpec()).isEqualTo(AudienceSpec.all().toJson());
        assertThat(c.getScheduledAt()).isNull();
        verify(events).publishEvent(new OpsCampaignReadyEvent(99L));
        verifyNoInteractions(targets);
    }

    @Test
    void scheduledCreateDoesNotPublish() {
        service.create(draft("Judul", "Isi", AudienceSpec.all(), null, NOW.plusSeconds(3600)), 7L);
        verify(repo).save(any());
        verifyNoInteractions(events);
    }

    @Test
    void idsCreateWritesTargets() {
        List<Long> ids = List.of(3L, 1L, 3L, 2L);
        service.create(draft("Judul", "Isi", AudienceSpec.ids(), ids, null), 7L);
        verify(targets).insertAll(99L, ids); // 去重由 OpsCampaignTargetDao.insertAll 负责（6.1）
    }

    @Test
    void cancelPendingSucceeds() {
        when(repo.cancelIfPending("tok", NOW)).thenReturn(1);
        service.cancel("tok", 1L);
        verify(repo).cancelIfPending("tok", NOW);
        verify(repo, never()).findByPublicToken(any());
    }

    @Test
    void cancelNonPendingConflicts() {
        when(repo.cancelIfPending(eq("tok"), any())).thenReturn(0);
        OpsNotificationCampaign c = OpsNotificationCampaign.create("tok", "a", "b", "c", "d", OpsTargetType.NONE,
                null, AudienceSpec.all().toJson(), null, 1L);
        when(repo.findByPublicToken("tok")).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> service.cancel("tok", 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getMessageCode()).isEqualTo("admin.err.ops-notify.cancel.not-pending");
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                });
    }

    @Test
    void cancelMissingIsNotFound() {
        when(repo.cancelIfPending(any(), any())).thenReturn(0);
        when(repo.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.cancel("nope", 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
        verify(targets, never()).insertAll(anyLong(), any());
    }
}
