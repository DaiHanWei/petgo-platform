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
    private final ContentApiController controller = new ContentApiController(service, rateLimiter);

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
}
