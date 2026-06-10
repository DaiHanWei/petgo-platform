package com.tailtopia.vet.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.vet.domain.VetAccount;
import com.tailtopia.vet.domain.VetStatus;
import com.tailtopia.vet.repository.VetAccountRepository;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * L0 单元测试（无 DB）：开户 BCrypt 落库（非明文）、登录防枚举、BANNED 不可登录、唯一校验。
 */
@ExtendWith(MockitoExtension.class)
class VetAccountServiceTest {

    @Mock
    VetAccountRepository repo;

    private final PasswordEncoder encoder = new BCryptPasswordEncoder();

    private VetAccountService service() {
        return new VetAccountService(repo, encoder);
    }

    @Test
    void createHashesPasswordNotPlaintext() {
        when(repo.existsByUsername("vet001")).thenReturn(false);
        when(repo.save(any(VetAccount.class))).thenAnswer(inv -> inv.getArgument(0));

        VetAccount created = service().create("王医生", "vet001", "secret-pass");

        assertThat(created.getPasswordHash()).isNotEqualTo("secret-pass");
        assertThat(created.getPasswordHash()).startsWith("$2");
        assertThat(encoder.matches("secret-pass", created.getPasswordHash())).isTrue();
        assertThat(created.getStatus()).isEqualTo(VetStatus.ACTIVE);
    }

    @Test
    void createRejectsDuplicateUsername() {
        when(repo.existsByUsername("dup")).thenReturn(true);
        assertThatThrownBy(() -> service().create("王医生", "dup", "secret-pass"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void createRejectsShortPassword() {
        assertThatThrownBy(() -> service().create("王医生", "vet001", "short"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void authenticateSucceedsForActiveWithCorrectPassword() {
        VetAccount vet = VetAccount.create("王医生", encoder.encode("secret-pass"), "王医生");
        when(repo.findByUsername("vet001")).thenReturn(Optional.of(vet));

        assertThat(service().authenticate("vet001", "secret-pass")).isSameAs(vet);
    }

    @Test
    void authenticateRejectsWrongPasswordAsUnauthorized() {
        VetAccount vet = VetAccount.create("王医生", encoder.encode("secret-pass"), "王医生");
        when(repo.findByUsername("vet001")).thenReturn(Optional.of(vet));

        assertThatThrownBy(() -> service().authenticate("vet001", "wrong"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void authenticateRejectsUnknownUserWithoutEnumeration() {
        when(repo.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().authenticate("ghost", "whatever"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("账号或密码错误");
    }

    @Test
    void authenticateRejectsBannedAccount() {
        VetAccount vet = VetAccount.create("王医生", encoder.encode("secret-pass"), "王医生");
        vet.setStatus(VetStatus.BANNED);
        when(repo.findByUsername("vet001")).thenReturn(Optional.of(vet));

        assertThatThrownBy(() -> service().authenticate("vet001", "secret-pass"))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("账号或密码错误");
    }
}
