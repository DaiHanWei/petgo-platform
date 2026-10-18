package com.tailtopia.profile.slot.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.slot.PetSlotQuota;
import com.tailtopia.profile.slot.PetSlotService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：V1.4.0 multi-pet Story 1.4 · AC6 名额摘要映射（字段与 PetSlotQuota 一一对应、cap 5、days 30）。 */
class PetSlotOverviewTest {

    @Test
    void mapsQuotaFieldsOneToOne() {
        PetSlotQuota q = new PetSlotQuota(2, 1, 1, 0, 1, 3, true, 10, false);
        PetSlotOverview o = PetSlotOverview.of(q, 5000L);
        assertThat(o.total()).isEqualTo(2);
        assertThat(o.cap()).isEqualTo(5);
        assertThat(o.defaultSlots()).isEqualTo(1);
        assertThat(o.levelSlots()).isEqualTo(1);
        assertThat(o.rentalSlots()).isZero();
        assertThat(o.petCount()).isEqualTo(1);
        assertThat(o.rentable()).isEqualTo(3);
        assertThat(o.canRent()).isTrue();
        assertThat(o.nextLevelSlot()).isEqualTo(10);
        assertThat(o.unitPrice()).isEqualTo(5000L);
        assertThat(o.days()).isEqualTo(30);
    }

    @Test
    void fieldNamesMatchStory22Contract() {
        assertThat(Arrays.stream(PetSlotOverview.class.getRecordComponents()).map(c -> c.getName()))
                .containsExactly("total", "cap", "defaultSlots", "levelSlots", "rentalSlots", "petCount",
                        "rentable", "canRent", "nextLevelSlot", "unitPrice", "days");
    }

    @Test
    void controllerReadsOnlyQuotaOf() {
        PetSlotService slots = mock(PetSlotService.class);
        when(slots.quotaOf(42L)).thenReturn(new PetSlotQuota(1, 1, 0, 0, 1, 4, true, 5, false));
        when(slots.unitPrice()).thenReturn(5000L);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("42").claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        PetSlotOverview o = new PetSlotController(slots).me(jwt);
        assertThat(o.total()).isEqualTo(1);
        assertThat(o.nextLevelSlot()).isEqualTo(5);
    }

    @Test
    void controllerRejectsMissingJwt() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PetSlotController(mock(PetSlotService.class)).me(null))
                .isInstanceOf(AppException.class);
    }
}
