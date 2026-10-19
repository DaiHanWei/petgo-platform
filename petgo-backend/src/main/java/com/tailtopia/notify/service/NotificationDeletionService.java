package com.tailtopia.notify.service;

import com.tailtopia.notify.ops.repository.OpsCampaignTargetDao;
import com.tailtopia.notify.repository.NotificationRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * notify 模块注销级联删除（Story 7.3）：该用户全部通知（纯个人数据，物理删除）+ Redis 未读角标键
 * + 运营通知 IDS 指定用户子表行（V1.3.4 Story 6.1，D1/D2：被指定为收件人也是个人数据；活动行不含个人数据，不删）。
 */
@Service
public class NotificationDeletionService {

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;
    private final OpsCampaignTargetDao campaignTargets;

    public NotificationDeletionService(NotificationRepository repo, StringRedisTemplate redis,
            OpsCampaignTargetDao campaignTargets) {
        this.repo = repo;
        this.redis = redis;
        this.campaignTargets = campaignTargets;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        repo.deleteByRecipientUserId(userId);
        redis.delete(NotificationService.UNREAD_KEY_PREFIX + userId);
        campaignTargets.deleteByUserId(userId);
    }
}
