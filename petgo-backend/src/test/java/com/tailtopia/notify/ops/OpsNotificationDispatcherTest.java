package com.tailtopia.notify.ops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.OpsAudienceQuery;
import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.ops.service.OpsDispatchPacer;
import com.tailtopia.notify.ops.service.OpsNotificationDispatcher;
import com.tailtopia.notify.ops.service.OpsNotifyParams;
import com.tailtopia.notify.preference.PushCategory;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.service.NotificationPusher;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.ops.target.OpsTargetType;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.LongStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：运营通知发送状态机（V1.3.4 Story 6.2 · AC3 · D-19 / D-20）。全部 mock，记录型 pacer，<b>不真睡</b>。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OpsNotificationDispatcherTest {

    private static final long ID = 5L;
    private static final String CAMPAIGN_TOKEN = "campTok22charsAbcdefgh";
    private static final Instant NOW = Instant.parse("2026-10-12T05:00:00Z");
    private static final Duration S1 = Duration.ofSeconds(1);
    private static final Duration S2 = Duration.ofSeconds(2);
    private static final Duration S4 = Duration.ofSeconds(4);

    @Mock
    OpsNotificationCampaignRepository repo;
    @Mock
    OpsCampaignTargetDao targets;
    @Mock
    OpsAudienceQuery audience;
    @Mock
    NotificationService notificationService;
    @Mock
    NotificationPusher pusher;
    /** V1.4.0 ④ Story 1.2：mutedAmong 默认返回空 Set（Mockito 默认值）→ 既有用例行为不变。 */
    @Mock
    PushPreferenceService prefs;

    private final List<Duration> sleeps = new ArrayList<>();
    private final OpsDispatchPacer pacer = sleeps::add;
    private OpsNotificationDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        dispatcher = new OpsNotificationDispatcher(repo, targets, audience, notificationService, pusher, prefs, pacer,
                Clock.fixed(NOW, ZoneOffset.UTC), OpsNotifyParams.DEFAULTS);
        when(repo.claimForSending(eq(ID), any())).thenReturn(1);
        when(repo.addRecipients(eq(ID), anyInt(), any())).thenReturn(1);
        when(repo.touchProgress(eq(ID), any())).thenReturn(1);
        when(repo.addFailed(eq(ID), anyInt(), any())).thenReturn(1);
        when(repo.setSkipped(eq(ID), anyInt(), any())).thenReturn(1);
        when(repo.setPlanned(eq(ID), anyInt(), any())).thenReturn(1);
        when(repo.finish(eq(ID), any(), any())).thenReturn(1);
        campaign(AudienceSpec.all());
        // 通知中心写入：每人一行，title 按语言（与 6.1 同口径），token = "dl" + userId
        when(notificationService.writeOpsAnnouncements(any(), any())).thenAnswer(inv -> rows(inv.getArgument(1)));
        when(pusher.pushToUserNow(anyLong(), any(), any(), any(), any(), any())).thenReturn(true);
    }

    private void campaign(AudienceSpec spec) {
        OpsNotificationCampaign c = OpsNotificationCampaign.create(CAMPAIGN_TOKEN, "Judul", "Title", "Isi",
                "Body", OpsTargetType.NONE, null, spec.toJson(), null, 1L);
        ReflectionTestUtils.setField(c, "id", ID);
        when(repo.findById(ID)).thenReturn(Optional.of(c));
    }

    private static List<Notification> rows(List<OpsRecipient> batch) {
        return batch.stream().map(r -> {
            boolean en = "en".equals(r.locale());
            return Notification.of(r.userId(), NotificationType.OPS_ANNOUNCEMENT, en ? "Title" : "Judul",
                    en ? "Body" : "Isi", "OPS_ANNOUNCEMENT", "dl" + r.userId(), CAMPAIGN_TOKEN);
        }).toList();
    }

    private void audienceOf(int n) {
        List<OpsRecipient> list = LongStream.rangeClosed(1, n).mapToObj(i -> new OpsRecipient(i, "id")).toList();
        when(audience.resolve(any(), eq(ID))).thenReturn(list);
    }

    @SuppressWarnings("unchecked")
    private List<Integer> writtenBatchSizes(int expectedCalls) {
        ArgumentCaptor<List<OpsRecipient>> cap = ArgumentCaptor.forClass(List.class);
        verify(notificationService, times(expectedCalls)).writeOpsAnnouncements(any(), cap.capture());
        return cap.getAllValues().stream().map(List::size).toList();
    }

    @Test
    void plannedCountWrittenAtSendStart() {
        audienceOf(7);
        dispatcher.dispatch(ID);
        verify(repo).setPlanned(ID, 7, NOW);
    }

    @Test
    void plannedCountWriteLostMeansStop() {
        audienceOf(3);
        when(repo.setPlanned(eq(ID), anyInt(), any())).thenReturn(0);
        dispatcher.dispatch(ID);
        verifyNoInteractions(notificationService, pusher);
    }

    @Test
    void notClaimedMeansZeroInteraction() {
        when(repo.claimForSending(eq(ID), any())).thenReturn(0);
        dispatcher.dispatch(ID);
        verify(repo).claimForSending(ID, NOW);
        verifyNoMoreInteractions(repo);
        verifyNoInteractions(audience, notificationService, pusher, targets);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void splitsIntoBatchesOf500AndSleepsOnlyBetweenBatches() {
        audienceOf(1201);
        dispatcher.dispatch(ID);

        assertThat(writtenBatchSizes(3)).containsExactly(500, 500, 201);
        assertThat(sleeps).containsExactly(S1, S1); // 批间 2 次，最后一批后不等
        verify(repo, times(2)).addRecipients(ID, 500, NOW);
        verify(repo).addRecipients(ID, 201, NOW);
        verify(pusher, times(1201)).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(repo, never()).addFailed(anyLong(), anyInt(), any());
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }

    @Test
    void progressTouchedEvery50PushesAndAtBatchEnd() {
        audienceOf(120);
        dispatcher.dispatch(ID);
        // 写库前一次 + 推到第 50、100 人各一次 + 批末一次
        verify(repo, times(4)).touchProgress(ID, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }

    /** bug 604：推送失败一律不重推（防重复收到），失败人数照计，批间等待不变。 */
    @Test
    void failedPushesAreNeverRetriedButCounted() {
        audienceOf(600); // 2 批：500 / 100
        Set<Long> fail = Set.of(510L, 520L, 530L);
        Map<Long, Integer> calls = new HashMap<>();
        doAnswer(inv -> {
            long uid = inv.getArgument(0);
            calls.merge(uid, 1, Integer::sum);
            return !fail.contains(uid);
        }).when(pusher).pushToUserNow(anyLong(), any(), any(), any(), any(), any());

        dispatcher.dispatch(ID);

        assertThat(sleeps).containsExactly(S1); // 只有批间 1s，没有重试退避
        assertThat(calls.values()).allMatch(k -> k == 1); // 每人恰好推一次
        assertThat(calls).hasSize(600);
        verify(repo).addFailed(ID, 3, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
    }

    @Test
    void writeFailingFourTimesCountsWholeBatchAndSkipsItsPushes() {
        audienceOf(600);
        int[] attempts = {0};
        doAnswer(inv -> {
            List<OpsRecipient> batch = inv.getArgument(1);
            if (batch.get(0).userId() == 1L && attempts[0]++ < 4) {
                throw new RuntimeException("db");
            }
            return rows(batch);
        }).when(notificationService).writeOpsAnnouncements(any(), any());

        dispatcher.dispatch(ID);

        assertThat(writtenBatchSizes(5)).containsExactly(500, 500, 500, 500, 100);
        assertThat(sleeps).containsExactly(S1, S2, S4, S1); // 写入重试退避 + 批间
        verify(repo).addFailed(ID, 500, NOW);
        verify(pusher, never()).pushToUserNow(eq(1L), any(), any(), any(), any(), any());
        verify(pusher, times(100)).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(repo).addRecipients(ID, 100, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
    }

    @Test
    void stopsImmediatelyWhenNoLongerSending() {
        audienceOf(1201);
        when(repo.addRecipients(eq(ID), anyInt(), any())).thenReturn(0); // 已被超时扫描改成 PARTIAL_FAILED

        dispatcher.dispatch(ID);

        assertThat(writtenBatchSizes(1)).containsExactly(500);
        verifyNoInteractions(pusher);
        assertThat(sleeps).isEmpty();
        verify(repo, never()).finish(anyLong(), any(), any());
    }

    @Test
    void stopsWhenProgressTouchReturnsZeroMidBatch() {
        audienceOf(600);
        when(repo.touchProgress(eq(ID), any())).thenReturn(1, 0); // 写库前通过，推到第 50 人时已被判超时

        dispatcher.dispatch(ID);

        verify(pusher, times(50)).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        assertThat(writtenBatchSizes(1)).containsExactly(500);
        verify(repo).addFailed(ID, 450, NOW); // 尽力计入本批未完成人数（条件更新，已非 SENDING 时为 no-op）
        verify(repo, never()).finish(anyLong(), any(), any());
    }

    @Test
    void stopsBeforeWritingWhenAlreadyStalled() {
        audienceOf(600);
        when(repo.touchProgress(eq(ID), any())).thenReturn(0); // 写库前检查即发现已不在 SENDING

        dispatcher.dispatch(ID);

        verifyNoInteractions(notificationService, pusher);
        verify(repo, never()).addRecipients(anyLong(), anyInt(), any());
        verify(repo, never()).addFailed(anyLong(), anyInt(), any());
        verify(repo, never()).finish(anyLong(), any(), any());
    }

    @Test
    void interruptDuringPushLoopStopsAndCountsUnfinished() {
        audienceOf(600);
        int[] n = {0};
        doAnswer(inv -> {
            if (++n[0] == 10) {
                Thread.currentThread().interrupt();
            }
            return true;
        }).when(pusher).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        try {
            dispatcher.dispatch(ID);
        } finally {
            Thread.interrupted();
        }
        verify(pusher, times(10)).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(repo).addFailed(ID, 490, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
        assertThat(writtenBatchSizes(1)).containsExactly(500);
    }

    @Test
    void emptyAudienceFinishesDone() {
        audienceOf(0);
        dispatcher.dispatch(ID);
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
        verify(repo, never()).addRecipients(anyLong(), anyInt(), any());
        verifyNoInteractions(notificationService, pusher);
    }

    @Test
    void idsModeWritesSkippedCount() {
        campaign(AudienceSpec.ids());
        audienceOf(7);
        when(targets.countByCampaign(ID)).thenReturn(10L);

        dispatcher.dispatch(ID);

        verify(repo).setSkipped(ID, 3, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }

    @Test
    void nonIdsModeDoesNotTouchSkipped() {
        audienceOf(3);
        dispatcher.dispatch(ID);
        verify(repo, never()).setSkipped(anyLong(), anyInt(), any());
        verifyNoInteractions(targets);
    }

    @Test
    void pushUsesRowTextOpsTypeTokenAndCampaignRef() {
        when(audience.resolve(any(), eq(ID))).thenReturn(List.of(new OpsRecipient(1, "id"),
                new OpsRecipient(2, "en")));

        dispatcher.dispatch(ID);

        verify(pusher).pushToUserNow(1L, "Judul", "Isi", "OPS_ANNOUNCEMENT", "dl1", CAMPAIGN_TOKEN);
        verify(pusher).pushToUserNow(2L, "Title", "Body", "OPS_ANNOUNCEMENT", "dl2", CAMPAIGN_TOKEN);
    }

    @Test
    void unexpectedErrorFinishesPartialFailedWithoutResend() {
        when(audience.resolve(any(), eq(ID))).thenThrow(new RuntimeException("db"));
        dispatcher.dispatch(ID);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
        verifyNoInteractions(notificationService, pusher);
    }

    @Test
    void interruptedPacerStopsSending() {
        audienceOf(1201);
        OpsDispatchPacer interrupting = d -> Thread.currentThread().interrupt();
        OpsNotificationDispatcher d = new OpsNotificationDispatcher(repo, targets, audience, notificationService,
                pusher, prefs, interrupting, Clock.fixed(NOW, ZoneOffset.UTC), OpsNotifyParams.DEFAULTS);
        try {
            d.dispatch(ID);
        } finally {
            Thread.interrupted(); // 清掉中断标志，不污染其它测试
        }
        assertThat(writtenBatchSizes(1)).containsExactly(500);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
        verify(pusher, times(500)).pushToUserNow(anyLong(), anyString(), anyString(), any(), any(), any());
    }

    // ===== V1.4.0 ④ Story 1.2：推送分类开关闸门（AD-5 / P-4）=====

    @Test
    void mutedRecipientsGetNotificationButNoPush() {
        audienceOf(5);
        when(prefs.mutedAmong(any(), eq(PushCategory.PROMO))).thenReturn(Set.of(2L, 4L));

        dispatcher.dispatch(ID);

        for (long uid : new long[] {1L, 3L, 5L}) {
            verify(pusher).pushToUserNow(eq(uid), any(), any(), any(), any(), any());
        }
        verify(pusher, never()).pushToUserNow(eq(2L), any(), any(), any(), any(), any());
        verify(pusher, never()).pushToUserNow(eq(4L), any(), any(), any(), any(), any());
        verify(repo).addRecipients(ID, 5, NOW); // 触达 = 写入通知中心人数，含被拦下的人
        verify(repo, never()).addFailed(anyLong(), anyInt(), any());
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }

    @Test
    @SuppressWarnings("unchecked")
    void mutedAmongQueriedOncePerBatchWithPromo() {
        audienceOf(1201);
        dispatcher.dispatch(ID);
        ArgumentCaptor<java.util.Collection<Long>> ids = ArgumentCaptor.forClass(java.util.Collection.class);
        ArgumentCaptor<PushCategory> cat = ArgumentCaptor.forClass(PushCategory.class);
        verify(prefs, times(3)).mutedAmong(ids.capture(), cat.capture());
        assertThat(ids.getAllValues()).extracting(java.util.Collection::size).containsExactly(500, 500, 201);
        assertThat(cat.getAllValues()).containsOnly(PushCategory.PROMO);
    }

    @Test
    void mutedRecipientsNeverRetriedAndNotCountedAsFailed() {
        audienceOf(10);
        when(prefs.mutedAmong(any(), eq(PushCategory.PROMO))).thenReturn(Set.of(1L, 2L));
        doAnswer(inv -> (long) inv.getArgument(0) != 3L) // 3 号恒失败
                .when(pusher).pushToUserNow(anyLong(), any(), any(), any(), any(), any());

        dispatcher.dispatch(ID);

        verify(pusher, never()).pushToUserNow(eq(1L), any(), any(), any(), any(), any());
        verify(pusher, never()).pushToUserNow(eq(2L), any(), any(), any(), any(), any());
        verify(pusher, times(1)).pushToUserNow(eq(3L), any(), any(), any(), any(), any()); // 失败不重推（bug 604）
        verify(repo).addFailed(ID, 1, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
    }

    @Test
    void wholeBatchMutedStillTouchesProgressAndFinishesDone() {
        audienceOf(3);
        when(prefs.mutedAmong(any(), eq(PushCategory.PROMO))).thenReturn(Set.of(1L, 2L, 3L));

        dispatcher.dispatch(ID);

        verifyNoInteractions(pusher);
        verify(repo, times(2)).touchProgress(ID, NOW); // 写库前一次 + 批末一次
        verify(repo, never()).addFailed(anyLong(), anyInt(), any());
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }

    @Test
    void interruptCountsOnlyUnfinishedPushTargets() {
        audienceOf(600);
        when(prefs.mutedAmong(any(), eq(PushCategory.PROMO)))
                .thenReturn(Set.copyOf(LongStream.rangeClosed(401, 500).boxed().toList()));
        int[] n = {0};
        doAnswer(inv -> {
            if (++n[0] == 10) {
                Thread.currentThread().interrupt();
            }
            return true;
        }).when(pusher).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        try {
            dispatcher.dispatch(ID);
        } finally {
            Thread.interrupted();
        }
        // 本批 500 人写入，100 人被拦下 → 待推 400；推完 10 人后中断 → 未完成 390（不含被拦下的人）
        verify(repo).addFailed(ID, 390, NOW);
        verify(repo).finish(ID, OpsCampaignStatus.PARTIAL_FAILED, NOW);
    }

    @Test
    void mutedAmongFailureFallsBackToPushEveryone() {
        audienceOf(5);
        when(prefs.mutedAmong(any(), any())).thenThrow(new RuntimeException("db"));

        dispatcher.dispatch(ID);

        verify(pusher, times(5)).pushToUserNow(anyLong(), any(), any(), any(), any(), any());
        verify(repo, never()).addFailed(anyLong(), anyInt(), any());
        verify(repo).finish(ID, OpsCampaignStatus.DONE, NOW);
    }
}
