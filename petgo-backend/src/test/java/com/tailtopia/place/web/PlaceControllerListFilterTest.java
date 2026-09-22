package com.tailtopia.place.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.place.service.PlaceListFilter;
import com.tailtopia.place.service.PlaceQueryService;
import com.tailtopia.place.service.PlaceService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.ratelimit.RedisRateLimiter;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：{@code GET /api/v1/places} 的 type / tag 参数接线（Story 1.11 · AC1 / AC4）。 */
class PlaceControllerListFilterTest {

    private PlaceQueryService query;
    private PlaceController controller;

    @BeforeEach
    void setUp() {
        query = mock(PlaceQueryService.class);
        controller = new PlaceController(query, mock(PlaceService.class), mock(RedisRateLimiter.class));
    }

    @Test
    void noFilterParamsPassNone() {
        controller.list(null, null, null, null, null);
        verify(query).list(isNull(), isNull(), isNull(), eq(PlaceListFilter.NONE));
    }

    @Test
    void filterParamsAreParsedAndPassedThrough() {
        controller.list(null, -6.2, 106.8, List.of("CAFE", "PARK"), List.of("PET_MENU"));
        verify(query).list(eq(-6.2), eq(106.8), isNull(), eq(new PlaceListFilter(
                Set.of(PlaceType.CAFE, PlaceType.PARK), Set.of(PlaceTag.PET_MENU))));
    }

    @Test
    void illegalValueIs422AndNeverQueries() {
        assertThatThrownBy(() -> controller.list(null, null, null, List.of("BAR"), null))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        verifyNoInteractions(query);
    }

    @Test
    void coordinateValidationStillComesFirst() {
        assertThatThrownBy(() -> controller.list(null, -6.2, null, List.of("CAFE"), null))
                .isInstanceOf(AppException.class);
        verifyNoInteractions(query);
    }
}
