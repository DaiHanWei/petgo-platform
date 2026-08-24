package com.tailtopia.content.rank;

import java.time.Instant;
import java.util.List;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 序列快照（V1.1.6 Story 16.1 · AC3）。
 *
 * <p>数据结构：
 * <pre>
 *   LIST feed:seq:{namespace}:{seed}   按推荐序排好的 contentId，下标即位次
 * </pre>
 * 用 LIST 而不是 STRING+JSON：翻页要按游标读一段，{@code LRANGE} 正好是「按下标取区间」，
 * 不必把整条序列取回来再切。
 *
 * <p>🔴 <b>这是正确性需求，不是性能优化</b>：分数随点赞/评论变化，每次翻页实时重算会让第 2 页
 * <b>重复</b>第 1 页的内容、也会<b>跳过</b>某些内容 —— 而这两种表现都不会报错，
 * 只会让用户觉得「首页怎么老是那几条」。
 *
 * <p>种子口径：冷启动 / 下拉刷新时按 {@code 用户 id + 刷新时间戳} 生成，一次性算出前
 * {@link FeedRankProperties#sequenceLength()} 条；翻页只按游标读缓存序列，🛡 <b>不重算分数</b>。
 * 下拉刷新 = 换种子 = 重新生成序列。游标超出已缓存长度 → 用<b>同一种子</b>续算下一段并 append。
 *
 * <p>🛡 游客<b>照常</b>使用序列快照（它解决同一会话内的翻页重复，与跨会话无关）——
 * 只有曝光衰减对游客不生效。
 */
@Component
public class FeedSequenceStore {

    static final String KEY_PREFIX = "feed:seq:";

    private final StringRedisTemplate redis;
    private final FeedRankProperties props;

    public FeedSequenceStore(StringRedisTemplate redis, FeedRankProperties props) {
        this.redis = redis;
        this.props = props;
    }

    static String key(FeedRankCacheKey cacheKey, String seed) {
        return KEY_PREFIX + cacheKey.namespace() + ":" + seed;
    }

    /**
     * 新种子：刷新时刻的毫秒数（36 进制压短）。
     *
     * <p>种子只需保证「同一次刷新内稳定、下一次刷新不同」，不需要不可猜 ——
     * 它不是对外暴露的标识，只是缓存键的一段。⚠️ 因此<b>不要</b>为它套不可枚举 token
     * 那套（那条护栏针对的是对外暴露的资源标识）。
     *
     * <p>⚠️ 键空间已经带了 namespace，所以种子里<b>不再拼 userId</b> ——
     * 拼了等于把 userId 在键里写两遍。
     *
     * <p>🔴 <b>前缀 {@value FeedRankCursor#SEED_PREFIX} 不是装饰</b>：它让推荐序游标与时间倒序游标的
     * 编码空间<b>不可能重叠</b>。没有它，时间倒序游标 {@code "<micros>:<id>"} 喂给推荐序解码器会被
     * <b>静默接受</b>（seed 变成那串毫秒数、consumed 变成 id），用户拿到一个不存在种子的
     * 任意偏移页 —— 不崩、不报错、只是页很怪，没人查得出来。
     */
    public String newSeed(Instant refreshAt) {
        return FeedRankCursor.SEED_PREFIX + Long.toString(refreshAt.toEpochMilli(), 36);
    }

    /** 已缓存的序列长度；Redis 不可用或键不存在 → 0。 */
    public long length(FeedRankCacheKey cacheKey, String seed) {
        String key = key(cacheKey, seed);
        Long size = FeedRankRedisGuard.guard("sequenceLength", () -> redis.opsForList().size(key),
                null);
        return size == null ? 0L : size;
    }

    /**
     * 读一段序列（{@code [offset, offset+limit)}）。
     *
     * <p>🛡 Redis 不可用 / 键已过期 → 返回<b>空列表</b>，由调用方实时算当页（级别 3 降级）。
     * 返回空 <b>不代表「没有内容」</b> —— 调用方不得把它当成空页返回给客户端。
     */
    public List<Long> read(FeedRankCacheKey cacheKey, String seed, long offset, int limit) {
        if (limit <= 0 || offset < 0) {
            return List.of();
        }
        String key = key(cacheKey, seed);
        List<String> raw = FeedRankRedisGuard.guard("sequenceRead",
                () -> redis.opsForList().range(key, offset, offset + limit - 1), null);
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        return raw.stream().map(Long::valueOf).toList();
    }

    /**
     * 写入 / 续写序列（RPUSH 追加 + 刷新 TTL）。
     *
     * <p>续算下一段时用<b>同一种子</b>再调一次本方法即可 —— 追加语义天然支持分段生成。
     *
     * <p>⚠️ TTL 每次写入都会续期。这是有意的：用户还在翻页说明这次刷新仍在进行中，
     * 快照不该在翻页途中过期（那会让下一页与已看过的页重复 —— 正是这个键要防的事）。
     */
    public void append(FeedRankCacheKey cacheKey, String seed, List<Long> contentIds) {
        if (contentIds == null || contentIds.isEmpty()) {
            return;
        }
        String key = key(cacheKey, seed);
        List<String> values = contentIds.stream().map(String::valueOf).toList();
        FeedRankRedisGuard.guardVoid("sequenceAppend", () -> {
            redis.opsForList().rightPushAll(key, values);
            redis.expire(key, props.sequenceTtl());
        });
    }

    /** 下拉刷新时丢弃旧快照（可选；不调也无害，旧种子的键会自行过期）。 */
    public void discard(FeedRankCacheKey cacheKey, String seed) {
        FeedRankRedisGuard.guardVoid("sequenceDiscard", () -> redis.delete(key(cacheKey, seed)));
    }
}
