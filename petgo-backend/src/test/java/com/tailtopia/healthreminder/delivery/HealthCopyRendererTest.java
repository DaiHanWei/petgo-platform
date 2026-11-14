package com.tailtopia.healthreminder.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.healthreminder.domain.HealthNotification;
import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.schedule.SendGroup;
import com.tailtopia.profile.domain.DewormType;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetStatus;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.domain.VaccinePreset;
import com.tailtopia.profile.health.PetHealthSnapshot;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.context.support.ReloadableResourceBundleMessageSource;

/** L0（V1.5.0 Story 2.4 · AC1 / AC2）：PRD 附录 A 文案渲染，AC37 / AC56 / AC57 / AC63。 */
class HealthCopyRendererTest {

    private static final Locale ID = Locale.forLanguageTag("id");
    private static final Locale EN = Locale.ENGLISH;
    private static final LocalDate SEND = LocalDate.parse("2027-03-01");
    private static final PetHealthSnapshot CAT = pet(PetType.CAT, PetSex.FEMALE, "2022-01-01");
    private static final PetHealthSnapshot DOG = pet(PetType.DOG, PetSex.MALE, "2022-01-01");
    private static final Function<Long, LocalDate> NO_DATES = id -> null;

    private final HealthCopyRenderer renderer = new HealthCopyRenderer(messages());

    private static ReloadableResourceBundleMessageSource messages() {
        ReloadableResourceBundleMessageSource m = new ReloadableResourceBundleMessageSource();
        m.setBasename("classpath:i18n/messages");
        m.setDefaultEncoding(StandardCharsets.UTF_8.name());
        m.setFallbackToSystemLocale(false);
        return m;
    }

    private static PetHealthSnapshot pet(PetType type, PetSex sex, String birthday) {
        return new PetHealthSnapshot(1L, 7L, type, sex, LocalDate.parse(birthday), null, null, false,
                LocalDate.parse("2025-01-01"), PetStatus.ACTIVE);
    }

    private static HealthReminder row(ReminderKind kind, PushPoint point, String next, long source) {
        HealthReminder r = HealthReminder.pending(1L, 7L, source, kind, point, ReminderOrigin.RECORD,
                next == null ? null : LocalDate.parse(next), SEND);
        return r;
    }

    private static PushPoint pointOf(ReminderKind k) {
        String n = k.name();
        if (n.endsWith("D_MINUS_7")) {
            return PushPoint.D_MINUS_7;
        }
        if (n.endsWith("D_MINUS_1")) {
            return PushPoint.D_MINUS_1;
        }
        if (n.endsWith("D_PLUS_7")) {
            return PushPoint.D_PLUS_7;
        }
        if (n.endsWith("D_PLUS_3")) {
            return PushPoint.D_PLUS_3;
        }
        return n.endsWith("D_DAY") ? PushPoint.D_DAY : PushPoint.NONE;
    }

    private RenderedCopy render(PetHealthSnapshot pet, Locale locale, Function<Long, LocalDate> dates,
            HealthReminder... rows) {
        return renderer.render(new SendGroup(1L, 7L, SEND, rows[0].getPushPoint(), List.of(rows)), pet, "Mochi",
                locale, dates);
    }

    @ParameterizedTest(name = "AC57 {0} 渲染完整（ID / EN）")
    @EnumSource(ReminderKind.class)
    void ac57EveryKindRendersWithoutLeftoverPlaceholders(ReminderKind kind) {
        HealthReminder r = row(kind, pointOf(kind), "2027-02-22", 5L);
        r.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", 2, DewormType.INTERNAL, "Grooming");
        for (PetHealthSnapshot pet : List.of(CAT, DOG)) {
            for (Locale l : List.of(ID, EN)) {
                RenderedCopy c = render(pet, l, id -> LocalDate.parse("2026-12-01"), r);
                assertThat(c.title()).doesNotContain("{").doesNotContain("}").isNotBlank();
                assertThat(c.body()).doesNotContain("{").doesNotContain("}").isNotBlank();
                assertThat(c.notificationKind()).isEqualTo(kind);
            }
        }
    }

    @Test
    void ac56SeriesDDayVsGenericBooster() {
        HealthReminder series = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", 5L);
        series.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", 2, null, null);
        assertThat(render(CAT, ID, NO_DATES, series).title()).isEqualTo("Dosis ke-2 untuk Mochi hari ini 💉");
        HealthReminder booster = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", 6L);
        booster.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", null, null, null);
        assertThat(render(CAT, ID, NO_DATES, booster).title()).isEqualTo("Hari ini hari vaksin Mochi! 💪");
        assertThat(render(CAT, EN, NO_DATES, booster).title()).isEqualTo("It's vaccine day for Mochi! 💪");
    }

    @Test
    void ac57SingularDayAndWeek() {
        HealthReminder catchUp = HealthReminder.pending(1L, 7L, 5L, ReminderKind.VACCINE_D_PLUS_7,
                PushPoint.RESUME_CATCH_UP, ReminderOrigin.RESUME_CATCH_UP, LocalDate.parse("2027-02-28"), SEND);
        assertThat(render(CAT, EN, NO_DATES, catchUp).body()).startsWith("It's 1 day late, but not too late!");
        assertThat(render(CAT, ID, NO_DATES, catchUp).body()).startsWith("Sudah lewat 1 hari");
        HealthReminder internal = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", 9L);
        internal.setSnapshot(null, null, null, DewormType.INTERNAL, null);
        assertThat(render(CAT, EN, id -> SEND.minusDays(10), internal).body())
                .startsWith("It's been 1 week since the last one.");
        assertThat(render(CAT, EN, id -> SEND.minusDays(92), internal).body())
                .startsWith("It's been 3 months since the last one.");
        assertThat(render(CAT, ID, id -> SEND.minusDays(55), internal).body()).startsWith("Sudah 7 minggu");
    }

    @Test
    void rabiesD7AndMovedHariLagiAndMergedVaccineNames() {
        HealthReminder rabies = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-07", 5L);
        rabies.setSnapshot(VaccinePreset.RABIES, "Rabies", null, null, null);
        assertThat(render(DOG, ID, NO_DATES, rabies).title()).isEqualTo("6 hari lagi Mochi vaksin rabies 🛡️");
        HealthReminder f4 = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", 6L);
        f4.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", null, null, null);
        HealthReminder felv = row(ReminderKind.VACCINE_D_MINUS_7, PushPoint.D_MINUS_7, "2027-03-08", 7L);
        felv.setSnapshot(VaccinePreset.FELV, "FeLV", null, null, null);
        RenderedCopy merged = render(CAT, EN, NO_DATES, f4, felv);
        assertThat(merged.body()).isEqualTo("Mochi's F4 (Tetracat) & FeLV vaccine is due next week. "
                + "Book the vet and record it in TailTopia!");
        assertThat(merged.route()).isEqualTo(HealthNotification.Route.PAGE);
    }

    @Test
    void ac37MergedVaccineDDayUsesGenericCopy() {
        HealthReminder a = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", 5L);
        a.setSnapshot(VaccinePreset.F4, "F4 (Tetracat)", 2, null, null);
        HealthReminder b = row(ReminderKind.VACCINE_D_DAY, PushPoint.D_DAY, "2027-03-01", 6L);
        b.setSnapshot(VaccinePreset.RABIES, "Rabies", null, null, null);
        RenderedCopy c = render(CAT, ID, NO_DATES, a, b);
        assertThat(c.title()).isEqualTo("Hari ini hari vaksin Mochi! 💪");
        assertThat(c.notificationKind()).isEqualTo(ReminderKind.VACCINE_D_DAY);
        assertThat(c.route()).isEqualTo(HealthNotification.Route.PAGE);
    }

    @Test
    void ac63MergedDewormKindAndCopy() {
        HealthReminder internal = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", 5L);
        internal.setSnapshot(null, null, null, DewormType.INTERNAL, null);
        HealthReminder external = row(ReminderKind.DEWORM_EXTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", 6L);
        external.setSnapshot(null, null, null, DewormType.EXTERNAL, null);
        RenderedCopy c = render(DOG, ID, NO_DATES, internal, external);
        assertThat(c.notificationKind()).isEqualTo(ReminderKind.DEWORM_FLEA_WORM_D_DAY);
        assertThat(c.title()).isEqualTo("Satu langkah, Mochi bebas parasit 🛡️");
        HealthReminder plus3a = row(ReminderKind.DEWORM_INTERNAL_D_PLUS_3, PushPoint.D_PLUS_3, "2027-02-26", 5L);
        plus3a.setSnapshot(null, null, null, DewormType.HEARTWORM, null);
        HealthReminder plus3b = row(ReminderKind.DEWORM_LEGACY_D_PLUS_3, PushPoint.D_PLUS_3, "2027-02-26", 6L);
        assertThat(HealthCopyRenderer.notificationKind(List.of(plus3a, plus3b)))
                .isEqualTo(ReminderKind.DEWORM_INTERNAL_D_PLUS_3);
    }

    @Test
    void youngInternalAndDogExternalD3AndSpayBySexAndSeniorCheckup() {
        var kitten = pet(PetType.CAT, PetSex.FEMALE, "2026-12-01");
        HealthReminder internal = row(ReminderKind.DEWORM_INTERNAL_D_DAY, PushPoint.D_DAY, "2027-03-01", 5L);
        assertThat(render(kitten, EN, NO_DATES, internal).title()).isEqualTo("Little Mochi needs a dewormer today 🍼");
        assertThat(render(kitten, ID, NO_DATES, internal).body()).startsWith("Anak kucing");
        HealthReminder ext3 = row(ReminderKind.DEWORM_EXTERNAL_D_PLUS_3, PushPoint.D_PLUS_3, "2027-02-26", 5L);
        assertThat(render(DOG, EN, NO_DATES, ext3).title()).startsWith("Watch out, ticks");
        assertThat(render(CAT, EN, NO_DATES, ext3).title()).isEqualTo("Has Mochi had flea treatment yet? 🤔");
        HealthReminder spay = HealthReminder.pending(1L, 7L, null, ReminderKind.SPAY_NEUTER_SUGGESTION, PushPoint.NONE,
                ReminderOrigin.PROFILE, null, SEND);
        assertThat(render(DOG, EN, NO_DATES, spay).title()).startsWith("Time to think about neutering");
        assertThat(render(CAT, EN, NO_DATES, spay).title()).startsWith("Mochi is old enough to be spayed");
        HealthReminder checkup = HealthReminder.pending(1L, 7L, null, ReminderKind.CHECKUP, PushPoint.NONE,
                ReminderOrigin.PROFILE, null, SEND);
        var seniorDog = pet(PetType.DOG, PetSex.MALE, "2019-01-01");
        assertThat(render(seniorDog, EN, NO_DATES, checkup).title()).startsWith("Mochi is a senior now");
        assertThat(render(DOG, EN, NO_DATES, checkup).title()).startsWith("Check-up time for Mochi");
    }

    @Test
    void missingNameFallsBackAndCustomNamesJoined() {
        HealthReminder a = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", 5L);
        a.setSnapshot(null, null, null, null, "Grooming\n");
        HealthReminder b = row(ReminderKind.CUSTOM_D_DAY, PushPoint.D_DAY, "2027-03-01", 6L);
        b.setSnapshot(null, null, null, null, "Potong kuku");
        RenderedCopy c = renderer.render(new SendGroup(1L, 7L, SEND, PushPoint.D_DAY, List.of(a, b)), CAT, null, EN,
                NO_DATES);
        assertThat(c.title()).isEqualTo("Reminder for your pet: Grooming & Potong kuku ⏰");
        assertThat(renderer.render(new SendGroup(1L, 7L, SEND, PushPoint.D_DAY, List.of(a)), CAT, " ", ID, NO_DATES)
                .title()).startsWith("Pengingat untuk hewanmu");
    }

    @Test
    void intervalRounding() {
        assertThat(HealthCopyRenderer.intervalArgs(3)).containsExactly(1L, 0);
        assertThat(HealthCopyRenderer.intervalArgs(55)).containsExactly(7L, 1);
        assertThat(HealthCopyRenderer.intervalArgs(56)).containsExactly(2L, 3);
        assertThat(HealthCopyRenderer.intervalArgs(31)).containsExactly(4L, 1);
        assertThat(Map.of()).isEmpty();
    }
}
