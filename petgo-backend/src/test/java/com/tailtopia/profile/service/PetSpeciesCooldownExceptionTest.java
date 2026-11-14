package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.error.ErrorTypes;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 pet-species Story 1.2 · AC5 —— 409 + type + 扩展成员 changeableFrom（yyyy-MM-dd）。 */
class PetSpeciesCooldownExceptionTest {

    @Test
    void problemShape() {
        PetSpeciesCooldownException e = new PetSpeciesCooldownException(LocalDate.of(2027, 2, 22));
        assertThat(e.getStatus().value()).isEqualTo(409);
        assertThat(e.getType()).isEqualTo(ErrorTypes.PET_SPECIES_COOLDOWN);
        assertThat(e.getType().toString()).endsWith("/pet-species-cooldown");
        assertThat(e.getMessage()).isEqualTo("宠物类型暂时不能修改");
        assertThat(e.problemExtensions()).containsEntry("changeableFrom", "2027-02-22").hasSize(1);
    }
}
