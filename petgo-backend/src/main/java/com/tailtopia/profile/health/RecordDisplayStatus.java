package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.RecordOrigin;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 列表显示状态（V1.5.0 Story 1.2，架构增量 §3.4，PRD FR-158 / FR-161 / FR-168 / FR-171）。服务端唯一来源，纯函数。
 *
 * <ul>
 *   <li>不是组内最新、组被覆盖、Vet visit 已满足、无 Next date → {@code DONE}、不显示；</li>
 *   <li>回填静默：{@code BACKFILL} 且 Next date 早于 {@code effectiveLaunchDate} → {@code DONE}；</li>
 *   <li>发情：只有未绝育母狗有；同一周期后续记录显示首条的 Next date（{@code nextDateLocked=true}）；永不 OVERDUE；</li>
 *   <li>否则 Next date &lt; 今天 → OVERDUE；今天 ≤ Next date ≤ 今天 + 7 → DUE_SOON；更晚 → UPCOMING。</li>
 * </ul>
 */
public final class RecordDisplayStatus {

    private static final int DUE_SOON_DAYS = 7;

    /** 单条记录的显示结果。 */
    public record Display(LocalDate displayNextDate, RecordStatus recordStatus, boolean nextDateLocked) {

        static final Display DONE = new Display(null, RecordStatus.DONE, false);
    }

    private RecordDisplayStatus() {
    }

    public static Map<Long, Display> compute(List<HealthRecordSnapshot> records, PetHealthSnapshot pet,
            LocalDate today, LocalDate effectiveLaunchDate) {
        CoverageGroups groups = CoverageGroups.of(records, pet.petType());
        HeatEpisodes heats = HeatEpisodes.of(records, pet.petType());
        boolean heatEligible = pet.isIntactFemaleDog();
        Map<Long, Display> out = new HashMap<>();
        for (HealthRecordSnapshot r : records) {
            out.put(r.id(), displayOf(r, groups, heats, heatEligible, today, effectiveLaunchDate));
        }
        return out;
    }

    private static Display displayOf(HealthRecordSnapshot r, CoverageGroups groups, HeatEpisodes heats,
            boolean heatEligible, LocalDate today, LocalDate launch) {
        if (r.type() == HealthRecordType.MENSTRUATION) {
            return heatDisplay(r, groups, heats, heatEligible, today, launch);
        }
        if (r.type() == HealthRecordType.NEUTER || r.nextDate() == null) {
            return Display.DONE;
        }
        if (r.type() == HealthRecordType.VET_VISIT) {
            if (groups.vetVisitSatisfiedBy(r).isPresent()) {
                return Display.DONE;
            }
        } else {
            String group = groups.groupOf(r);
            if (!groups.isLatest(r) || groups.isCovered(group)) {
                return Display.DONE;
            }
        }
        if (backfillSilent(r, r.nextDate(), launch)) {
            return Display.DONE;
        }
        return new Display(r.nextDate(), statusOf(r.nextDate(), today), false);
    }

    private static Display heatDisplay(HealthRecordSnapshot r, CoverageGroups groups, HeatEpisodes heats,
            boolean heatEligible, LocalDate today, LocalDate launch) {
        if (!heatEligible) {
            return Display.DONE;
        }
        HeatEpisodes.Episode episode = heats.episodeOf(r.id());
        boolean locked = episode != null && episode.first().id() != r.id();
        if (!groups.isLatest(r) || episode == null) {
            return new Display(null, RecordStatus.DONE, locked);
        }
        HealthRecordSnapshot first = episode.first();
        LocalDate next = first.nextDate();
        // 发情永不 OVERDUE：过了日期即 DONE。
        if (next == null || next.isBefore(today) || backfillSilent(first, next, launch)) {
            return new Display(null, RecordStatus.DONE, locked);
        }
        return new Display(next, statusOf(next, today), locked);
    }

    private static boolean backfillSilent(HealthRecordSnapshot r, LocalDate next, LocalDate launch) {
        return r.origin() == RecordOrigin.BACKFILL && launch != null && next.isBefore(launch);
    }

    static RecordStatus statusOf(LocalDate next, LocalDate today) {
        if (next.isBefore(today)) {
            return RecordStatus.OVERDUE;
        }
        return next.isAfter(today.plusDays(DUE_SOON_DAYS)) ? RecordStatus.UPCOMING : RecordStatus.DUE_SOON;
    }
}
