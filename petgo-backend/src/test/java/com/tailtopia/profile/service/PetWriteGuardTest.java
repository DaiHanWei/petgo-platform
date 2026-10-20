package com.tailtopia.profile.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.error.PetReadOnlyException;
import com.tailtopia.shared.error.ProblemExtensions;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：V1.4.0 multi-pet Story 2.1 · AC7.1 —— ACTIVE 通过；READ_ONLY 抛 409、type pet-read-only、扩展成员含 petId。 */
class PetWriteGuardTest {

    private final PetProfileRepository profiles = mock(PetProfileRepository.class);
    private final PetWriteGuard guard = new PetWriteGuard(profiles);

    @Test
    void activePetPasses() {
        PetProfile p = pet(1L, 10L, Instant.EPOCH);
        when(profiles.findById(10L)).thenReturn(Optional.of(p));
        assertThatCode(() -> guard.requireWritable(10L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireWritable(p)).doesNotThrowAnyException();
    }

    @Test
    void readOnlyPetIs409WithPetIdExtension() {
        PetProfile p = pet(1L, 10L, Instant.EPOCH);
        p.markReadOnly();
        when(profiles.findById(10L)).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> guard.requireWritable(10L)).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getType()).isEqualTo(ErrorTypes.PET_READ_ONLY);
            assertThat(e.getType().toString()).isEqualTo("https://petgo/errors/pet-read-only");
            assertThat(e).isInstanceOf(ProblemExtensions.class);
            assertThat(((ProblemExtensions) e).problemExtensions()).containsExactlyEntriesOf(
                    java.util.Map.of("petId", 10L));
        });
        assertThatThrownBy(() -> guard.requireWritable(p)).isInstanceOf(PetReadOnlyException.class);
    }

    /** 查不到时放行：归属 / 存在性由调用方在前面判（404），本类不另造错误。 */
    @Test
    void missingPetPasses() {
        when(profiles.findById(99L)).thenReturn(Optional.empty());
        assertThatCode(() -> guard.requireWritable(99L)).doesNotThrowAnyException();
        assertThatCode(() -> guard.requireWritable((PetProfile) null)).doesNotThrowAnyException();
    }

    @Test
    void newPetDefaultsToActive() {
        PetProfile p = PetProfile.create(1L, com.tailtopia.profile.domain.PetType.CAT, "M", null, null, null, null, "T");
        assertThat(p.getStatus()).isEqualTo(com.tailtopia.profile.domain.PetStatus.ACTIVE);
        assertThat(p.isKeepActive()).isFalse();
    }
}
