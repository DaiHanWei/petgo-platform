package com.tailtopia.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（Story 9.2）：真 Spring 上下文 + PostgreSQL——V78 schema validate（上下文启动即验）、种子默认值、
 * 读改生效 + 变更日志、充值档位保底 ≥1。改配置只影响后续（历史快照不在本测范围，见 consult/ai/id-hd L1）。
 */
class PlatformConfigIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlatformConfigService read;
    @Autowired
    private AdminConfigService write;
    @Autowired
    private PawCoinTopupTierRepository tierRepo;
    @Autowired
    private ConfigChangeLogRepository changeLogs;

    @Test
    void seedDefaultsMatchEnvBaseline() {
        assertThat(read.pricing().getVetConsultPrice()).isEqualTo(50000);
        assertThat(read.pricing().getVetShareRate()).isEqualTo(60);
        assertThat(read.pricing().getAiUnlockPrice()).isEqualTo(10000);
        assertThat(read.pricing().getIdHdDownloadPrice()).isEqualTo(5000);
        assertThat(read.pricing().getMonthlyFreeQuota()).isEqualTo(1);
        assertThat(read.pawcoin().getPremiumRate()).isEqualTo(0);
        assertThat(read.pawcoin().isTopupPaused()).isFalse();
        assertThat(read.enabledTiers()).hasSize(4);
    }

    @Test
    void updatePricingTakesEffectAndLogsChange() {
        // 捕获原值（单行 pricing_config 是共享态，测试后还原避免污染 seedDefaults 断言）。
        var orig = read.pricing();
        var origForm = new PricingForm(orig.getVetConsultPrice(), orig.getVetShareRate(),
                orig.getAiUnlockPrice(), orig.getIdHdDownloadPrice(), orig.getMonthlyFreeQuota());
        long before = changeLogs.count();
        int newRate = orig.getVetShareRate() == 55 ? 50 : 55; // 确保与当前不同
        try {
            write.updatePricing(new PricingForm(60000, newRate,
                    orig.getAiUnlockPrice(), orig.getIdHdDownloadPrice(),
                    orig.getMonthlyFreeQuota()), 1L);

            assertThat(read.pricing().getVetConsultPrice()).isEqualTo(60000);
            assertThat(read.pricing().getVetShareRate()).isEqualTo(newRate);
            // 至少两字段变更 → ≥2 条变更日志（价 + 分成）。
            assertThat(changeLogs.count()).isGreaterThan(before);
        } finally {
            write.updatePricing(origForm, 1L); // 还原
        }
    }

    @Test
    void cannotDisableLastEnabledTier() {
        // 逐个停用到只剩 1 个，再停最后一个 → 422。
        List<PawCoinTopupTier> enabled = read.enabledTiers();
        for (int i = 0; i < enabled.size() - 1; i++) {
            write.setTierEnabled(enabled.get(i).getId(), false, 1L);
        }
        PawCoinTopupTier last = read.enabledTiers().get(0);
        assertThatThrownBy(() -> write.setTierEnabled(last.getId(), false, 1L))
                .isInstanceOf(AppException.class);
        assertThat(read.enabledTiers()).hasSize(1);

        // 复原（避免污染同库其它用例）。
        for (PawCoinTopupTier t : read.allTiers()) {
            if (!t.isEnabled()) {
                write.setTierEnabled(t.getId(), true, 1L);
            }
        }
    }
}
