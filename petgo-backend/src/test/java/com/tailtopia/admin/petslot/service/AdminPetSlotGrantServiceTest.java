package com.tailtopia.admin.petslot.service;

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
import com.tailtopia.admin.audit.service.AuditActions;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.profile.slot.AdminSlotGrantCommand;
import com.tailtopia.profile.slot.AdminSlotGrantResult;
import com.tailtopia.profile.slot.PetSlotPurchaseService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * L0：V1.4.0 multi-pet Story 4.3 · AC7 —— 补发审计：首次补发写一条 {@code PET_SLOT_GRANTED}（摘要照
 * {@code grantPawCoin} 写法），重复提交（replayed）不写；写入失败（422）不写。纯 Mockito。
 */
class AdminPetSlotGrantServiceTest {

    private final PetSlotPurchaseService purchases = mock(PetSlotPurchaseService.class);
    private final AdminAuditService audit = mock(AdminAuditService.class);
    private final AdminPetSlotGrantService service = new AdminPetSlotGrantService(purchases, audit, new AdminTime());
    /** 2026-10-20 15:40 WIB。 */
    private static final Instant EXPIRES = Instant.parse("2026-10-20T08:40:00Z");

    @Test
    void firstGrantWritesOneAuditRowWithSummary() {
        when(purchases.grantByAdmin(any())).thenReturn(new AdminSlotGrantResult(EXPIRES, false, 7));

        AdminSlotGrantResult r = service.grant(42L, 7, " Klaim QRIS ", " tok-1 ", 900L);

        assertThat(r.replayed()).isFalse();
        ArgumentCaptor<AdminSlotGrantCommand> cmd = ArgumentCaptor.forClass(AdminSlotGrantCommand.class);
        verify(purchases).grantByAdmin(cmd.capture());
        assertThat(cmd.getValue()).isEqualTo(new AdminSlotGrantCommand(42L, 7, " Klaim QRIS ", " tok-1 ", 900L));
        verify(audit).record(900L, AuditActions.PET_SLOT_GRANTED, "USER", "42",
                "补发宠物名额（天数：7；到期：2026-10-20 15:40 WIB；原因：Klaim QRIS；幂等键：ADMIN_GRANT:tok-1）");
    }

    @Test
    void replayDoesNotAuditAgain() {
        when(purchases.grantByAdmin(any())).thenReturn(new AdminSlotGrantResult(EXPIRES, true, 7));

        assertThat(service.grant(42L, 7, "r", "tok-1", 900L).replayed()).isTrue();
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void failedGrantDoesNotAudit() {
        when(purchases.grantByAdmin(any())).thenThrow(AppException.validation("x").code("admin.err.petSlot.limitReached"));

        assertThatThrownBy(() -> service.grant(42L, 7, "r", "tok-1", 900L)).isInstanceOf(AppException.class);
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }
}
