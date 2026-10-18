package com.tailtopia.growth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.service.GrowthFrameService;
import com.tailtopia.growth.service.GrowthLedgerPageQuery;
import com.tailtopia.growth.service.GrowthPageQuery;
import com.tailtopia.growth.service.GrowthPopupService;
import com.tailtopia.growth.web.dto.GrowthFrameRequest;
import com.tailtopia.growth.web.dto.GrowthPageResponse.Frames;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

/** V1.4.0 ① Story 3.3 · AC1（L0）：{@code PUT /me/growth/frame}——200 四档、409 frame-locked、非法值 422、游客 401。 */
class MeGrowthFrameControllerTest {

    private final GrowthFrameService frames = mock(GrowthFrameService.class);
    private final MeGrowthController controller = new MeGrowthController(mock(GrowthPageQuery.class),
            mock(GrowthLedgerPageQuery.class), mock(GrowthPopupService.class), frames);
    private final Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("7").build();

    @Test
    void okReturnsTheFramesDto() {
        Frames dto = new Frames(FrameChoice.BASIC, true, List.of());
        when(frames.choose(7L, FrameChoice.BASIC)).thenReturn(dto);

        assertThat(controller.frame(jwt, new GrowthFrameRequest("BASIC"))).isSameAs(dto);
    }

    @Test
    void lockedIs409FrameLocked() {
        when(frames.choose(7L, FrameChoice.ANIMATED)).thenThrow(AppException.frameLocked("locked"));

        assertThatThrownBy(() -> controller.frame(jwt, new GrowthFrameRequest("ANIMATED")))
                .satisfies(e -> {
                    AppException ae = (AppException) e;
                    assertThat(ae.getStatus().value()).isEqualTo(409);
                    assertThat(ae.getType().toString()).endsWith("/frame-locked");
                });
    }

    @Test
    void invalidValueOrMissingFieldIs422() {
        for (GrowthFrameRequest body : new GrowthFrameRequest[] {
                new GrowthFrameRequest("GOLD"), new GrowthFrameRequest("basic"), new GrowthFrameRequest(null), null}) {
            assertThatThrownBy(() -> controller.frame(jwt, body))
                    .satisfies(e -> {
                        assertThat(((AppException) e).getStatus().value()).isEqualTo(422);
                        assertThat(((AppException) e).getType()).isEqualTo(ErrorTypes.VALIDATION);
                    });
        }
        verify(frames, never()).choose(anyLong(), any());
    }

    @Test
    void guestIs401() {
        assertThatThrownBy(() -> controller.frame(null, new GrowthFrameRequest("NONE")))
                .satisfies(e -> assertThat(((AppException) e).getStatus().value()).isEqualTo(401));
    }
}
