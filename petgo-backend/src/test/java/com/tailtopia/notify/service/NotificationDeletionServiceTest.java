package com.tailtopia.notify.service;

import static org.mockito.Mockito.inOrder;

import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.repository.NotificationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * L0：notify 模块注销级联（Story 7.3 + V1.3.4 Story 6.1 · AC7）：删通知 → 删角标键 → 删运营通知指定用户子表。
 */
@ExtendWith(MockitoExtension.class)
class NotificationDeletionServiceTest {

    @Mock
    NotificationRepository repo;
    @Mock
    StringRedisTemplate redis;
    @Mock
    OpsCampaignTargetDao campaignTargets;

    @Test
    void deletesNotificationsThenBadgeThenCampaignTargets() {
        new NotificationDeletionService(repo, redis, campaignTargets).deleteByUserId(42L);

        InOrder order = inOrder(repo, redis, campaignTargets);
        order.verify(repo).deleteByRecipientUserId(42L);
        order.verify(redis).delete(NotificationService.UNREAD_KEY_PREFIX + 42L);
        order.verify(campaignTargets).deleteByUserId(42L);
        order.verifyNoMoreInteractions();
    }
}
