package com.tailtopia.profile.recommend;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/** L0：V1.5.0 onboarding-recall Story 1.1 · AC3 —— 游标追加段号且兼容旧串。 */
class PetRecommendCursorTest {

    private static final Instant AT = Instant.parse("2026-11-14T03:04:05.123456Z");

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void 段号编解码往返() {
        for (int tier = 0; tier <= 2; tier++) {
            PetRecommendCursor c = new PetRecommendCursor(7L, AT, 99L, tier);
            assertThat(PetRecommendCursor.decodeOrNull(c.encode())).isEqualTo(c);
        }
    }

    @Test
    void 旧三段串按段0解析() {
        long micros = AT.getEpochSecond() * 1_000_000L + AT.getNano() / 1_000L;
        PetRecommendCursor c = PetRecommendCursor.decodeOrNull(b64("7:" + micros + ":99"));
        assertThat(c).isNotNull();
        assertThat(c.tier()).isZero();
        assertThat(c.petId()).isEqualTo(99L);
        assertThat(c.lastPostedAt()).isEqualTo(AT);
    }

    @Test
    void 三参构造器等价于段0() {
        assertThat(new PetRecommendCursor(1L, AT, 2L)).isEqualTo(new PetRecommendCursor(1L, AT, 2L, 0));
    }

    @Test
    void 坏串与越界段号返回null() {
        assertThat(PetRecommendCursor.decodeOrNull("%%%")).isNull();
        assertThat(PetRecommendCursor.decodeOrNull(b64("1:2:3:9"))).isNull();
        assertThat(PetRecommendCursor.decodeOrNull(b64("1:2:3:-1"))).isNull();
        assertThat(PetRecommendCursor.decodeOrNull(b64("1:2:3:4:5"))).isNull();
        assertThat(PetRecommendCursor.decodeOrNull(b64("1:2:3:x"))).isNull();
        assertThat(PetRecommendCursor.decodeOrNull(null)).isNull();
    }
}
