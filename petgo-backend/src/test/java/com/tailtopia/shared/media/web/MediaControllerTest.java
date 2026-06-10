package com.petgo.shared.media.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.petgo.shared.error.AppException;
import com.petgo.shared.media.MediaScope;
import com.petgo.shared.media.StsService;
import com.petgo.shared.media.dto.StsCredentialRequest;
import com.petgo.shared.media.dto.StsCredentialResponse;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0：控制器逻辑（无 Spring context）——JWT 主体解析、限流调用、委派 service。
 */
class MediaControllerTest {

    private final StsService stsService = mock(StsService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final MediaController controller = new MediaController(stsService, rateLimiter);

    private static Jwt jwtForUser(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void issuesCredentialForAuthenticatedUserWithRateLimit() {
        StsCredentialResponse stub = new StsCredentialResponse(
                "ak", "sk", "tok", "2026-06-02T00:00:00Z", "petgo-public",
                "ap-southeast-5", "ep", "https://cdn", "public/42/");
        when(stsService.issueUploadCredential(eq(MediaScope.PUBLIC), eq(42L))).thenReturn(stub);

        StsCredentialResponse resp = controller.issueCredential(
                jwtForUser("42"), new StsCredentialRequest(MediaScope.PUBLIC, "image/jpeg", 1));

        assertThat(resp.bucket()).isEqualTo("petgo-public");
        verify(rateLimiter).check(eq("rl:media:sts:42"), anyInt(), eq(Duration.ofMinutes(1)));
        verify(stsService).issueUploadCredential(MediaScope.PUBLIC, 42L);
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.issueCredential(
                null, new StsCredentialRequest(MediaScope.PUBLIC, null, 1)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsNonNumericSubject() {
        assertThatThrownBy(() -> controller.issueCredential(
                jwtForUser("not-a-number"), new StsCredentialRequest(MediaScope.PUBLIC, null, 1)))
                .isInstanceOf(AppException.class);
    }
}
