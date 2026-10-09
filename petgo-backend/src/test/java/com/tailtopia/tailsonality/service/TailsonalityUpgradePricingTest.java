package com.tailtopia.tailsonality.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.purchase.domain.KeepsakePurchase;
import com.tailtopia.purchase.domain.KeepsakeSku;
import com.tailtopia.purchase.repository.KeepsakePurchaseRepository;
import com.tailtopia.tailsonality.domain.TailsonalityCode;
import com.tailtopia.tailsonality.domain.TailsonalityQuestionSet;
import com.tailtopia.tailsonality.domain.TailsonalityResult;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 2026-10-09（L0）：已单独买配型的结果，完整解读补差价 = 结果价 − 实付配型价，下限 100。 */
class TailsonalityUpgradePricingTest {

    private final KeepsakePurchaseRepository purchases = mock(KeepsakePurchaseRepository.class);
    private final PlatformConfigService platformConfig = mock(PlatformConfigService.class);
    private final TailsonalityUpgradePricing pricing = new TailsonalityUpgradePricing(purchases, platformConfig);

    @BeforeEach
    void setUp() {
        PricingConfig c = mock(PricingConfig.class);
        when(c.getTailsonalityUnlockPrice()).thenReturn(5000L);
        when(c.getTailsonalityMatchUnlockPrice()).thenReturn(3000L);
        when(platformConfig.pricing()).thenReturn(c);
    }

    private static TailsonalityResult row(Instant unlockedAt, Instant matchUnlockedAt) {
        TailsonalityResult r = TailsonalityResult.create("tok", 3L, 7L, TailsonalityQuestionSet.CAT, Map.of(),
                new TailsonalityCode("ENTJ", "H"), 1, Instant.EPOCH);
        ReflectionTestUtils.setField(r, "id", 42L);
        ReflectionTestUtils.setField(r, "unlockedAt", unlockedAt);
        ReflectionTestUtils.setField(r, "matchUnlockedAt", matchUnlockedAt);
        return r;
    }

    private void paidMatch(long price) {
        KeepsakePurchase p = mock(KeepsakePurchase.class);
        when(p.getPriceIdr()).thenReturn(price);
        when(purchases.findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(eq(KeepsakeSku.TS_MATCH), eq(42L), any()))
                .thenReturn(Optional.of(p));
    }

    @Test
    void noMatchBoughtOrAlreadyFullyUnlockedIsFullPrice() {
        assertThat(pricing.upgradePriceOrNull(row(null, null))).isNull();
        assertThat(pricing.upgradePriceOrNull(row(Instant.EPOCH, Instant.EPOCH))).isNull();
    }

    @Test
    void subtractsWhatWasActuallyPaidNotTheCurrentConfig() {
        paidMatch(2500); // 买配型时运营价是 2500，之后改成 3000
        assertThat(pricing.upgradePriceOrNull(row(null, Instant.EPOCH))).isEqualTo(2500L);
    }

    @Test
    void fallsBackToCurrentMatchPriceWhenNoPaidRow() {
        when(purchases.findFirstBySkuAndRefIdAndStatusInOrderByIdDesc(any(), anyLong(), any()))
                .thenReturn(Optional.empty());
        assertThat(pricing.upgradePriceOrNull(row(null, Instant.EPOCH))).isEqualTo(2000L);
    }

    @Test
    void neverBelowHundred() {
        paidMatch(4950);
        assertThat(pricing.upgradePriceOrNull(row(null, Instant.EPOCH))).isEqualTo(100L);
    }
}
