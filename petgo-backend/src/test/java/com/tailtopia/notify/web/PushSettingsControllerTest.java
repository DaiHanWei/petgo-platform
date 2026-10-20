package com.tailtopia.notify.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.notify.dto.UpdatePushSettingsRequest;
import com.tailtopia.notify.preference.PushCategory;
import com.tailtopia.notify.preference.PushPreferenceService;
import com.tailtopia.notify.preference.PushSettingsView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * L0：V1.4.0 ④ Story 1.1 · AC4 / AC6 —— {@link PushSettingsController}（直接 new + mock）。
 */
@ExtendWith(MockitoExtension.class)
class PushSettingsControllerTest {

    @Mock PushPreferenceService service;
    @Mock RedisRateLimiter rateLimiter;

    private PushSettingsController controller() {
        return new PushSettingsController(service, rateLimiter);
    }

    private static Jwt user(long id) {
        return Jwt.withTokenValue("t").header("alg", "none")
                .subject(String.valueOf(id))
                .claim("role", "USER")
                .build();
    }

    @Test
    void missingJwtIsUnauthorized() {
        AppException e = catchThrowableOfType(AppException.class, () -> controller().get(null));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(service);
    }

    @Test
    void patchChecksRateLimitBeforeService() {
        Map<PushCategory, Boolean> body = Map.of(PushCategory.LIKE, false);
        PushSettingsView view = new PushSettingsView(List.of());
        when(service.update(7L, body)).thenReturn(view);

        assertThat(controller().patch(user(7L), new UpdatePushSettingsRequest(body))).isSameAs(view);

        InOrder order = inOrder(rateLimiter, service);
        order.verify(rateLimiter).check("rl:push-settings:7", 30, Duration.ofMinutes(1));
        order.verify(service).update(7L, body);
    }

    @Test
    void rateLimitedPatchNeverReachesService() {
        doThrow(AppException.rateLimited("slow down"))
                .when(rateLimiter).check(anyString(), anyInt(), any(Duration.class));
        AppException e = catchThrowableOfType(AppException.class,
                () -> controller().patch(user(7L), new UpdatePushSettingsRequest(Map.of(PushCategory.LIKE, false))));
        assertThat(e.getStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        verifyNoInteractions(service);
    }

    @Test
    void getDoesNotRateLimit() {
        PushSettingsView view = new PushSettingsView(List.of());
        when(service.get(7L)).thenReturn(view);
        assertThat(controller().get(user(7L))).isSameAs(view);
        verifyNoInteractions(rateLimiter);
    }
}
