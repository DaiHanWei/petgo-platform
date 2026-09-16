package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L0：脱敏护栏单测（令牌/密码/PII/健康/签名URL 必须打码；其余结构保留）。 */
class LogSanitizerTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    private String sanitizeJson(String json) {
        return sanitizer.sanitize(json.getBytes(StandardCharsets.UTF_8), "application/json");
    }

    /** 请求体口径（Story 5-1）：额外打码 {@code REQUEST_ONLY_SENSITIVE_KEYS}。 */
    private String sanitizeRequestJson(String json) {
        return sanitizer.sanitizeRequest(json.getBytes(StandardCharsets.UTF_8),
                "application/json");
    }

    @Test
    void masksTokensPasswordsAndPii() {
        String out = sanitizeJson(
                "{\"idToken\":\"abc.def.ghi\",\"password\":\"s3cret\",\"email\":\"a@b.com\","
                        + "\"nickname\":\"Mochi\"}");
        assertThat(out).contains("\"idToken\":\"***\"");
        assertThat(out).contains("\"password\":\"***\"");
        assertThat(out).contains("\"email\":\"***\"");
        // 非敏感字段保留，便于排查。
        assertThat(out).contains("\"nickname\":\"Mochi\"");
        assertThat(out).doesNotContain("abc.def.ghi");
        assertThat(out).doesNotContain("a@b.com");
    }

    @Test
    void masksHealthSymptomFields() {
        String out = sanitizeJson("{\"symptomText\":\"muntah dan lemas\",\"petId\":7}");
        assertThat(out).contains("\"symptomText\":\"***\"");
        assertThat(out).contains("\"petId\":7");
        assertThat(out).doesNotContain("muntah");
    }

    @Test
    void masksSignedUrlValues() {
        String out = sanitizeJson(
                "{\"url\":\"https://oss.example.com/k.jpg?OSSAccessKeyId=X&Signature=Y&Expires=1\"}");
        assertThat(out).contains("<signed-url>");
        assertThat(out).doesNotContain("Signature=Y");
    }

    @Test
    void redactsNestedAndArrays() {
        String out = sanitizeJson(
                "{\"data\":{\"accessToken\":\"t\",\"items\":[{\"refreshToken\":\"r\"}]}}");
        assertThat(out).doesNotContain("\"t\"");
        assertThat(out).doesNotContain("\"r\"");
        assertThat(out).contains("***");
    }

    @Test
    void nonJsonBodyOnlyRecordsTypeAndSize() {
        byte[] bytes = new byte[1234];
        String out = sanitizer.sanitize(bytes, "image/jpeg");
        assertThat(out).isEqualTo("<image/jpeg, 1234B>");
    }

    @Test
    void emptyBodyIsEmptyString() {
        assertThat(sanitizer.sanitize(new byte[0], "application/json")).isEmpty();
        assertThat(sanitizer.sanitize(null, "application/json")).isEmpty();
    }

    @Test
    void unparseableJsonNotEchoed() {
        String out = sanitizer.sanitize("{not json".getBytes(StandardCharsets.UTF_8), "application/json");
        assertThat(out).startsWith("<unparseable json");
        assertThat(out).doesNotContain("not json");
    }

    // ================================================================
    // Story 5-1：评价正文不进日志（SHOP-FR-27 / SHOP-NFR-01）
    //
    // 🎯 **变异靶子**：从 LogSanitizer.REQUEST_ONLY_SENSITIVE_KEYS 里删掉 "content"，
    //    reviewContentIsMaskedInRequestBody 必须变红；删掉 "detail"，
    //    reportDetailIsMaskedInRequestBody 必须变红。
    //
    // 🔴 这一整组同时守着「请求打码」与「响应不打码」两侧。只测前者是不够的：
    //    把 content 挪进 SENSITIVE_KEYS 也能让前者变绿，但那会把所有公开评价
    //    在响应日志里全打成 ***，排障时等于瞎了。
    // ================================================================

    @Test
    @DisplayName("🎯 评价提交请求：content 被打码，原文一个片段都不留")
    void reviewContentIsMaskedInRequestBody() {
        String out = sanitizeRequestJson(
                "{\"orderToken\":\"tok-abc\",\"orderLineId\":7,\"rating\":5,"
                        + "\"content\":\"狗粮很好，我家狗吃了三天\"}");

        assertThat(out)
                .as("🎯 从 REQUEST_ONLY_SENSITIVE_KEYS 删掉 \"content\"，这条必须红")
                .contains("\"content\":\"***\"");
        // 逐片段断言：只断言「有 ***」不够 —— 截断也会留下 ***，而原文可能还在别处。
        assertThat(out).doesNotContain("狗粮").doesNotContain("我家狗").doesNotContain("三天");
    }

    @Test
    @DisplayName("同一请求体里的非敏感字段不受牵连（orderToken 不是 token）")
    void neighbouringFieldsSurvive() {
        String out = sanitizeRequestJson(
                "{\"orderToken\":\"tok-abc\",\"orderLineId\":7,\"rating\":5,"
                        + "\"content\":\"x\"}");

        // redact 做的是**整键 equals** 比较：orderToken ≠ token，不该命中。
        // 打掉它会让「哪一单的评价出了问题」在日志里无从查起。
        assertThat(out).contains("\"orderToken\":\"tok-abc\"");
        assertThat(out).contains("\"orderLineId\":7");
        assertThat(out).contains("\"rating\":5");
    }

    @Test
    @DisplayName("🔴 响应侧的 content 原样保留 —— 公开评价打码对隐私零收益、对排障是纯损失")
    void publicReviewContentSurvivesInResponseBody() {
        String out = sanitizeJson(
                "{\"items\":[{\"id\":1,\"rating\":5,\"content\":\"公开可见的评价正文\"}]}");

        assertThat(out)
                .as("把 content 放进 SENSITIVE_KEYS 会让这条红 —— 那正是本 story 刻意避开的做法")
                .contains("公开可见的评价正文");
        assertThat(out).doesNotContain("\"content\":\"***\"");
    }

    @Test
    @DisplayName("🔴 嵌套在数组对象里的响应 content 同样不受影响（递归路径）")
    void nestedResponseContentSurvives() {
        String out = sanitizeJson(
                "{\"page\":{\"items\":[{\"content\":\"第一条\"},{\"content\":\"第二条\"}]}}");

        assertThat(out).contains("第一条").contains("第二条");
    }

    @Test
    @DisplayName("🎯 detail 的既有行为不回归：请求打码")
    void reportDetailIsMaskedInRequestBody() {
        String out = sanitizeRequestJson("{\"detail\":\"举报补充说明\"}");

        assertThat(out)
                .as("🎯 从 REQUEST_ONLY_SENSITIVE_KEYS 删掉 \"detail\"，这条必须红")
                .contains("\"detail\":\"***\"");
        assertThat(out).doesNotContain("举报补充说明");
    }

    @Test
    @DisplayName("🔴 detail 的既有行为不回归：RFC 9457 错误响应原样保留（它是排障主字段）")
    void problemDetailSurvivesInResponseBody() {
        String out = sanitizeJson("{\"detail\":\"请求格式不正确\",\"status\":400}");

        assertThat(out).contains("请求格式不正确");
        assertThat(out).contains("\"status\":400");
    }

    @Test
    @DisplayName("🔴 SENSITIVE_KEYS 的字段在**两个**口径下都打码（请求侧没被新逻辑绕过）")
    void globallySensitiveKeysAreMaskedInBothDirections() {
        String body = "{\"password\":\"s3cret\",\"email\":\"a@b.com\",\"content\":\"x\"}";

        assertThat(sanitizeRequestJson(body))
                .contains("\"password\":\"***\"").contains("\"email\":\"***\"");
        assertThat(sanitizeJson(body))
                .contains("\"password\":\"***\"").contains("\"email\":\"***\"");
    }
}
