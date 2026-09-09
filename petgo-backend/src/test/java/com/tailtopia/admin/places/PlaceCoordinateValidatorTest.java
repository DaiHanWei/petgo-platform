package com.tailtopia.admin.places;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.places.dto.PlaceEditForm;
import com.tailtopia.admin.places.service.PlaceCoordinateValidator;
import com.tailtopia.shared.error.AppException;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** L0：坐标硬拦（范围 / 小数位）与雅加达包围盒软警告（V1.3.0 Story 5.3 AC1，D-33）；编辑表单解析。 */
class PlaceCoordinateValidatorTest {

    private final PlaceCoordinateValidator v = new PlaceCoordinateValidator();

    private static String code(Throwable t) {
        return ((AppException) t).getMessageCode();
    }

    @Test
    void rangeAndPrecisionAreHardErrors() {
        assertThatThrownBy(() -> v.validate(new BigDecimal("-96.5"), new BigDecimal("106.8"))).isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.latRange")); // UI 稿 2-22 示例
        assertThatThrownBy(() -> v.validate(new BigDecimal("-6.2"), new BigDecimal("181"))).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.lngRange"));
        assertThatThrownBy(() -> v.validate(new BigDecimal("-6.2087631"), new BigDecimal("106.8"))).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.coordPrecision"));
        assertThatThrownBy(() -> v.validate(null, new BigDecimal("106.8"))).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.latRange"));
        v.validate(new BigDecimal("-6.208763"), new BigDecimal("106.845599"));
        v.validate(new BigDecimal("-6.2087630000"), new BigDecimal("106.8")); // 尾零不算小数位
        v.validate(new BigDecimal("90"), new BigDecimal("-180"));
    }

    @Test
    void jakartaBoundingBoxOnlyWarns() {
        assertThat(v.isOutsideJakarta(new BigDecimal("-6.208763"), new BigDecimal("106.845599"))).isFalse(); // Jakarta
        assertThat(v.isOutsideJakarta(new BigDecimal("-6.59"), new BigDecimal("106.79"))).isFalse(); // Bogor
        assertThat(v.isOutsideJakarta(new BigDecimal("-6.917"), new BigDecimal("107.619"))).isTrue(); // Bandung
        assertThat(v.isOutsideJakarta(new BigDecimal("1.3"), new BigDecimal("103.8"))).isTrue(); // Singapore
    }

    @Test
    void editFormParsesTagsAndValidatesLengths() {
        PlaceEditForm f = PlaceEditForm.of(" Kopi ", "cafe", "pet_friendly, outdoor wifi，PET_FRIENDLY", null, "Jakarta", "Jl. 1", "-6.2", "106.8");
        assertThat(f.name()).isEqualTo("Kopi");
        assertThat(f.placeType()).isEqualTo("CAFE");
        assertThat(f.tags()).containsExactly("PET_FRIENDLY", "OUTDOOR", "WIFI"); // 去重、大写
        assertThat(f.description()).isNull();
        assertThat(f.lat()).isEqualByComparingTo("-6.2");
        assertThatThrownBy(() -> PlaceEditForm.of("x".repeat(81), "CAFE", null, null, "Jakarta", "a", "0", "0")).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.nameInvalid"));
        assertThatThrownBy(() -> PlaceEditForm.of("n", "CAFE", null, null, "", "a", "0", "0")).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.cityInvalid"));
        assertThatThrownBy(() -> PlaceEditForm.of("n", "CAFE", null, null, "Jakarta", "a", "abc", "0")).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.latRange"));
        assertThatThrownBy(() -> PlaceEditForm.of("n", "", null, null, "Jakarta", "a", "0", "0")).satisfies(e -> assertThat(code(e)).isEqualTo("admin.err.places.typeInvalid"));
    }
}
