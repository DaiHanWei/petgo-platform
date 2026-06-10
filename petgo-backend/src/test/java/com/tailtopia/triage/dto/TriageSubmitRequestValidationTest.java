package com.tailtopia.triage.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * L0：分诊提交请求 Bean Validation —— Story 4.3 AC5（R2 回改）<b>文字必填、图片选填</b>。
 * 纯 {@link Validator} 单测（无 Spring / 无 DB），等价 MockMvc 422 但不需 Docker。
 */
class TriageSubmitRequestValidationTest {

    private static ValidatorFactory factory;
    private static Validator validator;

    @BeforeAll
    static void setUp() {
        factory = Validation.buildDefaultValidatorFactory();
        validator = factory.getValidator();
    }

    @AfterAll
    static void tearDown() {
        factory.close();
    }

    /** AC5：仅文字、无图（空数组）→ 通过（图片选填）。 */
    @Test
    void textOnlyEmptyImagesIsValid() {
        var violations = validator.validate(
                new TriageSubmitRequest("最近有点咳嗽", List.of(), null));
        assertThat(violations).isEmpty();
    }

    /** AC5：仅文字、图片缺省（null）→ 通过。 */
    @Test
    void textOnlyNullImagesIsValid() {
        var violations = validator.validate(
                new TriageSubmitRequest("最近有点咳嗽", null, null));
        assertThat(violations).isEmpty();
    }

    /** AC5：文字空白 → @NotBlank 拦截（等价 422），无论是否有图。 */
    @Test
    void blankSymptomTextIsRejected() {
        Set<ConstraintViolation<TriageSubmitRequest>> blank =
                validator.validate(new TriageSubmitRequest("   ", List.of("priv/k1.jpg"), null));
        assertThat(blank).isNotEmpty();

        Set<ConstraintViolation<TriageSubmitRequest>> empty =
                validator.validate(new TriageSubmitRequest("", null, null));
        assertThat(empty).isNotEmpty();

        Set<ConstraintViolation<TriageSubmitRequest>> nul =
                validator.validate(new TriageSubmitRequest(null, null, null));
        assertThat(nul).isNotEmpty();
    }

    /** 既有上限不变：图片 > 3 张 → @Size 拦截。 */
    @Test
    void tooManyImagesIsRejected() {
        var violations = validator.validate(
                new TriageSubmitRequest("咳嗽", List.of("k1", "k2", "k3", "k4"), null));
        assertThat(violations).isNotEmpty();
    }

    /** 既有上限不变：文字 > 2000 字 → @Size 拦截。 */
    @Test
    void oversizeSymptomTextIsRejected() {
        var violations = validator.validate(
                new TriageSubmitRequest("咳".repeat(2001), null, null));
        assertThat(violations).isNotEmpty();
    }
}
