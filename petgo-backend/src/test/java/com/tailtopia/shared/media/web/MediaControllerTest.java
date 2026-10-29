package com.tailtopia.shared.media.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.auth.service.VipLevelService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.media.MediaScope;
import com.tailtopia.shared.media.PresignedUploadService;
import com.tailtopia.shared.media.UploadPurpose;
import com.tailtopia.shared.media.VideoUploadPolicyService;
import com.tailtopia.shared.media.dto.VideoUploadPolicyResponse;
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
    private final VipLevelService vipLevelService = mock(VipLevelService.class);
    private final VideoUploadPolicyService videoPolicyService = mock(VideoUploadPolicyService.class);
    private final MediaController controller =
            new MediaController(uploadService, rateLimiter, vipLevelService, videoPolicyService);

    private static Jwt jwtForUser(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void issuesUploadUrlForAuthenticatedUserWithRateLimit() {
        UploadUrlResponse stub = new UploadUrlResponse(
                "https://tailtopia.oss.example/public/42/x.jpg?sig", "public/42/x.jpg", "PUT",
                Map.of("Content-Type", "image/jpeg", "x-oss-object-acl", "public-read"),
                "https://cdn.example/public/42/x.jpg");
        when(uploadService.issue(eq(MediaScope.PUBLIC), eq(42L), eq("image/jpeg"), isNull())).thenReturn(stub);

        UploadUrlResponse resp = controller.issueUploadUrl(
                jwtForUser("42"), new UploadUrlRequest(MediaScope.PUBLIC, "image/jpeg"));

        assertThat(resp.objectKey()).isEqualTo("public/42/x.jpg");
        assertThat(resp.method()).isEqualTo("PUT");
        verify(rateLimiter).check(eq("rl:media:upload:42"), anyInt(), eq(Duration.ofMinutes(1)));
        verify(uploadService).issue(MediaScope.PUBLIC, 42L, "image/jpeg", null);
        // 普通图片不查等级。
        verifyNoInteractions(vipLevelService);
    }

    @Test
    void videoCoverBelowLevel10Is403AndNotSigned() {
        when(vipLevelService.canPublishVideo(42L)).thenReturn(false);
        assertThatThrownBy(() -> controller.issueUploadUrl(jwtForUser("42"),
                new UploadUrlRequest(MediaScope.PUBLIC, "image/jpeg", UploadPurpose.VIDEO_COVER)))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getType()).isEqualTo(ErrorTypes.VIDEO_PERMISSION_DENIED));
        verifyNoInteractions(uploadService);
    }

    @Test
    void videoCoverAtLevel10DelegatesWithPurpose() {
        when(vipLevelService.canPublishVideo(42L)).thenReturn(true);
        controller.issueUploadUrl(jwtForUser("42"),
                new UploadUrlRequest(MediaScope.PUBLIC, "image/jpeg", UploadPurpose.VIDEO_COVER));
        verify(uploadService).issue(MediaScope.PUBLIC, 42L, "image/jpeg", UploadPurpose.VIDEO_COVER);
    }

    @Test
    void videoPolicyBelowLevel10Is403AndNeverReachesService() {
        when(vipLevelService.canPublishVideo(42L)).thenReturn(false);
        assertThatThrownBy(() -> controller.issueVideoUploadPolicy(jwtForUser("42")))
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(403);
                    assertThat(e.getType()).isEqualTo(ErrorTypes.VIDEO_PERMISSION_DENIED);
                });
        verifyNoInteractions(videoPolicyService);
    }

    @Test
    void videoPolicyAtLevel10Delegates() {
        when(vipLevelService.canPublishVideo(42L)).thenReturn(true);
        VideoUploadPolicyResponse stub = new VideoUploadPolicyResponse("https://h", "video/42/k.mp4", Map.of());
        when(videoPolicyService.issue(42L)).thenReturn(stub);
        assertThat(controller.issueVideoUploadPolicy(jwtForUser("42"))).isSameAs(stub);
    }

    @Test
    void videoPolicyRejectsMissingJwt() {
        assertThatThrownBy(() -> controller.issueVideoUploadPolicy(null)).isInstanceOf(AppException.class);
        verifyNoInteractions(vipLevelService, videoPolicyService);
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
