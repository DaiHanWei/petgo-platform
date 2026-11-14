package com.tailtopia.profile.health;

import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;

/** L0 夹具：造记录 / 宠物快照（V1.5.0 健康规则测试共用）。 */
final class HealthFixtures {

    private static final AtomicLong IDS = new AtomicLong(1);

    private HealthFixtures() {
    }

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static PetHealthSnapshot pet(PetType type, PetSex sex, LocalDate birthday, NeuterStatus neuter) {
        return new PetHealthSnapshot(1L, 7L, type, sex, birthday, neuter, null, false, d("2026-01-01"),
                PetStatus.ACTIVE);
    }

    static PetHealthSnapshot pregnant(PetHealthSnapshot p) {
        return new PetHealthSnapshot(p.petId(), p.ownerId(), p.petType(), p.sex(), p.birthday(), p.neuterStatus(),
                p.catInHome(), true, p.createdDate(), p.status());
    }

    static PetHealthSnapshot withCat(PetHealthSnapshot p) {
        return new PetHealthSnapshot(p.petId(), p.ownerId(), p.petType(), p.sex(), p.birthday(), p.neuterStatus(),
                true, p.pregnantNursing(), p.createdDate(), p.status());
    }

    static HealthRecordSnapshot rec(HealthRecordType type, String eventDate, String nextDate) {
        long id = IDS.incrementAndGet();
        return new HealthRecordSnapshot(id, type, null, null, null, null, null, d(eventDate),
                nextDate == null ? null : d(nextDate), null, RecordOrigin.APP, Instant.ofEpochSecond(id));
    }

    static HealthRecordSnapshot vaccine(VaccinePreset preset, String name, String eventDate, String nextDate) {
        HealthRecordSnapshot r = rec(HealthRecordType.VACCINE, eventDate, nextDate);
        return new HealthRecordSnapshot(r.id(), r.type(), preset, name, null, null, null, r.eventDate(),
                r.nextDate(), null, r.origin(), r.createdAt());
    }

    static HealthRecordSnapshot deworm(DewormType type, String eventDate, String nextDate) {
        HealthRecordSnapshot r = rec(HealthRecordType.DEWORM, eventDate, nextDate);
        return new HealthRecordSnapshot(r.id(), r.type(), null, null, null, type, null, r.eventDate(),
                r.nextDate(), null, r.origin(), r.createdAt());
    }

    static HealthRecordSnapshot backfill(HealthRecordSnapshot r) {
        return new HealthRecordSnapshot(r.id(), r.type(), r.vaccinePreset(), r.vaccineName(), r.dose(),
                r.dewormType(), r.durationMonths(), r.eventDate(), r.nextDate(), r.customName(),
                RecordOrigin.BACKFILL, r.createdAt());
    }

    static RecordDraft draft(HealthRecordType type, VaccinePreset preset, Integer dose, DewormType deworm,
            String eventDate) {
        return new RecordDraft(null, type, preset, null, dose, deworm, null,
                eventDate == null ? null : d(eventDate), null);
    }
}
