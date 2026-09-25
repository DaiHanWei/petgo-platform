package com.tailtopia.content.service;

import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * 一级评论**热度序**的游标（V1.3.0 批次 A · Story 2.4 · AD-A8.2/A8.6）。
 *
 * <p>排序键是三元组 {@code (likeCount DESC, createdAt ASC, id ASC)}，游标必须携带同样三元
 * —— 少一元就不是全序，翻页会出现重复或漏条。{@code id} 是最终 tie-breaker。
 *
 * <h2>🔴 为什么新建一个类，而不是给 {@code FeedCursor} 加一个字段</h2>
 * {@link FeedCursor} 是 <b>Feed 与评论共用</b>的二元组游标（{@code createdAt + id}）。
 * 给它加一元会**溢出到 Feed**：老客户端手里的二元组 token 进到三元组解码逻辑里，
 * 要么报错、要么按错误的字段对齐 —— 项目自家 {@code FeedRankCursor} 的注释里已经写过
 * 「静默误解码」的教训。两个游标并存的代价只是多一个类，远小于那个风险。
 *
 * <p>⚠️ <b>本类与 {@code FeedCursor} 的编码格式刻意不同</b>（多一段 + 前缀），
 * 这样万一有人把 token 传错地方，是**当场 422**，而不是解出一个看似合理的错位游标。
 *
 * @param likeCount 该条的点赞数（第一排序元）
 * @param createdAt 该条创建时刻（微秒精度，UTC；第二排序元）
 * @param id        该条 id（最终 tie-breaker，保证全序）
 */
public record CommentHotCursor(long likeCount, Instant createdAt, long id) {

    /** 前缀：与 {@code FeedCursor} 的二元组 token 区分开，传错地方时当场失败而不是静默错位。 */
    private static final String PREFIX = "h1";

    /** 编码为对外 token：{@code base64url("h1:<likeCount>:<epochMicros>:<id>")}。 */
    public String encode() {
        long micros = createdAt.getEpochSecond() * 1_000_000L + createdAt.getNano() / 1_000L;
        String raw = PREFIX + ":" + likeCount + ":" + micros + ":" + id;
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    /** 解码对外 token；格式非法一律 422（不外泄内部细节）。 */
    public static CommentHotCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            String[] parts = raw.split(":");
            if (parts.length != 4 || !PREFIX.equals(parts[0])) {
                throw new IllegalArgumentException("bad cursor shape");
            }
            long likeCount = Long.parseLong(parts[1]);
            long micros = Long.parseLong(parts[2]);
            long id = Long.parseLong(parts[3]);
            Instant ts = Instant.ofEpochSecond(micros / 1_000_000L, (micros % 1_000_000L) * 1_000L);
            return new CommentHotCursor(likeCount, ts, id);
        } catch (RuntimeException e) {
            throw AppException.validation("游标无效");
        }
    }
}
