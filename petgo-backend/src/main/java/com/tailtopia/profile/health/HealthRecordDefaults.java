package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import java.time.LocalDate;
import java.util.List;

/**
 * 旧版客户端（请求头 &lt; 1.5.0 或缺头）写健康记录时的默认值（V1.5.0 健康记录提醒，架构增量 §6.1「旧版客户端」，
 * PRD FR-168 Old app versions）。
 *
 * <p>旧版 App 不认识新字段：<b>新建</b>（Story 1.2）时疫苗名走 {@link VaccineKeywordMatcher}、按 {@link NextDateRules}
 * 写默认 Next date（无类型驱虫 = Legacy +3 个月）；<b>编辑</b>（Story 1.3）按「改了疫苗名 → 重匹配、清剂次、
 * 按新预设重算 Next date；改了日期使 next_date ≤ eventDate → 重算」处理。其余情况新字段一律不动（PRD AC54）。
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
        boolean renamedVaccine = change.vaccineNameChanged() && record.getType() == HealthRecordType.VACCINE;
        if (pet.isCatOrDog() && (change.created() || renamedVaccine)) {
            // 新建，或旧版改了疫苗名（AC54 例外一）：关键词匹配预设、清剂次，按默认值写 Next date；
            // 没有默认值时保留原值（PRD：旧版编辑从不清空新字段）。OTHER 物种不存预设、永不预填（FR-158）。
            if (record.getType() == HealthRecordType.VACCINE) {
                record.setVaccinePreset(VaccineKeywordMatcher.match(record.getVaccineName(), pet.petType()));
                record.setDose(null);
            }
            LocalDate next = defaultNextDate(pet, record, others);
            if (next != null) {
                record.setNextDate(next);
            }
        }
        // AC54 例外二：新 event date 让已存 Next date 失效（≤ event date）→ 按当前字段重算；没有默认值就清掉
        // （失效值不能留）。任何物种、含同时改名的编辑都要过这一道。
        LocalDate stored = record.getNextDate();
        if (!change.created() && stored != null && !stored.isAfter(record.getEventDate())) {
            record.setNextDate(pet.isCatOrDog() ? defaultNextDate(pet, record, others) : null);
        }
    }

    private static LocalDate defaultNextDate(PetHealthSnapshot pet, HealthRecord record,
            List<HealthRecordSnapshot> others) {
        return NextDateRules.defaultNextDate(pet, draftOf(record), others);
    }

    static RecordDraft draftOf(HealthRecord r) {
        return RecordDraft.of(HealthRecordSnapshot.of(r), null);
    }
}
