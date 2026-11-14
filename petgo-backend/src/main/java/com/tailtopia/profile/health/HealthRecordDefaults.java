package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.List;

/**
 * 旧版客户端（请求头 &lt; 1.5.0 或缺头）写健康记录时的默认值（V1.5.0 健康记录提醒，架构增量 §6.1「旧版客户端」，
 * PRD FR-168 Old app versions）。
 *
 * <p>旧版 App 不认识新字段：<b>新建</b>（Story 1.2）时疫苗名走 {@link VaccineKeywordMatcher}、按 {@link NextDateRules}
 * 写默认 Next date（无类型驱虫 = Legacy +3 个月）；<b>编辑</b>（Story 1.3）按「改了疫苗名 → 重匹配、清剂次；
 * 改了日期使 next_date ≤ eventDate → 重算」处理。其余情况新字段一律不动。
 */
public final class HealthRecordDefaults {

    /** 本次旧版写入改了什么（新建时 {@code created=true}，其余两项无意义）。 */
    public record LegacyChange(boolean created, boolean vaccineNameChanged, boolean eventDateChanged) {

        public static LegacyChange ofCreate() {
            return new LegacyChange(true, false, false);
        }
    }

    private HealthRecordDefaults() {
    }

    /**
     * 旧版客户端写入后补默认值。
     *
     * @param others 该宠物的其他记录（不含本条）：Combo 系列长度、发情周期要用
     */
    public static void applyForLegacyClient(PetHealthSnapshot pet, HealthRecord record,
            List<HealthRecordSnapshot> others, LegacyChange change) {
        if (!change.created()) {
            // TODO(v1.5.0 Story 1.3)：编辑 → 改名重匹配 / 改日期重算。
            return;
        }
        if (!pet.isCatOrDog()) {
            return; // OTHER 物种：不存预设、永不预填（FR-158）
        }
        if (record.getType() == HealthRecordType.VACCINE) {
            record.setVaccinePreset(VaccineKeywordMatcher.match(record.getVaccineName(), pet.petType()));
            record.setDose(null);
        }
        NextDatePlan plan = NextDateRules.plan(pet, draftOf(record), others);
        if (plan.allowed() && !plan.locked() && plan.suggestion() != null) {
            record.setNextDate(plan.suggestion().nextDate());
        }
    }

    static RecordDraft draftOf(HealthRecord r) {
        return new RecordDraft(r.getId(), r.getType(), r.getVaccinePreset() == VaccinePreset.OTHER ? null
                : r.getVaccinePreset(), r.getVaccineName(), r.getDose(), r.getDewormType(),
                r.getProductDurationMonths(), r.getEventDate(), r.getCreatedAt());
    }
}
