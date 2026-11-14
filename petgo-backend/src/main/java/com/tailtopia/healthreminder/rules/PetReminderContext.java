package com.tailtopia.healthreminder.rules;

import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.LocalDate;
import java.util.List;

/**
 * 档案类建议 / 上线排队需要的一只宠物的全部输入（V1.5.0 Story 2.2）。{@code lastConsultVisit} =
 * {@code health_vet_consult_visits} 里该宠物最近的到访日。
 */
public record PetReminderContext(PetHealthSnapshot pet, List<HealthRecordSnapshot> records, ReminderHistory history,
        LocalDate lastConsultVisit) {

    public boolean hasRecordOf(HealthRecordType type) {
        return records.stream().anyMatch(r -> r.type() == type);
    }

    /** 最近一次到访 = max(Vet visit 记录 event date, 兽医问诊到访日)；AI 分诊、疫苗不算。 */
    public LocalDate lastVisitDate() {
        LocalDate best = lastConsultVisit;
        for (HealthRecordSnapshot r : records) {
            if (r.type() == HealthRecordType.VET_VISIT && (best == null || r.eventDate().isAfter(best))) {
                best = r.eventDate();
            }
        }
        return best;
    }

    public CheckupDueRule.CheckupDue checkupDue() {
        return CheckupDueRule.due(pet, lastVisitDate(), history.lastCheckupCycleEnd());
    }
}
