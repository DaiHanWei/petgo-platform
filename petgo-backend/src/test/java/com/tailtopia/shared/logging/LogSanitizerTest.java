package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** L0：脱敏护栏单测（令牌/密码/PII/健康/签名URL 必须打码；其余结构保留）。 */
class LogSanitizerTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    private String sanitizeJson(String json) {
        return sanitizer.sanitize(json.getBytes(StandardCharsets.UTF_8), "application/json");
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
}
