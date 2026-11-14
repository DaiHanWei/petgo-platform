package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Next date 预填门面（V1.5.0 Story 1.2，架构增量 §3.2）：按类型分派到 {@link VaccineSeriesRule} / {@link DewormRule} /
 * {@link HeatRule}。纯函数；1.2 预填接口、旧版默认值、1.3 回填、2.x 提醒共用这一份。
 *
 * <ul>
 *   <li>NEUTER：无 Next date；发情：只有未绝育母狗（周期首条预填、后续记录锁定显示首条的值）；</li>
 *   <li>非猫狗物种：任何类型都不预填（字段照有）；Vet visit / Custom / 疫苗 OTHER：无预填。</li>
 * </ul>
 */
public final class NextDateRules {

    private NextDateRules() {
    }

    /**
     * @param history 该宠物现有记录（可含被编辑的这条，会按 {@code draft.recordId} 排除）
     */
    public static NextDatePlan plan(PetHealthSnapshot pet, RecordDraft draft, List<HealthRecordSnapshot> history) {
        HealthRecordType type = draft.type();
        if (type == null || type == HealthRecordType.NEUTER) {
            return NextDatePlan.NOT_ALLOWED;
        }
        List<HealthRecordSnapshot> others = excludeSelf(history, draft.recordId());
        if (type == HealthRecordType.MENSTRUATION) {
            return heatPlan(pet, draft, others);
        }
        if (!pet.isCatOrDog() || draft.eventDate() == null) {
            return NextDatePlan.NO_SUGGESTION;
        }
        NextDateSuggestion s = switch (type) {
            case VACCINE -> VaccineSeriesRule.next(draft.vaccinePreset(), draft.dose(), draft.eventDate(),
                    pet.birthday(), hasPuppyDp(others, pet));
            case DEWORM -> NextDateSuggestion.of(DewormRule.next(draft.dewormType(), draft.durationMonths(),
                    draft.eventDate(), pet.birthday()));
            default -> null;
        };
        return new NextDatePlan(true, false, null, s);
    }

    private static NextDatePlan heatPlan(PetHealthSnapshot pet, RecordDraft draft, List<HealthRecordSnapshot> others) {
        if (!pet.isIntactFemaleDog()) {
            return NextDatePlan.NOT_ALLOWED;
        }
        if (draft.eventDate() == null) {
            return NextDatePlan.NO_SUGGESTION;
        }
        List<HealthRecordSnapshot> all = new ArrayList<>(others);
        HealthRecordSnapshot self = draft.asSnapshot();
        all.add(self);
        HeatEpisodes episodes = HeatEpisodes.of(all, pet.petType());
        HeatEpisodes.Episode episode = episodes.episodeOf(self.id());
        if (episode.first().id() != self.id()) {
            return new NextDatePlan(true, true, episode.first().nextDate(), null);
        }
        LocalDate next = HeatRule.nextForFemaleDog(episodes.firstDaysUpTo(draft.eventDate()));
        return new NextDatePlan(true, false, null, NextDateSuggestion.of(next));
    }

    /** 宠物是否已有 Puppy DP 记录（Combo 的 young 系列因此为 2 剂）。 */
    static boolean hasPuppyDp(List<HealthRecordSnapshot> records, PetHealthSnapshot pet) {
        return records.stream().anyMatch(r -> RecordKinds.effectivePreset(r, pet.petType()) == VaccinePreset.PUPPY_DP);
    }

    private static List<HealthRecordSnapshot> excludeSelf(List<HealthRecordSnapshot> history, Long recordId) {
        if (recordId == null) {
            return history;
        }
        return history.stream().filter(r -> r.id() != recordId).toList();
    }
}
