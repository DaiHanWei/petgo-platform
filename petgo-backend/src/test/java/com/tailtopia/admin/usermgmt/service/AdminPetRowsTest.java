package com.tailtopia.admin.usermgmt.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.admin.usermgmt.dto.AdminUserDetailView;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChange;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.repository.PetSpeciesChangeRepository;
import com.tailtopia.profile.service.PetResolver;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0：V1.5.0 pet-species Story 2.1 · AC1 / AC4.3 —— 宠物行字段、修改记录一次批量、运营昵称一次批量。 */
class AdminPetRowsTest {

    private static PetProfile pet(long id, PetSpecies s) {
        PetProfile p = PetProfile.create(7L, s, "P" + id, null, null, LocalDate.of(2025, 9, 12), null, "T" + id);
        ReflectionTestUtils.setField(p, "id", id);
        return p;
    }

    private static PetSpeciesChange change(long petId, String from, String to, PetSpeciesChangeSource src, Long admin,
            String reason, Instant at) {
        PetSpeciesChange c = PetSpeciesChange.of(petId, from, to, true, false, src, admin, reason);
        ReflectionTestUtils.setField(c, "createdAt", at);
        return c;
    }

    @Test
    void rowsCarrySpeciesCooldownAndBatchedChanges() {
        PetSpeciesChangeRepository changes = mock(PetSpeciesChangeRepository.class);
        AdminAccountRepository admins = mock(AdminAccountRepository.class);
        AdminPetRows rows = new AdminPetRows(mock(PetResolver.class), changes, admins);

        PetProfile rabbit = pet(1L, PetSpecies.RABBIT);
        rabbit.markTypeChangedAt(Instant.parse("2026-11-24T12:05:00Z"));
        PetProfile civet = pet(2L, PetSpecies.CIVET);
        when(changes.findByPetProfileIdInOrderByCreatedAtDescIdDesc(anyCollection())).thenReturn(List.of(
                change(1L, "RABBIT", "HAMSTER", PetSpeciesChangeSource.ADMIN, 99L, "工单", Instant.parse("2026-12-02T07:20:00Z")),
                change(1L, "OTHER", "RABBIT", PetSpeciesChangeSource.USER, null, null, Instant.parse("2026-11-24T12:05:00Z")),
                change(2L, "OTHER", "CIVET", PetSpeciesChangeSource.MIGRATION, null, null, Instant.parse("2026-11-20T00:00:00Z"))));
        AdminAccount hex = mock(AdminAccount.class);
        when(hex.getId()).thenReturn(99L);
        when(hex.getDisplayName()).thenReturn("Hex");
        when(admins.findAllById(anyCollection())).thenReturn(List.of(hex));

        List<AdminUserDetailView.PetRow> out = rows.rows(List.of(rabbit, civet), LocalDate.of(2026, 12, 2));

        AdminUserDetailView.PetRow r = out.get(0);
        assertThat(r.species()).isEqualTo("RABBIT");
        assertThat(r.speciesPicker()).isEqualTo("RABBIT");
        assertThat(r.otherGroupMember()).isFalse();
        assertThat(r.speciesChangeableFrom()).isEqualTo(LocalDate.of(2027, 2, 22));
        assertThat(r.readOnly()).isFalse();
        assertThat(r.speciesChanges()).extracting(AdminUserDetailView.SpeciesChangeRow::source)
                .containsExactly("ADMIN", "USER");
        assertThat(r.speciesChanges().get(0).operatorName()).isEqualTo("Hex");
        assertThat(r.speciesChanges().get(1).operatorName()).isNull();

        AdminUserDetailView.PetRow c = out.get(1);
        assertThat(c.speciesPicker()).isEqualTo("OTHER");
        assertThat(c.otherGroupMember()).isTrue();
        assertThat(c.speciesChangeableFrom()).isNull();
        assertThat(c.speciesChanges()).hasSize(1);

        verify(changes, times(1)).findByPetProfileIdInOrderByCreatedAtDescIdDesc(anyCollection());
        verify(admins, times(1)).findAllById(anyCollection());
    }

    @Test
    void legacyRowConstructorHasNoChanges() {
        AdminUserDetailView.PetRow legacy = new AdminUserDetailView.PetRow(1L, "n", "CAT", null, null, null);
        assertThat(legacy.speciesChanges()).isEmpty();
        assertThat(legacy.otherGroupMember()).isFalse();
    }
}
