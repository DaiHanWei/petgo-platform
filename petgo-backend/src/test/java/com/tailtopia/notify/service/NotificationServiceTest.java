package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
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

    private NotificationService service() {
        // push 文案本地化（bug 20260625-105）：mock MessageSource 回退到传入原文案，保持既有推送文本断言。
        when(messageSource.getMessage(any(), any(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        when(accountQuery.localeOf(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Locale.forLanguageTag("id"));
        return new NotificationService(repo, redis, pusher, messageSource, accountQuery);
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
                new NotificationService(repo, redis, pusher, messageSource, accountQuery);
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
}
