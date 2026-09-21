package com.tailtopia.place.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * L0（纯反射读 JPQL 文本，无 DB）：场所评论读路径的**两层过滤一个都不能少**
 * （V1.3.0 batch-b1 Story 1.7 · AC6 · 安全攸关）。
 *
 * <h2>为什么用扫查询文本的方式守</h2>
 * 这两层过滤（审核可见性 + 拉黑 R1）漏掉任何一层，**功能看起来完全正常**：
 * 列表照常出来、页码照常翻、没有任何报错。区别只在于**被拉黑的人的评论出现在了列表里**，
 * 或者**没过审的文本对全世界可见**。而 AC6 的真跑验证需要 postgres（L1，留本地）——
 * 云端能做的就是钉住"这几段 WHERE 还在"。
 *
 * <p>⚠️ 这不是替代 L1 验收，是**在它之前先挡住手滑**：有人为了调性能重写一遍 JPQL、
 * 顺手丢掉一个 NOT EXISTS，这条会红。
 */
class PlaceCommentVisibilityQueryTest {

    /** 审核可见性：VISIBLE 或作者本人。 */
    private static final String MODERATION_CLAUSE = "CommentModerationStatus.VISIBLE";
    /** R1 拉黑过滤：查看者隐藏了评论作者 → 不展示。 */
    private static final String BLOCK_CLAUSE = "UserHideRelation";

    private static final List<String> VIEWER_SCOPED_QUERIES =
            List.of("findVisiblePage", "countVisibleForViewer", "countVisibleByPlaceIds");

    @Test
    void everyViewerScopedQueryKeepsBothFilters() {
        for (String name : VIEWER_SCOPED_QUERIES) {
            String jpql = jpqlOf(name);
            assertThat(jpql)
                    .as("🔴 %s 丢了审核可见性过滤 —— 未过审的文本会对所有人可见", name)
                    .contains(MODERATION_CLAUSE);
            assertThat(jpql)
                    .as("🔴 %s 丢了 R1 拉黑过滤（AC6）—— 被拉黑的人的评论会出现在列表里", name)
                    .contains(BLOCK_CLAUSE);
            assertThat(jpql)
                    .as("%s 必须只看未删的行", name)
                    .contains("deletedAt IS NULL");
        }
    }

    /**
     * 🔴 **计数与列表的过滤条件必须一致**：不一致的表现是
     * 「标题写着 3 条评论，往下数只有 2 条」—— 用户会以为有一条加载失败了。
     *
     * <p>这里比的是两段查询各自的可见性条件是否都在场（逐字比 SQL 太脆），
     * 外加一条：两者都不能出现只在一边有的过滤关键字。
     */
    @Test
    void countAndListUseTheSameVisibilityConditions() {
        String list = normalize(jpqlOf("findVisiblePage"));
        String count = normalize(jpqlOf("countVisibleForViewer"));

        for (String fragment : List.of(
                "c.moderationstatus = com.tailtopia.content.domain.commentmoderationstatus.visible",
                "(:hasviewer = true and c.authorid = :viewerid)",
                "not exists (select 1 from userhiderelation h where h.holderid = :viewerid "
                        + "and h.targetid = c.authorid)")) {
            assertThat(list).as("列表查询缺少片段：%s", fragment).contains(fragment);
            assertThat(count).as("计数查询缺少片段：%s", fragment).contains(fragment);
        }
    }

    /**
     * 🔴 **没有 R2（影子评论）**，这是有意的：场所没有主人。
     *
     * <p>内容评论的 R2 是「内容作者隐藏了评论作者 → 对所有人不展示」，前提是"这是我的地盘"。
     * 场所是共享条目，标记人连场所本身都改不了 —— 照抄 R2 等于凭空给他一份对公共条目的屏蔽权。
     * 真要加，先回决策日志改口径。
     */
    @Test
    void noSecondHideRuleKeyedOnThePlaceMarker() {
        for (String name : VIEWER_SCOPED_QUERIES) {
            String jpql = normalize(jpqlOf(name));
            assertThat(jpql)
                    .as("%s 出现了按标记人判定的隐藏过滤（R2）—— 场所没有主人，见方法注释", name)
                    .doesNotContain("markedbyid", "createdby", "placeauthorid", "ownerid");
        }
    }

    /** 排序必须是「最新在前」+ id 兜底（游标分页的稳定性靠这两列）。 */
    @Test
    void listIsOrderedNewestFirstWithIdTieBreaker() {
        assertThat(normalize(jpqlOf("findVisiblePage")))
                .contains("order by c.createdat desc, c.id desc");
    }

    private static String jpqlOf(String methodName) {
        Method m = Arrays.stream(PlaceCommentRepository.class.getDeclaredMethods())
                .filter(it -> it.getName().equals(methodName))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "PlaceCommentRepository 里没有方法 " + methodName + " —— 改名了就改这里"));
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as("%s 应当是一条显式 @Query（可见性过滤不能靠方法名推导）", methodName)
                .isNotNull();
        return q.value();
    }

    /** 压掉空白与大小写差异，只留结构。 */
    private static String normalize(String jpql) {
        return jpql.replaceAll("\\s+", " ").toLowerCase(java.util.Locale.ROOT).trim();
    }
}
