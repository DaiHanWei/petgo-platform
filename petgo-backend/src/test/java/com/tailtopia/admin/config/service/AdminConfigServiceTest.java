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
import com.tailtopia.admin.config.dto.KtpPricingForm;
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
    private com.tailtopia.config.repository.FeedRankConfigRepository feedRankRepo;
    private AdminConfigService svc;

    @BeforeEach
    void setUp() {
        pricingRepo = Mockito.mock(PricingConfigRepository.class);
        pawcoinRepo = Mockito.mock(PawCoinConfigRepository.class);
        tierRepo = Mockito.mock(PawCoinTopupTierRepository.class);
        changeLogs = Mockito.mock(ConfigChangeLogRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        // V1.1.6 Story 16.4：推荐算法参数（本类既有用例不碰它，另有专门用例）
        feedRankRepo = Mockito.mock(com.tailtopia.config.repository.FeedRankConfigRepository.class);
        svc = new AdminConfigService(pricingRepo, pawcoinRepo, tierRepo, changeLogs, audit,
                feedRankRepo);
    }

    private PricingConfig seedPricing() {
        PricingConfig c = instantiate(PricingConfig.class);
        set(c, "id", 1L);
        set(c, "vetConsultPrice", 50000L);
        set(c, "vetShareRate", 60);
        set(c, "aiUnlockPrice", 10000L);
        set(c, "idHdDownloadPrice", 5000L);
        set(c, "passportPageUnlockPrice", 5000L);
        set(c, "passportBoardingUnlockPrice", 5000L);
        set(c, "monthlyFreeQuota", 1);
        when(pricingRepo.findById(1L)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void rejectsShareRateOver100() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(50000, 101, 10000, 1), 7L))
                .isInstanceOf(AppException.class);
        verify(changeLogs, never()).saveAll(anyList());
    }

    @Test
    void rejectsFreeQuotaOver35() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(50000, 60, 10000, 36), 7L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsNegativePrice() {
        seedPricing();
        assertThatThrownBy(() -> svc.updatePricing(new PricingForm(-1, 60, 10000, 1), 7L))
                .isInstanceOf(AppException.class);
    }

    @Test
    void logsOnlyChangedFieldsAndAuditsOnce() {
        seedPricing();
        // 仅改单价 + 分成两字段。
        svc.updatePricing(new PricingForm(60000, 55, 10000, 1), 7L);

        ArgumentCaptor<List<ConfigChangeLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(changeLogs).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(2); // vet_consult_price + vet_share_rate
        assertThat(cap.getValue()).extracting(ConfigChangeLog::getField)
                .containsExactlyInAnyOrder("vet_consult_price", "vet_share_rate");
        verify(audit, times(1)).record(eq(7L), anyString(), anyString(), anyString(), anyString());
    }

    // ── V1.3.0 Story 6.1：KTP 模块高清图解锁定价三行（D-7 三价一律 ≥1）──────────
    @Test
    void ktpPricingRejectsZeroOrNegativeOnAnyOfTheThree() {
        seedPricing();
        assertThatThrownBy(() -> svc.updateKtpPricing(new KtpPricingForm(0, 5000, 5000), 7L))
                .isInstanceOf(AppException.class).hasMessageContaining("≥1");
        assertThatThrownBy(() -> svc.updateKtpPricing(new KtpPricingForm(5000, -1, 5000), 7L))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> svc.updateKtpPricing(new KtpPricingForm(5000, 5000, 0), 7L))
                .isInstanceOf(AppException.class);
        verify(changeLogs, never()).saveAll(anyList());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void ktpPricingLogsOnlyChangedColumnsIndependentlyAndAuditsOnce() {
        PricingConfig c = seedPricing();
        svc.updateKtpPricing(new KtpPricingForm(5000, 8000, 5000), 7L); // 只改护照内页

        ArgumentCaptor<List<ConfigChangeLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(changeLogs).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting(ConfigChangeLog::getField).containsExactly("passport_page_unlock_price");
        assertThat(c.getPassportPageUnlockPrice()).isEqualTo(8000);
        assertThat(c.getIdHdDownloadPrice()).isEqualTo(5000); // 不联动
        assertThat(c.getPassportBoardingUnlockPrice()).isEqualTo(5000);
        verify(audit, times(1)).record(eq(7L), eq("CONFIG_UPDATE_PRICING"), anyString(), anyString(), anyString());
    }

    @Test
    void ktpPricingNoChangeWritesNothing() {
        seedPricing();
        svc.updateKtpPricing(new KtpPricingForm(5000, 5000, 5000), 7L);
        verify(changeLogs, never()).saveAll(anyList());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void pricingCardNoLongerTouchesTheHdPrice() {
        PricingConfig c = seedPricing();
        svc.updatePricing(new PricingForm(60000, 60, 10000, 1), 7L);
        assertThat(c.getIdHdDownloadPrice()).isEqualTo(5000);
        ArgumentCaptor<List<ConfigChangeLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(changeLogs).saveAll(cap.capture());
        assertThat(cap.getValue()).extracting(ConfigChangeLog::getField).doesNotContain("id_hd_download_price");
    }

    @Test
    void noChangeWritesNothing() {
        seedPricing();
        svc.updatePricing(new PricingForm(50000, 60, 10000, 1), 7L); // 全同
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
        assertThatThrownBy(() -> svc.updatePawCoin(new PawCoinForm(51, 0, false), 7L))
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

    // ── V1.3.0 Story 6.2：新建档位 / 启用上限 ≤4（advisory 锁在 L1 验，单测 em 为 null 跳过）──
    private PawCoinTopupTier tier(long id, String key, long amount, boolean enabled, int sort) {
        PawCoinTopupTier t = instantiate(PawCoinTopupTier.class);
        set(t, "id", id);
        set(t, "tierKey", key);
        set(t, "amountIdr", amount);
        set(t, "enabled", enabled);
        set(t, "sortOrder", sort);
        return t;
    }

    @Test
    void createTierKeysByAmountResortsAllLogsAndAudits() {
        PawCoinTopupTier a = tier(1L, "10k", 10_000, true, 1);
        PawCoinTopupTier b = tier(2L, "50k", 50_000, false, 2);
        when(tierRepo.existsByAmountIdr(25_000L)).thenReturn(false);
        when(tierRepo.countByEnabledTrue()).thenReturn(1L);
        when(tierRepo.save(any(PawCoinTopupTier.class))).thenAnswer(inv -> {
            PawCoinTopupTier t = inv.getArgument(0);
            set(t, "id", 3L);
            return t;
        });
        when(tierRepo.findAllByOrderByAmountIdrAsc()).thenAnswer(inv -> {
            PawCoinTopupTier n = tier(3L, "t25000", 25_000, true, 0);
            return List.of(a, n, b);
        });

        PawCoinTopupTier created = svc.createTier(25_000L, 7L);

        assertThat(created.getTierKey()).isEqualTo("t25000");
        assertThat(created.isEnabled()).isTrue();
        assertThat(created.getAmountIdr()).isEqualTo(25_000L);
        assertThat(a.getSortOrder()).isEqualTo(1);
        assertThat(b.getSortOrder()).isEqualTo(3); // 全部档位（含停用）按金额升序重排 1..n
        ArgumentCaptor<List<ConfigChangeLog>> cap = ArgumentCaptor.forClass(List.class);
        verify(changeLogs).saveAll(cap.capture());
        assertThat(cap.getValue()).hasSize(1);
        assertThat(cap.getValue().get(0).getField()).isEqualTo("tier.t25000.created");
        assertThat(cap.getValue().get(0).getNewValue()).isEqualTo("25000");
        verify(audit).record(eq(7L), eq(com.tailtopia.admin.audit.service.AuditActions.TIER_CREATED), anyString(), eq("tier:t25000"), anyString());
    }

    @Test
    void createTierRejectsDuplicateAmountCapAndNonPositive() {
        assertThatThrownBy(() -> svc.createTier(0L, 7L)).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> svc.createTier(AdminConfigService.MAX_TIER_AMOUNT + 1, 7L)).isInstanceOf(AppException.class)
                .hasMessageContaining("100000000");
        when(tierRepo.existsByAmountIdr(10_000L)).thenReturn(true);
        assertThatThrownBy(() -> svc.createTier(10_000L, 7L)).isInstanceOf(AppException.class).hasMessageContaining("相同金额");
        when(tierRepo.existsByAmountIdr(75_000L)).thenReturn(false);
        when(tierRepo.countByEnabledTrue()).thenReturn(4L);
        assertThatThrownBy(() -> svc.createTier(75_000L, 7L)).isInstanceOf(AppException.class).hasMessageContaining("4 个启用档位");
        verify(tierRepo, never()).save(any());
        verify(changeLogs, never()).saveAll(anyList());
    }

    @Test
    void enablingFifthTierRejectedButDisablingStillWorks() {
        PawCoinTopupTier off = tier(9L, "t75000", 75_000, false, 5);
        when(tierRepo.findById(9L)).thenReturn(Optional.of(off));
        when(tierRepo.countByEnabledTrue()).thenReturn(4L);
        assertThatThrownBy(() -> svc.setTierEnabled(9L, true, 7L)).isInstanceOf(AppException.class).hasMessageContaining("4 个启用档位");
        assertThat(off.isEnabled()).isFalse();
        verify(tierRepo, never()).save(any());

        when(tierRepo.countByEnabledTrue()).thenReturn(3L);
        svc.setTierEnabled(9L, true, 7L);
        assertThat(off.isEnabled()).isTrue();
        verify(tierRepo).save(off);
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
