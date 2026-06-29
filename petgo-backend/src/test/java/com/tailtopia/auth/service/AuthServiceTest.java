package com.tailtopia.auth.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.domain.RefreshToken;
import com.tailtopia.auth.domain.User;
import com.tailtopia.auth.dto.LoginResponse;
import com.tailtopia.auth.dto.TokenResponse;
import com.tailtopia.auth.repository.RefreshTokenRepository;
import com.tailtopia.auth.repository.UserRepository;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.security.AppleIdentity;
import com.tailtopia.shared.security.AppleTokenVerifier;
import com.tailtopia.shared.security.GoogleIdentity;
import com.tailtopia.shared.security.GoogleTokenVerifier;
import com.tailtopia.shared.security.JwtService;
import com.tailtopia.vet.service.VetAccountService;
import java.time.Instant;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    AppleTokenVerifier appleVerifier;
    @Mock
    JwtService jwt;
    @Mock
    VetAccountService vetAccounts;
    @Mock
    PetProfileRepository petProfiles;

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
        assertThat(resp.profile().hasPetProfile()).isFalse(); // 新建号必无档案（短路免查）
        verify(users).save(any(User.class));
    }

    @Test
    void returningGoogleUserWithPetProfileReportsHasPet() {
        // 用户反馈根治：老用户登录响应须按真实档案返回 hasPetProfile，而非恒 false。
        User existing = User.newGoogleUser("sub-1", "a@b.com", "Alice", "http://pic");
        ReflectionTestUtils.setField(existing, "id", 7L);
        when(googleVerifier.verify("idtok"))
                .thenReturn(new GoogleIdentity("sub-1", "a@b.com", "Alice", "http://pic"));
        when(users.findByGoogleSub("sub-1")).thenReturn(Optional.of(existing));
        when(petProfiles.existsByOwnerId(7L)).thenReturn(true);
        stubIssue();

        LoginResponse resp = authService.loginWithGoogle("idtok");

        assertThat(resp.isNewUser()).isFalse();
        assertThat(resp.profile().hasPetProfile()).isTrue();
    }

    @Test
    void googleVerifyFailureCreatesNoAccount() {
        // Story 1.3 R2（F13）：OAuth 校验失败 → 短路抛异常，绝不建号/不签发。
        when(googleVerifier.verify("bad")).thenThrow(AppException.unauthorized("无效的 Google 凭证"));

        assertThatThrownBy(() -> authService.loginWithGoogle("bad")).isInstanceOf(AppException.class);
        verify(users, never()).save(any(User.class));
        verify(refreshTokens, never()).save(any(RefreshToken.class));
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

    // ===== FR-44：Apple 登录（与 Google 同构）=====

    @Test
    void firstAppleLoginCreatesUserAndFlagsNew() {
        when(appleVerifier.verify("apple-tok"))
                .thenReturn(new AppleIdentity("apple-sub-1", "a@privaterelay.appleid.com"));
        when(users.findByAppleSub("apple-sub-1")).thenReturn(Optional.empty());
        when(users.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));
        stubIssue();

        LoginResponse resp = authService.loginWithApple("apple-tok");

        assertThat(resp.isNewUser()).isTrue();
        assertThat(resp.role()).isEqualTo("USER");
        assertThat(resp.accessToken()).isEqualTo("access-token");
        assertThat(resp.refreshToken()).isEqualTo("raw-refresh");
        assertThat(resp.onboardingCompleted()).isFalse();
        verify(users).save(any(User.class));
    }

    @Test
    void appleVerifyFailureCreatesNoAccount() {
        // 与 Google 同口径：校验失败短路抛异常，绝不建号/不签发。
        when(appleVerifier.verify("bad")).thenThrow(AppException.unauthorized("无效的 Apple 凭证"));

        assertThatThrownBy(() -> authService.loginWithApple("bad")).isInstanceOf(AppException.class);
        verify(users, never()).save(any(User.class));
        verify(refreshTokens, never()).save(any(RefreshToken.class));
    }

    @Test
    void secondAppleLoginReturnsExistingUserNotNew() {
        User existing = User.newAppleUser("apple-sub-1", "a@privaterelay.appleid.com");
        when(appleVerifier.verify("apple-tok"))
                .thenReturn(new AppleIdentity("apple-sub-1", "a@privaterelay.appleid.com"));
        when(users.findByAppleSub("apple-sub-1")).thenReturn(Optional.of(existing));
        stubIssue();

        LoginResponse resp = authService.loginWithApple("apple-tok");

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
    void logoutRevokesRefreshHandleWithoutDeletingData() {
        RefreshToken token = new RefreshToken(7L, "h", Instant.now().plusSeconds(3600));
        when(jwt.hashRefresh("raw")).thenReturn("h");
        when(refreshTokens.findByTokenHash("h")).thenReturn(Optional.of(token));

        authService.logout("raw");

        assertThat(token.isRevoked()).isTrue();
        verify(refreshTokens).save(token);
    }

    @Test
    void logoutWithUnknownTokenIsSilentlyOk() {
        when(jwt.hashRefresh("ghost")).thenReturn("gh");
        when(refreshTokens.findByTokenHash("gh")).thenReturn(Optional.empty());
        authService.logout("ghost"); // 幂等，不抛
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
