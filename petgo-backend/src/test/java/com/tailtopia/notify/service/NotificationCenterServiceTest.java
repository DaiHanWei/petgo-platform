package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：未读角标 Redis 优先 + 缺值库回算回填；标记已读 token 定位 + 越权 404 + 角标递减。
 */
@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;

    private NotificationCenterService service() {
        return new NotificationCenterService(repo, redis);
    }

    @Test
    void unreadCountReadsRedisWhenPresent() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("notify:unread:7")).thenReturn("3");
        assertThat(service().unreadCount(7L)).isEqualTo(3);
    }

    @Test
    void unreadCountRecomputesAndBackfillsWhenRedisMissing() {
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("notify:unread:7")).thenReturn(null);
        when(repo.countByRecipientUserIdAndReadIsFalse(7L)).thenReturn(5L);

        assertThat(service().unreadCount(7L)).isEqualTo(5);
        verify(valueOps).set("notify:unread:7", "5"); // 回填
    }

    @Test
    void markReadSetsReadAndDecrementsBadge() {
        Notification n = Notification.of(7L, NotificationType.VET_REPLY, "t", "b", "VET_REPLY", "tok", "ref");
        when(repo.findByDeepLinkTokenAndRecipientUserId("tok", 7L)).thenReturn(Optional.of(n));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement("notify:unread:7")).thenReturn(2L);

        service().markRead(7L, "tok");

        assertThat(n.isRead()).isTrue();
        verify(repo).save(n);
        verify(valueOps).decrement("notify:unread:7");
    }

    @Test
    void markReadForeignOrMissingIsNotFound() {
        when(repo.findByDeepLinkTokenAndRecipientUserId("ghost", 7L)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service().markRead(7L, "ghost")).isInstanceOf(AppException.class);
    }

    @Test
    void decrementBelowZeroClampsToZero() {
        Notification n = Notification.of(7L, NotificationType.CONTENT_LIKED, "t", "b", "X", "tok", "ref");
        when(repo.findByDeepLinkTokenAndRecipientUserId("tok", 7L)).thenReturn(Optional.of(n));
        when(redis.opsForValue()).thenReturn(valueOps);
        when(valueOps.decrement("notify:unread:7")).thenReturn(-1L);

        service().markRead(7L, "tok");

        verify(valueOps).set(eq("notify:unread:7"), eq("0")); // 夹到 0
    }
}
