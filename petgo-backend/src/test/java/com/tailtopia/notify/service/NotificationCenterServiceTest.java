package com.tailtopia.notify.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.domain.Notification;
import com.tailtopia.notify.domain.NotificationType;
import com.tailtopia.notify.dto.NotificationItem;
import com.tailtopia.notify.dto.NotificationPage;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.notify.ops.repository.OpsNotificationCampaignRepository;
import com.tailtopia.notify.repository.NotificationRepository;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * L0：未读角标 Redis 优先 + 缺值库回算回填；标记已读 token 定位 + 越权 404 + 角标递减；
 * 运营通知列表项附带 opsTarget（V1.3.4 Story 6.1 · AC6，一页一次批量回查活动）。
 */
@ExtendWith(MockitoExtension.class)
class NotificationCenterServiceTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> valueOps;
    @Mock
    OpsNotificationCampaignRepository campaigns;

    private NotificationCenterService service() {
        return new NotificationCenterService(repo, redis, campaigns);
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

    // ===== V1.3.4 Story 6.1 · AC6：运营通知附带 opsTarget =====

    private static Notification row(NotificationType type, String targetRef, String token) {
        Notification n = Notification.of(7L, type, "t-" + token, "b-" + token, type.name(), token, targetRef);
        org.springframework.test.util.ReflectionTestUtils.setField(n, "createdAt", Instant.parse("2026-10-12T00:00:00Z"));
        return n;
    }

    private void pageOf(List<Notification> rows) {
        when(repo.findPageBefore(anyLong(), any(), anyLong(), any(Pageable.class))).thenReturn(rows);
        when(repo.countByRecipientUserIdAndReadIsFalse(7L)).thenReturn(0L);
        when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    @SuppressWarnings("unchecked")
    void opsAnnouncementsCarryOpsTargetWithOneBatchLookup() {
        OpsNotificationCampaign product = OpsNotificationCampaign.create("campA", "Judul", "Title", "Isi", "Body",
                OpsTargetType.PRODUCT, "prodTok", "{\"mode\":\"ALL\"}", null, null);
        pageOf(List.of(
                row(NotificationType.OPS_ANNOUNCEMENT, "campA", "n1"),
                row(NotificationType.CONTENT_LIKED, "123", "n2"),
                row(NotificationType.OPS_ANNOUNCEMENT, "campGone", "n3")));
        when(campaigns.findByPublicTokenIn(any())).thenReturn(List.of(product));

        NotificationPage page = service().list(7L, null, 20);

        ArgumentCaptor<Collection<String>> tokens = ArgumentCaptor.forClass(Collection.class);
        verify(campaigns, times(1)).findByPublicTokenIn(tokens.capture());
        assertThat(tokens.getValue()).containsExactlyInAnyOrder("campA", "campGone");

        List<NotificationItem> items = page.items();
        assertThat(items).hasSize(3);
        assertThat(items.get(0).opsTarget().type()).isEqualTo("PRODUCT");
        assertThat(items.get(0).opsTarget().value()).isEqualTo("prodTok");
        assertThat(items.get(0).targetRef()).isEqualTo("campA");
        assertThat(items.get(1).opsTarget()).as("非运营通知不带 opsTarget").isNull();
        assertThat(items.get(2).opsTarget().type()).as("活动查不到 → NONE").isEqualTo("NONE");
        assertThat(items.get(2).opsTarget().value()).isNull();
    }

    @Test
    void pageWithoutOpsAnnouncementsDoesNotQueryCampaigns() {
        pageOf(List.of(row(NotificationType.CONTENT_LIKED, "123", "n1")));

        NotificationPage page = service().list(7L, null, 20);

        verifyNoInteractions(campaigns);
        NotificationItem item = page.items().get(0);
        // 既有字段与值不变（回归）
        assertThat(item.type()).isEqualTo("CONTENT_LIKED");
        assertThat(item.title()).isEqualTo("t-n1");
        assertThat(item.body()).isEqualTo("b-n1");
        assertThat(item.deepLinkType()).isEqualTo("CONTENT_LIKED");
        assertThat(item.deepLinkToken()).isEqualTo("n1");
        assertThat(item.targetRef()).isEqualTo("123");
        assertThat(item.read()).isFalse();
        assertThat(item.opsTarget()).isNull();
    }

    @Test
    void noneTargetCampaignMapsToNoneWithoutValue() {
        OpsNotificationCampaign none = OpsNotificationCampaign.create("campN", "Judul", "Title", "Isi", "Body",
                OpsTargetType.NONE, "ignored", "{\"mode\":\"ALL\"}", null, null);
        pageOf(List.of(row(NotificationType.OPS_ANNOUNCEMENT, "campN", "n1")));
        when(campaigns.findByPublicTokenIn(any())).thenReturn(List.of(none));

        NotificationItem item = service().list(7L, null, 20).items().get(0);

        assertThat(item.opsTarget().type()).isEqualTo("NONE");
        assertThat(item.opsTarget().value()).isNull();
    }

    @Test
    void opsAnnouncementWithNullTargetRefFallsBackToNoneWithoutQuery() {
        pageOf(List.of(row(NotificationType.OPS_ANNOUNCEMENT, null, "n1")));

        NotificationItem item = service().list(7L, null, 20).items().get(0);

        verifyNoInteractions(campaigns);
        assertThat(item.opsTarget().type()).isEqualTo("NONE");
        assertThat(item.opsTarget().value()).isNull();
    }
}
