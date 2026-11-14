package com.tailtopia.healthreminder.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.healthreminder.domain.HealthReminder;
import com.tailtopia.healthreminder.domain.PushPoint;
import com.tailtopia.healthreminder.domain.ReminderKind;
import com.tailtopia.healthreminder.domain.ReminderOrigin;
import com.tailtopia.healthreminder.domain.ReminderStatus;
import com.tailtopia.healthreminder.repository.HealthReminderRepository;
import com.tailtopia.healthreminder.rules.PetReminderContext;
import com.tailtopia.healthreminder.rules.ReminderHistory;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSex;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.health.PetHealthSnapshot;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.support.PetFixtures;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.5.0 Story 2.2 · AC6）：出队时间线，PRD AC28 / AC43；暂停、起始日、3 天间隔、当天已有推送。 */
class LaunchQueuePickTest {

    private static final long USER = 7L;
    private static final LocalDate L = LocalDate.parse("2026-12-01");

    private final UserLaunchDao userLaunch = mock(UserLaunchDao.class);
    private final PetProfileRepository pets = mock(PetProfileRepository.class);
    private final HealthReminderRepository reminders = mock(HealthReminderRepository.class);
    private final PetContextLoader contexts = mock(PetContextLoader.class);
    private final LaunchQueueService service = new LaunchQueueService(userLaunch, pets, reminders, contexts,
            mock(HealthReminderAnalytics.class), mock(HealthReminderLock.class));

    private final List<HealthReminder> rows = new ArrayList<>();
    private final AtomicReference<LocalDate> lastSent = new AtomicReference<>();
    private PetProfile dog;
    private PetProfile kitten;

    @BeforeEach
    void setUp() {
        dog = PetFixtures.pet(USER, 2L, PetType.DOG, Instant.parse("2024-12-01T00:00:00Z"));
        dog.setBirthday(LocalDate.parse("2023-12-01"));
        dog.setSex(PetSex.MALE);
        kitten = PetFixtures.pet(USER, 3L, PetType.CAT, Instant.parse("2026-11-01T00:00:00Z"));
        kitten.setBirthday(LocalDate.parse("2026-08-01"));
        kitten.setSex(PetSex.FEMALE);
        when(pets.findAllByOwnerIdOrderByCreatedAtAscIdAsc(USER)).thenReturn(List.of(dog, kitten));
        when(pets.findById(2L)).thenReturn(Optional.of(dog));
        when(pets.findById(3L)).thenReturn(Optional.of(kitten));
        when(contexts.load(any())).thenAnswer(inv -> {
            PetProfile p = inv.getArgument(0);
            return new PetReminderContext(PetHealthSnapshot.of(p), List.of(), ReminderHistory.EMPTY, null);
        });
        when(reminders.findByPetProfileIdAndStatus(anyLong(), eq(ReminderStatus.PENDING))).thenAnswer(inv -> {
            long petId = inv.getArgument(0);
            return rows.stream().filter(r -> r.getPetProfileId() == petId
                    && r.getStatus() == ReminderStatus.PENDING).toList();
        });
        when(userLaunch.find(USER)).thenAnswer(inv -> Optional.of(new UserLaunchDao.UserLaunch(USER, L,
                L.plusDays(2), lastSent.get())));
        queue(3L, ReminderKind.FIRST_VACCINE, 1);
        queue(2L, ReminderKind.FIRST_DEWORM, 2);
        queue(3L, ReminderKind.FIRST_DEWORM, 3);
        queue(2L, ReminderKind.CHECKUP, 4);
    }

    private void queue(long petId, ReminderKind kind, int position) {
        HealthReminder r = HealthReminder.pending(petId, USER, null, kind, PushPoint.NONE,
                ReminderOrigin.LAUNCH_QUEUE, null, L);
        r.moveSendDate(L.plusDays(2));
        r.setQueuePosition(position);
        ReflectionTestUtils.setField(r, "id", (long) position);
        rows.add(r);
    }

    private List<org.assertj.core.groups.Tuple> simulate(int days, int busyOffset, boolean paused) {
        List<org.assertj.core.groups.Tuple> sent = new ArrayList<>();
        for (int i = 0; i <= days; i++) {
            LocalDate day = L.plusDays(i);
            Optional<HealthReminder> picked = service.pickForUser(USER, day, i == busyOffset, paused);
            if (picked.isPresent()) {
                picked.get().markSent(1L, Instant.EPOCH);
                lastSent.set(day);
                sent.add(tuple(i, picked.get().getPetProfileId(), picked.get().getReminderKind()));
            }
        }
        return sent;
    }

    @Test
    void ac28TimelineOneEveryThreeDaysFromStartDay() {
        assertThat(simulate(12, -1, false)).containsExactly(
                tuple(2, 3L, ReminderKind.FIRST_VACCINE), tuple(5, 2L, ReminderKind.FIRST_DEWORM),
                tuple(8, 3L, ReminderKind.FIRST_DEWORM), tuple(11, 2L, ReminderKind.CHECKUP));
    }

    @Test
    void ac28BirthdayOnLaunchPlus8PushesTheRestByOneDay() {
        assertThat(simulate(13, 8, false)).containsExactly(
                tuple(2, 3L, ReminderKind.FIRST_VACCINE), tuple(5, 2L, ReminderKind.FIRST_DEWORM),
                tuple(9, 3L, ReminderKind.FIRST_DEWORM), tuple(12, 2L, ReminderKind.CHECKUP));
    }

    @Test
    void pausedNeverSendsButRowsStayPendingAndMove() {
        assertThat(simulate(6, -1, true)).isEmpty();
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getStatus()).isEqualTo(ReminderStatus.PENDING);
            assertThat(r.getSendDate()).isEqualTo(L.plusDays(7));
        });
    }

    @Test
    void recheckDropsItemAndNextMovesUp() {
        // 幼猫在排队期间记了疫苗 → 首针作废，同一天改发狗的首驱虫
        when(contexts.load(kitten)).thenReturn(new PetReminderContext(PetHealthSnapshot.of(kitten),
                List.of(new com.tailtopia.profile.health.HealthRecordSnapshot(9L,
                        com.tailtopia.profile.domain.HealthRecordType.VACCINE, null, null, null, null, null,
                        L, null, null, com.tailtopia.profile.domain.RecordOrigin.APP, Instant.EPOCH)),
                ReminderHistory.EMPTY, null));
        Optional<HealthReminder> picked = service.pickForUser(USER, L.plusDays(2), false, false);
        assertThat(picked).get().extracting(HealthReminder::getReminderKind).isEqualTo(ReminderKind.FIRST_DEWORM);
        assertThat(rows.get(0).getStatus()).isEqualTo(ReminderStatus.CANCELLED);
    }

    @Test
    void ac43LateUpdaterGetsPersonalQueueSpacedThreeDays() {
        assertThat(LaunchQueueService.recentlySent(new UserLaunchDao.UserLaunch(USER, L, L, L), L.plusDays(2)))
                .isTrue();
        assertThat(LaunchQueueService.recentlySent(new UserLaunchDao.UserLaunch(USER, L, L, L), L.plusDays(3)))
                .isFalse();
    }
}
