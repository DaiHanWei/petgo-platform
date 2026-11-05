package com.tailtopia.mention.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.tailtopia.mention.service.MentionCandidateQueryService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：@ 候选端点（V1.4.2 social-topic Story 5.1 · AC1 / AC2 · 拍板点 ②）。 */
class MentionCandidateControllerTest {

    private MentionCandidateQueryService service;
    private RedisRateLimiter rateLimiter;
    private MentionCandidateController controller;

    @BeforeEach
    void setUp() {
        service = mock(MentionCandidateQueryService.class);
        rateLimiter = mock(RedisRateLimiter.class);
        controller = new MentionCandidateController(service, rateLimiter);
        when(service.candidatesFor(org.mockito.ArgumentMatchers.anyLong())).thenReturn(List.of());
        when(service.search(org.mockito.ArgumentMatchers.anyLong(), anyString())).thenReturn(List.of());
    }

    private static Jwt jwt(String sub, String role) {
        Jwt.Builder b = Jwt.withTokenValue("t").header("alg", "none").subject(sub)
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60));
        if (role != null) {
            b.claim("role", role);
        }
        return b.build();
    }

    private static void assert401(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus()).isEqualTo(HttpStatus.UNAUTHORIZED));
    }

    @Test
    void noJwtIs401() {
        assert401(() -> controller.candidates(null, null));
        assert401(() -> controller.candidates(null, "ra"));
        verifyNoInteractions(service, rateLimiter);
    }

    @Test
    void vetTokenIs401() {
        // 兽医 sub=vetId 与 users.id 是两个会碰撞的命名空间。
        assert401(() -> controller.candidates(jwt("7", "VET"), "ra"));
        assert401(() -> controller.candidates(jwt("7", "VET"), null));
        verifyNoInteractions(service, rateLimiter);
    }

    @Test
    void withKeywordRateLimitsBeforeSearching() {
        controller.candidates(jwt("7", "USER"), "ra");

        InOrder order = inOrder(rateLimiter, service);
        order.verify(rateLimiter).check("rl:mention-search:7", 60, Duration.ofMinutes(1));
        order.verify(service).search(7L, "ra");
        verify(service, never()).candidatesFor(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void rateLimitedRequestNeverReachesTheService() {
        doThrow(AppException.rateLimited("x")).when(rateLimiter).check(anyString(), anyInt(), any());

        assertThatThrownBy(() -> controller.candidates(jwt("7", "USER"), "ra"))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus())
                        .isEqualTo(HttpStatus.TOO_MANY_REQUESTS));
        verifyNoInteractions(service);
    }

    @Test
    void withoutKeywordIsNotRateLimited() {
        controller.candidates(jwt("7", "USER"), null);
        controller.candidates(jwt("7", "USER"), "   ");

        verifyNoInteractions(rateLimiter);
        verify(service, org.mockito.Mockito.times(2)).candidatesFor(7L);
        verify(service, never()).search(org.mockito.ArgumentMatchers.anyLong(), anyString());
    }
}
