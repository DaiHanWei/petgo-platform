package com.tailtopia.content.rank;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import com.tailtopia.config.service.PlatformConfigService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Component;

/**
 * 曝光记录（V1.1.6 Story 16.1 · AC1 / AC2 / AC4）。
 *
 * <p>数据结构：
 * <pre>
 *   ZSET feed:seen:{namespace}   member=contentId   score=曝光时刻(epochMillis)
 * </pre>
 *
 * <p>⚠️ <b>用 ZSET 而不是 AC 字面写的 SET，是一处有意的偏离</b>，理由是 SET 做不到 AC 的意图：
 * Redis 的 TTL 只能设在<b>键</b>上，SET 成员没有各自的过期。而每次曝光都要写入 ⇒ 键 TTL 必然被
 * 反复续期 ⇒ 对天天刷首页的用户，那个键<b>永不过期</b>，半年前看过的内容仍然算「已曝光」。
 * 后果不是报错而是排序悄悄失真：几乎所有内容都挂着 0.3 系数，曝光衰减这一维等于被抹平。
 * 换成 ZSET 后「7 天」是<b>每条曝光各自的</b>窗口 —— 这才是 AC 想要的。
 * 顺带也让 16.4 的「曝光窗口天数」可调真正有意义（改 SET 的键 TTL 改不动存量成员）。
 *
 * <p>🛡 <b>不入 PG</b>：写入量大、无查询价值（§8.1）。
 *
 * <p>🔴 <b>什么时候算「已曝光」：序列返回给客户端时即记入，不等客户端上报</b>（AC2）。
 * 等上报需要一套曝光埋点回传，而数据侧<b>没有曝光类埋点</b>（§8.3 已核实）——「下发即记」是
 * 现在唯一做得到的口径。⚠️ 代价是「下发但用户没滚到」的内容也会被记入、下次被降权；
 * 因为这是<b>降权不是过滤</b>，偏差的后果只是那条排得靠后一点，明确接受。
 */
@Component
public class FeedSeenStore {

    static final String KEY_PREFIX = "feed:seen:";

    private final StringRedisTemplate redis;

    /**
     * 曝光衰减系数与曝光窗口天数的来源（Story 16.4 起归配置表）。
     *
     * <p>🛡 每次生成序列只读一次单行 —— 不是每条内容读一次。
     */
    private final PlatformConfigService platformConfig;

    public FeedSeenStore(StringRedisTemplate redis, PlatformConfigService platformConfig) {
        this.redis = redis;
        this.platformConfig = platformConfig;
    }

    /** 曝光窗口（Story 16.4：可配）。 */
    private java.time.Duration seenWindow() {
        return java.time.Duration.ofDays(platformConfig.feedRank().getSeenWindowDays());
    }

    static String key(FeedRankCacheKey cacheKey) {
        return KEY_PREFIX + cacheKey.namespace();
    }

    /**
     * 记入曝光（序列下发时调用）。
     *
     * <p>🛡 游客直接 no-op（AC4：曝光衰减对游客不生效，记了也没人读）。
     */
    public void markSeen(FeedRankCacheKey cacheKey, Collection<Long> contentIds, Instant now) {
        if (cacheKey.guest() || contentIds == null || contentIds.isEmpty()) {
            return;
        }
        String key = key(cacheKey);
        double score = now.toEpochMilli();
        java.time.Duration window = seenWindow();
        FeedRankRedisGuard.guardVoid("markSeen", () -> {
            ZSetOperations<String, String> z = redis.opsForZSet();
            for (Long id : contentIds) {
                z.add(key, String.valueOf(id), score);
            }
            // 修剪掉出窗的成员，防止键无界增长。读路径按 score 判窗，不依赖这一步的及时性。
            z.removeRangeByScore(key, Double.NEGATIVE_INFINITY, cutoff(window, now) - 1);
            redis.expire(key, window);
        });
    }

    /**
     * 这批内容各自的曝光衰减系数：窗口内曝光过 → {@link FeedRankProperties#exposureDecay()}，否则 1.0。
     *
     * <p>供 Story 16.2 的打分作为入参 —— 引擎本身不碰 Redis（它要能在纯单测里穷举）。
     *
     * <p>🛡 一次 ZMSCORE 批量取完，<b>不逐条查</b>。
     * 🛡 游客、Redis 不可用、键不存在 → 一律全 1.0（不是 0，也不是抛错）。
     */
    public Map<Long, Double> decayFactors(FeedRankCacheKey cacheKey, List<Long> contentIds,
            Instant now) {
        Map<Long, Double> out = new HashMap<>();
        if (contentIds == null || contentIds.isEmpty()) {
            return out;
        }
        for (Long id : contentIds) {
            out.put(id, 1.0);
        }
        if (cacheKey.guest()) {
            return out;
        }
        String key = key(cacheKey);
        com.tailtopia.config.domain.FeedRankConfig cfg = platformConfig.feedRank();
        Object[] members = contentIds.stream().map(String::valueOf).toArray();
        List<Double> scores = FeedRankRedisGuard.guard("decayFactors",
                () -> redis.opsForZSet().score(key, members), null);
        if (scores == null) {
            return out;
        }
        double cutoff = cutoff(java.time.Duration.ofDays(cfg.getSeenWindowDays()), now);
        for (int i = 0; i < contentIds.size() && i < scores.size(); i++) {
            Double s = scores.get(i);
            if (s != null && s >= cutoff) {
                out.put(contentIds.get(i), cfg.getExposureDecay());
            }
        }
        return out;
    }

    /** 窗口下界（epochMillis）：早于此的曝光记录视为已过期。 */
    private static double cutoff(java.time.Duration window, Instant now) {
        return now.minus(window).toEpochMilli();
    }
}
