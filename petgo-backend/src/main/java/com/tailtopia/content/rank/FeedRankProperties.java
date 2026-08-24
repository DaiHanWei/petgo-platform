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

    public FeedRankProperties(
            @Value("${petgo.feed.rank.exposure-decay:0.3}") double exposureDecay,
            @Value("${petgo.feed.rank.seen-window-days:7}") int seenWindowDays,
            @Value("${petgo.feed.rank.sequence-ttl-minutes:30}") int sequenceTtlMinutes,
            @Value("${petgo.feed.rank.sequence-length:100}") int sequenceLength) {
        this.exposureDecay = exposureDecay;
        this.seenWindowDays = seenWindowDays;
        this.sequenceTtlMinutes = sequenceTtlMinutes;
        this.sequenceLength = sequenceLength;
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
}
