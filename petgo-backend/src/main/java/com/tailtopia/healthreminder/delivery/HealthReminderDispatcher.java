package com.tailtopia.healthreminder.delivery;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthNotificationRepository;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.schedule.HealthDispatchPort;
import com.tailtopia.healthreminder.schedule.SendGroup;
import com.tailtopia.healthreminder.service.AfterCommit;
import com.tailtopia.healthreminder.service.HealthReminderAnalytics;
import com.tailtopia.notify.service.HealthPushOutcome;
import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 健康提醒投递（V1.5.0 Story 2.4 · AC3，实现 2.3 的 {@link HealthDispatchPort}，架构增量 §5.3）。在调用方（每宠物 /
 * 每用户 REQUIRES_NEW、已持宠物锁）事务内：渲染文案 → 生成 32 位 base62 {@code public_token} → 插 {@code health_notifications}
 * → 组内各行 SENT；<b>提交之后</b>经 {@link NotificationService#sendHealthReminder}（唯一推送出口，自带分类闸门）写通知中心并
 * 推送 → 回写推送结果与通知中心 id → 发埋点。幂等：组内只有仍为 PENDING 的行才置 SENT；一条都不是 PENDING 就什么都不做。
 * 🔒 日志只记 id 与计数，不记宠物名 / 文案 / 健康字段。
 */
@Component
public class HealthReminderDispatcher implements HealthDispatchPort {

    private static final Logger log = LoggerFactory.getLogger(HealthReminderDispatcher.class);
    private static final char[] BASE62 =
            "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".toCharArray();
    private static final int TOKEN_LEN = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final HealthNotificationRepository notifications;
    private final HealthReminderRepository reminders;
    private final HealthRecordRepository records;
    private final PetProfileRepository pets;
    private final HealthCopyRenderer renderer;
    private final NotificationService notificationService;
    private final AccountQueryService accounts;
    private final HealthReminderAnalytics analytics;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate requiresNew;

    public HealthReminderDispatcher(HealthNotificationRepository notifications, HealthReminderRepository reminders,
            HealthRecordRepository records, PetProfileRepository pets, HealthCopyRenderer renderer,
            NotificationService notificationService, AccountQueryService accounts,
            HealthReminderAnalytics analytics, JdbcTemplate jdbc, PlatformTransactionManager txManager) {
        this.jdbc = jdbc;
        this.requiresNew = new TransactionTemplate(txManager);
        this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.notifications = notifications;
        this.reminders = reminders;
        this.records = records;
        this.pets = pets;
        this.renderer = renderer;
        this.notificationService = notificationService;
        this.accounts = accounts;
        this.analytics = analytics;
    }

    @Override
    public DispatchOutcome dispatch(SendGroup group) {
        List<HealthReminder> rows = group.rows().stream()
                .filter(r -> r.getStatus() == ReminderStatus.PENDING).toList();
        if (rows.isEmpty()) {
            return DispatchOutcome.NOT_SENT;
        }
        PetProfile pet = pets.findById(group.petId()).orElse(null);
        if (pet == null) {
            return DispatchOutcome.NOT_SENT;
        }
        SendGroup live = new SendGroup(group.petId(), group.ownerId(), group.day(), group.point(), rows);
        Map<Long, LocalDate> eventDates = records.findByPetProfileIdOrderByEventDateDescIdDesc(group.petId()).stream()
                .collect(Collectors.toMap(HealthRecord::getId, HealthRecord::getEventDate, (a, b) -> a));
        RenderedCopy copy = renderer.render(live, PetHealthSnapshot.of(pet), pet.getName(),
                accounts.localeOf(group.ownerId()), eventDates::get);
        int deferred = rows.stream().mapToInt(HealthReminder::getDeferredDays).max().orElse(0);
        // ① 本事务（持宠物锁）里只落健康通知并把行置 SENT：提交即「已发出」，同日重跑不会再发。
        HealthNotification n = notifications.save(HealthNotification.create(newToken(), group.ownerId(),
                group.petId(), copy.notificationKind(), group.point(), rows.get(0).getOrigin(), rows.size(), deferred,
                copy.route(), group.day()));
        Instant now = Instant.now();
        for (HealthReminder r : rows) {
            r.markSent(n.getId(), now);
            reminders.save(r);
        }
        // ② 提交之后再写通知中心 + 推送（不持锁、不占外层连接；回滚时不会留下孤儿推送；用户点开时健康通知已提交），
        //    回写推送结果，再发埋点（health_notification_sent 要带 push_result）。
        long ownerId = group.ownerId();
        AfterCommit.run(() -> deliver(n, rows, ownerId, copy));
        log.info("health notification planned petId={} healthNotificationId={} rows={}", group.petId(), n.getId(),
                rows.size());
        return new DispatchOutcome(true, n.getId());
    }

    private void deliver(HealthNotification n, List<HealthReminder> rows, long ownerId, RenderedCopy copy) {
        try {
            HealthPushOutcome outcome = notificationService.sendHealthReminder(ownerId,
                    copy.notificationKind().notificationType(), copy.title(), copy.body(), n.getPublicToken());
            n.recordDelivery(HealthNotification.PushResult.valueOf(outcome.result().name()), outcome.notificationId());
            // 🔴 提交后回调里不能直接写库（会并入已提交的事务而静默丢失），必须另开 REQUIRES_NEW。
            requiresNew.executeWithoutResult(st -> jdbc.update(
                    "UPDATE health_notifications SET push_result = ?, inbox_notification_id = ? WHERE id = ?",
                    n.getPushResult().name(), outcome.notificationId(), n.getId()));
            log.info("health notification delivered healthNotificationId={} result={}", n.getId(), outcome.result());
        } catch (RuntimeException ex) {
            // 推送 / 通知中心失败：健康通知保持 FAILED（落库时的初值），行已 SENT，不重发。
            log.warn("health notification delivery failed healthNotificationId={} error={}", n.getId(),
                    ex.getClass().getSimpleName());
        }
        analytics.notificationSent(n);
        rows.forEach(r -> analytics.reminderSent(r, n));
    }

    static String newToken() {
        char[] out = new char[TOKEN_LEN];
        for (int i = 0; i < TOKEN_LEN; i++) {
            out[i] = BASE62[RANDOM.nextInt(BASE62.length)];
        }
        return new String(out);
    }
}
