package com.tailtopia.shop.repurchase.web;

import static com.tailtopia.profile.support.PetFixtures.pet;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shop.domain.ShopProduct;
import com.tailtopia.shop.domain.ShopSku;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.domain.RepurchaseTrigger;
import com.tailtopia.shop.repurchase.dto.RecommendationView;
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
 * L0：补货卡接口。
 *
 * <p>v1.4.2 shop Story 6.2（PRD FR-136 / D-20）<b>按设计打破</b>原断言「每张卡显示自己的宠物名」（V1.4.0 multi-pet X22 / X23）：
 * 复购下线后 {@code cards} 恒返回空列表、不触碰任何仓库（旧版 App 兼容，不 404）；{@code recommendations} 照常委托。
 */
class MeRepurchaseControllerTest {

    private static final long USER = 7L;
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void eachCardShowsItsOwnPetName() {
        RepurchaseScanService repurchase = mock(RepurchaseScanService.class);
        RepurchaseTriggerRepository triggers = mock(RepurchaseTriggerRepository.class);
        ShopSkuRepository skus = mock(ShopSkuRepository.class);
        ShopProductRepository products = mock(ShopProductRepository.class);
        PetProfileRepository profiles = mock(PetProfileRepository.class);
        MeRepurchaseController controller = new MeRepurchaseController(mock(ProfileRecommendationService.class),
                repurchase, triggers, skus, products, profiles);

        // 即使有活动触发器（旧数据），也不再拼卡
        RepurchaseTrigger ta = trigger(1L, 10L);
        RepurchaseTrigger tb = trigger(2L, 11L);
        when(repurchase.activeTriggersFor(USER)).thenReturn(List.of(ta, tb));
        ShopSku sku = mock(ShopSku.class);
        when(skus.findById(any())).thenReturn(Optional.of(sku));
        ShopProduct product = mock(ShopProduct.class);
        when(products.findById(5L)).thenReturn(Optional.of(product));
        when(profiles.findAllById(anyCollection())).thenReturn(List.of(
                pet(USER, 10L, PetType.CAT, T0), pet(USER, 11L, PetType.DOG, T0.plusSeconds(60))));
        org.mockito.Mockito.clearInvocations(repurchase, skus, products, profiles);

        var cards = controller.cards(jwt());

        assertThat(cards).isEmpty();
        verifyNoInteractions(repurchase, triggers, skus, products, profiles);
    }

    @Test
    void recommendationsStillDelegates() {
        ProfileRecommendationService recommendations = mock(ProfileRecommendationService.class);
        RecommendationView view = mock(RecommendationView.class);
        when(recommendations.recommendFor(USER)).thenReturn(view);
        MeRepurchaseController controller = new MeRepurchaseController(recommendations,
                mock(RepurchaseScanService.class), mock(RepurchaseTriggerRepository.class),
                mock(ShopSkuRepository.class), mock(ShopProductRepository.class), mock(PetProfileRepository.class));

        assertThat(controller.recommendations(jwt())).isSameAs(view);
        verify(recommendations, times(1)).recommendFor(USER);
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
