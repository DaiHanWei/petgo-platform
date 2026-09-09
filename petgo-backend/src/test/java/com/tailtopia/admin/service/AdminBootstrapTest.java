package com.tailtopia.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.admin.account.domain.AdminAccount;
import com.tailtopia.admin.account.domain.AdminAccountStatus;
import com.tailtopia.admin.account.repository.AdminAccountRepository;
import com.tailtopia.auth.domain.Role;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.repository.UserRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * L0：bootstrap 超管 upsert 在「邮箱仅 ACTIVE 唯一」（V1.3.0 Story 1.3，D-21）下的三条路径：
 * 有 ACTIVE 行 → 重置密码；只有已停用行 → 复活最新那行（不建第二个超管）；都没有 → 建号。
 */
class AdminBootstrapTest {

    private AdminAccountRepository adminAccounts;
    private UserRepository users;
    private AdminBootstrap bootstrap;

    @BeforeEach
    void setUp() {
        adminAccounts = mock(AdminAccountRepository.class);
        users = mock(UserRepository.class);
        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(anyString())).thenReturn("{bcrypt}h");
        when(users.findByEmailAndRole(anyString(), any(Role.class)))
                .thenReturn(Optional.of(mock(User.class)));
        when(adminAccounts.save(any(AdminAccount.class))).thenAnswer(inv -> inv.getArgument(0));
        bootstrap = new AdminBootstrap(adminAccounts, users, encoder, "boot@tailtopia.id", "pw");
    }

    private AdminAccount account(long id, AdminAccountStatus status) {
        AdminAccount a = AdminAccount.newSuperAdmin("boot@tailtopia.id", "运营", "{bcrypt}old");
        ReflectionTestUtils.setField(a, "id", id);
        ReflectionTestUtils.setField(a, "status", status);
        return a;
    }

    @Test
    void activeRowGetsPasswordReset() {
        AdminAccount active = account(1L, AdminAccountStatus.ACTIVE);
        when(adminAccounts.findByLarkEmailIgnoreCaseAndStatus("boot@tailtopia.id", AdminAccountStatus.ACTIVE))
                .thenReturn(Optional.of(active));
        bootstrap.run(null);
        assertThat(active.getPasswordHash()).isEqualTo("{bcrypt}h");
        verify(adminAccounts, never()).findByLarkEmailIgnoreCaseOrderByIdDesc(anyString());
    }

    @Test
    void disabledRowIsRevivedInsteadOfCreatingSecondSuperAdmin() {
        AdminAccount disabled = account(1L, AdminAccountStatus.DISABLED);
        when(adminAccounts.findByLarkEmailIgnoreCaseAndStatus("boot@tailtopia.id", AdminAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(adminAccounts.findByLarkEmailIgnoreCaseOrderByIdDesc("boot@tailtopia.id"))
                .thenReturn(List.of(disabled));
        bootstrap.run(null);
        assertThat(disabled.getStatus()).isEqualTo(AdminAccountStatus.ACTIVE);
        assertThat(disabled.getPasswordHash()).isEqualTo("{bcrypt}h");
        ArgumentCaptor<AdminAccount> saved = ArgumentCaptor.forClass(AdminAccount.class);
        verify(adminAccounts).save(saved.capture());
        assertThat(saved.getValue()).isSameAs(disabled);
    }

    @Test
    void noRowCreatesSuperAdmin() {
        when(adminAccounts.findByLarkEmailIgnoreCaseAndStatus("boot@tailtopia.id", AdminAccountStatus.ACTIVE))
                .thenReturn(Optional.empty());
        when(adminAccounts.findByLarkEmailIgnoreCaseOrderByIdDesc("boot@tailtopia.id")).thenReturn(List.of());
        bootstrap.run(null);
        ArgumentCaptor<AdminAccount> saved = ArgumentCaptor.forClass(AdminAccount.class);
        verify(adminAccounts).save(saved.capture());
        assertThat(saved.getValue().getId()).isNull();
        assertThat(saved.getValue().getLarkEmail()).isEqualTo("boot@tailtopia.id");
    }
}
