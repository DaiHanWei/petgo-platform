package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.dto.LoginResponse;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SocialLoginServiceTest {

    private final AuthService authService = mock(AuthService.class);
    private final SocialLoginService service = new SocialLoginService(authService);
    private final LoginResponse ok = mock(LoginResponse.class);

    private static DataIntegrityViolationException violation(String constraint) {
        return new DataIntegrityViolationException("could not execute statement",
                new RuntimeException("duplicate key value violates unique constraint \"" + constraint + "\""));
    }

    @Test
    void googleSignupRace_retriesOnceAndReturnsExistingAccount() {
        when(authService.loginWithGoogle("tok"))
                .thenThrow(violation(SocialLoginService.GOOGLE_SUB_CONSTRAINT))
                .thenReturn(ok);

        assertThat(service.loginWithGoogle("tok")).isSameAs(ok);
        verify(authService, times(2)).loginWithGoogle("tok");
    }

    @Test
    void appleSignupRace_retriesOnceAndReturnsExistingAccount() {
        when(authService.loginWithApple("tok"))
                .thenThrow(violation(SocialLoginService.APPLE_SUB_CONSTRAINT))
                .thenReturn(ok);

        assertThat(service.loginWithApple("tok")).isSameAs(ok);
        verify(authService, times(2)).loginWithApple("tok");
    }

    @Test
    void otherIntegrityViolation_isNotRetried() {
        when(authService.loginWithGoogle("tok")).thenThrow(violation("uq_users_public_token"));

        assertThatThrownBy(() -> service.loginWithGoogle("tok"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(authService, times(1)).loginWithGoogle("tok");
    }

    @Test
    void secondConflict_propagatesAfterSingleRetry() {
        when(authService.loginWithGoogle("tok"))
                .thenThrow(violation(SocialLoginService.GOOGLE_SUB_CONSTRAINT));

        assertThatThrownBy(() -> service.loginWithGoogle("tok"))
                .isInstanceOf(DataIntegrityViolationException.class);
        verify(authService, times(2)).loginWithGoogle("tok");
    }
}
