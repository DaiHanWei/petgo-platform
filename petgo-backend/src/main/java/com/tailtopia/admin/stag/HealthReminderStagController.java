package com.tailtopia.admin.stag;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.healthreminder.schedule.HealthReminderJob;
import com.tailtopia.healthreminder.service.HealthReminderRebuilder;
import com.tailtopia.healthreminder.service.RebuildTrigger;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康提醒 QA 入口（V1.5.0 Story 2.3 · AC6，PRD AC36 / §9 QA endpoint）。🔴 {@link StagOnly}：生产不注册。挂在后台链
 * {@code /admin/stag/**}（后台登录 + {@code SUPER_ADMIN}，该前缀免 CSRF），与 {@code ShopStagController} 同。
 *
 * <p>{@code run?date=} 只注入日跑的「D」，不改系统时钟；{@code rebuild?petId=} 以当前时刻重建一只宠物。薄包装，不写业务逻辑。
 */
@RestController
@StagOnly
public class HealthReminderStagController {

    private static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final HealthReminderJob job;
    private final HealthReminderRebuilder rebuilder;

    public HealthReminderStagController(HealthReminderJob job, HealthReminderRebuilder rebuilder) {
        this.job = job;
        this.rebuilder = rebuilder;
    }

    @PostMapping("/admin/stag/health-reminders/run")
    @PreAuthorize(AUTH)
    public Map<String, Object> run(@RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate date) {
        HealthReminderJob.RunResult r = job.runFor(date);
        return Map.of("day", r.day().toString(), "enabled", r.enabled(), "pets", r.pets(),
                "groupsSent", r.groupsSent(), "queueSent", r.queueSent(), "cancelledByReason",
                r.cancelledByReason());
    }

    @PostMapping("/admin/stag/health-reminders/rebuild")
    @PreAuthorize(AUTH)
    public Map<String, Object> rebuild(@RequestParam("petId") long petId) {
        rebuilder.rebuildPet(petId, RebuildTrigger.of("stag"));
        return Map.of("petId", petId, "rebuilt", true);
    }
}
