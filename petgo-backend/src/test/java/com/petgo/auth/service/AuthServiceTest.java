package com.petgo.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.auth.domain.RefreshToken;
import com.petgo.auth.domain.User;
import com.petgo.auth.dto.LoginResponse;
import com.petgo.auth.dto.TokenResponse;
import com.petgo.auth.repository.RefreshTokenRepository;
import com.petgo.auth.repository.UserRepository;
import com.petgo.shared.error.AppException;
import com.petgo.shared.security.GoogleIdentity;
import com.petgo.shared.security.GoogleTokenVerifier;
import com.petgo.shared.security.JwtService;
import com.petgo.vet.service.VetAccountService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * L0 单元测试（无 DB/容器）：登录建号/取号分流（AC1/AC4）+ refresh 轮换（AC3）。
 * GoogleTokenVerifier / JwtService / Repository 全部 mock。
 */
@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    UserRepository users;
    @Mock
    RefreshTokenRepository refreshTokens;
    @Mock
    GoogleTokenVerifier googleVerifier;
    @Mock
    JwtService jwt;
    @Mock
    VetAccountService vetAccounts;

    @InjectMocks
    AuthService authService;

    private void stubIssue() {
        lenient().when(jwt.issueAccessToken(any())).thenReturn("access-token");
        lenient().when(jwt.generateRefreshRaw()).thenReturn("raw-refresh");
        lenient().when(jwt.hashRefresh("raw-refresh")).thenReturn("hash-refresh");
        lenient().when(jwt.refreshExpiry(any())).thenReturn(Instant.now().plusSeconds(3600));
    }

    @Test
    void firstGoogleLoginCreatesUserAndFlagsNew() {
        when(googleVerifier.verify("idtok"))
                .thenReturn(new GoogleIdentity("sub-1", "a@b.com", "Alice", "http://pic"));
        when(users.findByGoogleSub("sub-1")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubIssue();

        LoginResponse resp = authService.loginWithGoogle("idtok");

        assertThat(resp.isNewUser()).isTrue();
        assertThat(resp.role()).isEqualTo("USER");
        assertThat(resp.accessToken()).isEqualTo("access-token");
        assertThat(resp.refreshToken()).isEqualTo("raw-refresh");
        assertThat(resp.onboardingCompleted()).isFalse();
        verify(users).save(any(User.class));
    }

    @Test
    void secondGoogleLoginReturnsExistingUserNotNew() {
        User existing = User.newGoogleUser("sub-1", "a@b.com", "Alice", "http://pic");
        when(googleVerifier.verify("idtok"))
                .thenReturn(new GoogleIdentity("sub-1", "a@b.com", "Alice", "http://pic"));
        when(users.findByGoogleSub("sub-1")).thenReturn(Optional.of(existing));
        stubIssue();

        LoginResponse resp = authService.loginWithGoogle("idtok");

        assertThat(resp.isNewUser()).isFalse();
    }

    @Test
    void refreshRotationRevokesOldAndIssuesNew() {
        RefreshToken token = new RefreshToken(7L, "old-hash", Instant.now().plusSeconds(3600));
        User user = User.newGoogleUser("sub-1", "a@b.com", "Alice", "http://pic");
        when(jwt.hashRefresh("old-raw")).thenReturn("old-hash");
        when(refreshTokens.findByTokenHash("old-hash")).thenReturn(Optional.of(token));
        when(users.findById(7L)).thenReturn(Optional.of(user));
        when(jwt.issueAccessToken(user)).thenReturn("new-access");
        when(jwt.generateRefreshRaw()).thenReturn("new-raw");
        when(jwt.hashRefresh("new-raw")).thenReturn("new-hash");
        when(jwt.refreshExpiry(any())).thenReturn(Instant.now().plusSeconds(7200));

        TokenResponse resp = authService.rotateRefresh("old-raw");

        assertThat(resp.accessToken()).isEqualTo("new-access");
        assertThat(resp.refreshToken()).isEqualTo("new-raw");
        assertThat(token.isRevoked()).isTrue(); // 旧句柄失效防重放
    }

    @Test
    void refreshWithUnknownTokenIsUnauthorized() {
        when(jwt.hashRefresh("bad")).thenReturn("bad-hash");
        when(refreshTokens.findByTokenHash("bad-hash")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.rotateRefresh("bad"))
                .isInstanceOf(AppException.class);
    }

    @Test
    void refreshWithRevokedTokenIsUnauthorized() {
        RefreshToken token = new RefreshToken(7L, "h", Instant.now().plusSeconds(3600));
        token.revoke();
        when(jwt.hashRefresh("replayed")).thenReturn("h");
        when(refreshTokens.findByTokenHash("h")).thenReturn(Optional.of(token));

        assertThatThrownBy(() -> authService.rotateRefresh("replayed"))
                .isInstanceOf(AppException.class);
    }
}
