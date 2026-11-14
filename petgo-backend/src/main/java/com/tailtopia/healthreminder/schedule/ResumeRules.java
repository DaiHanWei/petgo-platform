package com.tailtopia.healthreminder.schedule;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.ReminderFamily;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.health.CoverageGroups;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 怀孕 / 哺乳清除后的疫苗恢复表（V1.5.0 Story 2.3，PRD FR-166 Resuming 表、AC45，架构增量 §4.6「清除」）。纯函数。
 *
 * <p>R = 清除后的下一跑。对每个疫苗覆盖组的最新记录（N = Next date，组未被覆盖、非回填静默、该 N 未补发过）：
 * R ≤ N → 普通重建；N+1 ≤ R ≤ N+14 → 在 R 补发一条 {@code VACCINE_D_PLUS_7}（RESUME_CATCH_UP），并取消同 N 其余 PENDING 点
 * （catch_up_sent）；R &gt; N+14 → 什么都不发。
 */
public final class ResumeRules {

    static final int CATCH_UP_WINDOW_DAYS = 14;

    /** 一次补发。 */
    public record CatchUp(HealthRecordSnapshot record, LocalDate nextDate, LocalDate sendDate) {
    }

    /** 恢复方案：要插入的补发与要取消（catch_up_sent）的行。 */
    public record Plan(List<CatchUp> catchUps, List<HealthReminder> cancelCatchUpSent) {
    }

    private ResumeRules() {
    }

    public static Plan plan(PetHealthSnapshot pet, List<HealthRecordSnapshot> records, ReminderHistory history,
            List<HealthReminder> pendingRows, LocalDate resumeDate, LocalDate effectiveLaunchDate) {
        List<CatchUp> catchUps = new ArrayList<>();
        List<HealthReminder> cancels = new ArrayList<>();
        CoverageGroups groups = CoverageGroups.of(records, pet.petType());
        for (Map.Entry<String, List<HealthRecordSnapshot>> e : groups.groups().entrySet()) {
            HealthRecordSnapshot latest = e.getValue().get(e.getValue().size() - 1);
            LocalDate n = latest.nextDate();
            if (latest.type() != HealthRecordType.VACCINE || n == null || groups.isCovered(e.getKey())
                    || (latest.origin() == RecordOrigin.BACKFILL && effectiveLaunchDate != null
                            && n.isBefore(effectiveLaunchDate))
                    || history.catchUpUsed(latest.id(), n)) {
                continue;
            }
            if (resumeDate.isAfter(n) && !resumeDate.isAfter(n.plusDays(CATCH_UP_WINDOW_DAYS))) {
                catchUps.add(new CatchUp(latest, n, resumeDate));
                for (HealthReminder row : pendingRows) {
                    if (row.getStatus() == ReminderStatus.PENDING && row.getOrigin() == ReminderOrigin.RECORD
                            && row.getReminderKind().family() == ReminderFamily.VACCINE
                            && row.getSourceRecordId() != null && row.getSourceRecordId() == latest.id()
                            && n.equals(row.getNextDateRef())) {
                        cancels.add(row);
                    }
                }
            }
        }
        return new Plan(catchUps, cancels);
    }
}
