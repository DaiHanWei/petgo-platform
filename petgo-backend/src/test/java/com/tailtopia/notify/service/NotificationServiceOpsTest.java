package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.domain.OpsRecipient;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.ops.target.OpsTargetType;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.MessageSource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * L0：运营通知批量写入通知中心（V1.3.4 Story 6.1 · AC5）。语言按账号定死、每人角标 +1、
 * <b>不推送、不查 messageSource</b>、Redis 失败不影响返回。
 */
@ExtendWith(MockitoExtension.class)
class NotificationServiceOpsTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    NotificationPusher pusher;
    @Mock
    MessageSource messageSource;
    @Mock
    com.tailtopia.auth.service.AccountQueryService accountQuery;
    // follow 主题 Story 3.1 给构造器加的依赖（点赞合并 / 触发人 / 现拼文案 / 未读回算）—— 运营通知路径不用，给 mock 即可。
    @Mock
    com.tailtopia.notify.repository.NotificationAggregateDao aggregates;
    @Mock
    com.tailtopia.notify.repository.NotificationActorRepository actors;
    @Mock
    InteractionCopyRenderer copy;
    @Mock
    InteractionActorResolver unreadResolver;
    /** V1.4.0 ④ Story 1.2：运营公告写库路径不经推送闸门（闸门在 dispatcher）。 */
    @Mock
    com.tailtopia.notify.preference.PushPreferenceService prefs;

    private NotificationService service() {
        return new NotificationService(repo, redis, pusher, messageSource, accountQuery, aggregates, actors, copy,
                unreadResolver, prefs);
    }

    private static OpsNotificationCampaign campaign() {
        return OpsNotificationCampaign.create("campTok22charsAbcdefgh", "Promo akhir pekan", "Weekend promo",
                "Isi ID", "Body EN", OpsTargetType.PRODUCT, "prodTok", AudienceSpec.all().toJson(), null, 1L);
    }

    @Test
    void localeDecidesCopyAndRowsAreOpsAnnouncements() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(5L);

        List<OpsRecipient> recipients = List.of(
                new OpsRecipient(1L, "en"), new OpsRecipient(2L, "EN"),
                new OpsRecipient(3L, "id"), new OpsRecipient(4L, null),
                new OpsRecipient(5L, ""), new OpsRecipient(6L, "zh"));

        List<Notification> saved = service().writeOpsAnnouncements(campaign(), recipients);

        assertThat(saved).extracting(Notification::getRecipientUserId).containsExactly(1L, 2L, 3L, 4L, 5L, 6L);
        assertThat(saved).extracting(Notification::getTitle).containsExactly(
                "Weekend promo", "Weekend promo",
                "Promo akhir pekan", "Promo akhir pekan", "Promo akhir pekan", "Promo akhir pekan");
        assertThat(saved).extracting(Notification::getBody).containsExactly(
                "Body EN", "Body EN", "Isi ID", "Isi ID", "Isi ID", "Isi ID");
        assertThat(saved).allSatisfy(n -> {
            assertThat(n.getType()).isEqualTo(NotificationType.OPS_ANNOUNCEMENT);
            assertThat(n.getDeepLinkType()).isEqualTo("OPS_ANNOUNCEMENT");
            assertThat(n.getTargetRef()).isEqualTo("campTok22charsAbcdefgh");
            assertThat(n.getDeepLinkToken()).hasSize(32).matches("[0-9A-Za-z]{32}");
        });
        Set<String> tokens = saved.stream().map(Notification::getDeepLinkToken).collect(Collectors.toSet());
        assertThat(tokens).hasSize(6);

        verify(valueOps, times(6)).increment(org.mockito.ArgumentMatchers.anyString());
        for (long id = 1; id <= 6; id++) {
            verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + id);
        }
        verifyNoInteractions(pusher, messageSource, accountQuery, prefs);
    }

    @Test
    void redisFailureDoesNotAffectReturn() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenThrow(new RuntimeException("redis down"));

        List<Notification> saved = service().writeOpsAnnouncements(campaign(),
                List.of(new OpsRecipient(1L, "en"), new OpsRecipient(2L, "id")));

        assertThat(saved).hasSize(2);
        verify(repo, times(2)).save(any(Notification.class));
        verifyNoInteractions(pusher);
    }

    @Test
    void badgeRecalibratesFromDbWhenKeyWasMissing() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(1L);
        // follow 3.3 F4 起自愈值取「有效未读数」（T-36 不可见条目不计），不再直接数库
        when(unreadResolver.effectiveUnreadCount(9L)).thenReturn(4L);

        service().writeOpsAnnouncements(campaign(), List.of(new OpsRecipient(9L, "id")));

        // 复用既有 bumpUnreadBadge：缺键从 0 起算时回库纠正
        verify(valueOps).set(NotificationService.UNREAD_KEY_PREFIX + 9L, "4");
        verifyNoInteractions(pusher);
    }

    @Test
    void badgeIsBumpedOnlyAfterCommitWhenSynchronizationActive() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            List<Notification> saved = service().writeOpsAnnouncements(campaign(),
                    List.of(new OpsRecipient(1L, "en"), new OpsRecipient(2L, "id")));
            assertThat(saved).hasSize(2);
            // 提交前：角标一次都不动（整批回滚时不会凭空多出未读）
            verifyNoInteractions(redis);

            List<TransactionSynchronization> syncs = TransactionSynchronizationManager.getSynchronizations();
            assertThat(syncs).hasSize(1);

            when(redis.opsForValue()).thenReturn(valueOps);
            when(valueOps.increment(org.mockito.ArgumentMatchers.anyString())).thenReturn(3L);
            syncs.forEach(TransactionSynchronization::afterCommit);

            verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + 1L);
            verify(valueOps).increment(NotificationService.UNREAD_KEY_PREFIX + 2L);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackLeavesBadgeUntouched() {
        when(repo.save(any(Notification.class))).thenAnswer(inv -> inv.getArgument(0));
        TransactionSynchronizationManager.initSynchronization();
        try {
            service().writeOpsAnnouncements(campaign(), List.of(new OpsRecipient(1L, "en")));
            // 回滚：只走 afterCompletion(ROLLED_BACK)，afterCommit 不触发
            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(sync -> sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK));
            verifyNoInteractions(redis, pusher);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
