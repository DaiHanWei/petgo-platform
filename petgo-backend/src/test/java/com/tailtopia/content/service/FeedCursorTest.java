package com.petgo.content.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgo.shared.error.AppException;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** L0：游标编解码往返 + 非法 token 拒绝（AC2 游标稳定性基础）。 */
class FeedCursorTest {

    @Test
    void roundTripPreservesCreatedAtAndId() {
        Instant ts = Instant.parse("2026-06-02T10:15:30.123456Z");
        FeedCursor c = new FeedCursor(ts, 42L);
        FeedCursor back = FeedCursor.decode(c.encode());
        assertThat(back.id()).isEqualTo(42L);
        assertThat(back.createdAt()).isEqualTo(ts);
    }

    @Test
    void tokenIsOpaqueBase64NotPlainId() {
        String token = new FeedCursor(Instant.now(), 7L).encode();
        // 不直接暴露顺序 id 语义（不是裸 "...:7"）。
        assertThat(token).doesNotContain(":");
    }

    @Test
    void invalidTokenRejected() {
        assertThatThrownBy(() -> FeedCursor.decode("!!!not-base64!!!"))
                .isInstanceOf(AppException.class);
    }
}
