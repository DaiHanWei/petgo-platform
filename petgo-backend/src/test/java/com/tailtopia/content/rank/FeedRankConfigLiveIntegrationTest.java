package com.tailtopia.content.rank;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.admin.config.dto.FeedRankForm;
import com.tailtopia.admin.config.service.AdminConfigService;
import com.tailtopia.config.domain.FeedRankConfig;
import com.tailtopia.config.repository.FeedRankConfigRepository;
import com.tailtopia.config.service.PlatformConfigService;
import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：改配置 → <b>下一次生成序列即生效，不需要重启</b>（Story 16.4 · AC1）。
 *
 * <p>⚠️ 本类会真的改那一行单行配置，所以 {@link #restore()} 必须把它改回去 ——
 * 共享测试库不回滚，留下的脏配置会让**后面所有**首页相关测试按错参数排序，
 * 而它们红起来跟本类毫无关系。
 */
class FeedRankConfigLiveIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PlatformConfigService read;

    @Autowired
    private AdminConfigService write;

    @Autowired
    private FeedRankConfigRepository repo;

    @Autowired
    private ContentPostRepository posts;

    @Autowired
    private JdbcTemplate jdbc;

    private static final FeedRankForm SEED =
            new FeedRankForm(0.6, 0.4, 2, 0.3, 0.8, 0.2, 7, 10, 5, 3, 2, 6, 2, 2);

    @AfterEach
    void restore() {
        // V1.3.4 Story 1.2：科普半衰期不在后台表单里，单独还原。⚠️ 放最前：它不经校验不会抛，
        // 后面的表单还原万一抛了也不会把 360 留在共享库里。
        jdbc.update("UPDATE feed_rank_config SET edu_freshness_half_life_hours = 720 WHERE id = 1");
        write.updateFeedRank(SEED, 1L);
    }

    @Test
    void seedRowExistsWithMigrationDefaults() {
        FeedRankConfig c = read.feedRank();

        assertThat(c.getFreshnessWeight()).isEqualTo(0.6);
        assertThat(c.getInteractionWeight()).isEqualTo(0.4);
        assertThat(c.getCommentWeight()).isEqualTo(2.0);
        assertThat(c.getExposureDecay()).isEqualTo(0.3);
        assertThat(c.getSeenWindowDays()).isEqualTo(7);
        assertThat(c.getWindowSize()).isEqualTo(10);
        assertThat(c.getAttrFunQuota()).isEqualTo(5);
        assertThat(c.getSpeciesMainQuota()).isEqualTo(6);
        assertThat(c.getEduFreshnessHalfLifeHours()).isEqualTo(720.0); // V1.3.4 Story 1.2
    }

    // ── V1.3.4 Story 1.2：科普半衰期（FR-122.3 / 122.4）────────────────

    /** AC3.3：改库后不重启，读服务下一次就拿到新值。 */
    @Test
    void eduHalfLifeChangeIsVisibleWithoutRestart() {
        jdbc.update("UPDATE feed_rank_config SET edu_freshness_half_life_hours = 360 WHERE id = 1");

        assertThat(read.feedRank().getEduFreshnessHalfLifeHours()).isEqualTo(360.0);
    }

    /** AC4.3：后台保存其他参数，科普半衰期保持原值。 */
    @Test
    void adminSaveLeavesEduHalfLifeUntouched() {
        jdbc.update("UPDATE feed_rank_config SET edu_freshness_half_life_hours = 360 WHERE id = 1");

        write.updateFeedRank(new FeedRankForm(0.7, 0.3, 3, 0.2, 0.8, 0.2, 14, 10, 5, 3, 2, 6, 2, 2), 1L);

        assertThat(read.feedRank().getEduFreshnessHalfLifeHours()).isEqualTo(360.0);
    }

    /** AC1.3：CHECK > 0 兜底 —— 绕过服务层存 0 也失败。 */
    @Test
    void databaseRejectsNonPositiveEduHalfLife() {
        FeedRankConfig c = repo.findById(FeedRankConfig.SINGLETON_ID).orElseThrow();
        c.setEduFreshnessHalfLifeHours(0);

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.saveAndFlush(c))
                .isInstanceOf(RuntimeException.class);
    }

    /** 🔴 写进去的值，读服务下一次就拿到 —— 中间没有任何缓存层。 */
    @Test
    void writeIsVisibleToTheReadServiceImmediately() {
        write.updateFeedRank(new FeedRankForm(0.9, 0.1, 5, 0.15, 0.8, 0.2, 21, 10, 5, 3, 2, 6, 2, 2), 1L);

        FeedRankConfig c = read.feedRank();
        assertThat(c.getFreshnessWeight()).isEqualTo(0.9);
        assertThat(c.getCommentWeight()).isEqualTo(5.0);
        assertThat(c.getExposureDecay()).isEqualTo(0.15);
        assertThat(c.getSeenWindowDays()).isEqualTo(21);
    }

    /**
     * 🔴 改了配比，<b>下一次刷新的首页属性节奏真的变了</b>。
     *
     * <p>这条钉的是 16.2 留下那处接缝确实被补上了：配比与模板由同一处产生。
     * 断言落在<b>生成出来的排期</b>上（确定性），而不是"首页看起来不一样"（会随测试库数据飘）。
     */
    @Test
    void changedQuotasReachTheGeneratedSchedule() {
        AttributeSchedule before = AttributeTemplate.forQuotas(read.feedRank().getAttrFunQuota(),
                read.feedRank().getAttrEduQuota(), read.feedRank().getAttrLifeQuota(),
                read.feedRank().getWindowSize());
        assertThat(before.variantA()).containsExactlyElementsOf(AttributeTemplate.A);

        write.updateFeedRank(new FeedRankForm(0.6, 0.4, 2, 0.3, 0.8, 0.2, 7, 10, 4, 4, 2, 6, 2, 2), 1L);

        FeedRankConfig c = read.feedRank();
        AttributeSchedule after = AttributeTemplate.forQuotas(c.getAttrFunQuota(),
                c.getAttrEduQuota(), c.getAttrLifeQuota(), c.getWindowSize());
        assertThat(after.variantA()).isNotEqualTo(AttributeTemplate.A);
        assertThat(after.variantA().stream().filter(a -> a == FeedAttribute.EDU).count())
                .isEqualTo(4L);
    }

    /** 🛡 表级 CHECK 兜底：绕过业务层直接存不自洽的配比也应失败。 */
    @Test
    void databaseRejectsInconsistentQuotasEvenWithoutTheServiceLayer() {
        FeedRankConfig c = repo.findById(FeedRankConfig.SINGLETON_ID).orElseThrow();
        c.setAttrLifeQuota(9); // 5 + 3 + 9 ≠ 10

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> repo.saveAndFlush(c))
                .isInstanceOf(RuntimeException.class);
    }

    /** 🛡 首页照常出内容（改配置不该让 ALL Tab 挂掉）。 */
    @Test
    void feedStillServesAfterConfigChange() throws Exception {
        for (int i = 0; i < 25; i++) {
            posts.save(ContentPost.publish(newUser().getId(), ContentType.DAILY, null,
                    "cfg-live-" + SEQ.incrementAndGet(), List.of()));
        }
        write.updateFeedRank(new FeedRankForm(0.2, 0.8, 4, 0.5, 0.8, 0.2, 3, 10, 4, 4, 2, 5, 3, 2), 1L);

        mvc.perform(get("/api/v1/content-posts"))
                .andExpect(status().isOk());
    }
}
