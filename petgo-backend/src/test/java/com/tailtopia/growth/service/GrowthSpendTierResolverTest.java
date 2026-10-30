package com.tailtopia.growth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.GrowthSpendTier;
import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.domain.SpendTier;
import com.tailtopia.growth.repository.GrowthSpendTierRepository;
import com.tailtopia.pay.domain.PaymentPurpose;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** V1.4.0 ① Story 1.3 · AC1（L0）：11 个用途的档位（照 V20261018_1504 seed）；未登记 → DIGITAL；NONE / GIFT 不入账。 */
class GrowthSpendTierResolverTest {

    /** 与迁移 seed 一致。 */
    private static final Map<String, SpendTier> SEED = Map.ofEntries(
            Map.entry("VET_CONSULT", SpendTier.DIGITAL), Map.entry("AI_UNLOCK", SpendTier.DIGITAL),
            Map.entry("ID_HD", SpendTier.DIGITAL), Map.entry("TAILSONALITY", SpendTier.DIGITAL),
            Map.entry("PASSPORT_SNAP", SpendTier.DIGITAL), Map.entry("BOARDING_PASS", SpendTier.DIGITAL),
            Map.entry("TS_MATCH", SpendTier.DIGITAL), Map.entry("PET_SLOT_RENT", SpendTier.DIGITAL),
            Map.entry("SHOP_ORDER", SpendTier.TOKO), Map.entry("PAWCOIN_TOPUP", SpendTier.NONE),
            Map.entry("GIFT", SpendTier.GIFT), Map.entry("MERCHANT_PO", SpendTier.NONE));

    private final GrowthSpendTierRepository repo = mock(GrowthSpendTierRepository.class);
    private final GrowthSpendTierResolver resolver = new GrowthSpendTierResolver(repo);

    @BeforeEach
    void seed() {
        when(repo.findById(anyString())).thenAnswer(inv -> {
            String p = inv.getArgument(0);
            SpendTier t = SEED.get(p);
            return t == null ? Optional.empty() : Optional.of(GrowthSpendTier.of(p, t, false));
        });
    }

    @Test
    void seedCoversEveryPaymentPurpose() {
        for (PaymentPurpose p : PaymentPurpose.values()) {
            assertThat(SEED).containsKey(p.name());
        }
        assertThat(SEED).hasSize(PaymentPurpose.values().length);
    }

    @Test
    void tiersMapToRules() {
        assertThat(resolver.ruleFor("ID_HD")).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(resolver.ruleFor("PET_SLOT_RENT")).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(resolver.ruleFor("SHOP_ORDER")).isEqualTo(RuleCode.SPEND_SHOP);
        assertThat(resolver.ruleFor("PAWCOIN_TOPUP")).isNull();
        assertThat(resolver.ruleFor("GIFT")).isNull();
    }

    @Test
    void unregisteredPurposeDefaultsToDigital() {
        assertThat(resolver.tierOf("SOMETHING_NEW")).isEqualTo(SpendTier.DIGITAL);
        assertThat(resolver.ruleFor("SOMETHING_NEW")).isEqualTo(RuleCode.SPEND_DIGITAL);
        assertThat(resolver.tierOf(null)).isEqualTo(SpendTier.DIGITAL);
    }
}
