package com.tailtopia.admin.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.event.PetSpeciesChangedEvent;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.profile.service.MilestoneTrackSwitcher;
import com.tailtopia.profile.service.PetSpeciesChangeLog;
import com.tailtopia.profile.service.PetWriteGuard;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：V1.5.0 pet-species Story 2.1 · AC3.3 —— 404 / 422 三种 / 只读 409 / 成功清品种、不写 pet_type_changed_at /
 * 大类变才切清单 / 修改记录 ADMIN / 审计 summary 不含原因 / 埋点 source=admin。
 */
class AdminPetSpeciesServiceTest {

    private static final long USER = 7L;
    private static final long PET = 10L;
    private static final long ADMIN = 99L;

    private PetProfileRepository profiles;
    private MilestoneTrackSwitcher switcher;
    private PetSpeciesChangeLog changeLog;
    private AdminAuditService audit;
    private ApplicationEventPublisher events;
    private AdminPetSpeciesService service;

    @BeforeEach
    void setUp() {
        profiles = mock(PetProfileRepository.class);
        switcher = mock(MilestoneTrackSwitcher.class);
        changeLog = mock(PetSpeciesChangeLog.class);
        audit = mock(AdminAuditService.class);
        events = mock(ApplicationEventPublisher.class);
        service = new AdminPetSpeciesService(profiles, new PetWriteGuard(profiles), switcher, changeLog, audit, events);
    }

    private PetProfile pet(PetSpecies species, long owner) {
        PetProfile p = PetProfile.create(owner, species, "Mochi", null, "Holland Lop", LocalDate.of(2025, 9, 12), null,
                "T");
        ReflectionTestUtils.setField(p, "id", PET);
        when(profiles.findByIdForUpdate(PET)).thenReturn(Optional.of(p));
        return p;
    }

    @Test
    void notOwnedIs404() {
        pet(PetSpecies.RABBIT, 8L);
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "HAMSTER", "x"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(404));
        verifyNoInteractions(changeLog, audit, events, switcher);
    }

    @Test
    void reasonRequiredTooLongInvalidSame() {
        pet(PetSpecies.RABBIT, USER);
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "HAMSTER", "   "))
                .isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("messageCode", "admin.err.petSpecies.reasonRequired");
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "HAMSTER", "x".repeat(201)))
                .isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("messageCode", "admin.err.petSpecies.reasonTooLong");
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "DRAGON", "x"))
                .isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("messageCode", "admin.err.petSpecies.invalid");
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "RABBIT", "x"))
                .isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("messageCode", "admin.err.petSpecies.same");
        verifyNoInteractions(changeLog, audit, events, switcher);
    }

    @Test
    void readOnlyPetRejectedWith409() {
        PetProfile p = pet(PetSpecies.RABBIT, USER);
        ReflectionTestUtils.setField(p, "status", com.tailtopia.profile.domain.PetStatus.READ_ONLY);
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "HAMSTER", "工单 1"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(409));
        // 只读先于原因校验：原因为空也是 409。
        assertThatThrownBy(() -> service.change(ADMIN, USER, PET, "HAMSTER", " "))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(409));
        // 弹层预览同样拒。
        when(profiles.findByIdAndOwnerId(PET, USER)).thenReturn(Optional.of(p));
        assertThatThrownBy(() -> service.requireOwnedPet(USER, PET)).isInstanceOf(AppException.class);
        verifyNoInteractions(changeLog, audit);
    }

    @Test
    void rabbitToHamsterClearsBreedKeepsCooldownNoSwitch() {
        PetProfile p = pet(PetSpecies.RABBIT, USER);
        Instant userChanged = Instant.parse("2026-11-24T12:05:00Z");
        p.markTypeChangedAt(userChanged);

        service.change(ADMIN, USER, PET, "HAMSTER", " 用户客诉（工单 #8812） ");

        assertThat(p.getPetSpecies()).isEqualTo(PetSpecies.HAMSTER);
        assertThat(p.getBreed()).isNull();
        assertThat(p.getPetTypeChangedAt()).isEqualTo(userChanged); // 不写、不重置冷却
        assertThat(p.getMilestoneTrack()).isEqualTo(PetType.OTHER);
        verify(switcher, never()).switchTo(any(), any());
        verify(changeLog).append(PET, PetSpecies.RABBIT, PetSpecies.HAMSTER, PetSpeciesChangeSource.ADMIN, ADMIN,
                "用户客诉（工单 #8812）");
        ArgumentCaptor<String> summary = ArgumentCaptor.forClass(String.class);
        verify(audit).record(eq(ADMIN), eq(AuditActions.PET_SPECIES_CHANGED), eq("PET"), eq("10"), summary.capture());
        assertThat(summary.getValue()).isEqualTo("RABBIT→HAMSTER user=7").doesNotContain("8812").doesNotContain("工单");
        verify(events).publishEvent(new PetSpeciesChangedEvent(USER, PET, "RABBIT", "HAMSTER", false, "admin"));
    }

    @Test
    void catToRabbitSwitchesTrackAndNeverTouchesCooldown() {
        PetProfile p = pet(PetSpecies.CAT, USER);
        service.change(ADMIN, USER, PET, "RABBIT", "选错");
        assertThat(p.getPetType()).isEqualTo(PetType.OTHER);
        assertThat(p.getPetTypeChangedAt()).isNull();
        verify(switcher).switchTo(p, PetType.OTHER);
        verify(audit).record(eq(ADMIN), eq(AuditActions.PET_SPECIES_CHANGED), eq("PET"), eq("10"), anyString());
    }
}
