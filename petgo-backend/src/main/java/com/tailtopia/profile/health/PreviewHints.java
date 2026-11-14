package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.VaccinePreset;
import java.util.ArrayList;
import java.util.List;

/**
 * 表单提示判定（V1.5.0 Story 1.2，架构增量 §6.1，PRD FR-159 / FR-160 / FR-171 helper text）。纯函数，顺序固定。
 * eventDate 为空时只给与日期无关的提示。
 */
public final class PreviewHints {

    private PreviewHints() {
    }

    public static List<PreviewHint> of(PetHealthSnapshot pet, RecordDraft draft, List<HealthRecordSnapshot> history) {
        List<PreviewHint> out = new ArrayList<>();
        HealthRecordType type = draft.type();
        DewormType deworm = draft.dewormType();
        boolean isVaccine = type == HealthRecordType.VACCINE;
        boolean isDeworm = type == HealthRecordType.DEWORM;
        boolean riskyDeworm = isDeworm && (deworm == DewormType.EXTERNAL || deworm == DewormType.FLEA_WORM);
        if (isVaccine && pet.pregnantNursing()) {
            out.add(PreviewHint.VACCINE_PREGNANT);
        }
        if (isVaccine && draft.vaccinePreset() == VaccinePreset.RABIES) {
            out.add(PreviewHint.RABIES_YEARLY);
        }
        if (isDeworm && draft.eventDate() != null
                && (riskyDeworm || deworm == DewormType.HEARTWORM)
                && HealthDates.isUnderWeeks(pet.birthday(), draft.eventDate(), 8)) {
            out.add(PreviewHint.DEWORM_UNDER_8_WEEKS);
        }
        if (isDeworm && pet.pregnantNursing()) {
            out.add(PreviewHint.DEWORM_PREGNANT);
        }
        if (riskyDeworm && pet.isDog() && pet.hasCatInHome()) {
            out.add(PreviewHint.PERMETHRIN);
        }
        if (isDeworm && deworm == DewormType.HEARTWORM && pet.isDog() && draft.eventDate() != null
                && !HealthDates.isYoung(pet.birthday(), draft.eventDate())
                && noOtherHeartworm(history, draft.recordId())) {
            out.add(PreviewHint.HEARTWORM_TEST);
        }
        if (type == HealthRecordType.MENSTRUATION && pet.isNeutered()) {
            out.add(PreviewHint.HEAT_NEUTERED);
        }
        return out;
    }

    private static boolean noOtherHeartworm(List<HealthRecordSnapshot> history, Long recordId) {
        return history.stream().noneMatch(r -> r.type() == HealthRecordType.DEWORM
                && r.dewormType() == DewormType.HEARTWORM && (recordId == null || r.id() != recordId));
    }
}
