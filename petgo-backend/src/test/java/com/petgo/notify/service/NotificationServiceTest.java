package com.petgo.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.notify.domain.Notification;
import com.petgo.notify.domain.NotificationType;
import com.petgo.notify.repository.NotificationRepository;
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

    private NotificationService service() {
        return new NotificationService(repo, redis, pusher);
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
        verify(pusher).push(eq(7L), eq("标题"), eq("正文"),
                eq(NotificationType.VET_REPLY.name()), anyString());
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
}
