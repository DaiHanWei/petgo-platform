package com.tailtopia.admin.config.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.config.domain.ConfigChangeLog;
import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.repository.ConfigChangeLogRepository;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import com.tailtopia.config.repository.PawCoinConfigRepository;
import com.tailtopia.config.repository.PawCoinTopupTierRepository;
import com.tailtopia.config.repository.PricingConfigRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

/** L0：推荐算法参数的写入校验与 diff 审计（Story 16.4 · AC1 / AC2 / AC4）。 */
class AdminFeedRankConfigTest {

    private FeedRankConfigRepository repo;
    private ConfigChangeLogRepository changeLogs;
    private AdminAuditService audit;
    private AdminConfigService svc;
    private FeedRankConfig cfg;

    @BeforeEach
    void setUp() {
        repo = Mockito.mock(FeedRankConfigRepository.class);
        changeLogs = Mockito.mock(ConfigChangeLogRepository.class);
        audit = Mockito.mock(AdminAuditService.class);
        svc = new AdminConfigService(Mockito.mock(PricingConfigRepository.class),
                Mockito.mock(PawCoinConfigRepository.class),
                Mockito.mock(PawCoinTopupTierRepository.class), changeLogs, audit, repo);
        cfg = seed();
        Mockito.when(repo.findById(FeedRankConfig.SINGLETON_ID)).thenReturn(Optional.of(cfg));
    }

    /** 种子值 = 迁移里的默认值。 */
    private static FeedRankConfig seed() {
        FeedRankConfig c = instantiate();
        set(c, "freshnessWeight", 0.6);
        set(c, "interactionWeight", 0.4);
        set(c, "commentWeight", 2.0);
        set(c, "interactionP95", 0d);
        set(c, "exposureDecay", 0.3);
        set(c, "seenWindowDays", 7);
        set(c, "windowSize", 10);
        set(c, "attrFunQuota", 5);
        set(c, "attrEduQuota", 3);
        set(c, "attrLifeQuota", 2);
        set(c, "speciesMainQuota", 6);
        set(c, "speciesOtherQuota", 2);
        set(c, "speciesGeneralQuota", 2);
        return c;
    }

    private static FeedRankConfig instantiate() {
        try {
            var ctor = FeedRankConfig.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            return ctor.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void set(Object o, String field, Object v) {
        try {
            var f = o.getClass().getDeclaredField(field);
            f.setAccessible(true);
            f.set(o, v);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static FeedRankForm form(double fw, double iw, double cw, double decay, int days,
            int window, int fun, int edu, int life, int main, int other, int general) {
        return new FeedRankForm(fw, iw, cw, decay, days, window, fun, edu, life, main, other, general);
    }

    private static FeedRankForm valid() {
        return form(0.7, 0.3, 3, 0.2, 14, 10, 5, 3, 2, 6, 2, 2);
    }

    // ── AC1 / AC2：改了就生效，且逐字段留痕 ─────────────────────────

    @Test
    void savesAndLogsEveryChangedField() {
        svc.updateFeedRank(valid(), 7L);

        assertThat(cfg.getFreshnessWeight()).isEqualTo(0.7);
        assertThat(cfg.getCommentWeight()).isEqualTo(3);
        assertThat(cfg.getExposureDecay()).isEqualTo(0.2);
        assertThat(cfg.getSeenWindowDays()).isEqualTo(14);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConfigChangeLog>> logs = ArgumentCaptor.forClass(List.class);
        Mockito.verify(changeLogs).saveAll(logs.capture());
        // 🛡 只记**改动过的**字段（未改的不记，沿用本类既有口径）。
        //    按字段名断言而不是数个数 —— 数个数在漏算一个字段时给不出任何线索。
        assertThat(logs.getValue()).extracting(ConfigChangeLog::getField)
                .containsExactlyInAnyOrder("freshness_weight", "interaction_weight",
                        "comment_weight", "exposure_decay", "seen_window_days");
        Mockito.verify(audit).record(Mockito.eq(7L), Mockito.eq("CONFIG_UPDATE_FEED_RANK"),
                Mockito.any(), Mockito.any(), Mockito.any());
    }

    /** 🛡 无变更 → 不写库、不记日志、不审计。 */
    @Test
    void noChangeWritesNothing() {
        svc.updateFeedRank(form(0.6, 0.4, 2, 0.3, 7, 10, 5, 3, 2, 6, 2, 2), 7L);

        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
        Mockito.verifyNoInteractions(changeLogs, audit);
    }

    // ── AC4：🛡 配比自洽是硬校验 ────────────────────────────────────

    /**
     * 🔴 属性配比之和不等于窗口 → 拒绝保存。
     *
     * <p>不校验的后果：窗口凑不满或溢出 —— 而那<b>不会报错</b>，
     * 只会让首页节奏莫名其妙，且极难被想到去查配置。
     */
    @Test
    void inconsistentAttributeQuotasAreRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0.6, 0.4, 2, 0.3, 7, 10, 5, 3, 3, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("须等于窗口大小");
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    @Test
    void inconsistentSpeciesQuotasAreRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0.6, 0.4, 2, 0.3, 7, 10, 5, 3, 2, 6, 2, 3), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("物种配比之和");
        Mockito.verify(repo, Mockito.never()).save(Mockito.any());
    }

    /** 🔴 单一属性配额超过窗口一半 → 拒绝（否则必然同属性相邻，穿插失去意义）。 */
    @Test
    void oversizedSingleAttributeQuotaIsRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0.6, 0.4, 2, 0.3, 7, 10, 6, 3, 1, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不得超过窗口的一半");
    }

    /** 🛡 曝光衰减 > 1 = 「看过的排更前面」，与这一维的意图完全相反。 */
    @Test
    void exposureDecayAboveOneIsRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0.6, 0.4, 2, 1.5, 7, 10, 5, 3, 2, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("曝光衰减");
    }

    /** 🛡 两个权重同时为 0 会让所有内容同分（排序完全由 tie-breaker 决定）。 */
    @Test
    void bothWeightsZeroIsRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0, 0, 2, 0.3, 7, 10, 5, 3, 2, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("不可同时为 0");
    }

    @Test
    void negativeValuesAreRejected() {
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(-1, 0.4, 2, 0.3, 7, 10, 5, 3, 2, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> svc.updateFeedRank(
                form(0.6, 0.4, 2, 0.3, 0, 10, 5, 3, 2, 6, 2, 2), 7L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("曝光窗口");
    }

    /**
     * 🔴 <b>校验与生成排期用同一处规则</b>。
     *
     * <p>两处各写一遍就会出现「保存通过了，但生成出来的模板是坏的」——
     * 那种不一致没有任何报错，只能靠人肉刷首页发现。
     */
    @Test
    void anyQuotaThatPassesValidationCanActuallyGenerateASchedule() {
        int[][] passing = {{5, 3, 2, 10}, {4, 4, 2, 10}, {5, 5, 0, 10}, {3, 3, 2, 8}};
        for (int[] q : passing) {
            assertThat(com.tailtopia.content.rank.AttributeTemplate
                    .rejectUnusableQuotas(q[0], q[1], q[2], q[3])).isNull();
            var schedule = com.tailtopia.content.rank.AttributeTemplate
                    .forQuotas(q[0], q[1], q[2], q[3]);
            assertThat(schedule.window()).isEqualTo(q[3]);
            assertThat(schedule.variantA()).hasSize(q[3]);
        }
    }
}
