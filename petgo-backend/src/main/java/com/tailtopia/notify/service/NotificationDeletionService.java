package com.tailtopia.notify.service;

import com.tailtopia.notify.repository.NotificationRepository;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * notify 模块注销级联删除（Story 7.3）：该用户全部通知（纯个人数据，物理删除）+ Redis 未读角标键。
 */
@Service
public class NotificationDeletionService {

    private final NotificationRepository repo;
    private final StringRedisTemplate redis;

    public NotificationDeletionService(NotificationRepository repo, StringRedisTemplate redis) {
        this.repo = repo;
        this.redis = redis;
    }

    @Transactional
    public void deleteByUserId(long userId) {
        repo.deleteByRecipientUserId(userId);
        redis.delete(NotificationService.UNREAD_KEY_PREFIX + userId);
    }
}
