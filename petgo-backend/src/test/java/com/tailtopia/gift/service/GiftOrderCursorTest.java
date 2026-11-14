package com.tailtopia.gift.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.gift.web.dto.GiftOrderCursor;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 gift-pack Story 1-4 · AC1.5 游标往返与非法输入。 */
class GiftOrderCursorTest {

    @Test
    void roundTripKeepsMicros() {
        GiftOrderCursor c = new GiftOrderCursor(1, Instant.parse("2026-11-15T03:04:05.123456Z"), 99L);
        assertThat(GiftOrderCursor.decodeOrNull(c.encode())).isEqualTo(c);
        GiftOrderCursor v = new GiftOrderCursor(0, Instant.parse("1969-12-31T23:59:59.000001Z"), 1L);
        assertThat(GiftOrderCursor.decodeOrNull(v.encode())).isEqualTo(v);
    }

    @Test
    void invalidIsNull() {
        assertThat(GiftOrderCursor.decodeOrNull(null)).isNull();
        assertThat(GiftOrderCursor.decodeOrNull("")).isNull();
        assertThat(GiftOrderCursor.decodeOrNull("!!!")).isNull();
        assertThat(GiftOrderCursor.decodeOrNull(b64("2:1:1"))).isNull();
        assertThat(GiftOrderCursor.decodeOrNull(b64("1:1:0"))).isNull();
        assertThat(GiftOrderCursor.decodeOrNull(b64("1:1"))).isNull();
        assertThat(GiftOrderCursor.decodeOrNull(b64("a:b:c"))).isNull();
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }
}
