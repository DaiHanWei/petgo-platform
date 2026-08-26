package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0：P95 定期重算（Story 16.4 · AC3）。
 *
 * <p>🔴 本类的重点全在<b>失败路径</b>：{@code ln(1 + P95)} 是分母，
 * 把它写成 0 会让互动度对<b>所有内容</b>记 0（不崩，但排序退化成纯新鲜度）——
 * 表现是"首页又变回时间倒序了"，而且不报错。
 */
class FeedRankP95ScannerTest {

    private FeedRankConfigRepository configs;
    private FeedRankInteractionStats stats;
    private FeedRankConfig cfg;
    private FeedRankP95Scanner scanner;

    @BeforeEach
    void setUp() {
        configs = mock(FeedRankConfigRepository.class);
        stats = mock(FeedRankInteractionStats.class);
        cfg = mock(FeedRankConfig.class);
        when(cfg.getCommentWeight()).thenReturn(2.0);
        when(cfg.getInteractionP95()).thenReturn(50.0);
        when(configs.findById(FeedRankConfig.SINGLETON_ID)).thenReturn(Optional.of(cfg));
        scanner = new FeedRankP95Scanner(configs, stats, true);
    }

    @Test
    void writesTheRecomputedPercentile() {
        // 100 个值 0..99 → floor(0.95*99) = 94 → 94.0
        List<Double> values = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) {
            values.add((double) i);
        }
        when(stats.interactionValues(any(), anyDouble())).thenReturn(values);

        Double written = scanner.recompute();

        assertThat(written).isEqualTo(94.0);
        verify(cfg).setInteractionP95(94.0);
        verify(cfg).setP95RecomputedAt(any());
        verify(configs).save(cfg);
    }

    /** 🔴 无数据 → 沿用旧值，<b>绝不写 0</b>。 */
    @Test
    void noDataKeepsThePreviousValue() {
        when(stats.interactionValues(any(), anyDouble())).thenReturn(List.of());

        assertThat(scanner.recompute()).isNull();

        verify(cfg, never()).setInteractionP95(anyDouble());
        verify(configs, never()).save(any());
    }

    /** 🔴 全是 0 互动（新平台）→ 算出 0 → 不写，沿用旧值。 */
    @Test
    void allZeroInteractionKeepsThePreviousValue() {
        when(stats.interactionValues(any(), anyDouble()))
                .thenReturn(List.of(0d, 0d, 0d, 0d, 0d));

        assertThat(scanner.recompute()).isNull();

        verify(cfg, never()).setInteractionP95(anyDouble());
    }

    /** 🔴 统计查询抛错 → 沿用旧值，且<b>不抛出</b>（抛了会污染调度线程后续执行）。 */
    @Test
    void statsFailureIsSwallowedAndKeepsThePreviousValue() {
        when(stats.interactionValues(any(), anyDouble()))
                .thenThrow(new org.springframework.dao.QueryTimeoutException("db down"));

        assertThat(scanner.recompute()).isNull();

        verify(cfg, never()).setInteractionP95(anyDouble());
    }

    /** 配置行缺失 → 记告警、不抛出。 */
    @Test
    void missingConfigRowIsReportedNotThrown() {
        when(configs.findById(FeedRankConfig.SINGLETON_ID)).thenReturn(Optional.empty());

        assertThat(scanner.recompute()).isNull();
    }

    /** 值没变 → 不改值，但记一次「算过了」（便于确认扫描在跑）。 */
    @Test
    void unchangedValueStillStampsTheTimestamp() {
        when(stats.interactionValues(any(), anyDouble())).thenReturn(List.of(50d, 50d, 50d));

        assertThat(scanner.recompute()).isNull();

        verify(cfg, never()).setInteractionP95(anyDouble());
        verify(cfg).setP95RecomputedAt(any());
        verify(configs).save(cfg);
    }

    /** 开关关掉 → 定时入口什么都不做。 */
    @Test
    void disabledScannerDoesNothingOnSchedule() {
        new FeedRankP95Scanner(configs, stats, false).scheduled();
        verify(configs, never()).findById(any());
    }

    /** 分位口径：升序取 floor(0.95×(n-1)) 位 —— 与推荐序冷启动兜底同一口径。 */
    @Test
    void percentileUsesTheSameConventionEverywhere() {
        assertThat(FeedRankP95Scanner.percentile95(List.of(1d))).isEqualTo(1d);
        assertThat(FeedRankP95Scanner.percentile95(List.of(1d, 2d))).isEqualTo(1d);
        assertThat(FeedRankP95Scanner.percentile95(List.of())).isEqualTo(0d);
    }
}
