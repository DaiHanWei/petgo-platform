package com.tailtopia.content.rank;

import com.tailtopia.shared.error.AppException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * 推荐序游标（V1.1.6 Story 16.3 · AC3 的翻页契约）。
 *
 * <p>与时间倒序的 {@code FeedCursor} 是<b>两种不同的游标</b>，刻意不复用：
 * 时间倒序按 {@code (createdAt, id)} 定位，推荐序按 <b>(种子, 已消费条数)</b> 定位。
 * 硬塞进同一个 record 会让两条路径的翻页语义纠缠在一起。
 *
 * <p>⚠️ {@code consumed} 是「已从序列里消费掉的条数」，<b>不是「页码 × 20」</b>：
 * 服务页时会有条目因为已不合格（被删 / 被拉黑）而被丢弃，那些也算消费掉了 ——
 * 用页码乘法会让下一页重复读到那些被丢弃的位置。
 *
 * @param seed     这次刷新的序列种子
 * @param consumed 已消费的序列条数
 */
public record FeedRankCursor(String seed, int consumed) {

    /**
     * 种子前缀。
     *
     * <p>🔴 存在的唯一理由：把本游标与时间倒序游标的编码空间<b>隔开</b>。
     * 两者都是 {@code base64url("<a>:<b>")}，不加区分标记时时间倒序游标
     * {@code "<micros>:<id>"} 会被本类<b>静默解出</b>一个看似合法的 (seed, consumed) ——
     * 表现是客户端切 Tab 忘了清游标就拿到一页莫名其妙的内容，且服务端一条错都不记。
     */
    public static final String SEED_PREFIX = "s";

    /** 编码为对外 token：{@code base64url("<seed>:<consumed>")}（不暴露顺序语义）。 */
    public String encode() {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString((seed + ":" + consumed).getBytes(StandardCharsets.UTF_8));
    }

    /** 解码；格式非法一律 422（不外泄内部细节，沿用 FeedCursor 的口径）。 */
    public static FeedRankCursor decode(String token) {
        try {
            String raw = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);
            int sep = raw.lastIndexOf(':');
            String seed = raw.substring(0, sep);
            int consumed = Integer.parseInt(raw.substring(sep + 1));
            if (!seed.startsWith(SEED_PREFIX) || seed.length() <= SEED_PREFIX.length()
                    || consumed < 0) {
                // 🔴 拒绝而不是"尽力解读"：这里最可能收到的就是另一条路径的游标。
                throw new IllegalArgumentException("bad cursor");
            }
            return new FeedRankCursor(seed, consumed);
        } catch (RuntimeException e) {
            throw AppException.validation("游标无效");
        }
    }
}
