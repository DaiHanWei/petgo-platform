package com.tailtopia.content.rank;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 推荐序基建参数（V1.1.6 Story 16.1）。
 *
 * <p>⚠️ <b>本类是临时载体</b>：Story 16.4 会把打分类参数搬到既有配置中心（改后无需发版）。
 * 那一步不是优化项 —— FR-95 与首页点赞同批发版，开发阶段线上不存在 {@code source=feed} 的点赞数据，
 * 参数<b>第一次校准必然发生在发版之后</b>（OQ-B1）。所以这里的取值只当默认值看，
 * 别在别处再抄一份常量。
 *
 * <p>🛡 曝光衰减与序列长度归本 story；打分权重（0.6/0.4）、评论权重、P95 归 16.2/16.4，<b>不在这里</b>。
 */
@Component
public class FeedRankProperties {

    private final double exposureDecay;
    private final int seenWindowDays;
    private final int sequenceTtlMinutes;
    private final int sequenceLength;
    private final int candidatePoolSize;

    public FeedRankProperties(
            @Value("${petgo.feed.rank.exposure-decay:0.3}") double exposureDecay,
            @Value("${petgo.feed.rank.seen-window-days:7}") int seenWindowDays,
            @Value("${petgo.feed.rank.sequence-ttl-minutes:30}") int sequenceTtlMinutes,
            @Value("${petgo.feed.rank.sequence-length:100}") int sequenceLength,
            @Value("${petgo.feed.rank.candidate-pool-size:1000}") int candidatePoolSize) {
        this.exposureDecay = exposureDecay;
        this.seenWindowDays = seenWindowDays;
        this.sequenceTtlMinutes = sequenceTtlMinutes;
        this.sequenceLength = sequenceLength;
        this.candidatePoolSize = candidatePoolSize;
    }

    /**
     * 已曝光内容的打分乘数（默认 0.3）。
     *
     * <p>🛡 <b>是降权不是硬过滤</b>：候选池只有几百条，硬过滤已看过的内容会让活跃用户直接刷空。
     */
    public double exposureDecay() {
        return exposureDecay;
    }

    /** 曝光记录的保留窗口（默认 7 天）。 */
    public Duration seenWindow() {
        return Duration.ofDays(seenWindowDays);
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
