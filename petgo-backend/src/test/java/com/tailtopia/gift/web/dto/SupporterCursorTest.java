package com.tailtopia.gift.web.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** V1.4.0 gift Story 2.1 · AC2.7 · L0：粉丝榜游标编解码。 */
class SupporterCursorTest {

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void roundTripKeepsAllKeysAndMicroPrecision() {
        SupporterCursor c = new SupporterCursor(53_000L, Instant.parse("2026-10-18T08:30:01.123456Z"), 77L, 21);
        String token = c.encode();
        assertThat(token).doesNotContain("=").doesNotContain("+").doesNotContain("/");
        assertThat(SupporterCursor.decodeOrNull(token)).isEqualTo(c);
        assertThat(SupporterCursor.decodeOrNull(token).nextRank()).isEqualTo(21);
    }

    @Test
    void nanosBeyondMicrosAreDroppedLikeTheDatabase() {
        SupporterCursor c = new SupporterCursor(1L, Instant.parse("2026-10-18T08:30:01.123456789Z"), 5L, 2);
        assertThat(SupporterCursor.decodeOrNull(c.encode()).lastSentAt())
                .isEqualTo(Instant.parse("2026-10-18T08:30:01.123456Z"));
    }

    @Test
    void blankOrTamperedReturnsNull() {
        assertThat(SupporterCursor.decodeOrNull(null)).isNull();
        assertThat(SupporterCursor.decodeOrNull("")).isNull();
        assertThat(SupporterCursor.decodeOrNull("   ")).isNull();
        assertThat(SupporterCursor.decodeOrNull("%%%")).isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("1:2:3"))).isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("1:2:3:4:5"))).isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("x:2:3:4"))).isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("1000:2:3:0"))).as("名次从 1 起").isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("-1:2:3:4"))).isNull();
        assertThat(SupporterCursor.decodeOrNull(b64("1000:2:0:4"))).isNull();
        // 篡改一位：解得出就必须仍是合法形态，解不出就是 null —— 绝不抛异常。
        String token = new SupporterCursor(9L, Instant.EPOCH, 3L, 1).encode();
        String tampered = token.substring(0, token.length() - 1) + (token.endsWith("A") ? "B" : "A");
        SupporterCursor d = SupporterCursor.decodeOrNull(tampered);
        assertThat(d == null || d.nextRank() >= 1).isTrue();
    }
}
