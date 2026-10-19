package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationAggregateDao;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.ops.target.OpsTargetType;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.4.0 ④ Story 1.2 · AC1 / AC3 / AC4 —— {@link NotificationService} 的推送分类开关闸门。
 *
 * <p>闸门在「落库与加角标之后、调 pusher 之前」：关了的类型照样落库 + 角标 +1，只是不推。
 * 宽松桩：各用例共用一套渲染桩，按需取用。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class NotificationServicePushGateTest {

    @Mock NotificationRepository repo;
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    @Mock NotificationPusher pusher;
    @Mock MessageSource messageSource;
    @Mock AccountQueryService accountQuery;
    @Mock NotificationAggregateDao aggregates;
    @Mock NotificationActorRepository actors;
    @Mock InteractionCopyRenderer copy;
    @Mock InteractionActorResolver resolver;
    @Mock PushPreferenceService prefs;

    private static final InteractionCopyRenderer.ActorSummary SUMMARY =
            new InteractionCopyRenderer.ActorSummary(2L, "Rani", "tok2", 0);

    private NotificationService service;

    @BeforeEach
    void setUp() {
        when(messageSource.getMessage(any(), any(), anyString(), any())).thenAnswer(inv -> inv.getArgument(2));
        when(accountQuery.localeOf(anyLong())).thenReturn(Locale.forLanguageTag("id"));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(repo.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            ReflectionTestUtils.setField(n, "id", 100L);
            return n;
        });
        when(repo.findById(100L)).thenAnswer(inv -> Optional.of(persisted()));
        when(actors.findActorIdsNewestFirst(anyLong())).thenReturn(List.of(2L));
        when(copy.resolve(anyLong(), any())).thenReturn(Optional.of(SUMMARY));
        when(copy.title(any(), any(), eq(SUMMARY), any())).thenReturn("Rani menyukai postinganmu");
        when(prefs.isEnabled(anyLong(), any())).thenReturn(true);
        service = new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors,
                copy, resolver, prefs);
    }

    private static Notification persisted() {
        Notification n = Notification.interaction(9L, NotificationType.CONTENT_LIKED, "t", "b",
                NotificationType.CONTENT_LIKED.name(), "tok", "55", "LIKE:POST:55", null, null);
        ReflectionTestUtils.setField(n, "id", 100L);
        return n;
    }

    private void likeUpsert(long actorId) {
        when(aggregates.upsertAggregate(eq(9L), eq(NotificationType.CONTENT_LIKED), anyString(), anyString(),
                anyString(), anyString(), eq("55"), eq("LIKE:POST:55"), any(), any()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, actorId)).thenReturn(true);
    }

    @Test
    void mutedSendStillWritesRowAndBadgeButDoesNotPush() {
        when(prefs.isEnabled(7L, NotificationType.MILESTONE_NODE)).thenReturn(false);

        service.send(7L, NotificationType.MILESTONE_NODE, "t", "b", "MILESTONE_NODE", "ref");

        verify(repo, times(1)).save(any(Notification.class));
        verify(valueOps, times(1)).increment("notify:unread:7");
        verifyNoInteractions(pusher);
        verify(accountQuery, never()).localeOf(anyLong());
    }

    @Test
    void mentionMutedButCommentEnabled() {
        when(prefs.isEnabled(9L, NotificationType.CONTENT_MENTIONED)).thenReturn(false);
        when(prefs.isEnabled(9L, NotificationType.CONTENT_COMMENTED)).thenReturn(true);

        service.sendWithCopy(9L, NotificationType.CONTENT_MENTIONED, "CONTENT_MENTIONED", null, "t", "b", "55");
        verifyNoInteractions(pusher);

        service.sendInteraction(9L, NotificationType.CONTENT_COMMENTED, 2L, null, "55", 7L, null, "halo");
        verify(pusher, times(1)).pushToUser(eq(9L), any(), any(),
                eq(NotificationType.CONTENT_COMMENTED.name()), any(), any());
    }

    @Test
    void mutedLikeDoesNotTakeDebounceSlotNorResolveActors() {
        likeUpsert(2L);
        when(prefs.isEnabled(9L, NotificationType.CONTENT_LIKED)).thenReturn(false);

        service.sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55", null, null, null);

        verify(valueOps).increment("notify:unread:9");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(Duration.class));
        verify(actors, never()).findActorIdsNewestFirst(anyLong());
        verifyNoInteractions(pusher);

        // 重新打开后，下一个触发人（合并进同一行）抢到去抖键、推送一次
        likeUpsert(3L);
        when(prefs.isEnabled(9L, NotificationType.CONTENT_LIKED)).thenReturn(true);
        when(valueOps.setIfAbsent("notify:likepush:55", "1", Duration.ofMinutes(10))).thenReturn(true);

        service.sendInteraction(9L, NotificationType.CONTENT_LIKED, 3L, "LIKE:POST:55", "55", null, null, null);

        verify(valueOps).setIfAbsent("notify:likepush:55", "1", Duration.ofMinutes(10));
        verify(pusher, times(1)).pushToUser(eq(9L), any(), any(), eq("CONTENT_LIKED"), any(), any());
    }

    @Test
    void ticketResolvedPushesButCsatMuted() {
        when(prefs.isEnabled(7L, NotificationType.TICKET_RESOLVED)).thenReturn(true);
        when(prefs.isEnabled(7L, NotificationType.CSAT_SURVEY)).thenReturn(false);

        service.send(7L, NotificationType.TICKET_RESOLVED, "t", "b", "TICKET_RESOLVED", "tk");
        service.send(7L, NotificationType.CSAT_SURVEY, "t", "b", "CSAT_SURVEY", "tk");

        verify(repo, times(2)).save(any(Notification.class));
        verify(valueOps, times(2)).increment("notify:unread:7");
        verify(pusher, times(1)).pushToUser(anyLong(), any(), any(), any(), any(), any());
        verify(pusher).pushToUser(eq(7L), any(), any(), eq("TICKET_RESOLVED"), any(), eq("tk"));
    }

    @Test
    void orderAccountTypesPassedThroughAndPushed() {
        List<NotificationType> types = List.of(NotificationType.SHOP_ORDER_SHIPPED,
                NotificationType.PET_SLOT_EXPIRING, NotificationType.ACCOUNT_SUSPENDED);
        for (NotificationType t : types) {
            service.send(7L, t, "t", "b", t.name(), "r");
        }
        ArgumentCaptor<NotificationType> cap = ArgumentCaptor.forClass(NotificationType.class);
        verify(prefs, times(3)).isEnabled(eq(7L), cap.capture());
        assertThat(cap.getAllValues()).containsExactlyElementsOf(types);
        verify(pusher, times(3)).pushToUser(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void virtualAndOfficialAccountsSkipWithoutQueryingPrefs() {
        when(accountQuery.isVirtual(7L)).thenReturn(true);
        service.send(7L, NotificationType.CONTENT_REMOVED, "t", "b", "X", "r");
        service.sendWithCopy(7L, NotificationType.LIFECYCLE_D1, "LIFECYCLE_D1.RECORD", null, "t", "b", "r");

        likeUpsert(2L);
        when(accountQuery.isOfficialAccount(9L)).thenReturn(true);
        service.sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55", null, null, null);

        verifyNoInteractions(prefs, pusher);
    }

    @Test
    void prefsFailureFallsBackToPushOnAllThreePaths() {
        when(prefs.isEnabled(anyLong(), any())).thenThrow(new IllegalStateException("db"));
        when(valueOps.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        likeUpsert(2L);

        service.send(7L, NotificationType.USER_FOLLOWED, "t", "b", "X", "r");
        service.sendWithCopy(7L, NotificationType.PET_BIRTHDAY, "PET_BIRTHDAY", null, "t", "b", "r");
        service.sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55", null, null, null);

        verify(pusher, times(3)).pushToUser(anyLong(), any(), any(), any(), any(), any());
        verify(repo, times(2)).save(any(Notification.class));
    }

    @Test
    void nonPushPathsNeverTouchPrefs() {
        service.sendWithoutPush(7L, NotificationType.MILESTONE_SM_NODE, "t", "b", "X", "r");
        service.sendToVet(3L, NotificationType.NEW_CONSULT_REQUEST, "t", "b", "NEW_CONSULT_REQUEST");
        OpsNotificationCampaign c = OpsNotificationCampaign.create("campTok22charsAbcdefgh", "Judul", "Title",
                "Isi", "Body", OpsTargetType.NONE, null, AudienceSpec.all().toJson(), null, 1L);
        service.writeOpsAnnouncements(c, List.of(new OpsRecipient(5L, "id")));

        verifyNoInteractions(prefs);
        verify(pusher).pushToVet(eq(3L), any(), any(), eq("NEW_CONSULT_REQUEST"), any(), any());
        verify(pusher, never()).pushToUser(anyLong(), any(), any(), any(), any(), any());
    }

    // ===== V1.4.0 push-collect Story 3.1 · AC1.2：③ sendGiftReceived 直接 pushToUser 出口的闸门 =====

    private void giftUpsert(long actorId, boolean inserted, boolean newActor) {
        when(aggregates.upsertAggregate(eq(9L), eq(NotificationType.PET_GIFT_RECEIVED), anyString(), anyString(),
                anyString(), anyString(), eq("70"), eq("GIFT:PET:70"), any(), any()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", inserted));
        when(aggregates.addActor(100L, actorId)).thenReturn(newActor);
    }

    @Test
    void mutedGiftStillWritesAndBadgesButDoesNotPush() {
        when(prefs.isEnabled(9L, NotificationType.PET_GIFT_RECEIVED)).thenReturn(false);
        giftUpsert(2L, true, true);

        Notification n = service.sendGiftReceived(9L, 2L, 70L, "Andi mengirim Tulang untuk Mochi");

        assertThat(n).isNotNull();
        verify(aggregates).addActor(100L, 2L);
        verify(valueOps).increment("notify:unread:9");
        verify(prefs).isEnabled(9L, NotificationType.PET_GIFT_RECEIVED);
        verifyNoInteractions(pusher);
    }

    /** T-29 分支：同一送礼人未读期间再送 → touch 照常；关了 Hadiah 仍不推。 */
    @Test
    void mutedSameSenderAgainTouchesButDoesNotPush() {
        when(prefs.isEnabled(9L, NotificationType.PET_GIFT_RECEIVED)).thenReturn(false);
        giftUpsert(2L, false, false);

        service.sendGiftReceived(9L, 2L, 70L, "Andi mengirim Tulang untuk Mochi");

        verify(aggregates).touch(100L);
        verifyNoInteractions(pusher);
    }

    @Test
    void enabledGiftPushesOnceAndQueriesOnlyGiftType() {
        giftUpsert(2L, false, false);

        service.sendGiftReceived(9L, 2L, 70L, "Andi mengirim Tulang untuk Mochi");

        verify(pusher, times(1)).pushToUser(9L, "Andi mengirim Tulang untuk Mochi", "", "PET_GIFT_RECEIVED", "tok",
                "70");
        verify(prefs, times(1)).isEnabled(9L, NotificationType.PET_GIFT_RECEIVED);
        verify(prefs, never()).isEnabled(anyLong(), eq(NotificationType.CONTENT_LIKED));
    }

    /** 虚拟号 / 官方号的既有跳过保留，且在闸门之前（不查偏好）。 */
    @Test
    void giftToOfficialRecipientSkipsBeforeGate() {
        when(accountQuery.isOfficialAccount(9L)).thenReturn(true);
        giftUpsert(2L, true, true);

        service.sendGiftReceived(9L, 2L, 70L, "x");

        verifyNoInteractions(prefs, pusher);
    }

    @Test
    void giftPrefsFailureFallsBackToPush() {
        when(prefs.isEnabled(anyLong(), any())).thenThrow(new IllegalStateException("db"));
        giftUpsert(2L, true, true);

        service.sendGiftReceived(9L, 2L, 70L, "x");

        verify(pusher, times(1)).pushToUser(eq(9L), eq("x"), eq(""), eq("PET_GIFT_RECEIVED"), eq("tok"), eq("70"));
    }
}
