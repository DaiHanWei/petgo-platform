package com.tailtopia.shop.repurchase.web;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.repository.RepurchaseTriggerRepository;
import com.tailtopia.shop.repurchase.service.ProfileRecommendationService;
import com.tailtopia.shop.repurchase.service.RepurchaseScanService;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0（V1.4.0 multi-pet Story 1.2 · X22 / X23）：补货卡名字按<b>每张卡自己的宠物</b>（触发器 pet_profile_id），
 * 一次批量取；推算依据按触发器上的宠物。
 */
class MeRepurchaseControllerTest {

    private static final long USER = 7L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void eachCardShowsItsOwnPetName() {
        RepurchaseScanService repurchase = mock(RepurchaseScanService.class);
        ShopSkuRepository skus = mock(ShopSkuRepository.class);
        ShopProductRepository products = mock(ShopProductRepository.class);
        PetProfileRepository profiles = mock(PetProfileRepository.class);
        MeRepurchaseController controller = new MeRepurchaseController(mock(ProfileRecommendationService.class),
                repurchase, mock(RepurchaseTriggerRepository.class), skus, products, profiles);

        RepurchaseTrigger ta = trigger(1L, 10L);
        RepurchaseTrigger tb = trigger(2L, 11L);
        when(repurchase.activeTriggersFor(USER)).thenReturn(List.of(ta, tb));
        ShopSku sku = mock(ShopSku.class);
        when(sku.getProductId()).thenReturn(5L);
        when(sku.getPublicToken()).thenReturn("sku");
        when(skus.findById(any())).thenReturn(Optional.of(sku));
        ShopProduct product = mock(ShopProduct.class);
        when(product.getPublicToken()).thenReturn("prod");
        when(products.findById(5L)).thenReturn(Optional.of(product));
        when(profiles.findAllById(anyCollection())).thenReturn(List.of(
                pet(USER, 10L, PetType.CAT, T0), pet(USER, 11L, PetType.DOG, T0.plusSeconds(60))));

        var cards = controller.cards(jwt());

        assertThat(cards).extracting(c -> c.petName()).containsExactly("Pet10", "Pet11");
        verify(profiles, times(1)).findAllById(anyCollection());
        verify(profiles, never()).findDefault(org.mockito.ArgumentMatchers.anyLong());
    }

    private static RepurchaseTrigger trigger(long id, long petId) {
        RepurchaseTrigger t = RepurchaseTrigger.foodLow(USER, petId, 3L, 9L, LocalDate.now().plusDays(5));
        ReflectionTestUtils.setField(t, "id", id);
        return t;
    }

    private static Jwt jwt() {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(String.valueOf(USER)).claim("x", "y").build();
    }
}
