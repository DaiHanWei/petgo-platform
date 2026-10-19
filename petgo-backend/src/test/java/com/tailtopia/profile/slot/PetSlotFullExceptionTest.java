package com.tailtopia.profile.slot;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import org.junit.jupiter.api.Test;

/** L0：V1.4.0 multi-pet Story 1.4 · AC2.2 扩展成员键名（camelCase）与取值。 */
class PetSlotFullExceptionTest {

    @Test
    void extensionsCarryQuotaValues() {
        PetSlotQuota q = new PetSlotQuota(1, 1, 0, 0, 1, 4, true, 5, false);
        PetSlotFullException e = new PetSlotFullException(q, 5000L);
        assertThat(e).isInstanceOf(AppException.class);
        assertThat(e.getStatus().value()).isEqualTo(409);
        assertThat(e.getType()).isEqualTo(ErrorTypes.PET_SLOT_FULL);
        assertThat(e.getType().toString()).endsWith("/pet-slot-full");
        assertThat(e.problemExtensions()).containsOnlyKeys("canRent", "nextLevelSlot", "petCount", "slotTotal",
                "unitPrice");
        assertThat(e.problemExtensions()).containsEntry("canRent", true).containsEntry("nextLevelSlot", 5)
                .containsEntry("petCount", 1).containsEntry("slotTotal", 1).containsEntry("unitPrice", 5000L);
    }

    @Test
    void nextLevelSlotMayBeNull() {
        PetSlotQuota q = new PetSlotQuota(3, 1, 2, 0, 3, 2, true, null, false);
        PetSlotFullException e = new PetSlotFullException(q, 5000L);
        assertThat(e.problemExtensions()).containsKey("nextLevelSlot");
        assertThat(e.problemExtensions().get("nextLevelSlot")).isNull();
    }

    @Test
    void limitReachedTypeSlug() {
        assertThat(ErrorTypes.PET_LIMIT_REACHED.toString()).endsWith("/pet-limit-reached");
    }
}
