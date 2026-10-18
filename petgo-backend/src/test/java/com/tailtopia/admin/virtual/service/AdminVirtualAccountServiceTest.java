package com.tailtopia.admin.virtual.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.auth.domain.AccountType;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.shared.error.AppException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** L0（Story 9.8，A-6）：虚拟账号工厂 + 建号校验 + 仅 VIRTUAL 可启停。纯 Mockito。 */
class AdminVirtualAccountServiceTest {

    private UserRepository users;
    private AdminAuditService audit;
    private com.tailtopia.growth.service.GrowthDisplayLevelService displayLevels;
    private AdminVirtualAccountService svc;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        displayLevels = Mockito.mock(com.tailtopia.growth.service.GrowthDisplayLevelService.class);
        svc = new AdminVirtualAccountService(users, audit, displayLevels);
    }

    @Test
    void newVirtualIsVirtualNoLoginNoPassword() {
        User u = User.newVirtual("virtual:abc", "喵星人", null, 7L);
        assertThat(u.getAccountType()).isEqualTo(AccountType.VIRTUAL);
        assertThat(u.isEnabled()).isTrue();
        assertThat(u.getCreatedBy()).isEqualTo(7L);
        assertThat(u.getNickname()).isEqualTo("喵星人");
        // 无密码（无登录能力）。
        assertThat(u.getPasswordHash()).isNull();
    }

    @Test
    void createPersistsAndAudits() {
        when(users.save(any(User.class))).thenAnswer(inv -> {
            User u = inv.getArgument(0);
            setId(u, 42L); // 模拟 DB 赋 id
            return u;
        });
        long id = svc.create("汪星人", "https://x/a.jpg", 7L);
        assertThat(id).isEqualTo(42L);
        verify(audit).record(eq(7L), eq("VIRTUAL_ACCOUNT_CREATE"), anyString(), eq("42"), anyString());
        // V1.4.0 ① Story 2.2 · AC4：新建虚拟号随机展示等级（表单不加字段）。
        verify(displayLevels).assignInitial(42L);
    }

    private static void setId(User u, long id) {
        try {
            var f = User.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(u, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void createRejectsBlankNickname() {
        assertThatThrownBy(() -> svc.create("  ", null, 7L)).isInstanceOf(AppException.class);
        verify(users, never()).save(any());
        verify(displayLevels, never()).assignInitial(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void createRejectsTooLongNickname() {
        assertThatThrownBy(() -> svc.create("x".repeat(21), null, 7L)).isInstanceOf(AppException.class);
    }

    @Test
    void setEnabledRejectsRealAccount() {
        User real = User.newGoogleUser("g-1", "e@x", "真名", null);
        when(users.findById(5L)).thenReturn(Optional.of(real));
        assertThatThrownBy(() -> svc.setEnabled(5L, false, 7L)).isInstanceOf(AppException.class);
        verify(users, never()).save(any());
    }

    @Test
    void setEnabledTogglesVirtualAndAudits() {
        User v = User.newVirtual("virtual:x", "喵", null, 7L); // enabled=true
        when(users.findById(5L)).thenReturn(Optional.of(v));
        svc.setEnabled(5L, false, 7L);
        assertThat(v.isEnabled()).isFalse();
        verify(users).save(v);
        verify(audit).record(eq(7L), eq("VIRTUAL_ACCOUNT_DISABLE"), anyString(), anyString(), anyString());
    }

    @Test
    void setEnabledNoOpWhenUnchanged() {
        User v = User.newVirtual("virtual:x", "喵", null, 7L); // enabled=true
        when(users.findById(5L)).thenReturn(Optional.of(v));
        svc.setEnabled(5L, true, 7L); // 已启用
        verify(users, never()).save(any());
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    // ------------------------------------------------------------------ V1.4.0 ① Story 4.4 展示等级

    private User virtual(long id) {
        User u = User.newVirtual("virtual:x" + id, "喵星人", null, 7L);
        setId(u, id);
        when(users.findById(id)).thenReturn(java.util.Optional.of(u));
        return u;
    }

    @Test
    void setDisplayLevelWritesThroughGrowthAndAudits() {
        virtual(5L);
        when(displayLevels.set(5L, 12)).thenReturn(new com.tailtopia.growth.service.GrowthDisplayLevelService.Change(7, 12,
                true));

        svc.setDisplayLevel(5L, "12", 99L);

        verify(displayLevels).set(5L, 12);
        verify(audit).record(99L, com.tailtopia.admin.audit.service.AuditActions.GROWTH_DISPLAY_LEVEL_CHANGED, "user",
                "5", "displayLevel 7→12");
    }

    @Test
    void setDisplayLevelFromUninitializedAuditsDash() {
        virtual(5L);
        when(displayLevels.set(5L, 3)).thenReturn(new com.tailtopia.growth.service.GrowthDisplayLevelService.Change(null, 3,
                true));
        svc.setDisplayLevel(5L, "3", 99L);
        verify(audit).record(eq(99L), anyString(), eq("user"), eq("5"), eq("displayLevel —→3"));
    }

    @Test
    void unchangedDisplayLevelWritesNoAudit() {
        virtual(5L);
        when(displayLevels.set(5L, 7)).thenReturn(new com.tailtopia.growth.service.GrowthDisplayLevelService.Change(7, 7,
                false));
        svc.setDisplayLevel(5L, "7", 99L);
        verify(audit, never()).record(anyLong(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void displayLevelOnlyForVirtualAndExistingAccounts() {
        User real = User.newGoogleUser("g-1", "a@b.c", "Ana", null);
        setId(real, 6L);
        when(users.findById(6L)).thenReturn(java.util.Optional.of(real));
        assertThatThrownBy(() -> svc.setDisplayLevel(6L, "5", 99L))
                .satisfies(t -> assertThat(((com.tailtopia.shared.error.AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.display.virtualOnly"));
        when(users.findById(8L)).thenReturn(java.util.Optional.empty());
        assertThatThrownBy(() -> svc.setDisplayLevel(8L, "5", 99L))
                .satisfies(t -> assertThat(((com.tailtopia.shared.error.AppException) t).getMessageCode())
                        .isEqualTo("admin.err.virtualAccount.accountNotFound"));
        verify(displayLevels, never()).set(anyLong(), org.mockito.ArgumentMatchers.anyInt());
    }

    @Test
    void nonNumericDisplayLevelIsRangeChecked() {
        virtual(5L);
        when(displayLevels.set(5L, 0)).thenThrow(com.tailtopia.shared.error.AppException.validation("x")
                .code("admin.err.growth.display.outOfRange", 20));
        assertThatThrownBy(() -> svc.setDisplayLevel(5L, "Lv3", 99L))
                .satisfies(t -> assertThat(((com.tailtopia.shared.error.AppException) t).getMessageCode())
                        .isEqualTo("admin.err.growth.display.outOfRange"));
    }
}
