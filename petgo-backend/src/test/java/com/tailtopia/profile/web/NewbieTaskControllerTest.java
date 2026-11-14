package com.tailtopia.profile.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.profile.dto.NewbieTaskResponse;
import com.tailtopia.profile.service.LegacyNewbieTaskCompat;
import com.tailtopia.profile.service.NewbieTaskService;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** L0：V1.5.0 onboarding-recall Story 2.1 · AC10 —— 按 {@code X-App-Version} 新旧分流（缺头 / 坏头 = 旧版）。 */
class NewbieTaskControllerTest {

    private NewbieTaskService service;
    private LegacyNewbieTaskCompat legacy;
    private NewbieTaskController controller;
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").claim("role", "USER")
            .issuedAt(Instant.EPOCH).expiresAt(Instant.EPOCH.plusSeconds(60)).build();

    @BeforeEach
    void setUp() {
        service = mock(NewbieTaskService.class);
        legacy = mock(LegacyNewbieTaskCompat.class);
        controller = new NewbieTaskController(service, legacy);
        when(service.progress(anyLong(), any())).thenReturn(new NewbieTaskResponse(List.of(), 0, 7, false, false, 7, null));
        when(legacy.progress(anyLong())).thenReturn(new NewbieTaskResponse(List.of(), 0, 6, false));
    }

    @Test
    void 新版走7项() {
        assertThat(controller.progress(jwt, "1.5.0").total()).isEqualTo(7);
        assertThat(controller.progress(jwt, "1.6.2 (321)").total()).isEqualTo(7);
        verify(legacy, never()).progress(anyLong());
    }

    @Test
    void 旧版缺头坏头都走旧6项() {
        assertThat(controller.progress(jwt, "1.4.2").total()).isEqualTo(6);
        assertThat(controller.progress(jwt, null).total()).isEqualTo(6);
        assertThat(controller.progress(jwt, "garbage").total()).isEqualTo(6);
        verify(service, never()).progress(anyLong(), any());
    }

    @Test
    void 旧版形状不含新字段() throws Exception {
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(
                new NewbieTaskResponse(List.of(), 0, 6, false));
        assertThat(json).doesNotContain("hasPet").doesNotContain("oneWeekDaysLeft").doesNotContain("replyGoPostId");
    }
}
