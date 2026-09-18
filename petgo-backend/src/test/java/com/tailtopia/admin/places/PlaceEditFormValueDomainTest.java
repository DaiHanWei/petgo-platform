package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.places.dto.PlaceEditForm;
import com.tailtopia.shared.error.AppException;
import org.junit.jupiter.api.Test;

/**
 * 2026-09-18 场所表对齐：后台录入 / 编辑的类型与标签按 App 的值域服务端校验。
 * <p>类型写进未知值会撞 {@code ck_places_type}（500）；标签写进未知值，App 按枚举读这个场所时整页 500。
 */
class PlaceEditFormValueDomainTest {

    private static PlaceEditForm form(String type, String tags) {
        return PlaceEditForm.of("Kopi", type, tags, null, "Jakarta", "Jl. 1", "-6.2", "106.8");
    }

    @Test
    void appValuesPass() {
        PlaceEditForm f = form("park", "pets_allowed_inside, large_dog_friendly");
        assertThat(f.placeType()).isEqualTo("PARK");
        assertThat(f.tags()).containsExactly("PETS_ALLOWED_INSIDE", "LARGE_DOG_FRIENDLY");
    }

    @Test
    void unknownTypeIsRejected() {
        assertThatThrownBy(() -> form("PET_PARK", "PET_MENU"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.places.typeInvalid");
    }

    @Test
    void unknownTagIsRejected() {
        assertThatThrownBy(() -> form("CAFE", "PET_MENU, WIFI"))
                .isInstanceOf(AppException.class)
                .extracting(e -> ((AppException) e).getMessageCode()).isEqualTo("admin.err.places.tagUnknown");
    }
}
