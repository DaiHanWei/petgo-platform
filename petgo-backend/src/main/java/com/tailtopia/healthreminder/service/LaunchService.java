package com.tailtopia.healthreminder.service;

import com.tailtopia.notify.service.AppVersionGate;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 上线开关与个人上线（V1.5.0 Story 2.2 · AC4，PRD §0 Launch、FR-164 Launch rules，架构增量 §4.7）。由 2.3 日跑在开头调用
 * {@link #onRun}。开关只经运维 SQL 打开（无后台页，见 story 2.2 runbook）。
 *
 * <ul>
 *   <li>开关打开后第一次日跑：写 {@code launch_date = D}（上线日），并对<b>全部</b>宠物重建（回填记录的未来点排上）；</li>
 *   <li>每次日跑：最后上报 ≥1.5.0、还没有个人上线行的用户 → 建行（上线日 = D）、排队、重建其宠物（档案类此时生效）。</li>
 * </ul>
 * 版本门槛只走地基 {@link AppVersionGate#usersOnV150OrLater}（分批 500，禁 N+1）。🔒 日志只记计数。
 */
@Service
public class LaunchService {

    private static final Logger log = LoggerFactory.getLogger(LaunchService.class);
    static final int BATCH = 500;

    /** 一次 onRun 的计数。 */
    public record LaunchRunResult(boolean enabled, boolean firstRun, int newUsers, int queuedItems) {
    }

    private final HealthReminderSettingsDao settings;
    private final AppVersionGate versionGate;
    private final UserLaunchDao userLaunch;
    private final LaunchQueueService queue;
    private final HealthReminderRebuilder rebuilder;
    private final JdbcTemplate jdbc;

    public LaunchService(HealthReminderSettingsDao settings, AppVersionGate versionGate, UserLaunchDao userLaunch,
            LaunchQueueService queue, HealthReminderRebuilder rebuilder, JdbcTemplate jdbc) {
        this.settings = settings;
        this.versionGate = versionGate;
        this.userLaunch = userLaunch;
        this.queue = queue;
        this.rebuilder = rebuilder;
        this.jdbc = jdbc;
    }

    public LaunchRunResult onRun(LocalDate day) {
        HealthReminderSettingsDao.Settings s = settings.read();
        if (!s.enabled()) {
            return new LaunchRunResult(false, false, 0, 0);
        }
        boolean firstRun = s.launchDate() == null;
        if (firstRun) {
            settings.setLaunchDateIfAbsent(day);
        }
        Instant runStart = day.atStartOfDay(ScheduleWindow.WIB).toInstant();
        // 只扫还没有个人上线行的宠物主（已上线的用户每天不再过版本门槛）。
        List<Long> owners = jdbc.queryForList("SELECT DISTINCT p.owner_id FROM pet_profiles p WHERE NOT EXISTS "
                + "(SELECT 1 FROM health_reminder_user_launch l WHERE l.user_id = p.owner_id) ORDER BY p.owner_id",
                Long.class);
        int newUsers = 0;
        int queued = 0;
        for (int from = 0; from < owners.size(); from += BATCH) {
            List<Long> batch = owners.subList(from, Math.min(from + BATCH, owners.size()));
            Set<Long> onV150 = versionGate.usersOnV150OrLater(batch);
            Set<Long> launched = userLaunch.usersWithLaunch(onV150);
            for (Long userId : onV150) {
                if (launched.contains(userId)) {
                    continue;
                }
                try {
                    queued += queue.enqueueUser(userId, day);
                } catch (RuntimeException ex) {
                    // 单个用户失败不中断整次日跑（尤其首跑后面的全宠重建）；该用户明天再试。
                    log.warn("health launch enqueue failed userId={} error={}", userId, ex.getClass().getSimpleName());
                    continue;
                }
                newUsers++;
                if (!firstRun) {
                    rebuildOwner(userId, runStart);
                }
            }
        }
        if (firstRun) {
            for (Long petId : jdbc.queryForList("SELECT id FROM pet_profiles ORDER BY id", Long.class)) {
                rebuild(petId, runStart);
            }
        }
        log.info("health launch run day={} firstRun={} newUsers={} queued={}", day, firstRun, newUsers, queued);
        return new LaunchRunResult(true, firstRun, newUsers, queued);
    }

    private void rebuildOwner(long userId, Instant runStart) {
        for (Long petId : jdbc.queryForList("SELECT id FROM pet_profiles WHERE owner_id = ? ORDER BY id", Long.class,
                userId)) {
            rebuild(petId, runStart);
        }
    }

    private void rebuild(long petId, Instant runStart) {
        try {
            rebuilder.rebuildPetAt(petId, RebuildTrigger.of("launch"), runStart);
        } catch (RuntimeException ex) {
            log.warn("health launch rebuild failed petId={} error={}", petId, ex.getClass().getSimpleName());
        }
    }
}
