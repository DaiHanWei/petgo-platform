package com.tailtopia.content.rank;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 推荐序<b>基建</b>参数（V1.1.6 Story 16.1，16.4 收窄）。
 *
 * <p>✅ 打分类参数（权重 / 评论权重 / P95 / 曝光衰减 / 曝光窗口 / 两项配比 / 窗口大小）
 * 已在 Story 16.4 迁到配置表 {@code feed_rank_config}，改后<b>无需发版</b>。
 *
 * <p>🛡 <b>留在本类的只有"改了要发版也无所谓"的基建量</b>：序列快照 TTL、序列长度、候选池上界。
 * 它们不是运营会去调的东西（调错了表现是缓存失效或查询变慢，不是排序效果变差），
 * 放进后台只会多三个没人敢动的输入框。
 */
@Component
public class FeedRankProperties {

    private final int sequenceTtlMinutes;
    private final int sequenceLength;
    private final int candidatePoolSize;

    public FeedRankProperties(
            @Value("${petgo.feed.rank.sequence-ttl-minutes:30}") int sequenceTtlMinutes,
            @Value("${petgo.feed.rank.sequence-length:100}") int sequenceLength,
            @Value("${petgo.feed.rank.candidate-pool-size:1000}") int candidatePoolSize) {
        this.sequenceTtlMinutes = sequenceTtlMinutes;
        this.sequenceLength = sequenceLength;
        this.candidatePoolSize = candidatePoolSize;
    }

    /** 序列快照 TTL（默认 30 分钟）。 */
    public Duration sequenceTtl() {
        return Duration.ofMinutes(sequenceTtlMinutes);
    }

    /** 一次生成的序列长度（默认前 100 条）。游标超出后用同一种子续算下一段。 */
    public int sequenceLength() {
        return sequenceLength;
    }

    /**
     * 候选池上界（默认最近 1000 条公开内容）。
     *
     * <p>🔴 <b>候选池必须有上界</b>：不设的话这个查询随总帖数线性变大，而它每次下拉刷新都要跑一次，
     * 且推导物种、批量取赞评、取装饰标签都按这一批的规模走。
     * 取「最近 N 条」是有意的 —— 更早的内容新鲜度已趋近 0，靠互动度单独也很难排上来。
     *
     * <p>⚠️ 顺带这条 {@code ORDER BY created_at DESC LIMIT N} 让候选池查询走既有
     * {@code idx_content_posts_feed} 索引而不是全表扫（Story 16.1 的索引重估留的那条）。
     */
    public int candidatePoolSize() {
        return candidatePoolSize;
    }
}
