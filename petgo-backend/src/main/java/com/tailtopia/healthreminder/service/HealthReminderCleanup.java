package com.tailtopia.healthreminder.service;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthNotificationRepository;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 删宠 / 注销时清理健康提醒数据（V1.5.0 Story 2.1 · AC6，架构增量 §4.8，PRD FR-166 Pet deleted）。在
 * {@code ProfileDeletionService} 的事务里、删宠物行<b>之前</b>调用。PENDING 行逐条发 {@code cancelled(pet_deleted)} 埋点后
 * 删除；表上的 FK 级联只是兜底。
 */
@Service
public class HealthReminderCleanup {

    private final HealthReminderRepository reminders;
    private final HealthNotificationRepository notifications;
    private final HealthReminderAnalytics analytics;
    private final JdbcTemplate jdbc;
    private final HealthReminderLock lock;

    public HealthReminderCleanup(HealthReminderRepository reminders, HealthNotificationRepository notifications,
            HealthReminderAnalytics analytics, JdbcTemplate jdbc, HealthReminderLock lock) {
        this.lock = lock;
        this.reminders = reminders;
        this.notifications = notifications;
        this.analytics = analytics;
        this.jdbc = jdbc;
    }

    @Transactional
    public void deleteForPet(long petId) {
        // 与重建 / 日跑同一把宠物锁：清理期间不会有并发重建插进新行。埋点在删档事务提交后发（回滚不误发）。
        lock.lockPet(petId);
        List<HealthReminder> pending = reminders.findByPetProfileIdAndStatus(petId, ReminderStatus.PENDING);
        AfterCommit.run(() -> pending.forEach(r -> analytics.cancelled(r, CancelReason.PET_DELETED, null, null)));
        reminders.deleteByPetProfileId(petId);
        notifications.deleteByPetProfileId(petId);
    }

    /** 注销：该用户的个人上线行（无 FK，须显式删）。 */
    @Transactional
    public void deleteForAccount(long userId) {
        jdbc.update("DELETE FROM health_reminder_user_launch WHERE user_id = ?", userId);
    }
}
