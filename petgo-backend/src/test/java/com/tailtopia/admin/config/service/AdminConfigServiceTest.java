package com.tailtopia.admin.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.config.dto.PawCoinForm;
import com.tailtopia.admin.config.dto.PricingForm;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.PawCoinConfig;
import com.tailtopia.config.domain.PawCoinTopupTier;
import com.tailtopia.config.domain.PricingConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** L0（Story 9.2）：配置写校验护栏 + 逐字段变更日志 + 保底 ≥1 启用。纯 Mockito。 */
class AdminConfigServiceTest {

    private PricingConfigRepository pricingRepo;
    private PawCoinConfigRepository pawcoinRepo;
    private PawCoinTopupTierRepository tierRepo;
    private ConfigChangeLogRepository changeLogs;
    private AdminAuditService audit;
    private AdminConfigService svc;

    @BeforeEach
    void setUp() {
        pricingRepo = Mockito.mock(PricingConfigRepository.class);
        pawcoinRepo = Mockito.mock(PawCoinConfigRepository.class);
        tierRepo = Mockito.mock(PawCoinTopupTierRepository.class);
        changeLogs = Mockito.mock(ConfigChangeLogRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new AdminConfigService(pricingRepo, pawcoinRepo, tierRepo, changeLogs, audit);
    }

    private PricingConfig seedPricing() {
        PricingConfig c = instantiate(PricingConfig.class);
        set(c, "id", 1L);
        set(c, "vetConsultPrice", 50000L);
        set(c, "vetShareRate", 60);
        set(c, "aiUnlockPrice", 10000L);
        set(c, "idHdDownloadPrice", 5000L);
        set(c, "monthlyFreeQuota", 1);
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void rejectsShareRateOver100() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(50000, 101, 10000, 5000, 1), 7L))
                .isInstanceOf(AppException.class);
        verify(changeLogs, never()).saveAll(anyList());
    }

    @Test
    void rejectsFreeQuotaOver35() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(50000, 60, 10000, 5000, 36), 7L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsNegativePrice() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(-1, 60, 10000, 5000, 1), 7L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void logsOnlyChangedFieldsAndAuditsOnce() {
        seedPricing();
        // 仅改单价 + 分成两字段。
        svc.updatePricing(new PricingForm(60000, 55, 10000, 5000, 1), 7L);

        ArgumentCaptor<List<ConfigChangeLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(changeLogs).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2); // vet_consult_price + vet_share_rate
        assertThat(cap.getValue()).extracting(ConfigChangeLog::getField)
                .containsExactlyInAnyOrder("vet_consult_price", "vet_share_rate");
        verify(audit, times(1)).record(eq(7L), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void noChangeWritesNothing() {
        seedPricing();
        svc.updatePricing(new PricingForm(50000, 60, 10000, 5000, 1), 7L); // 全同
        verify(changeLogs, never()).saveAll(anyList());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void rejectsPremiumRateOver50() {
        PawCoinConfig c = instantiate(PawCoinConfig.class);
        set(c, "id", 1L);
        set(c, "premiumRate", 0);
        set(c, "topupPaused", false);
        when(pawcoinRepo.findById(1L)).thenReturn(Optional.of(c));
        assertThatThrownBy(() -> svc.updatePawCoin(new PawCoinForm(51, false), 7L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void disablingLastEnabledTierRejected() {
        PawCoinTopupTier tier = instantiate(PawCoinTopupTier.class);
        set(tier, "id", 3L);
        set(tier, "tierKey", "10k");
        set(tier, "enabled", true);
        when(tierRepo.findById(3L)).thenReturn(Optional.of(tier));
        when(tierRepo.countByEnabledTrue()).thenReturn(1L); // 只剩这一个启用

        assertThatThrownBy(() -> svc.setTierEnabled(3L, false, 7L))
                .isInstanceOf(AppException.class);
        verify(tierRepo, never()).save(any());
    }

    @Test
    void disablingTierWhenOthersEnabledSucceeds() {
        PawCoinTopupTier tier = instantiate(PawCoinTopupTier.class);
        set(tier, "id", 3L);
        set(tier, "tierKey", "10k");
        set(tier, "enabled", true);
        when(tierRepo.findById(3L)).thenReturn(Optional.of(tier));
        when(tierRepo.countByEnabledTrue()).thenReturn(3L);

        svc.setTierEnabled(3L, false, 7L);

        assertThat(tier.isEnabled()).isFalse();
        verify(tierRepo).save(tier);
        verify(changeLogs).saveAll(anyList());
        verify(audit).record(eq(7L), anyString(), anyString(), anyString(), anyString());
    }

    private static <T> T instantiate(Class<T> cls) {
        try {
            var ctor = cls.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object o, String name, Object value) {
        try {
            var f = o.getClass().getDeclaredField(name);
            f.setAccessible(true);
            f.set(o, value);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
