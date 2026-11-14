package com.tailtopia.healthreminder.rules;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.NeuterStatus;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.RecordOrigin;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.HealthRecordSnapshot;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.time.Instant;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.test.util.ReflectionTestUtils;

/** L0 夹具（V1.5.0 Story 2.x 提醒规则测试共用）。 */
final class RuleFixtures {

    private static final AtomicLong IDS = new AtomicLong(1000);

    private RuleFixtures() {
    }

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    static PetHealthSnapshot pet(PetType type, PetSex sex, String birthday, NeuterStatus neuter) {
        return new PetHealthSnapshot(1L, 7L, type, sex, birthday == null ? null : d(birthday), neuter, null, false,
                d("2025-01-01"), PetStatus.ACTIVE);
    }

    static PetHealthSnapshot readOnly(PetHealthSnapshot p) {
        return new PetHealthSnapshot(p.petId(), p.ownerId(), p.petType(), p.sex(), p.birthday(), p.neuterStatus(),
                p.catInHome(), p.pregnantNursing(), p.createdDate(), PetStatus.READ_ONLY);
    }

    static HealthRecordSnapshot rec(HealthRecordType type, VaccinePreset preset, DewormType deworm, String event,
            String next, RecordOrigin origin) {
        long id = IDS.incrementAndGet();
        return new HealthRecordSnapshot(id, type, preset, preset == null ? null : preset.label(), null, deworm, null,
                d(event), next == null ? null : d(next), type == HealthRecordType.CUSTOM ? "Grooming" : null, origin,
                Instant.ofEpochSecond(id));
    }

    static HealthRecordSnapshot vaccine(VaccinePreset preset, Integer dose, String event, String next) {
        HealthRecordSnapshot r = rec(HealthRecordType.VACCINE, preset, null, event, next, RecordOrigin.APP);
        return new HealthRecordSnapshot(r.id(), r.type(), preset, r.vaccineName(), dose, null, null, r.eventDate(),
                r.nextDate(), null, r.origin(), r.createdAt());
    }

    static HealthRecordSnapshot deworm(DewormType type, String event, String next) {
        return rec(HealthRecordType.DEWORM, null, type, event, next, RecordOrigin.APP);
    }

    static HealthRecordSnapshot of(HealthRecordType type, String event, String next) {
        return rec(type, null, null, event, next, RecordOrigin.APP);
    }

    static HealthRecordSnapshot backfill(HealthRecordSnapshot r) {
        return new HealthRecordSnapshot(r.id(), r.type(), r.vaccinePreset(), r.vaccineName(), r.dose(),
                r.dewormType(), r.durationMonths(), r.eventDate(), r.nextDate(), r.customName(),
                RecordOrigin.BACKFILL, r.createdAt());
    }

    /** 一条带 id 的提醒行（不经 JPA）。 */
    static HealthReminder row(Long source, ReminderKind kind, PushPoint point, String due, ReminderOrigin origin) {
        HealthReminder r = HealthReminder.pending(1L, 7L, source, kind, point, origin, null, d(due));
        ReflectionTestUtils.setField(r, "id", IDS.incrementAndGet());
        return r;
    }
}
