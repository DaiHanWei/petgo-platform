package com.tailtopia.shop.shipping.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shop.shipping.domain.ShippingSettings;
import com.tailtopia.shop.shipping.repository.ShippingSettingsRepository;
import com.tailtopia.shop.shipping.repository.ShippingZoneRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.BeanUtils;

/**
 * L0：v1.4.2 shop Story 1.1 · 发货地配置校验（长度上限与列宽一致，超长 422 而非 DB 500；邮编格式）。
 */
class AdminShippingZoneServiceOriginTest {

    private final ShippingSettingsRepository settingsRepo = mock(ShippingSettingsRepository.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminShippingZoneService service = new AdminShippingZoneService(
            mock(ShippingZoneRepository.class), settingsRepo, audit);
    private ShippingSettings settings;

    @BeforeEach
    void setUp() {
        settings = BeanUtils.instantiateClass(ShippingSettings.class);
        when(settingsRepo.findById(ShippingSettings.SINGLETON_ID)).thenReturn(Optional.of(settings));
        when(settingsRepo.save(any(ShippingSettings.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private void assertValidation(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(AppException.class, e -> {
            assertThat(e.getStatus().value()).isEqualTo(422);
            assertThat(e.getType()).isEqualTo(ErrorTypes.VALIDATION);
        });
        verify(settingsRepo, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("恰好到上限（60 / 20 / 300）可保存，审计不含联系人 / 电话 / 地址")
    void atLimitAccepted() {
        String name = "n".repeat(60);
        String phone = "6".repeat(20);
        String addr = "a".repeat(300);
        ShippingSettings s = service.setOrigin("12440", name, phone, addr, 1L);
        assertThat(s.getOriginContactName()).isEqualTo(name);
        assertThat(s.getOriginContactPhone()).isEqualTo(phone);
        assertThat(s.getOriginAddress()).isEqualTo(addr);
        assertThat(s.getOriginPostalCode()).isEqualTo("12440");
        org.mockito.ArgumentCaptor<String> summary = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(audit).record(org.mockito.ArgumentMatchers.eq(1L), anyString(), anyString(), anyString(),
                summary.capture());
        assertThat(summary.getValue()).doesNotContain(name).doesNotContain(phone).doesNotContain(addr)
                .doesNotContain("12440");
    }

    @Test
    @DisplayName("联系人超 60 → 422")
    void contactNameTooLong() {
        assertValidation(() -> service.setOrigin("12440", "n".repeat(61), "62812", "Jl. X", 1L));
    }

    @Test
    @DisplayName("电话超 20 → 422")
    void contactPhoneTooLong() {
        assertValidation(() -> service.setOrigin("12440", "Budi", "6".repeat(21), "Jl. X", 1L));
    }

    @Test
    @DisplayName("地址超 300 → 422")
    void addressTooLong() {
        assertValidation(() -> service.setOrigin("12440", "Budi", "62812", "a".repeat(301), 1L));
    }

    @Test
    @DisplayName("首尾空白不计入长度（与落库 trim 口径一致）")
    void surroundingWhitespaceIgnored() {
        ShippingSettings s = service.setOrigin("12440", "  " + "n".repeat(60) + "  ", "62812", "Jl. X", 1L);
        assertThat(s.getOriginContactName()).hasSize(60);
    }

    @Test
    @DisplayName("邮编格式不对 → 422")
    void badPostalCode() {
        assertValidation(() -> service.setOrigin("01234", "Budi", "62812", "Jl. X", 1L));
    }

    @Test
    @DisplayName("实体层兜底：applyOrigin 超长 → IllegalArgumentException")
    void entityGuard() {
        assertThatThrownBy(() -> settings.applyOrigin("12440", "n".repeat(61), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.applyOrigin("12440", null, "6".repeat(21), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> settings.applyOrigin("12440", null, null, "a".repeat(301)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
