package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：🛡 手机号**禁止写入任何日志**（V1.1.6 Story 7.1 · FR-70 AC1）。
 *
 * <h2>这条守的是「命名」，不是「脱敏功能」</h2>
 * 请求/响应体的脱敏是**按字段名整串打码**的。手机号之所以安全，靠的是它恰好叫 {@code phone}
 * ——名单里有这个名字。
 *
 * <p>🔴 一旦有人把字段改成 {@code phoneNumber} / {@code mobile} / {@code contact}，
 * 脱敏**静默失效**：日志里就会明晃晃出现真实手机号，**而且不会有任何报错、任何测试变红**……
 * 除了这一条。
 */
class PhoneNeverLoggedTest {

    private final LogSanitizer sanitizer = new LogSanitizer();

    /** 请求体里的手机号被打码。 */
    @Test
    void phoneInRequestBodyIsMasked() {
        String out = sanitizer.sanitizeText("{\"phone\":\"+6281234567890\",\"nickname\":\"Budi\"}");

        assertThat(out).doesNotContain("6281234567890");
        assertThat(out).contains("Budi"); // 非敏感字段照常可读，便于排查
    }

    /** 响应体（/me 聚合视图）里的手机号同样被打码。 */
    @Test
    void phoneInResponseBodyIsMasked() {
        String out = sanitizer.sanitizeText(
                "{\"id\":1,\"phone\":\"+6281234567890\",\"email\":\"a@b.com\"}");

        assertThat(out).doesNotContain("6281234567890");
        assertThat(out).doesNotContain("a@b.com");
    }

    /**
     * 🔴 **改名即失去保护** —— 这条把风险写成可执行的证据。
     *
     * <p>它故意演示：同一个号码，字段叫 `phone` 会被打码、叫 `phoneNumber` 就原样进日志。
     * 谁哪天想改字段名，先看这条。
     */
    @Test
    void renamingTheFieldSilentlyLosesProtection() {
        assertThat(sanitizer.sanitizeText("{\"phone\":\"+6281234567890\"}"))
                .doesNotContain("6281234567890");

        assertThat(sanitizer.sanitizeText("{\"phoneNumber\":\"+6281234567890\"}"))
                .as("改名之后脱敏不再命中 —— 所以字段名必须保持 phone")
                .contains("6281234567890");
    }
}
