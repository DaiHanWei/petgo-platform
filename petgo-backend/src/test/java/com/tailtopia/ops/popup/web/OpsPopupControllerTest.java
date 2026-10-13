package com.tailtopia.ops.popup.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.ops.popup.service.OpsPopupCandidateService;
import com.tailtopia.ops.popup.service.OpsPopupImpressionService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0（直接 new 控制器 + mock 服务）：V1.3.4 Story 5.1 · AC3.1 / AC4。 */
class OpsPopupControllerTest {

    private final OpsPopupCandidateService candidates = mock(OpsPopupCandidateService.class);
    private final OpsPopupImpressionService impressions = mock(OpsPopupImpressionService.class);
    private final RedisRateLimiter rateLimiter = mock(RedisRateLimiter.class);
    private final OpsPopupController controller = new OpsPopupController(candidates, impressions, rateLimiter);

    private static Jwt jwt(String sub, String role) {
        return Jwt.withTokenValue("t").header("alg", "HS256").subject(sub).claim("role", role).build();
    }

    @Test
    void guestPassesNull() {
        when(candidates.candidates(null)).thenReturn(List.of());
        assertThat(controller.candidates(null).items()).isEmpty();
        verify(candidates).candidates(null);
    }

    @Test
    void vetTokenIsTreatedAsGuest() {
        when(candidates.candidates(null)).thenReturn(List.of());
        controller.candidates(jwt("4021", "VET"));
        verify(candidates).candidates(null);
    }

    @Test
    void userTokenPassesUserId() {
        when(candidates.candidates(4021L)).thenReturn(List.of());
        controller.candidates(jwt("4021", "USER"));
        verify(candidates).candidates(4021L);
    }

    @Test
    void impressionRecordsWithRateLimit() {
        controller.impression(jwt("7", "USER"), "tok");
        verify(rateLimiter).check(eq("rl:ops-popup:impression:7"), eq(30), eq(Duration.ofMinutes(1)));
        verify(impressions).record("tok", 7L);
    }

    @Test
    void impressionUnknownTokenIs404() {
        doThrow(AppException.notFound("弹窗不存在")).when(impressions).record(eq("nope"), any(Long.class));
        assertThatThrownBy(() -> controller.impression(jwt("7", "USER"), "nope"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.NOT_FOUND));
    }

    @Test
    void impressionWithoutUserIs401() {
        assertThatThrownBy(() -> controller.impression(null, "tok"))
                .isInstanceOfSatisfying(AppException.class,
                        e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
        verify(rateLimiter, org.mockito.Mockito.never()).check(any(), anyInt(), any());
    }
}
