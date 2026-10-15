package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** V1.3.4 Story 2.1（AD-5）：露出位置严格校验的每条分支。纯函数，L0。 */
class ImageCropOffsetValidatorTest {

    private final ImageCropOffsetValidator validator = new ImageCropOffsetValidator();

    private static void assert422(Runnable r, String detail) {
        assertThatThrownBy(r::run)
                .isInstanceOfSatisfying(AppException.class, e -> {
                    assertThat(e.getStatus().value()).isEqualTo(422);
                    assertThat(e.getMessage()).isEqualTo(detail);
                });
    }

    @Test
    void nullOffsetsReturnNull() {
        assertThat(validator.validate(List.of("a"), null)).isNull();
        assertThat(validator.validate(null, null)).isNull();
    }

    @Test
    void noImagesAndEmptyArrayTreatedAsAbsent() {
        assertThat(validator.validate(null, List.of())).isNull();
        assertThat(validator.validate(List.of(), List.of())).isNull();
    }

    @Test
    void noImagesButNonEmptyArrayRejected() {
        assert422(() -> validator.validate(null, List.of(0.5)), "图片露出位置与图片数量不符");
        assert422(() -> validator.validate(List.of(), List.of(0.5)), "图片露出位置与图片数量不符");
    }

    @Test
    void imagesButEmptyArrayRejectedAsLengthMismatch() {
        assert422(() -> validator.validate(List.of("a"), List.of()), "图片露出位置与图片数量不符");
    }

    @Test
    void lengthMismatchRejected() {
        assert422(() -> validator.validate(List.of("a", "b"), List.of(0.5)), "图片露出位置与图片数量不符");
        assert422(() -> validator.validate(List.of("a"), Arrays.asList(0.5, null)), "图片露出位置与图片数量不符");
    }

    @Test
    void equalLengthWithNullPlaceholdersKeptAsIs() {
        List<Double> in = Arrays.asList(0.333, null, 0.5);
        List<Double> out = validator.validate(List.of("a", "b", "c"), in);
        assertThat(out).isEqualTo(Arrays.asList(0.333, null, 0.5));
        // 返回拷贝，不与请求体共享引用。
        assertThat(out).isNotSameAs(in);
    }

    @Test
    void boundariesZeroAndOneAreValid() {
        assertThat(validator.validate(List.of("a", "b"), List.of(0.0, 1.0))).containsExactly(0.0, 1.0);
    }

    @Test
    void outOfRangeRejected() {
        assert422(() -> validator.validate(List.of("a"), List.of(1.0001)), "图片露出位置超出范围");
        assert422(() -> validator.validate(List.of("a"), List.of(-0.0001)), "图片露出位置超出范围");
    }

    @Test
    void nonFiniteRejected() {
        assert422(() -> validator.validate(List.of("a"), List.of(Double.NaN)), "图片露出位置超出范围");
        assert422(() -> validator.validate(List.of("a"), List.of(Double.POSITIVE_INFINITY)), "图片露出位置超出范围");
        assert422(() -> validator.validate(List.of("a"), List.of(Double.NEGATIVE_INFINITY)), "图片露出位置超出范围");
    }
}
