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
    private AdminVirtualAccountService svc;

    @BeforeEach
    void setUp() {
        users = Mockito.mock(UserRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new AdminVirtualAccountService(users, audit);
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
}
