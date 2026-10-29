package com.tailtopia.shared.media.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.MediaScope;
import com.tailtopia.shared.media.PresignedUploadService;
import com.tailtopia.shared.media.dto.UploadUrlRequest;
import com.tailtopia.shared.media.dto.UploadUrlResponse;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0：控制器逻辑（无 Spring context）——JWT 主体解析、限流调用、委派 service。
 */
class MediaControllerTest {

    private final PresignedUploadService uploadService = mock(PresignedUploadService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final MediaController controller = new MediaController(uploadService, rateLimiter);

    private static Jwt jwtForUser(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void issuesUploadUrlForAuthenticatedUserWithRateLimit() {
        UploadUrlResponse stub = new UploadUrlResponse(
                "https://tailtopia.oss.example/public/42/x.jpg?sig", "public/42/x.jpg", "PUT",
                Map.of("Content-Type", "image/jpeg", "x-oss-object-acl", "public-read"),
                "https://cdn.example/public/42/x.jpg");
        when(uploadService.issue(eq(MediaScope.PUBLIC), eq(42L), eq("image/jpeg"))).thenReturn(stub);

        UploadUrlResponse resp = controller.issueUploadUrl(
                jwtForUser("42"), new UploadUrlRequest(MediaScope.PUBLIC, "image/jpeg"));

        assertThat(resp.objectKey()).isEqualTo("public/42/x.jpg");
        assertThat(resp.method()).isEqualTo("PUT");
        verify(rateLimiter).check(eq("rl:media:upload:42"), anyInt(), eq(Duration.ofMinutes(1)));
        verify(uploadService).issue(MediaScope.PUBLIC, 42L, "image/jpeg");
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.issueUploadUrl(
                null, new UploadUrlRequest(MediaScope.PUBLIC, null)))
                .isInstanceOf(AppException.class);
    }

    @Test
    void rejectsNonNumericSubject() {
        assertThatThrownBy(() -> controller.issueUploadUrl(
                jwtForUser("not-a-number"), new UploadUrlRequest(MediaScope.PUBLIC, null)))
                .isInstanceOf(AppException.class);
    }
}
