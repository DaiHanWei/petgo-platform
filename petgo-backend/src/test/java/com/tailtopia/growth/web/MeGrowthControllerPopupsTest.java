package com.tailtopia.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.service.GrowthLedgerPageQuery;
import com.tailtopia.growth.service.GrowthPageQuery;
import com.tailtopia.growth.service.GrowthPopupService;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** V1.4.0 ① Story 2.1 · AC3 / AC4（L0）：游客 401、非法 kind 400、ack 204。 */
class MeGrowthControllerPopupsTest {

    private final GrowthPopupService popups = mock(GrowthPopupService.class);
    private final MeGrowthController controller = new MeGrowthController(mock(GrowthPageQuery.class),
            mock(GrowthLedgerPageQuery.class), popups, mock(com.tailtopia.growth.service.GrowthFrameService.class));
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").build();

    @Test
    void guestIsUnauthorized() {
        assertThatThrownBy(() -> controller.popups(null))
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(401));
    }

    @Test
    void popupsForwardsToService() {
        when(popups.getPopups(7L)).thenReturn(GrowthPopupsResponse.none());
        assertThat(controller.popups(jwt)).isEqualTo(GrowthPopupsResponse.none());
    }

    @Test
    void ackReturns204ForBothKinds() {
        assertThat(controller.ack(jwt, "celebration", 7).getStatusCode().value()).isEqualTo(204);
        verify(popups).ackCelebration(7L, 7);
        assertThat(controller.ack(jwt, "backfill", null).getStatusCode().value()).isEqualTo(204);
        verify(popups).ackBackfill(7L);
    }

    @Test
    void unknownKindIs400() {
        assertThatThrownBy(() -> controller.ack(jwt, "CELEBRATION", null))
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(400));
    }
}
