package com.tailtopia.triage.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import com.tailtopia.triage.domain.TriageStatus;
import com.tailtopia.triage.dto.TriageAcceptedResponse;
import com.tailtopia.triage.dto.TriageSubmitRequest;
import com.tailtopia.triage.service.TriageService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：控制器 userId 取自 JWT（不信任客户端）+ 写端点限流（AC1）+ 缺 JWT 401。 */
class TriageControllerTest {

    private final TriageService triageService = mock(TriageService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final TriageController controller = new TriageController(triageService, rateLimiter);

    private static Jwt jwt(String sub) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("x", "y").build();
    }

    @Test
    void submitUsesUserFromJwtAndRateLimits() {
        when(triageService.submit(eq(77L), any(), ArgumentMatchers.isNull()))
                .thenReturn(TriageAcceptedResponse.of(3L, TriageStatus.PENDING));

        TriageAcceptedResponse resp = controller.submit(
                jwt("77"), null, new TriageSubmitRequest("咳嗽", List.of("k1"), null));

        assertThat(resp.triageId()).isEqualTo(3L);
        verify(rateLimiter).check(eq("rl:triage:submit:77"), anyInt(), any());
        verify(triageService).submit(eq(77L), any(), ArgumentMatchers.isNull());
    }

    @Test
    void getUsesUserFromJwt() {
        controller.get(jwt("77"), 9L);
        verify(triageService).getResult(77L, 9L);
    }

    @Test
    void rejectsMissingJwt() {
        assertThatThrownBy(() -> controller.submit(
                null, null, new TriageSubmitRequest("x", null, null)))
                .isInstanceOf(AppException.class);
    }
}
