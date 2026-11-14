package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.CancelReason;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.service.AfterCommit;
import com.tailtopia.healthreminder.service.HealthReminderAnalytics;
import com.tailtopia.healthreminder.service.HealthReminderLock;
import com.tailtopia.notify.service.AppVersionGate;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 日跑的批量读写（V1.5.0 Story 2.3 · AC2 / AC3）：过期作废、当天待发 + 版本门槛、当天已通知用户、有排队的用户。
 * 版本门槛只走地基 {@link AppVersionGate#usersOnV150OrLater}（一次批量）。
 */
@Component
public class DailyRunStore {

    /** 当天待发的宠物与主人；以及因旧版作废的计数。 */
    public record DuePets(List<Long> petIds, Map<Long, Long> ownerByPet, Map<CancelReason, Integer> oldAppCancelled) {
    }

    private final HealthReminderRepository reminders;
    private final AppVersionGate versionGate;
    private final HealthReminderAnalytics analytics;
    private final HealthReminderLock lock;
    private final NamedParameterJdbcTemplate jdbc;

    public DailyRunStore(HealthReminderRepository reminders, AppVersionGate versionGate,
            HealthReminderAnalytics analytics, HealthReminderLock lock, NamedParameterJdbcTemplate jdbc) {
        this.reminders = reminders;
        this.versionGate = versionGate;
        this.analytics = analytics;
        this.lock = lock;
        this.jdbc = jdbc;
    }

    /**
     * ③ 发送日已过仍 PENDING 的非排队行 → missed_run（用户 ≥1.5.0 才发埋点）；排队行发送日早于今天 → 挪到今天（排队永不丢）。
     */
    @Transactional
    public Map<CancelReason, Integer> expireMissed(LocalDate day) {
        // 先取锁再读实体：避免读到并发重建改过之前的旧状态再覆盖回去。
        lockPetIds("send_date < :d", day);
        List<HealthReminder> stale = reminders.findByStatusAndSendDateBefore(ReminderStatus.PENDING, day);
        Set<Long> v150 = versionGate.usersOnV150OrLater(owners(stale));
        Map<CancelReason, Integer> out = new EnumMap<>(CancelReason.class);
        for (HealthReminder r : stale) {
            if (r.getOrigin() == ReminderOrigin.LAUNCH_QUEUE) {
                r.moveSendDate(day);
            } else {
                r.cancel(CancelReason.MISSED_RUN, null);
                out.merge(CancelReason.MISSED_RUN, 1, Integer::sum);
                if (v150.contains(r.getOwnerId())) {
                    AfterCommit.run(() -> analytics.cancelled(r, CancelReason.MISSED_RUN, null, null));
                }
            }
            reminders.save(r);
        }
        return out;
    }

    /** ④ 当天待发（非排队）的宠物；主人不在 1.5.0 名单 → 这些行 old_app 作废（无埋点）。 */
    @Transactional
    public DuePets duePets(LocalDate day) {
        lockPetIds("send_date = :d", day);
        List<HealthReminder> due = reminders.findByStatusAndSendDate(ReminderStatus.PENDING, day).stream()
                .filter(r -> r.getOrigin() != ReminderOrigin.LAUNCH_QUEUE).toList();
        Set<Long> v150 = versionGate.usersOnV150OrLater(owners(due));
        Map<CancelReason, Integer> cancelled = new EnumMap<>(CancelReason.class);
        List<HealthReminder> old = due.stream().filter(r -> !v150.contains(r.getOwnerId())).toList();
        for (HealthReminder r : old) {
            r.cancel(CancelReason.OLD_APP, null);
            reminders.save(r);
            cancelled.merge(CancelReason.OLD_APP, 1, Integer::sum);
        }
        Map<Long, Long> ownerByPet = new TreeMap<>();
        for (HealthReminder r : due) {
            if (v150.contains(r.getOwnerId())) {
                ownerByPet.put(r.getPetProfileId(), r.getOwnerId());
            }
        }
        return new DuePets(new ArrayList<>(ownerByPet.keySet()), ownerByPet, cancelled);
    }

    /** 当天已写过健康通知的用户（同日重跑时，之前那次已发的也算「今天有推送」）。 */
    public Set<Long> usersNotifiedOn(LocalDate day) {
        return new HashSet<>(jdbc.queryForList("SELECT DISTINCT user_id FROM health_notifications WHERE send_date = :d",
                new MapSqlParameterSource("d", day), Long.class));
    }

    /** 有 PENDING 排队行的用户。 */
    public List<Long> usersWithQueue() {
        return jdbc.queryForList("SELECT DISTINCT owner_id FROM health_reminders WHERE origin = 'LAUNCH_QUEUE' "
                + "AND status = 'PENDING' ORDER BY owner_id", new MapSqlParameterSource(), Long.class);
    }

    /** 这些用户的全部宠物（占用判断：任一宠物今天有生日 / 纪念日推送）。 */
    public Map<Long, Long> petsOfOwners(Collection<Long> owners) {
        Map<Long, Long> out = new TreeMap<>();
        if (owners.isEmpty()) {
            return out;
        }
        jdbc.query("SELECT id, owner_id FROM pet_profiles WHERE owner_id IN (:o)", new MapSqlParameterSource("o",
                owners), rs -> {
                    out.put(rs.getLong("id"), rs.getLong("owner_id"));
                });
        return out;
    }

    /** 按 id 升序给符合条件的 PENDING 行所属宠物上锁（只读 id，不加载实体）。 */
    private void lockPetIds(String sendDateCondition, LocalDate day) {
        jdbc.queryForList("SELECT DISTINCT pet_profile_id FROM health_reminders WHERE status = 'PENDING' AND "
                + sendDateCondition + " ORDER BY pet_profile_id", new MapSqlParameterSource("d", day), Long.class)
                .forEach(lock::lockPet);
    }

    private static Set<Long> owners(List<HealthReminder> rows) {
        return rows.stream().map(HealthReminder::getOwnerId).collect(Collectors.toSet());
    }
}
