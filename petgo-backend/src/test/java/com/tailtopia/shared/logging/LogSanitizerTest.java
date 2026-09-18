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
    // 🎯 **变异靶子**：从 LogSanitizer.SENSITIVE_KEYS 里删掉 "content"，
    //    reviewContentIsMaskedInRequestBody 与 reviewContentIsMaskedInResponseBody
    //    必须同时变红；从 REQUEST_ONLY_SENSITIVE_KEYS 删掉 "detail"，
    //    reportDetailIsMaskedInRequestBody 必须变红。
    //
    // 🔴 **2026-09-18 复审 #3 推翻了本组原来的立场**：原实现只打码请求侧，理由是
    //    「ShopReviewView.content 是公开响应字段，打码零收益」。该理由不成立 ——
    //    ShopReviewView.mine 会返回本人 PENDING / REJECTED 的评价，那些正文**从未公开**，
    //    却照样随响应体原样落盘（同一行日志里 req 打码、resp 明文）。
    //    日志脱敏不能按「这个字段通常是公开的」来判，要按「它有没有可能不是」。
    //    现在两侧都打码；代价只是日志里看不到公开评价原文，而那本就无排障价值。
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
    @DisplayName("🎯 响应侧的 content 同样被打码 —— 「我的评价」会带出审核未通过的正文")
    void reviewContentIsMaskedInResponseBody() {
        // 这条 JSON 模拟的正是 ShopReviewView.mine 的形状：reviewStatus 非 null，
        // 意味着它是本人可见、**外人从来看不到**的待审 / 被驳回评价。
        String out = sanitizeJson(
                "{\"items\":[{\"id\":1,\"rating\":5,\"reviewStatus\":\"REJECTED\","
                        + "\"content\":\"这条被审核驳回了，从未公开\"}]}");

        assertThat(out)
                .as("🎯 从 SENSITIVE_KEYS 删掉 \"content\"，这条必须红")
                .contains("\"content\":\"***\"");
        // 逐片段断言：只断言「有 ***」不够——截断也会留下 ***，而原文可能还在别处。
        assertThat(out).doesNotContain("驳回").doesNotContain("从未公开");
        // 邻居字段不受牵连：出了问题还得靠它们定位是哪条评价。
        assertThat(out).contains("\"reviewStatus\":\"REJECTED\"").contains("\"rating\":5");
    }

    @Test
    @DisplayName("🎯 嵌套在数组对象里的响应 content 同样打码（递归路径不得漏）")
    void nestedResponseContentIsMasked() {
        String out = sanitizeJson(
                "{\"page\":{\"items\":[{\"content\":\"第一条\"},{\"content\":\"第二条\"}]}}");

        assertThat(out).doesNotContain("第一条").doesNotContain("第二条");
        assertThat(out).contains("\"content\":\"***\"");
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
