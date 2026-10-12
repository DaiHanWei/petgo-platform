package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationActorRepository;
import com.tailtopia.notify.repository.NotificationAggregateDao;
import com.tailtopia.notify.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0 单元测试：统一推送出口写库 + 不可枚举 token（非顺序 id）+ Redis 角标自增 + 异步离线投递。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    NotificationPusher pusher;
    @Mock
    org.springframework.context.MessageSource messageSource;
    @Mock
    com.tailtopia.auth.service.AccountQueryService accountQuery;
    @Mock
    NotificationAggregateDao aggregates;
    @Mock
    NotificationActorRepository actors;
    @Mock
    InteractionCopyRenderer copy;
    @Mock
    InteractionActorResolver resolver;

    private NotificationService service() {
        // push 文案本地化（bug 20260625-105）：mock MessageSource 回退到传入原文案，保持既有推送文本断言。
        when(messageSource.getMessage(any(), any(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(accountQuery.localeOf(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Locale.forLanguageTag("id"));
        return new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver);
    }

    @Test
    void sendWritesRowIncrementsBadgeAndDispatchesPushWithUnguessableToken() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);

        Notification n = service().send(7L, NotificationType.VET_REPLY, "标题", "正文",
                NotificationType.VET_REPLY.name(), "ref-1");

        // 不可枚举 token：32 位 base62，非顺序 id。
        assertThat(n.getDeepLinkToken()).hasSize(32);
        assertThat(n.getDeepLinkToken()).doesNotContainPattern("^[0-9]+$");
        assertThat(n.getRecipientUserId()).isEqualTo(7L);
        verify(repo).save(any(Notification.class));
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "7");
        verify(pusher).pushToUser(eq(7L), eq("标题"), eq("正文"),
                eq(NotificationType.VET_REPLY.name()), anyString(), eq("ref-1"));
    }

    @Test
    void eachSendGeneratesDistinctToken() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        NotificationService svc = service();
        String t1 = svc.send(7L, NotificationType.CONTENT_LIKED, "a", "b", "X", "r").getDeepLinkToken();
        String t2 = svc.send(7L, NotificationType.CONTENT_LIKED, "a", "b", "X", "r").getDeepLinkToken();
        assertThat(t1).isNotEqualTo(t2);
    }

    /**
     * 🛡 只写不推（V1.1.6 Story 6.1 · FR-76）：**写通知行 + 角标 +1，但不投递推送**。
     *
     * <p>这条守的是 AC2 —— 它之所以"白送"，前提是这条路径**与 send 共用同一段落库 + 角标逻辑**。
     * 谁要是另写一套"只落库"的实现，角标就不会涨：通知进了中心而铃铛不动，
     * 用户仍需主动去翻，FR-76 的核心诉求直接失效。
     */
    @Test
    void sendWithoutPushStillWritesRowAndBumpsBadgeButNeverPushes() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);

        // ⚠️ 不走 service()：那个辅助方法会 stub 推送文案本地化与收件人语言，
        // 而本路径**根本不发推送**、用不到它们 —— 严格 stub 模式下会报 UnnecessaryStubbing。
        // 这本身就是"没发推送"的一个旁证。
        NotificationService svc =
                new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver);
        Notification n = svc.sendWithoutPush(7L, NotificationType.MILESTONE_SM_NODE,
                "留痕标题", "留痕正文", NotificationType.MILESTONE_NODE.name(), "C-S14");

        // ① 通知行照写
        verify(repo).save(any(Notification.class));
        assertThat(n.getType()).isEqualTo(NotificationType.MILESTONE_SM_NODE);
        assertThat(n.getTargetRef()).isEqualTo("C-S14");
        // ② 🛡 未读角标照涨
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + 7L);
        // ③ 但**一条推送都不发**
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    /**
     * 虚拟号（VIRTUAL）没有 IM 账号，推过去腾讯回 90001 —— 落库 + 角标照常，但跳过推送（bug 519/521 附带）。
     */
    @Test
    void virtualRecipientGetsRowAndBadgeButNoPush() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(accountQuery.isVirtual(7L)).thenReturn(true);
        // 不走 service()：推送文案/语言根本用不到（严格 stub 下即为"没推送"的旁证）。
        NotificationService svc = new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver);

        svc.send(7L, NotificationType.CONTENT_LIKED, "a", "b", "X", "r");
        svc.sendWithCopy(7L, NotificationType.CONTENT_LIKED, "K", null, "a", "b", "r");

        verify(repo, org.mockito.Mockito.times(2)).save(any(Notification.class));
        verify(valueOps, org.mockito.Mockito.times(2)).increment(NotificationService.UNREAD_KEY_PREFIX + "7");
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    // ===== V1.3.4 follow · Story 3.1：sendInteraction（AD-9 / AD-10） =====

    private static final InteractionCopyRenderer.ActorSummary SUMMARY =
            new InteractionCopyRenderer.ActorSummary(2L, "Rani", "tok2", 0);

    /** 互动路径用的服务：不 stub 旧 pushText（严格 stub 下多余 stub 会报错）。 */
    private NotificationService interactionService() {
        return new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver);
    }

    private void stubPushRendering() {
        when(actors.findActorIdsNewestFirst(org.mockito.ArgumentMatchers.anyLong())).thenReturn(java.util.List.of(2L));
        when(copy.resolve(eq(9L), any())).thenReturn(java.util.Optional.of(SUMMARY));
        when(accountQuery.localeOf(9L)).thenReturn(java.util.Locale.forLanguageTag("id"));
        when(copy.title(any(), any(), eq(SUMMARY), any())).thenReturn("Rani menyukai postinganmu");
    }

    private static Notification persisted(long id, String token) {
        Notification n = Notification.interaction(9L, NotificationType.CONTENT_COMMENTED, "t", "b",
                NotificationType.CONTENT_COMMENTED.name(), token, "55", null, 7L, null);
        org.springframework.test.util.ReflectionTestUtils.setField(n, "id", id);
        return n;
    }

    @Test
    void nonAggregateInteractionCreatesRowAddsActorBumpsBadgeAndPushesOriginalText() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(n, "id", 100L);
            return n;
        });
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        stubPushRendering();

        interactionService().sendInteraction(9L, NotificationType.CONTENT_COMMENTED, 2L, null, "55", 7L,
                null, "halo");

        org.mockito.ArgumentCaptor<Notification> saved = org.mockito.ArgumentCaptor.forClass(Notification.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getCommentId()).isEqualTo(7L);
        assertThat(saved.getValue().getAggregateKey()).isNull();
        assertThat(saved.getValue().getTitle()).as("落库不含昵称 / 原文（AD-3）").doesNotContain("Rani", "halo");
        verify(aggregates).addActor(100L, 2L);
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "9");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
        verify(pusher).pushToUser(eq(9L), eq("Rani menyukai postinganmu"), eq("halo"),
                eq(NotificationType.CONTENT_COMMENTED.name()), anyString(), eq("55"));
    }

    @Test
    void firstLikeCreatesAggregateRowBumpsBadgeAndTakesDebounceSlot() {
        when(aggregates.upsertAggregate(eq(9L), eq(NotificationType.CONTENT_LIKED), anyString(), anyString(),
                anyString(), anyString(), eq("55"), eq("LIKE:POST:55"), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, 2L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("notify:likepush:55", "1", java.time.Duration.ofMinutes(10))).thenReturn(true);
        stubPushRendering();

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "9");
        verify(aggregates, never()).touch(org.mockito.ArgumentMatchers.anyLong());
        verify(pusher).pushToUser(eq(9L), eq("Rani menyukai postinganmu"), eq(""),
                eq(NotificationType.CONTENT_LIKED.name()), eq("tok"), eq("55"));
    }

    @Test
    void mergedLikeTouchesRowWithoutBadgeAndSkipsPushInsideDebounceWindow() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("LIKE:POST:55"), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", false));
        when(aggregates.addActor(100L, 3L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("notify:likepush:55", "1", java.time.Duration.ofMinutes(10))).thenReturn(false);

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 3L, "LIKE:POST:55", "55",
                null, null, null);

        verify(aggregates).touch(100L);
        verify(valueOps, never()).increment(anyString());
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    /** T-29：同一人取消赞再赞、通知仍未读 → 什么都不做（不刷新、不加角标、不推送、不抢去抖键）。 */
    @Test
    void sameActorAgainOnUnreadAggregateDoesNothing() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("LIKE:POST:55"), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", false));
        when(aggregates.addActor(100L, 2L)).thenReturn(false);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(aggregates, never()).touch(org.mockito.ArgumentMatchers.anyLong());
        org.mockito.Mockito.verifyNoInteractions(redis, pusher, copy);
    }

    @Test
    void virtualRecipientNeverTakesDebounceSlotOrPushes() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, 2L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(accountQuery.isVirtual(9L)).thenReturn(true);

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "9");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    /** T-32：去抖键所在的 Redis 不可用 → 照常推送（宁多勿漏）。 */
    @Test
    void debounceRedisFailureStillPushes() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, 2L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class)))
                .thenThrow(new org.springframework.data.redis.RedisConnectionFailureException("down"));
        stubPushRendering();

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(pusher).pushToUser(eq(9L), anyString(), eq(""), anyString(), eq("tok"), eq("55"));
    }

    @Test
    void debounceKeysFollowAggregatePrefix() {
        assertThat(NotificationService.debounceKey("LIKE:POST:55")).isEqualTo("notify:likepush:55");
        assertThat(NotificationService.debounceKey("COMMENT:PLACE:abc")).isEqualTo("notify:placecommentpush:abc");
        assertThat(NotificationService.debounceKey(null)).isNull();
    }

    /** T-31：推送正文截到 100 字（按码点，不劈开 emoji）加「…」；空 → 空串。 */
    @Test
    void pushBodyIsTruncatedToHundredCodePoints() {
        assertThat(NotificationService.truncateBody(null)).isEmpty();
        assertThat(NotificationService.truncateBody("  ")).isEmpty();
        String hundred = "a".repeat(100);
        assertThat(NotificationService.truncateBody(hundred)).isEqualTo(hundred);
        String emojis = "🐶".repeat(101);
        String cut = NotificationService.truncateBody(emojis);
        assertThat(cut).isEqualTo("🐶".repeat(100) + "…");
    }

    // ===== 3.1 code-review 修复 =====

    /** F1：非互动类型写库之前就拒绝（否则行写了、渲染推送时才炸、整条回滚）。 */
    @Test
    void nonInteractionTypeIsRejectedBeforeAnyWrite() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> interactionService().sendInteraction(9L,
                NotificationType.VET_REPLY, 2L, null, "55", null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        org.mockito.Mockito.verifyNoInteractions(repo, aggregates, redis, pusher);
    }

    /** F1：推送环节抛异常只丢这次推送 —— 不冒泡、不影响已落库的通知。 */
    @Test
    void pushFailureIsSwallowedAfterWrite() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> {
            Notification n = inv.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(n, "id", 100L);
            return n;
        });
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(actors.findActorIdsNewestFirst(100L)).thenThrow(new IllegalStateException("db"));

        Notification n = interactionService().sendInteraction(9L, NotificationType.CONTENT_COMMENTED, 2L, null,
                "55", 7L, null, "halo");

        assertThat(n.getId()).isEqualTo(100L);
        verify(aggregates).addActor(100L, 2L);
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    /** F3 / D-9：官方内容账号（role=ADMIN 作者 shim）不接收推送，通知照常落库。 */
    @Test
    void officialRecipientGetsRowButNoPush() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, 2L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(accountQuery.isOfficialAccount(9L)).thenReturn(true);

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "9");
        verify(valueOps, never()).setIfAbsent(anyString(), anyString(), any(java.time.Duration.class));
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    /** F4：触发人全被滤掉 → 不推，并把刚占的去抖键还回去。 */
    @Test
    void emptySummaryReleasesDebounceSlot() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", true));
        when(aggregates.addActor(100L, 2L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent("notify:likepush:55", "1", java.time.Duration.ofMinutes(10))).thenReturn(true);
        when(actors.findActorIdsNewestFirst(100L)).thenReturn(java.util.List.of(2L));
        when(copy.resolve(9L, java.util.List.of(2L))).thenReturn(java.util.Optional.empty());

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 2L, "LIKE:POST:55", "55",
                null, null, null);

        verify(redis).delete("notify:likepush:55");
        org.mockito.Mockito.verifyNoInteractions(pusher);
    }

    // ===== 3.3 code-review F3 / F4 =====

    /** F3：合并进一条原本不可见（触发人全被过滤、角标未计）的未读行 → 角标补 +1。 */
    @Test
    void mergeIntoPreviouslyInvisibleRowBumpsBadge() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("LIKE:POST:55"), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", false));
        when(aggregates.addActor(100L, 3L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        when(actors.findActorIdsNewestFirst(100L)).thenReturn(java.util.List.of(3L, 2L));
        when(copy.resolve(9L, java.util.List.of(2L))).thenReturn(java.util.Optional.empty());

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 3L, "LIKE:POST:55", "55",
                null, null, null);

        verify(aggregates).touch(100L);
        verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + "9");
    }

    @Test
    void mergeIntoVisibleRowDoesNotBumpBadge() {
        when(aggregates.upsertAggregate(eq(9L), any(), anyString(), anyString(), anyString(), anyString(),
                anyString(), eq("LIKE:POST:55"), isNull(), isNull()))
                .thenReturn(new NotificationAggregateDao.UpsertResult(100L, "tok", false));
        when(aggregates.addActor(100L, 3L)).thenReturn(true);
        when(repo.findById(100L)).thenAnswer(inv -> java.util.Optional.of(persisted(100L, "tok")));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.setIfAbsent(anyString(), anyString(), any(java.time.Duration.class))).thenReturn(false);
        when(actors.findActorIdsNewestFirst(100L)).thenReturn(java.util.List.of(3L, 2L));
        when(copy.resolve(9L, java.util.List.of(2L))).thenReturn(java.util.Optional.of(SUMMARY));

        interactionService().sendInteraction(9L, NotificationType.CONTENT_LIKED, 3L, "LIKE:POST:55", "55",
                null, null, null);

        verify(valueOps, never()).increment(anyString());
    }

    /** F4：角标键丢失后 INCR 得 1 → 用与通知中心同口径的有效未读数自愈（T-36）。 */
    @Test
    void badgeSelfHealUsesEffectiveUnreadCount() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(NotificationService.UNREAD_KEY_PREFIX + "7")).thenReturn(1L);
        when(resolver.effectiveUnreadCount(7L)).thenReturn(4L);

        new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy, resolver)
                .sendWithoutPush(7L, NotificationType.MILESTONE_SM_NODE, "t", "b", "MILESTONE_NODE", "C-S1");

        verify(valueOps).set(NotificationService.UNREAD_KEY_PREFIX + "7", "4");
        verify(repo, never()).countByRecipientUserIdAndReadIsFalse(org.mockito.ArgumentMatchers.anyLong());
    }
}
