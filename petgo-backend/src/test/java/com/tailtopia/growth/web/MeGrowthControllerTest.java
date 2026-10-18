package com.tailtopia.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.growth.domain.GrowthTier;
import com.tailtopia.growth.service.GrowthLedgerPageQuery;
import com.tailtopia.growth.service.GrowthPageQuery;
import com.tailtopia.growth.web.dto.GrowthLedgerPage;
import com.tailtopia.growth.web.dto.GrowthLedgerRow;
import com.tailtopia.growth.web.dto.GrowthPageResponse;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** V1.4.0 ① Story 1.4 · AC1 / AC3 / AC4 / AC6（L0）：只作用于 JWT sub 本人、无 JWT 401、JSON 字段名。 */
class MeGrowthControllerTest {

    private final GrowthPageQuery page = mock(GrowthPageQuery.class);
    private final GrowthLedgerPageQuery ledger = mock(GrowthLedgerPageQuery.class);
    private final MeGrowthController controller = new MeGrowthController(page, ledger);
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void noJwtIsUnauthorized() {
        assertThatThrownBy(() -> controller.growth(null))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(401));
        assertThatThrownBy(() -> controller.ledger(null, null)).isInstanceOf(AppException.class);
    }

    @Test
    void userIdParamIsIgnoredAndJsonIsCamelCase() throws Exception {
        when(page.page(7L)).thenReturn(new GrowthPageResponse(true, 7, GrowthTier.BUDDY, 1420L, 1250L, 8, 1800L,
                List.of(), null, List.of(), null, List.of(), List.of()));

        mvc.perform(get("/api/v1/me/growth").param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participating").value(true))
                .andExpect(jsonPath("$.level").value(7))
                .andExpect(jsonPath("$.tier").value("BUDDY"))
                .andExpect(jsonPath("$.lifetimeTotal").value(1420))
                .andExpect(jsonPath("$.nextThreshold").value(1800));

        verify(page).page(7L);
        verify(page, never()).page(99L);
    }

    @Test
    void ledgerForParticipantPagesOwnRows() throws Exception {
        when(page.participating(7L)).thenReturn(true);
        when(ledger.page(7L, "c1")).thenReturn(new GrowthLedgerPage(List.of(new GrowthLedgerRow("LIKE_GIVEN", 1,
                null, null, null, Instant.parse("2026-10-18T05:00:00Z"))), "c2"));

        mvc.perform(get("/api/v1/me/growth/ledger").param("cursor", "c1").param("userId", "99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].ruleCode").value("LIKE_GIVEN"))
                .andExpect(jsonPath("$.items[0].delta").value(1))
                .andExpect(jsonPath("$.nextCursor").value("c2"));
    }

    @Test
    void ledgerForNonParticipantIsEmpty() {
        when(page.participating(7L)).thenReturn(false);
        Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").build();

        GrowthLedgerPage p = controller.ledger(jwt, null);

        assertThat(p.items()).isEmpty();
        assertThat(p.nextCursor()).isNull();
        verify(ledger, never()).page(anyLong(), anyString());
    }
}
