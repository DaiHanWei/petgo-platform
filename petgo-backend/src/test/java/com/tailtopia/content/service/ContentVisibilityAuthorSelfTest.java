package com.tailtopia.content.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.ContentType;
import com.tailtopia.content.domain.ContentVisibility;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * Story 4.1 · AC4 **安全回归防线**（NFR-4 · AD-4 Rule 3）：`visibility` 过滤的作用范围
 * 严格限定「平台自动分发」。
 *
 * <h2>三分法（写反任一侧就摧毁 FR-83 的产品语义）</h2>
 * <ul>
 *   <li><b>作者自视</b>（成长档案时间线 / 日历 / 当天详情 / 我的发布）→ <b>不过滤</b>
 *       ——加了过滤就是把用户自己的私密日记从他自己的档案里藏起来；</li>
 *   <li><b>平台自动分发</b>（Feed / 聚合 / 他人主页 / 公开位）→ 按 PUBLIC 过滤；</li>
 *   <li><b>作者主动分享</b>（宠物名片 H5，FR-14）→ <b>不过滤</b>（OQ-18 2026-08-03 拍板：
 *       visibility 约束平台分发，不约束用户自己按下分享键的行为）。</li>
 * </ul>
 *
 * <p>⚠️ <b>本类是回归防线，不得删除或弱化。</b>后续若有人为「统一口径」给作者自视或名片 H5 的查询
 * 加上 visibility 过滤，这里会红 —— <b>那不是缺陷，是本 Story 明确禁止的改动</b>。
 *
 * <p>实现手法：直接在 JPQL 源串上断言过滤子句的有无。比起搭一整套 DB 夹具，这种「查询文本级」断言
 * 更能精确表达「这条查询**不许**出现 visibility 过滤」，也不受测试数据布置的影响（headless 可跑）。
 */
class ContentVisibilityAuthorSelfTest {

    /** 作者自视 / 主动分享的查询方法名 —— 这些**不许**带 visibility 过滤。 */
    private static final Set<String> MUST_NOT_FILTER = Set.of(
            "findGrowthMomentsBeforeAnchor", // 成长档案时间线（Story 3.1 锚点取数）
            "findGrowthMomentsBeforeAnchorLegacyNullEventDate", // 同上，老数据分支
            // 日历月视图 / 当天详情（派生查询，语义写在方法名里）
            "findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndEventDateBetweenOrderByEventDateAscCreatedAtAsc",
            "findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndEventDateOrderByCreatedAtAsc",
            // 名片 H5（作者主动分享，OQ-18）——派生查询，语义写在方法名里（bug 435 起带 petId）
            "findByAuthorIdAndPetIdAndTypeAndDeletedAtIsNullAndStatusOrderByEventDateDescCreatedAtDesc",
            "findMyPosts"); // 我的发布

    /** 平台自动分发的查询 —— 这些**必须**带 visibility 过滤。 */
    private static final Set<String> MUST_FILTER = Set.of("findFeed");

    /**
     * 取一条查询的「过滤条件来源文本」：`@Query` 声明式查询取 JPQL 串；派生查询（方法名即语义）取方法名
     * —— 派生查询要加过滤只能改方法名，所以在名字上断言同样严密。
     */
    private static String querySourceOf(String methodName) {
        List<Method> matches = Arrays.stream(
                        com.tailtopia.content.repository.ContentPostRepository.class.getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .toList();
        assertThat(matches)
                .as("查询方法 %s 应存在（改名了就把本测试的名单一起改，别只改实现）", methodName)
                .isNotEmpty();
        org.springframework.data.jpa.repository.Query q =
                matches.get(0).getAnnotation(org.springframework.data.jpa.repository.Query.class);
        return q != null ? q.value() : methodName;
    }

    @Test
    void authorSelfAndSharedCardQueries_mustNotFilterByVisibility() {
        for (String name : MUST_NOT_FILTER) {
            assertThat(querySourceOf(name))
                    .as("%s 是作者自视/主动分享视图，**不得**加 visibility 过滤（NFR-4 / OQ-18）", name)
                    .doesNotContain("visibility")
                    .doesNotContain("Visibility");
        }
    }

    @Test
    void platformDistributionQuery_mustFilterByVisibility() {
        for (String name : MUST_FILTER) {
            assertThat(querySourceOf(name))
                    .as("%s 是平台自动分发视图，必须按 PUBLIC 过滤（AD-4 Rule 2）", name)
                    .contains("visibility")
                    .contains("ContentVisibility.PUBLIC");
        }
    }

    @Test
    void feedQueryNoLongerBranchesOnPetStatus() {
        // AC5：V1.0.0「状态 B 用户 Feed 不显示成长日历」整条废止 —— 查询里不该再有该分支。
        assertThat(querySourceOf("findFeed")).doesNotContain("excludeGrowth");
    }

    @Test
    void newPostDefaultsToPublic_soExistingBehaviourIsUnchanged() {
        // 私密只由用户主动关同步开关产生（NFR-6）：新建内容默认公开，存量迁移统一回填 PUBLIC。
        ContentPost p = ContentPost.publish(1L, ContentType.GROWTH_MOMENT, null, "文字", List.of());
        assertThat(p.getVisibility()).isEqualTo(ContentVisibility.PUBLIC);
    }

    @Test
    void visibilityNeverNull_evenIfPersistedRowIsNull() {
        // 老行理论上不会有 null（迁移 DEFAULT + NOT NULL），但 getter 仍兜底为 PUBLIC ——
        // 宁可多显示（作者自己的东西），也不能因为一个 null 把内容藏起来。
        ContentPost p = ContentPost.publish(1L, ContentType.DAILY, null, "文字", List.of());
        p.setVisibility(null);
        assertThat(p.getVisibility()).isEqualTo(ContentVisibility.PUBLIC);
    }
}
