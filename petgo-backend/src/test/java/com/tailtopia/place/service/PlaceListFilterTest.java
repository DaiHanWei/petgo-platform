package com.tailtopia.place.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import com.tailtopia.shared.error.AppException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** L0：场所列表筛选参数解析（Story 1.11 · AC1 / AC4 / AC6）。 */
class PlaceListFilterTest {

    private static void assert422(Runnable r) {
        assertThatThrownBy(r::run).isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void absentOrEmptyParamsMeanNoFilter() {
        assertThat(PlaceListFilter.parse(null, null)).isSameAs(PlaceListFilter.NONE);
        assertThat(PlaceListFilter.parse(List.of(), List.of()).isEmpty()).isTrue();
        assertThat(PlaceListFilter.NONE.typesParam()).isEmpty();
        assertThat(PlaceListFilter.NONE.tagsJsonParam()).isEqualTo("[]");
    }

    @Test
    void validValuesParseIntoEnumSets() {
        PlaceListFilter f = PlaceListFilter.parse(List.of("PARK", "CAFE"), List.of("PET_MENU", "OUTDOOR_SEATING"));
        assertThat(f.types()).containsExactlyInAnyOrder(PlaceType.CAFE, PlaceType.PARK);
        assertThat(f.tags()).containsExactlyInAnyOrder(PlaceTag.PET_MENU, PlaceTag.OUTDOOR_SEATING);
        // 绑定值顺序稳定、只含枚举名。
        assertThat(f.typesParam()).isEqualTo("CAFE,PARK");
        assertThat(f.tagsJsonParam()).isEqualTo("[\"OUTDOOR_SEATING\",\"PET_MENU\"]");
    }

    @Test
    void duplicatesWithinTheLimitCollapse() {
        PlaceListFilter f = PlaceListFilter.parse(List.of("CAFE", "CAFE"), null);
        assertThat(f.types()).containsExactly(PlaceType.CAFE);
    }

    @Test
    void unknownValueIs422() {
        assert422(() -> PlaceListFilter.parse(List.of("CAFE", "BAR"), null));
        assert422(() -> PlaceListFilter.parse(null, List.of("WIFI")));
        // 大小写敏感：取值必须与枚举名完全一致。
        assert422(() -> PlaceListFilter.parse(List.of("cafe"), null));
        assert422(() -> PlaceListFilter.parse(List.of(""), null));
        assert422(() -> PlaceListFilter.parse(Arrays.asList((String) null), null));
        // 注入形状的输入同样只是「不认识的值」。
        assert422(() -> PlaceListFilter.parse(null, List.of("PET_MENU\"]'::jsonb OR 1=1 --")));
    }

    @Test
    void repeatCountAboveEnumSizeIs422() {
        assertThat(PlaceListFilter.parse(Collections.nCopies(7, "CAFE"), Collections.nCopies(6, "PET_MENU"))
                .isEmpty()).isFalse();
        assert422(() -> PlaceListFilter.parse(Collections.nCopies(8, "CAFE"), null));
        assert422(() -> PlaceListFilter.parse(null, Collections.nCopies(7, "PET_MENU")));
    }
}
