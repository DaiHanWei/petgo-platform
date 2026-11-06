package com.tailtopia.shop.repurchase.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shop.coupon.repository.ShopCouponRepository;
import com.tailtopia.shop.coupon.service.PromoResolver;
import com.tailtopia.shop.repository.ShopProductRepository;
import com.tailtopia.shop.repository.ShopSkuRepository;
import com.tailtopia.shop.repurchase.dto.RecommendationView;
import com.tailtopia.shop.service.ShopImageUrlResolver;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0（v1.4.2 shop Story 6.1 AC13）：{@code /me/shop/recommendations} 的 {@code profileSummary}。 */
class ProfileSummaryTest {

    private static final long USER = 42L;

    private PetProfileRepository profiles;
    private RecommendationSilenceService silence;
    private ProfileRecommendationService service;

    @BeforeEach
    void setUp() {
        profiles = mock(PetProfileRepository.class);
        silence = mock(RecommendationSilenceService.class);
        ShopProductRepository products = mock(ShopProductRepository.class);
        when(products.findByActiveTrueOrderBySortWeightDescIdDesc()).thenReturn(List.of());
        MediaProperties mp = new MediaProperties();
        mp.getOss().setCdnBaseUrl("https://cdn.test");
        service = new ProfileRecommendationService(profiles, products, mock(ShopSkuRepository.class), silence,
                new ShopImageUrlResolver(mp), new PromoResolver(mock(ShopCouponRepository.class)), 1, 7);
    }

    private void pet(PetType type, LocalDate birthday, String weightKg) {
        PetProfile p = PetProfile.create(USER, type, "Mochi", null, null, birthday, null, "card");
        if (weightKg != null) {
            p.setWeightKg(new BigDecimal(weightKg));
        }
        when(profiles.findDefault(USER)).thenReturn(Optional.of(p));
    }

    private RecommendationView.ProfileSummary summary() {
        return service.recommendFor(USER).profileSummary();
    }

    @Test
    @DisplayName("狗 · 成年 · 中型：枚举齐全，weightMissing=false")
    void dogAdultMedium() {
        pet(PetType.DOG, LocalDate.now().minusYears(3), "12.5");
        RecommendationView.ProfileSummary s = summary();
        assertThat(s.petName()).isEqualTo("Mochi");
        assertThat(s.species()).isEqualTo("DOG");
        assertThat(s.ageStage()).isEqualTo("ADULT");
        assertThat(s.sizeClass()).isEqualTo("MEDIUM");
        assertThat(s.weightMissing()).isFalse();
    }

    @Test
    @DisplayName("猫 · 幼年 · 小型；老年 · 大型")
    void catAndBoundaries() {
        pet(PetType.CAT, LocalDate.now().minusMonths(5), "3");
        assertThat(summary().species()).isEqualTo("CAT");
        assertThat(summary().ageStage()).isEqualTo("PUPPY");
        assertThat(summary().sizeClass()).isEqualTo("SMALL");

        pet(PetType.DOG, LocalDate.now().minusYears(10), "30");
        assertThat(summary().ageStage()).isEqualTo("SENIOR");
        assertThat(summary().sizeClass()).isEqualTo("LARGE");
    }

    @Test
    @DisplayName("其他物种 → UNIVERSAL")
    void otherSpeciesIsUniversal() {
        pet(PetType.OTHER, LocalDate.now().minusYears(2), "5");
        assertThat(summary().species()).isEqualTo("UNIVERSAL");
    }

    @Test
    @DisplayName("无体重 → sizeClass=null 且 weightMissing=true；无生日 → ageStage=null")
    void missingFacts() {
        pet(PetType.DOG, null, null);
        RecommendationView.ProfileSummary s = summary();
        assertThat(s.sizeClass()).isNull();
        assertThat(s.weightMissing()).isTrue();
        assertThat(s.ageStage()).isNull();
        // 推荐理由与降级标记照旧
        RecommendationView v = service.recommendFor(USER);
        assertThat(v.degraded()).isTrue();
        assertThat(v.missing()).isEqualTo("BOTH");
    }

    @Test
    @DisplayName("未建档 → profileSummary=null（missing=PROFILE）")
    void noProfileHasNoSummary() {
        when(profiles.findDefault(USER)).thenReturn(Optional.empty());
        RecommendationView v = service.recommendFor(USER);
        assertThat(v.missing()).isEqualTo("PROFILE");
        assertThat(v.profileSummary()).isNull();
    }

    @Test
    @DisplayName("🔒 静默期 → profileSummary=null（不能让客户端推断出静默态）")
    void silencedHasNoSummary() {
        pet(PetType.DOG, LocalDate.now().minusYears(3), "12");
        when(silence.isSilenced(USER)).thenReturn(true);
        RecommendationView v = service.recommendFor(USER);
        assertThat(v.profileSummary()).isNull();
        assertThat(v.items()).isEmpty();
        assertThat(v.degraded()).isFalse();
    }
}
