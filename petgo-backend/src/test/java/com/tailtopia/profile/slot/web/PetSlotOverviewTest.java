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
                        "rentable", "canRent", "nextLevelSlot", "unitPrice", "days",
                        // Story 2.2 AC8.1 只在末尾追加（1.4 字段名与顺序不变）
                        "readOnlyCount", "shortBy", "renewableMax", "rentals");
    }

    /** Story 2.2 AC8.1：shortBy / renewableMax / readOnlyCount / rentals（仅生效中、index 从 1、daysLeft 向上取整）。 */
    @Test
    void story22FieldsDerivedFromQuotaAndActiveRentals() {
        Instant now = Instant.parse("2026-10-28T03:00:00Z");
        com.tailtopia.profile.slot.domain.PetSlotRental a = com.tailtopia.profile.slot.domain.PetSlotRental.start("tokA", 7L,
                com.tailtopia.profile.slot.domain.PetSlotRentalSource.RENT, Instant.parse("2026-10-01T03:00:00Z"), 30, 1L);
        com.tailtopia.profile.slot.domain.PetSlotRental b = com.tailtopia.profile.slot.domain.PetSlotRental.start("tokB", 7L,
                com.tailtopia.profile.slot.domain.PetSlotRentalSource.ADMIN_GRANT, Instant.parse("2026-10-20T00:00:00Z"), 30, 2L);
        // Lv5（等级 1）+ 月租 2 → total 4；已建 5 只 → 缺 1
        PetSlotQuota q = new PetSlotQuota(4, 1, 1, 2, 5, 1, true, 10, true);
        PetSlotOverview o = PetSlotOverview.of(q, 7000L, 1, java.util.List.of(a, b), now);
        assertThat(o.unitPrice()).isEqualTo(7000L);
        assertThat(o.readOnlyCount()).isEqualTo(1);
        assertThat(o.shortBy()).isEqualTo(1);
        assertThat(o.renewableMax()).isEqualTo(3);
        assertThat(o.rentable()).isEqualTo(1);
        assertThat(o.rentals()).hasSize(2);
        assertThat(o.rentals().get(0).index()).isEqualTo(1);
        assertThat(o.rentals().get(0).token()).isEqualTo("tokA");
        assertThat(o.rentals().get(0).daysLeft()).isEqualTo(3); // 10/31 到期
        assertThat(o.rentals().get(1).index()).isEqualTo(2);
        assertThat(o.rentals().get(1).source()).isEqualTo("ADMIN_GRANT");
        assertThat(o.rentals().get(1).daysLeft()).isEqualTo(22); // 11/19 00:00 到期，剩 21 天 21 小时 → 22
        assertThat(PetSlotOverview.of(new PetSlotQuota(2, 1, 1, 0, 1, 3, true, 10, false), 5000L).shortBy()).isZero();
    }

    @Test
    void daysLeftCeilsAndFloorsAtZero() {
        Instant now = Instant.parse("2026-10-28T03:00:00Z");
        assertThat(PetSlotOverview.daysLeft(now.plusSeconds(1), now)).isEqualTo(1);
        assertThat(PetSlotOverview.daysLeft(now.plusSeconds(86_400), now)).isEqualTo(1);
        assertThat(PetSlotOverview.daysLeft(now.plusSeconds(86_401), now)).isEqualTo(2);
        assertThat(PetSlotOverview.daysLeft(now, now)).isZero();
        assertThat(PetSlotOverview.daysLeft(now.minusSeconds(5), now)).isZero();
    }

    @Test
    void controllerReadsOnlyQuotaOf() {
        PetSlotService slots = mock(PetSlotService.class);
        when(slots.quotaOf(42L)).thenReturn(new PetSlotQuota(1, 1, 0, 0, 1, 4, true, 5, false));
        when(slots.unitPrice()).thenReturn(5000L);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("42").claim("role", "USER")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60)).build();
        PetSlotOverview o = new PetSlotController(slots, mock(com.tailtopia.profile.slot.PetSlotPurchaseService.class)).me(jwt);
        assertThat(o.total()).isEqualTo(1);
        assertThat(o.nextLevelSlot()).isEqualTo(5);
    }

    @Test
    void controllerRejectsMissingJwt() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> new PetSlotController(mock(PetSlotService.class),
                mock(com.tailtopia.profile.slot.PetSlotPurchaseService.class)).me(null))
                .isInstanceOf(AppException.class);
    }
}
