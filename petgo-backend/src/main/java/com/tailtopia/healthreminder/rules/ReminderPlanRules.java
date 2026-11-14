package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.CoverageGroups;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.HeatEpisodes;
import com.tailtopia.profile.health.NextDateSuggestion;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.health.RecordKinds;
import com.tailtopia.profile.health.VaccineSeriesRule;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 记录类与事件类提醒的期望集合（V1.5.0 Story 2.1，架构增量 §4.3「期望集合」，PRD FR-163 表、FR-161、FR-171、附录 C）。
 * 纯函数：快照入、结果出，不读库。只输出 {@code due_date ≥ fromDate} 的点（回溯录入早于下一跑的点不生成）。
 *
 * <ul>
 *   <li><b>记录类</b>：每个覆盖组的最新记录（组未被覆盖、有 Next date、非回填静默）按 FR-163 表生成推送点；Vet visit 每条
 *       各自生成（未满足时）；未绝育母狗最新发情周期首条 → D-7。</li>
 *   <li><b>事件类</b>（只看非 BACKFILL 记录）：拆线检查、子宫蓄脓观察、猫二次发情、公的首次发情。</li>
 *   <li><b>全局过滤</b>：只读 → 空；非猫狗物种只留记录类（无发情、无事件类）。</li>
 * </ul>
 */
public final class ReminderPlanRules {

    private static final int STITCH_DAYS = 10;
    private static final int PYOMETRA_DAYS = 42;
    private static final int CAT_SECOND_HEAT_GAP_DAYS = 60;
    private static final int RECENT_HEAT_DAYS = 7;
    private static final int CAT_SECOND_HEAT_COOLDOWN_MONTHS = 6;

    private ReminderPlanRules() {
    }

    public static List<DesiredReminder> recordAndEventReminders(PetHealthSnapshot pet,
            List<HealthRecordSnapshot> records, ReminderHistory history, LocalDate fromDate,
            LocalDate effectiveLaunchDate) {
        List<DesiredReminder> out = new ArrayList<>();
        if (pet.status() == PetStatus.READ_ONLY) {
            return out;
        }
        CoverageGroups groups = CoverageGroups.of(records, pet.petType());
        recordReminders(pet, records, groups, history, effectiveLaunchDate, out);
        if (pet.isCatOrDog()) {
            heatNextReminder(pet, records, effectiveLaunchDate, out);
            eventReminders(pet, records, history, fromDate, out);
        }
        return out.stream().filter(d -> !d.dueDate().isBefore(fromDate)).toList();
    }

    // ===== 记录类 =====

    private static void recordReminders(PetHealthSnapshot pet, List<HealthRecordSnapshot> records,
            CoverageGroups groups, ReminderHistory history, LocalDate launch, List<DesiredReminder> out) {
        for (Map.Entry<String, List<HealthRecordSnapshot>> e : groups.groups().entrySet()) {
            HealthRecordSnapshot latest = e.getValue().get(e.getValue().size() - 1);
            HealthRecordType type = latest.type();
            if (type == HealthRecordType.VET_VISIT) {
                // Vet visit 每条一组：未满足时各自 D-1 / D-Day（FR-161）。
                if (hasLiveNext(latest, launch) && groups.vetVisitSatisfiedBy(latest).isEmpty()) {
                    LocalDate next = latest.nextDate();
                    out.add(DesiredReminder.simple(latest.id(), ReminderKind.VET_VISIT_D_MINUS_1, PushPoint.D_MINUS_1,
                            ReminderOrigin.RECORD, next, next.minusDays(1)));
                    out.add(DesiredReminder.simple(latest.id(), ReminderKind.VET_VISIT_D_DAY, PushPoint.D_DAY,
                            ReminderOrigin.RECORD, next, next));
                }
                continue;
            }
            if (type != HealthRecordType.VACCINE && type != HealthRecordType.DEWORM
                    && type != HealthRecordType.CUSTOM) {
                continue; // 发情单独处理；绝育没有 Next date
            }
            if (!hasLiveNext(latest, launch) || groups.isCovered(e.getKey())) {
                continue;
            }
            LocalDate next = latest.nextDate();
            switch (type) {
                case VACCINE -> vaccinePoints(pet, latest, records, history, next, out);
                case DEWORM -> dewormPoints(latest, next, out);
                default -> out.add(new DesiredReminder(latest.id(), ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY,
                        ReminderOrigin.RECORD, next, next, null, null, null, null, truncate(latest.customName(), 20)));
            }
        }
    }

    private static void vaccinePoints(PetHealthSnapshot pet, HealthRecordSnapshot r, List<HealthRecordSnapshot> all,
            ReminderHistory history, LocalDate next, List<DesiredReminder> out) {
        if (history.catchUpUsed(r.id(), next)) {
            return; // 该 Next date 已用过恢复补发：保持「已完成」
        }
        // 到期疫苗 / 剂次 = event date 当时命中的分支（即使用户后来手改了 Next date，FR-159 Due vaccine）。
        VaccinePreset preset = RecordKinds.effectivePreset(r, pet.petType());
        VaccinePreset duePreset = preset;
        Integer dueDose = null;
        if (preset != null) {
            boolean hasDp = all.stream().anyMatch(o -> o.id() != r.id()
                    && RecordKinds.effectivePreset(o, pet.petType()) == VaccinePreset.PUPPY_DP);
            NextDateSuggestion s = VaccineSeriesRule.next(preset, r.dose(), r.eventDate(), pet.birthday(), hasDp);
            if (s != null) {
                duePreset = s.dueVaccinePreset();
                dueDose = s.dueDose();
            }
        }
        String dueName = duePreset != null && duePreset.label() != null ? duePreset.label()
                : truncate(r.vaccineName(), 30);
        VaccinePreset storedPreset = duePreset != null ? duePreset : r.vaccinePreset();
        out.add(new DesiredReminder(r.id(), ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, ReminderOrigin.RECORD,
                next, next.minusDays(7), storedPreset, dueName, dueDose, null, null));
        out.add(new DesiredReminder(r.id(), ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, ReminderOrigin.RECORD,
                next, next, storedPreset, dueName, dueDose, null, null));
        out.add(new DesiredReminder(r.id(), ReminderKind.VACCINE_D_PLUS_7, PushPoint.D_PLUS_7, ReminderOrigin.RECORD,
                next, next.plusDays(7), storedPreset, dueName, dueDose, null, null));
    }

    private static void dewormPoints(HealthRecordSnapshot r, LocalDate next, List<DesiredReminder> out) {
        DewormType t = r.dewormType();
        ReminderKind dDay;
        ReminderKind dPlus3;
        if (t == null) {
            dDay = ReminderKind.DEWORM_LEGACY_D_DAY;
            dPlus3 = ReminderKind.DEWORM_LEGACY_D_PLUS_3;
        } else {
            switch (t) {
                case INTERNAL -> {
                    dDay = ReminderKind.DEWORM_INTERNAL_D_DAY;
                    dPlus3 = ReminderKind.DEWORM_INTERNAL_D_PLUS_3;
                }
                case HEARTWORM -> {
                    dDay = ReminderKind.DEWORM_HEARTWORM_D_DAY;
                    dPlus3 = ReminderKind.DEWORM_INTERNAL_D_PLUS_3;
                }
                case EXTERNAL -> {
                    dDay = ReminderKind.DEWORM_EXTERNAL_D_DAY;
                    dPlus3 = ReminderKind.DEWORM_EXTERNAL_D_PLUS_3;
                }
                default -> {
                    dDay = ReminderKind.DEWORM_FLEA_WORM_D_DAY;
                    dPlus3 = ReminderKind.DEWORM_EXTERNAL_D_PLUS_3;
                }
            }
        }
        out.add(new DesiredReminder(r.id(), dDay, PushPoint.D_DAY, ReminderOrigin.RECORD, next, next,
                null, null, null, t, null));
        out.add(new DesiredReminder(r.id(), dPlus3, PushPoint.D_PLUS_3, ReminderOrigin.RECORD, next, next.plusDays(3),
                null, null, null, t, null));
    }

    /** 未绝育母狗：最新发情周期首条的 Next date → D-7（FR-171）。 */
    private static void heatNextReminder(PetHealthSnapshot pet, List<HealthRecordSnapshot> records, LocalDate launch,
            List<DesiredReminder> out) {
        if (!pet.isIntactFemaleDog()) {
            return;
        }
        List<HeatEpisodes.Episode> episodes = HeatEpisodes.of(records, pet.petType()).episodes();
        if (episodes.isEmpty()) {
            return;
        }
        HealthRecordSnapshot first = episodes.get(episodes.size() - 1).first();
        if (hasLiveNext(first, launch)) {
            out.add(DesiredReminder.simple(first.id(), ReminderKind.HEAT_DOG_D_MINUS_7, PushPoint.D_MINUS_7,
                    ReminderOrigin.RECORD, first.nextDate(), first.nextDate().minusDays(7)));
        }
    }

    // ===== 事件类（只看非 BACKFILL 记录）=====

    private static void eventReminders(PetHealthSnapshot pet, List<HealthRecordSnapshot> records,
            ReminderHistory history, LocalDate fromDate, List<DesiredReminder> out) {
        for (HealthRecordSnapshot r : records) {
            if (r.type() == HealthRecordType.NEUTER && r.origin() != RecordOrigin.BACKFILL) {
                out.add(DesiredReminder.simple(r.id(), ReminderKind.STITCH_CHECK, PushPoint.NONE,
                        ReminderOrigin.EVENT, null, r.eventDate().plusDays(STITCH_DAYS)));
            }
        }
        if (pet.sex() == null || pet.isNeutered()) {
            return; // 性别空：无任何发情相关提醒；已绝育：无发情 / 子宫蓄脓
        }
        List<HeatEpisodes.Episode> episodes = HeatEpisodes.of(records, pet.petType()).episodes();
        if (episodes.isEmpty()) {
            return;
        }
        if (pet.isIntactFemaleDog()) {
            for (HeatEpisodes.Episode ep : episodes) {
                HealthRecordSnapshot first = ep.first();
                if (first.origin() != RecordOrigin.BACKFILL) {
                    out.add(DesiredReminder.simple(first.id(), ReminderKind.PYOMETRA_WATCH, PushPoint.NONE,
                            ReminderOrigin.EVENT, null, first.eventDate().plusDays(PYOMETRA_DAYS)));
                }
            }
        }
        if (pet.isCat() && pet.sex() == PetSex.FEMALE) {
            catSecondHeat(episodes, history, fromDate, out);
        }
        if (pet.sex() == PetSex.MALE) {
            maleFirstHeat(records, episodes, history, fromDate, out);
        }
    }

    /**
     * 猫二次发情：某周期首条与上一周期首条间隔 ≤ 60 天、首条 event date ≥ 下一跑 − 7、6 个月内没发过、上一周期也非回填 →
     * 下一跑发（原定日 = 该记录保存时的下一跑，已有行时沿用它保证幂等）。
     */
    private static void catSecondHeat(List<HeatEpisodes.Episode> episodes, ReminderHistory history,
            LocalDate fromDate, List<DesiredReminder> out) {
        List<LocalDate> sent = history.sentDates(ReminderKind.HEAT_CAT_SECOND);
        for (int i = 1; i < episodes.size(); i++) {
            HealthRecordSnapshot prev = episodes.get(i - 1).first();
            HealthRecordSnapshot cur = episodes.get(i).first();
            if (cur.origin() == RecordOrigin.BACKFILL || prev.origin() == RecordOrigin.BACKFILL) {
                continue; // 只看上线后的发情（FR-168）
            }
            if (ChronoUnit.DAYS.between(prev.eventDate(), cur.eventDate()) > CAT_SECOND_HEAT_GAP_DAYS) {
                continue;
            }
            var existing = history.existingDueDate(cur.id(), ReminderKind.HEAT_CAT_SECOND);
            if (existing.isEmpty() && cur.eventDate().isBefore(fromDate.minusDays(RECENT_HEAT_DAYS))) {
                continue;
            }
            LocalDate due = existing.orElse(fromDate);
            boolean cooledDown = sent.stream().noneMatch(d -> !d.isBefore(due.minusMonths(
                    CAT_SECOND_HEAT_COOLDOWN_MONTHS)) && d.isBefore(due));
            if (existing.isPresent() || cooledDown) {
                out.add(DesiredReminder.simple(cur.id(), ReminderKind.HEAT_CAT_SECOND, PushPoint.NONE,
                        ReminderOrigin.EVENT, null, due));
                sent = append(sent, due); // 同一次重建里后面的周期也受 6 个月冷却约束
            }
        }
    }

    /** 公的首次发情：第一个周期首条（且没有任何回填发情记录）、event ≥ 下一跑 − 7、从未发过 → 下一跑发。 */
    private static void maleFirstHeat(List<HealthRecordSnapshot> records, List<HeatEpisodes.Episode> episodes,
            ReminderHistory history, LocalDate fromDate, List<DesiredReminder> out) {
        boolean anyBackfillHeat = records.stream().anyMatch(r -> r.type() == HealthRecordType.MENSTRUATION
                && r.origin() == RecordOrigin.BACKFILL);
        if (anyBackfillHeat) {
            return; // 有上线前发情记录的公的视为已收到过（FR-168）
        }
        HealthRecordSnapshot first = episodes.get(0).first();
        var existing = history.existingDueDate(first.id(), ReminderKind.HEAT_MALE_FIRST);
        if (existing.isEmpty() && (history.sentEver(ReminderKind.HEAT_MALE_FIRST)
                || first.eventDate().isBefore(fromDate.minusDays(RECENT_HEAT_DAYS)))) {
            return;
        }
        out.add(DesiredReminder.simple(first.id(), ReminderKind.HEAT_MALE_FIRST, PushPoint.NONE,
                ReminderOrigin.EVENT, null, existing.orElse(fromDate)));
    }

    // ===== 工具 =====

    /** 有 Next date 且不是回填静默（BACKFILL 且 Next date 早于有效上线日）。 */
    private static boolean hasLiveNext(HealthRecordSnapshot r, LocalDate launch) {
        if (r.nextDate() == null) {
            return false;
        }
        return !(r.origin() == RecordOrigin.BACKFILL && launch != null && r.nextDate().isBefore(launch));
    }

    private static List<LocalDate> append(List<LocalDate> list, LocalDate d) {
        List<LocalDate> copy = new ArrayList<>(list);
        copy.add(d);
        return copy;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
