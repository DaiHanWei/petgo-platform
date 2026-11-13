package com.tailtopia.content.rank;

import com.tailtopia.content.repository.ContentPostRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 优质老帖候选 id（修复线 2026-11-12）：近 N 天互动最高的前 M 条，<b>全站共用一份</b>，Redis 存 {@link #TTL}。
 *
 * <p>为什么要存：{@link ContentPostRepository#findQualityCandidateIds} 要把近 30 天全部帖子的赞评聚合一遍，
 * 生产实测约 0.2 秒、且随帖量线性增长；而它与查看者无关、10 分钟内排名几乎不变 —— 每次下拉刷新都重算是纯浪费。
 * 用的是推荐序已在用的 Redis（与 {@link FeedSequenceStore} 同一套），不引新组件。
 *
 * <p>🛡 键里带 {@code 天数 / 条数 / 评论权重}：后台改了参数，下一次刷新即按新参数重算，不必等旧值过期。
 * 🛡 Redis 不可用 → 直接查库（走 {@link FeedRankRedisGuard} 告警），不影响出页。
 * 🛡 这里只存 id；查看者维度的过滤（拉黑、举报）由调用方每次走 {@code findRankableByIds} 现套，存的东西里没有任何人的信息。
 */
@Component
public class FeedQualityPoolCache {

    static final String KEY_PREFIX = "feed:quality:";

    static final Duration TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redis;
    private final ContentPostRepository posts;

    public FeedQualityPoolCache(StringRedisTemplate redis, ContentPostRepository posts) {
        this.redis = redis;
        this.posts = posts;
    }

    /** 互动量降序的帖子 id；{@code size <= 0} 或 {@code days <= 0} → 空（不查不存）。 */
    public List<Long> topIds(int days, int size, double commentWeight, Instant now) {
        if (size <= 0 || days <= 0) {
            return List.of();
        }
        String key = KEY_PREFIX + days + ":" + size + ":" + commentWeight;
        String cached = FeedRankRedisGuard.guard("qualityPool.get", () -> redis.opsForValue().get(key), null);
        if (cached != null) {
            return parse(cached);
        }
        List<Long> ids = posts.findQualityCandidateIds(now.minus(Duration.ofDays(days)), commentWeight, size)
                .stream().map(Number::longValue).toList();
        String value = String.join(",", ids.stream().map(String::valueOf).toList());
        FeedRankRedisGuard.guardVoid("qualityPool.set", () -> redis.opsForValue().set(key, value, TTL));
        return ids;
    }

    /** 存的是逗号分隔 id；空串 = 当时没有任何候选（也要存，否则冷清时段每次刷新都会重查）。 */
    static List<Long> parse(String raw) {
        if (raw.isBlank()) {
            return List.of();
        }
        return Arrays.stream(raw.split(",")).map(Long::parseLong).toList();
    }
}
