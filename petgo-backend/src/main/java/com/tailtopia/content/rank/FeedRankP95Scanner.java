package com.tailtopia.content.rank;

import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * P95 定期重算（V1.1.6 Story 16.4 · AC3）。
 *
 * <p>{@code 互动度 = ln(1 + 赞 + w×评) / ln(1 + P95)}。这个 P95 <b>必须是动态值</b>：
 * 首页点赞上线后互动量会整体跳升，写死的分母会让互动度整体贬值 ——
 * 而那不报错，只是"排序看着越来越像时间倒序"。
 *
 * <p>🔴 <b>重算失败一律沿用上一次的值</b>：{@code ln(1 + P95)} 是分母，
 * 回落到 0 会让互动度对<b>所有内容</b>记 0（打分器有除零保护，不会崩，但排序退化成纯新鲜度）。
 * 所以本类<b>只在算出结果后才写库</b>，出错就什么都不动。
 */
@Component
public class FeedRankP95Scanner {

    /** 告警锚点串 —— 连续失败意味着 P95 在悄悄变旧。 */
    static final String ALERT_MARKER = "feed-rank-p95-recompute-failed";

    private static final Logger log = LoggerFactory.getLogger(FeedRankP95Scanner.class);

    /** 统计窗口：近 30 天。更长会把早期低互动期拖进来，更短在小体量下抖动大。 */
    private static final int WINDOW_DAYS = 30;

    private final FeedRankConfigRepository configs;
    private final FeedRankInteractionStats stats;
    private final boolean enabled;

    public FeedRankP95Scanner(FeedRankConfigRepository configs, FeedRankInteractionStats stats,
            @Value("${petgo.feed.rank.p95-recompute-enabled:true}") boolean enabled) {
        this.configs = configs;
        this.stats = stats;
        this.enabled = enabled;
    }

    /** 每 6 小时一次。P95 是慢变量，更密只是白跑；更疏则首页点赞上线那几天跟不上。 */
    @Scheduled(fixedDelayString = "${petgo.feed.rank.p95-recompute-interval-ms:21600000}",
            initialDelayString = "${petgo.feed.rank.p95-recompute-initial-delay-ms:120000}")
    public void scheduled() {
        if (!enabled) {
            return;
        }
        recompute();
    }

    /**
     * 跑一次重算。
     *
     * @return 写入的新值；{@code null} = 本次没写（算不出来或与旧值相同）
     */
    @Transactional
    public Double recompute() {
        try {
            Instant since = Instant.now().minus(WINDOW_DAYS, ChronoUnit.DAYS);
            FeedRankConfig cfg = configs.findById(FeedRankConfig.SINGLETON_ID).orElse(null);
            if (cfg == null) {
                log.warn("{} reason=config-row-missing", ALERT_MARKER);
                return null;
            }
            List<Double> values = stats.interactionValues(since, cfg.getCommentWeight());
            if (values.isEmpty()) {
                // 🔴 不是错误：新平台近 30 天可能一条互动都没有。
                //    但也**绝不能因此把 P95 写成 0** —— 沿用旧值。
                log.info("P95 重算：近 {} 天无互动数据，沿用旧值 {}", WINDOW_DAYS,
                        cfg.getInteractionP95());
                return null;
            }
            double p95 = percentile95(values);
            if (p95 <= 0) {
                log.info("P95 重算：算出 {}，非正数不写，沿用旧值 {}", p95, cfg.getInteractionP95());
                return null;
            }
            if (Math.abs(p95 - cfg.getInteractionP95()) < 1e-9) {
                cfg.setP95RecomputedAt(Instant.now()); // 值没变也记一次"算过了"，便于看是否在跑
                configs.save(cfg);
                return null;
            }
            log.info("P95 重算：{} → {}（近 {} 天 {} 条互动）", cfg.getInteractionP95(), p95,
                    WINDOW_DAYS, values.size());
            cfg.setInteractionP95(p95);
            cfg.setP95RecomputedAt(Instant.now());
            configs.save(cfg);
            return p95;
        } catch (RuntimeException e) {
            // 🔴 AC3：失败沿用上一次的值，不回落 0、不抛错（抛了会污染调度线程的后续执行）。
            log.warn("{} cls={} msg={}", ALERT_MARKER, e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }

    /** 95 分位（升序取 floor(0.95×(n-1)) 位）。与推荐序冷启动兜底用的是同一口径。 */
    static double percentile95(List<Double> values) {
        double[] v = values.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        if (v.length == 0) {
            return 0d;
        }
        return v[(int) Math.floor(0.95 * (v.length - 1))];
    }
}
