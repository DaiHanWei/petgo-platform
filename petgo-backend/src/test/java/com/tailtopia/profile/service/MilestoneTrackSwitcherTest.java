package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.MilestoneCatalog;
import com.tailtopia.profile.domain.MilestoneCompletion;
import com.tailtopia.profile.domain.MilestoneCompletionSource;
import com.tailtopia.profile.domain.MilestoneDefinition;
import com.tailtopia.profile.domain.PetMilestone;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.MilestoneCompletionRepository;
import com.tailtopia.profile.repository.PetMilestoneRepository;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

/**
 * L0：V1.5.0 pet-species Story 1.2 · AC6 —— 退役不删、复活、新建、按事实补记（ofCelebrated、不发事件）、
 * 打卡类不补、来回改、同 track 直接返回、track 改在补账号 flag 之前。
 */
class MilestoneTrackSwitcherTest {

    private static final Instant NOW = Instant.parse("2026-11-24T12:05:00Z");
    private static final Instant DONE_AT = Instant.parse("2026-01-02T00:00:00Z");

    private PetMilestoneRepository milestones;
    private MilestoneCompletionRepository completions;
    private AccountMilestoneService accountMilestones;
    private MilestoneTrackSwitcher switcher;

    /** 内存 roster / completions。 */
    private final List<PetMilestone> rows = new ArrayList<>();
    private final List<MilestoneCompletion> done = new ArrayList<>();
    private final AtomicLong seq = new AtomicLong(1);

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        milestones = mock(PetMilestoneRepository.class);
        completions = mock(MilestoneCompletionRepository.class);
        accountMilestones = mock(AccountMilestoneService.class);
        switcher = new MilestoneTrackSwitcher(milestones, completions, accountMilestones,
                Clock.fixed(NOW, ZoneOffset.UTC));
        when(milestones.findByPetProfileId(10L)).thenAnswer(inv -> new ArrayList<>(rows));
        when(milestones.saveAll(anyList())).thenAnswer(inv -> {
            List<PetMilestone> in = inv.getArgument(0);
            for (PetMilestone m : in) {
                if (m.getId() == null) {
                    setId(m, seq.getAndIncrement());
                    rows.add(m);
                }
            }
            return in;
        });
        when(completions.findByPetMilestoneIdIn(anyCollection())).thenAnswer(inv -> {
            Collection<Long> ids = inv.getArgument(0);
            return done.stream().filter(c -> ids.contains(c.getPetMilestoneId())).toList();
        });
        when(completions.saveAll(anyList())).thenAnswer(inv -> {
            done.addAll(inv.getArgument(0));
            return inv.getArgument(0);
        });
    }

    private static void setId(Object o, long id) {
        try {
            Field f = o.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(o, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private PetProfile catWithCRoster() {
        PetProfile p = PetProfile.create(7L, PetType.CAT, "Momo", null, null, LocalDate.of(2022, 1, 1), null, "T");
        setId(p, 10L);
        for (MilestoneDefinition def : MilestoneCatalog.forType(PetType.CAT)) {
            PetMilestone m = PetMilestone.of(10L, def);
            setId(m, seq.getAndIncrement());
            rows.add(m);
        }
        complete("C-S1"); // PROFILE_CREATED
        complete("C-S2"); // GROWTH_MOMENT_FIRST
        complete("C-S6"); // 打卡类（无语义对应）
        return p;
    }

    private void complete(String code) {
        done.add(MilestoneCompletion.ofCelebrated(row(code).getId(), MilestoneCompletionSource.SYSTEM_AUTO, DONE_AT));
    }

    private PetMilestone row(String code) {
        return rows.stream().filter(m -> m.getCode().equals(code)).findFirst().orElseThrow();
    }

    private boolean isDone(String code) {
        long id = row(code).getId();
        return done.stream().anyMatch(c -> c.getPetMilestoneId() == id);
    }

    @Test
    void catToOtherRetiresCRowsCreatesGAndCarriesFacts() {
        PetProfile p = catWithCRoster();
        int cDone = done.size();

        switcher.switchTo(p, PetType.OTHER);

        assertThat(p.getMilestoneTrack()).isEqualTo(PetType.OTHER);
        assertThat(rows.stream().filter(m -> m.getCode().startsWith("C-"))).allMatch(m -> !m.isActive());
        List<String> active = rows.stream().filter(PetMilestone::isActive).map(PetMilestone::getCode).toList();
        assertThat(active).containsExactlyInAnyOrderElementsOf(
                MilestoneCatalog.forType(PetType.OTHER).stream().map(MilestoneDefinition::code).toList());
        // 补记：G-S1 / G-S2 已完成且已庆祝，时间沿用旧完成时间；打卡类不补。
        assertThat(isDone("G-S1")).isTrue();
        assertThat(isDone("G-S2")).isTrue();
        assertThat(done).hasSize(cDone + 2);
        assertThat(done.subList(cDone, done.size())).allSatisfy(c -> {
            assertThat(c.getCompletedAt()).isEqualTo(DONE_AT);
            assertThat(c.getCelebratedAt()).isEqualTo(DONE_AT);
        });
        // 旧完成记录不删。
        assertThat(isDone("C-S1")).isTrue();
        verify(completions, never()).deleteByPetMilestoneIdIn(anyCollection());
        verify(milestones, never()).deleteByPetProfileId(org.mockito.ArgumentMatchers.anyLong());
        verify(accountMilestones).applyExistingFlagsToPet(p);
    }

    @Test
    void roundTripRevivesCRowsWithoutDuplicates() {
        PetProfile p = catWithCRoster();
        switcher.switchTo(p, PetType.OTHER);
        int afterFirst = done.size();
        int rowCount = rows.size();

        switcher.switchTo(p, PetType.CAT);

        assertThat(p.getMilestoneTrack()).isEqualTo(PetType.CAT);
        assertThat(rows).hasSize(rowCount); // 不新建：C 行复活
        assertThat(rows.stream().filter(m -> m.getCode().startsWith("C-"))).allMatch(PetMilestone::isActive);
        assertThat(rows.stream().filter(m -> m.getCode().startsWith("G-"))).allMatch(m -> !m.isActive());
        assertThat(done).hasSize(afterFirst); // C-S1 / C-S2 本就完成，不重复补
        assertThat(isDone("C-S6")).isTrue();
    }

    @Test
    void sameTrackReturnsImmediately() {
        PetProfile p = catWithCRoster();
        switcher.switchTo(p, PetType.CAT);
        verify(milestones, never()).findByPetProfileId(10L);
        verifyNoInteractions(accountMilestones);
    }

    @Test
    void trackIsSetBeforeAccountFlagsApplied() {
        PetProfile p = catWithCRoster();
        PetType[] seen = new PetType[1];
        org.mockito.Mockito.doAnswer(inv -> {
            seen[0] = ((PetProfile) inv.getArgument(0)).getMilestoneTrack();
            return null;
        }).when(accountMilestones).applyExistingFlagsToPet(p);
        switcher.switchTo(p, PetType.DOG);
        assertThat(seen[0]).isEqualTo(PetType.DOG);
        InOrder o = inOrder(milestones, accountMilestones);
        o.verify(milestones).saveAll(anyList());
        o.verify(accountMilestones).applyExistingFlagsToPet(p);
        ArgumentCaptor<List<MilestoneCompletion>> cap = ArgumentCaptor.forClass(List.class);
        verify(completions).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2);
    }

    @Test
    void healthComboCarriedWhenAllPrereqsCarried() {
        PetProfile p = catWithCRoster();
        complete("C-M3");
        complete("C-M4");
        complete("C-M5");
        complete("C-L4");
        switcher.switchTo(p, PetType.DOG);
        assertThat(isDone("D-M3")).isTrue();
        assertThat(isDone("D-M4")).isTrue();
        assertThat(isDone("D-M5")).isTrue();
        assertThat(isDone("D-L4")).isTrue(); // 组合节点按事实补记
    }
}
