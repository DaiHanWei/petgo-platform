package com.tailtopia.healthreminder.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.repository.HealthNotificationRepository;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.notify.service.NotificationCenterService;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.HealthRecord;
import com.tailtopia.profile.domain.HealthRecordType;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.repository.HealthRecordRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.5.0 Story 2.4 · AC5）：点开落点，PRD AC12 / AC46 ~ AC49。 */
class HealthNotificationOpenServiceTest {

    private static final long USER = 7L;
    private static final LocalDate DAY = LocalDate.parse("2027-03-01");

    private final HealthNotificationRepository notifications = mock(HealthNotificationRepository.class);
    private final HealthReminderRepository reminders = mock(HealthReminderRepository.class);
    private final PetProfileRepository pets = mock(PetProfileRepository.class);
    private final HealthRecordRepository records = mock(HealthRecordRepository.class);
    private final NotificationCenterService center = mock(NotificationCenterService.class);
    private final HealthNotificationOpenService service = new HealthNotificationOpenService(notifications, reminders,
            pets, records, center);

    private PetProfile pet(long id, PetType type, PetSex sex) {
        PetProfile p = PetFixtures.pet(USER, id, type, Instant.EPOCH);
        p.setSex(sex);
        when(pets.findById(id)).thenReturn(Optional.of(p));
        return p;
    }

    private HealthNotification notification(String token, long petId, ReminderKind kind, PushPoint point,
            HealthNotification.Route route, HealthReminder... rows) {
        HealthNotification n = HealthNotification.create(token, USER, petId, kind, point, rows[0].getOrigin(),
                rows.length, 0, route, DAY);
        ReflectionTestUtils.setField(n, "id", 50L);
        n.recordDelivery(HealthNotification.PushResult.SENT, 900L);
        when(notifications.findByPublicToken(token)).thenReturn(Optional.of(n));
        when(reminders.findByHealthNotificationId(50L)).thenReturn(List.of(rows));
        return n;
    }

    private static HealthReminder row(long petId, ReminderKind kind, PushPoint point, Long source, String next) {
        return HealthReminder.pending(petId, USER, source, kind, point,
                source == null ? ReminderOrigin.PROFILE : ReminderOrigin.RECORD,
                next == null ? null : LocalDate.parse(next), DAY);
    }

    private static HealthRecord record(long id, long petId, HealthRecordType type, String event) {
        HealthRecord r = HealthRecord.create(petId, type, null, null, LocalDate.parse(event), null);
        ReflectionTestUtils.setField(r, "id", id);
        ReflectionTestUtils.setField(r, "createdAt", Instant.parse(event + "T01:00:00Z"));
        return r;
    }

    @Test
    void ac46DDayOpensFormWithPrefillForThatPetAndMarksInboxRead() {
        pet(20L, PetType.CAT, PetSex.FEMALE);
        HealthReminder r = row(20L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, 11L, "2027-03-01");
        r.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", 2, null, null);
        notification("tokB", 20L, ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, HealthNotification.Route.FORM, r);
        HealthRecord rec = record(11L, 20L, HealthRecordType.VACCINE, "2027-02-01");
        rec.setVaccinePreset(VaccinePreset.F4);
        rec.setNextDate(LocalDate.parse("2027-03-01"));
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of(rec));
        var resp = service.open(USER, "tokB");
        assertThat(resp.status()).isEqualTo("OK");
        assertThat(resp.petId()).isEqualTo(20L);
        assertThat(resp.route()).isEqualTo("FORM");
        assertThat(resp.prefill().type()).isEqualTo("VACCINE");
        assertThat(resp.prefill().vaccinePreset()).isEqualTo("F4");
        assertThat(resp.prefill().dose()).isEqualTo(2);
        assertThat(resp.prefill().eventDateEmpty()).isFalse();
        assertThat(resp.analytics().notificationKind()).isEqualTo("VACCINE_D_DAY");
        assertThat(resp.analytics().pushPoint()).isEqualTo("d_day");
        assertThat(resp.analytics().origin()).isEqualTo("record");
        verify(center).markReadById(USER, 900L);
    }

    @Test
    void ac47DMinus7OpensPageFocused() {
        pet(20L, PetType.CAT, PetSex.FEMALE);
        HealthReminder r = row(20L, ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, 11L, "2027-03-08");
        notification("t", 20L, ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, HealthNotification.Route.PAGE, r);
        HealthRecord rec = record(11L, 20L, HealthRecordType.VACCINE, "2027-02-08");
        rec.setNextDate(LocalDate.parse("2027-03-08"));
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of(rec));
        var resp = service.open(USER, "t");
        assertThat(resp.route()).isEqualTo("PAGE");
        assertThat(resp.focusRecordId()).isEqualTo(11L);
        assertThat(resp.prefill()).isNull();
    }

    @Test
    void ac48AlreadyLoggedOpensSatisfiedFocusedOnNewerRecord() {
        pet(20L, PetType.DOG, PetSex.MALE);
        HealthReminder r = row(20L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, 11L, "2027-03-01");
        r.setSnapshot(null, null, null, DewormType.INTERNAL, null);
        notification("t", 20L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, HealthNotification.Route.FORM, r);
        HealthRecord old = record(11L, 20L, HealthRecordType.DEWORM, "2026-12-01");
        old.setDewormType(DewormType.INTERNAL);
        HealthRecord newer = record(12L, 20L, HealthRecordType.DEWORM, "2027-03-01");
        newer.setDewormType(DewormType.INTERNAL);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of(newer, old));
        var resp = service.open(USER, "t");
        assertThat(resp.route()).isEqualTo("PAGE_SATISFIED");
        assertThat(resp.focusRecordId()).isEqualTo(12L);
    }

    @Test
    void ac49DeletedOrReadOnlyPetUnavailableForeignToken404() {
        HealthReminder r = row(20L, ReminderKind.CHECKUP, PushPoint.NONE, null, null);
        notification("t", 20L, ReminderKind.CHECKUP, PushPoint.NONE, HealthNotification.Route.FORM, r);
        when(pets.findById(20L)).thenReturn(Optional.empty());
        assertThat(service.open(USER, "t").status()).isEqualTo("PET_UNAVAILABLE");
        PetProfile ro = pet(20L, PetType.CAT, PetSex.FEMALE);
        ro.markReadOnly();
        assertThat(service.open(USER, "t").status()).isEqualTo("PET_UNAVAILABLE");
        assertThatThrownBy(() -> service.open(99L, "t"))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        when(notifications.findByPublicToken("nope")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.open(USER, "nope")).isInstanceOf(AppException.class);
    }

    @Test
    void ac12SafetyBoxes() {
        // 家里有猫的狗 External D-Day → PAGE + PERMETHRIN
        PetProfile dog = pet(20L, PetType.DOG, PetSex.MALE);
        dog.setCatInHome(true);
        HealthReminder ext = row(20L, ReminderKind.DEWORM_EXTERNAL_D_DAY, PushPoint.D_DAY, 11L, "2027-03-01");
        ext.setSnapshot(null, null, null, DewormType.EXTERNAL, null);
        notification("a", 20L, ReminderKind.DEWORM_EXTERNAL_D_DAY, PushPoint.D_DAY, HealthNotification.Route.FORM, ext);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(20L)).thenReturn(List.of());
        var a = service.open(USER, "a");
        assertThat(a.route()).isEqualTo("PAGE");
        assertThat(a.safetyBoxes()).containsExactly("PERMETHRIN");
        // 同一只狗 Internal D-Day → 正常 FORM
        HealthReminder internal = row(20L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, 12L, "2027-03-01");
        internal.setSnapshot(null, null, null, DewormType.INTERNAL, null);
        notification("b", 20L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, HealthNotification.Route.FORM,
                internal);
        assertThat(service.open(USER, "b").route()).isEqualTo("FORM");
        // 怀孕的猫 Internal D-Day → PAGE + PREGNANT
        PetProfile cat = pet(21L, PetType.CAT, PetSex.FEMALE);
        cat.setPregnantNursing(true, Instant.now());
        HealthReminder catInternal = row(21L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, 13L, "2027-03-01");
        catInternal.setSnapshot(null, null, null, DewormType.INTERNAL, null);
        notification("c", 21L, ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, HealthNotification.Route.FORM,
                catInternal);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(21L)).thenReturn(List.of());
        assertThat(service.open(USER, "c").safetyBoxes()).containsExactly("PREGNANT");
        // 怀孕且家里有猫的母狗首驱虫 → 两个框，怀孕在前
        PetProfile bitch = pet(22L, PetType.DOG, PetSex.FEMALE);
        bitch.setCatInHome(true);
        bitch.setPregnantNursing(true, Instant.now());
        HealthReminder first = row(22L, ReminderKind.FIRST_DEWORM, PushPoint.NONE, null, null);
        notification("d", 22L, ReminderKind.FIRST_DEWORM, PushPoint.NONE, HealthNotification.Route.FORM, first);
        when(records.findByPetProfileIdOrderByEventDateDescIdDesc(22L)).thenReturn(List.of());
        assertThat(service.open(USER, "d").safetyBoxes()).containsExactly("PREGNANT", "PERMETHRIN");
    }

    @Test
    void suggestionsPrefillWithEmptyDate() {
        assertThat(HealthNotificationOpenService.prefill(row(20L, ReminderKind.CHECKUP, PushPoint.NONE, null, null)))
                .satisfies(p -> {
                    assertThat(p.type()).isEqualTo("VET_VISIT");
                    assertThat(p.eventDateEmpty()).isTrue();
                });
        assertThat(HealthNotificationOpenService.prefill(row(20L, ReminderKind.SPAY_NEUTER_SUGGESTION,
                PushPoint.NONE, null, null)).type()).isEqualTo("NEUTER");
        var legacy = HealthNotificationOpenService.prefill(row(20L, ReminderKind.DEWORM_LEGACY_D_DAY, PushPoint.D_DAY,
                11L, "2027-03-01"));
        assertThat(legacy.type()).isEqualTo("DEWORM");
        assertThat(legacy.dewormType()).isNull();
    }
}
