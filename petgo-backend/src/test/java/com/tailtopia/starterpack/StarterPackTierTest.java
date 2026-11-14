package com.tailtopia.starterpack;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.starterpack.domain.StarterPackContent;
import com.tailtopia.starterpack.domain.StarterPackTier;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 4-2 AC2.5 —— 三档内容、单买合计与节省（默认价）。 */
class StarterPackTierTest {

    static PricingConfig defaults() {
        PricingConfig p = mock(PricingConfig.class);
        when(p.getIdHdDownloadPrice()).thenReturn(1000L);
        when(p.getTailsonalityUnlockPrice()).thenReturn(5000L);
        when(p.getPetSlotMonthlyPrice()).thenReturn(5000L);
        when(p.getStarterPackTier1Price()).thenReturn(8000L);
        when(p.getStarterPackTier2Price()).thenReturn(11000L);
        when(p.getStarterPackTier3Price()).thenReturn(13000L);
        when(p.isStarterPackEnabled()).thenReturn(true);
        return p;
    }

    @Test
    void separateTotalAndSavingAtDefaultPrices() {
        PricingConfig p = defaults();
        assertThat(StarterPackTier.TIER_1.separateTotal(p)).isEqualTo(13000);
        assertThat(StarterPackTier.TIER_2.separateTotal(p)).isEqualTo(18000);
        assertThat(StarterPackTier.TIER_3.separateTotal(p)).isEqualTo(23000);
        assertThat(StarterPackTier.TIER_1.saving(p)).isEqualTo(5000);
        assertThat(StarterPackTier.TIER_2.saving(p)).isEqualTo(7000);
        assertThat(StarterPackTier.TIER_3.saving(p)).isEqualTo(10000);
    }

    @Test
    void contentsSlotAndGiftCredits() {
        assertThat(StarterPackTier.TIER_1.contents())
                .containsExactly(StarterPackContent.KTP_HD_8, StarterPackContent.TAILSONALITY_1);
        assertThat(StarterPackTier.TIER_2.contents()).containsExactly(StarterPackContent.KTP_HD_8,
                StarterPackContent.TAILSONALITY_1, StarterPackContent.PET_SLOT_30D);
        assertThat(StarterPackTier.TIER_3.contents()).containsExactly(StarterPackContent.KTP_HD_8,
                StarterPackContent.TAILSONALITY_1, StarterPackContent.PET_SLOT_30D, StarterPackContent.GIFT_1K_5);
        assertThat(StarterPackTier.TIER_1.hasSlot()).isFalse();
        assertThat(StarterPackTier.TIER_2.hasSlot()).isTrue();
        assertThat(StarterPackTier.TIER_3.giftCredits()).isEqualTo(5);
        assertThat(StarterPackTier.TIER_2.giftCredits()).isZero();
        assertThat(StarterPackTier.of(2)).contains(StarterPackTier.TIER_2);
        assertThat(StarterPackTier.of(4)).isEmpty();
        assertThat(StarterPackTier.of(null)).isEmpty();
    }
}
