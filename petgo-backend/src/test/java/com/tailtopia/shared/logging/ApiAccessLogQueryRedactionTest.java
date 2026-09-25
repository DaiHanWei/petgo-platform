package com.tailtopia.shared.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * L0：access log 的 query string 打码（V1.3.0 batch-b1 Story 1.2）。
 *
 * <p>🔴 这条守的是 CLAUDE.md / NFR-4 的红线 —— <b>位置坐标禁止进日志</b>。
 * {@code LogSanitizer} 只脱敏 JSON body，URL 上的坐标此前是原样落盘的（prod INFO，留 14 天）。
 */
class ApiAccessLogQueryRedactionTest {

    @Test
    void coordinatesAreRedactedButOtherParamsSurvive() {
        String out = ApiAccessLoggingFilter.redactQuery("lat=-6.235&lng=106.81&category=CAFE");

        assertThat(out).isEqualTo("lat=***&lng=***&category=CAFE");
        assertThat(out).doesNotContain("6.235").doesNotContain("106.81");
    }

    @Test
    void longFormKeysAreRedactedToo() {
        assertThat(ApiAccessLoggingFilter.redactQuery("latitude=1.5&longitude=2.5"))
                .isEqualTo("latitude=***&longitude=***");
    }

    @Test
    void keysAreMatchedCaseInsensitively() {
        assertThat(ApiAccessLoggingFilter.redactQuery("LAT=-6.2&LnG=106.8"))
                .isEqualTo("lat=***&lng=***");
    }

    /** 单个参数、无其它键的常见形态。 */
    @Test
    void singleCoordinateParamIsRedacted() {
        assertThat(ApiAccessLoggingFilter.redactQuery("lat=-6.235")).isEqualTo("lat=***");
    }

    /** 🛡 只有键名恰好命中才打码 —— `template=x` 里含 "lat" 但不是坐标键。 */
    @Test
    void keysThatMerelyContainTheWordAreNotTouched() {
        assertThat(ApiAccessLoggingFilter.redactQuery("template=abc&translate=1"))
                .isEqualTo("template=abc&translate=1");
    }

    @Test
    void unrelatedQueryIsReturnedUnchanged() {
        assertThat(ApiAccessLoggingFilter.redactQuery("category=CAFE&q=kopi"))
                .isEqualTo("category=CAFE&q=kopi");
    }

    @Test
    void nullAndEmptyPassThrough() {
        assertThat(ApiAccessLoggingFilter.redactQuery(null)).isNull();
        assertThat(ApiAccessLoggingFilter.redactQuery("")).isEmpty();
    }

    /** 形如 `lat` 但没有 `=` 的畸形片段也打码（宁可多打码，不可漏一个坐标）。 */
    @Test
    void malformedCoordinateFragmentIsStillRedacted() {
        assertThat(ApiAccessLoggingFilter.redactQuery("lat&lng=1.0")).isEqualTo("lat=***&lng=***");
    }
}
