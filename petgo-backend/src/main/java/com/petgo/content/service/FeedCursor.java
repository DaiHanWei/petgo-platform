package com.petgo.content.service;

import com.petgo.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Feed 游标（Story 3.2）。编码 {@code (createdAt, id)} 复合键，base64-url 不可枚举对外，
 * **不暴露顺序 id 语义**给客户端推算。排序 {@code created_at DESC, id DESC}，游标稳定防漏防重。
 *
 * @param createdAt 该条创建时刻（微秒精度，UTC）
 * @param id        该条 id（tie-breaker）
 */
public record FeedCursor(Instant createdAt, long id) {

    /** 编码为对外 token：{@code base64url("<epochMicros>:<id>")}。 */
    public String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码对外 token；格式非法一律 422（不外泄内部细节）。 */
    public static FeedCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.indexOf(':');
            long micros = Long.parseLong(raw.substring(0, sep));
            long id = Long.parseLong(raw.substring(sep + 1));
            Instant ts = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
            return new FeedCursor(ts, id);
        } catch (RuntimeException e) {
            throw AppException.validation("游标无效");
        }
    }
}
