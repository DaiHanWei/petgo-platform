package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetSpecies;
import com.tailtopia.profile.domain.PetSpeciesChange;
import com.tailtopia.profile.domain.PetSpeciesChangeSource;
import com.tailtopia.profile.repository.PetSpeciesChangeRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 1.2 · AC1.3 —— type_changed / group_changed 计算、ADMIN 必填项。 */
class PetSpeciesChangeLogTest {

    private PetSpeciesChangeLog log;

    @BeforeEach
    void setUp() {
        PetSpeciesChangeRepository repo = mock(PetSpeciesChangeRepository.class);
        when(repo.save(any(PetSpeciesChange.class))).thenAnswer(inv -> inv.getArgument(0));
        log = new PetSpeciesChangeLog(repo);
    }

    @Test
    void flagsComputed() {
        PetSpeciesChange c = log.append(1L, PetSpecies.CAT, PetSpecies.RABBIT, PetSpeciesChangeSource.USER, null, null);
        assertThat(c.isTypeChanged()).isTrue();
        assertThat(c.isGroupChanged()).isTrue();
        assertThat(c.getFromSpecies()).isEqualTo("CAT");
        assertThat(c.getToSpecies()).isEqualTo("RABBIT");

        PetSpeciesChange inGroup = log.append(1L, PetSpecies.OTHER, PetSpecies.CIVET, PetSpeciesChangeSource.USER,
                null, null);
        assertThat(inGroup.isTypeChanged()).isFalse();
        assertThat(inGroup.isGroupChanged()).isFalse();

        PetSpeciesChange sameGroup = log.append(1L, PetSpecies.RABBIT, PetSpecies.HAMSTER,
                PetSpeciesChangeSource.USER, null, null);
        assertThat(sameGroup.isTypeChanged()).isTrue();
        assertThat(sameGroup.isGroupChanged()).isFalse();
    }

    @Test
    void adminRequiresOperatorAndReason() {
        assertThatThrownBy(() -> log.append(1L, PetSpecies.CAT, PetSpecies.DOG, PetSpeciesChangeSource.ADMIN, null, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> log.append(1L, PetSpecies.CAT, PetSpecies.DOG, PetSpeciesChangeSource.ADMIN, 9L, " "))
                .isInstanceOf(IllegalArgumentException.class);
        PetSpeciesChange ok = log.append(1L, PetSpecies.CAT, PetSpecies.DOG, PetSpeciesChangeSource.ADMIN, 9L,
                " 工单 123 ");
        assertThat(ok.getOperatorAdminId()).isEqualTo(9L);
        assertThat(ok.getReason()).isEqualTo("工单 123");
    }
}
