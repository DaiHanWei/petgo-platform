package com.tailtopia.shop.repurchase.service;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.service.NotificationService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.repository.RepurchaseTriggerRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** L0（V1.4.0 multi-pet Story 1.2 · X22）：推算依据按<b>触发器上的宠物</b>，不再按账号取唯一宠物。 */
class RepurchaseScanServiceTest {

    private static final long USER = 7L;
    private static final long ORDER = 9L;

    private ShopOrderRepository orders;
    private ShopSkuRepository skus;
    private PetProfileRepository profiles;
    private RepurchaseScanService service;

    @BeforeEach
    void setUp() {
        orders = mock(ShopOrderRepository.class);
        skus = mock(ShopSkuRepository.class);
        profiles = mock(PetProfileRepository.class);
        service = new RepurchaseScanService(orders, mock(ShopOrderLineRepository.class), skus,
                mock(ShopProductRepository.class), profiles, mock(RepurchaseTriggerRepository.class),
                mock(NotificationService.class), mock(RecommendationSilenceService.class));
        ShopOrder order = mock(ShopOrder.class);
        when(order.getDeliveredAt()).thenReturn(Instant.parse("2026-10-01T00:00:00Z"));
        when(orders.findById(ORDER)).thenReturn(Optional.of(order));
    }

    @Test
    void basisUsesThePetOnTheTrigger() {
        PetProfile b = pet(USER, 11L, PetType.DOG, Instant.EPOCH);
        ReflectionTestUtils.setField(b, "weightKg", new BigDecimal("8.0"));
        when(profiles.findById(11L)).thenReturn(Optional.of(b));
        when(skus.findById(3L)).thenReturn(Optional.empty()); // 走到 SKU 这一步即证明宠物取对了

        RepurchaseTrigger t = RepurchaseTrigger.foodLow(USER, 11L, 3L, ORDER, LocalDate.of(2026, 11, 1));

        assertThat(service.basisFor(t, LocalDate.of(2026, 10, 18))).isNull();
        verify(profiles).findById(11L);
        verify(skus).findById(3L);
        verify(profiles, never()).findDefault(anyLong());
        verify(profiles, never()).findAllByOwnerIdOrderByCreatedAtAscIdAsc(anyLong());
    }

    @Test
    void deletedPetOnTriggerYieldsNullBasis() {
        when(profiles.findById(11L)).thenReturn(Optional.empty());
        RepurchaseTrigger t = RepurchaseTrigger.foodLow(USER, 11L, 3L, ORDER, LocalDate.of(2026, 11, 1));

        assertThat(service.basisFor(t, LocalDate.of(2026, 10, 18))).isNull();
        verify(skus, never()).findById(anyLong());
    }
}
