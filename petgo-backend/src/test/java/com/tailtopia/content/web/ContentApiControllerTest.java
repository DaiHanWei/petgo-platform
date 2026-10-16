package com.tailtopia.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.dto.ContentPostCreateRequest;
import com.tailtopia.content.dto.ContentPostResponse;
import com.tailtopia.content.service.ContentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：author 取自 JWT + 限流 + Idempotency-Key 透传（AC1/AC4）。 */
class ContentApiControllerTest {

    private final ContentService service = mock(ContentService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final com.tailtopia.content.service.VideoPostValidator validator =
            mock(com.tailtopia.content.service.VideoPostValidator.class);
    private final com.tailtopia.content.service.MediaAclReconciler reconciler =
            mock(com.tailtopia.content.service.MediaAclReconciler.class);
    private final ContentApiController controller =
            new ContentApiController(service, rateLimiter, validator, reconciler);

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void publishUsesJwtAuthorRateLimitsAndPassesIdempotencyKey() {
        ContentPostResponse stub = new ContentPostResponse(
                9L, ContentType.DAILY, null, "hi", null, null, Instant.now());
        when(service.publish(eq(7L), ArgumentMatchers.any(), eq("IDEM"))).thenReturn(stub);

        ContentPostResponse resp = controller.publish(
                jwt("7"), "IDEM", new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null));

        assertThat(resp.id()).isEqualTo(9L);
        verify(rateLimiter).check(eq("rl:content:publish:7"), anyInt(), ArgumentMatchers.any());
        verify(service).publish(eq(7L), ArgumentMatchers.any(), eq("IDEM"));
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.publish(
                null, null, new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null)))
                .isInstanceOf(AppException.class);
    }

    // ---------- 专题线 video Story 1.3：HEAD 在事务外 ----------

    @Test
    void videoIsVerifiedOutsideTransactionBeforePublish() {
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, "hi", java.util.List.of("c"), null, null,
                null, null, null, null, com.tailtopia.content.domain.MediaType.VIDEO,
                new com.tailtopia.content.dto.VideoPayload("video/7/k.mp4", 5000, 720, 1280));
        var verified = new com.tailtopia.content.service.VideoPostValidator.VerifiedVideo("video/7/k.mp4", 10L);
        when(validator.verifyObjects(7L, req)).thenReturn(verified);
        when(service.publish(7L, req, "IDEM", verified)).thenReturn(
                new ContentPostResponse(42L, ContentType.DAILY, null, "hi", null, null, Instant.now()));

        controller.publish(jwt("7"), "IDEM", req);

        var order = org.mockito.Mockito.inOrder(validator, service, reconciler);
        order.verify(validator).verifyObjects(7L, req);
        order.verify(service).publish(7L, req, "IDEM", verified);
        // Story 1.4：发布返回（事务已提交）之后才即时对账。
        order.verify(reconciler).reconcileOne(42L);
    }

    @Test
    void inlineReconcileFailureDoesNotFailPublish() {
        var req = new ContentPostCreateRequest(ContentType.DAILY, null, "hi", java.util.List.of("c"), null, null,
                null, null, null, null, com.tailtopia.content.domain.MediaType.VIDEO,
                new com.tailtopia.content.dto.VideoPayload("video/7/k.mp4", 5000, 720, 1280));
        var verified = new com.tailtopia.content.service.VideoPostValidator.VerifiedVideo("video/7/k.mp4", 10L);
        when(validator.verifyObjects(7L, req)).thenReturn(verified);
        when(service.publish(7L, req, null, verified)).thenReturn(
                new ContentPostResponse(42L, ContentType.DAILY, null, "hi", null, null, Instant.now()));
        when(reconciler.reconcileOne(42L)).thenThrow(new IllegalStateException("oss down"));

        assertThat(controller.publish(jwt("7"), null, req).id()).isEqualTo(42L);
    }

    @Test
    void imagePostSkipsVideoVerification() {
        controller.publish(jwt("7"), null, new ContentPostCreateRequest(ContentType.DAILY, null, "hi", null));
        org.mockito.Mockito.verifyNoInteractions(validator, reconciler);
    }

    @Test
    void videoPostValidatorIsNotTransactional() {
        Class<?> c = com.tailtopia.content.service.VideoPostValidator.class;
        assertThat(c.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isFalse();
        assertThat(java.util.Arrays.stream(c.getDeclaredMethods())
                .noneMatch(m -> m.isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)))
                .isTrue();
    }

    /** 专题线 video Story 3.1：作者删除后事务外即时对账（吞异常由 reconcileQuietly 负责）。 */
    @Test
    void deleteReconcilesInlineAfterCommit() {
        controller.delete(jwt("7"), 42L);
        var order = org.mockito.Mockito.inOrder(service, reconciler);
        order.verify(service).deleteByAuthor(42L, 7L);
        order.verify(reconciler).reconcileQuietly(42L);
    }
}
