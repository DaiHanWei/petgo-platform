package com.tailtopia.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.social.repository.UserFollowRepository;
import java.lang.reflect.Method;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.data.jpa.repository.Query;

/**
 * L0 · V1.5.0 likes Story 1.2（PRD 自动点赞 FR-218）：排序 / 推荐 / 热度 / 运营统计查询排除自动赞，
 * 展示口径 {@code countByPostIdIn} 不排除。读 {@code @Query} 注解值做结构断言；真跑 SQL 在 L1
 * {@code AutoLikeExclusionIntegrationTest}（待本地验收）。
 */
class AutoLikeExclusionSqlTest {

    private static String queryOf(Class<?> repo, String method) {
        Method m = Arrays.stream(repo.getDeclaredMethods())
                .filter(x -> x.getName().equals(method))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no method " + method));
        Query q = m.getAnnotation(Query.class);
        assertThat(q).as(method + " has @Query").isNotNull();
        return q.value();
    }

    @Test
    void topicPopularExcludesAutomated() {
        assertThat(queryOf(ContentPostRepository.class, "findTopicPopularIds"))
                .contains("FROM content_likes l")
                .contains("WHERE l.post_id = p.id AND NOT l.is_automated");
    }

    @Test
    void recommendablePetsExcludesAutomated() {
        assertThat(queryOf(ContentPostRepository.class, "findRecommendablePets"))
                .containsPattern("AND l\\.created_at >= :since\\s+AND NOT l\\.is_automated");
    }

    @Test
    void followSuggestionsExcludeAutomated() {
        assertThat(queryOf(UserFollowRepository.class, "findSuggestionIds"))
                .containsPattern("WHERE l\\.created_at >= :since\\s+AND NOT l\\.is_automated");
    }

    @Test
    void adminLikeWindowExcludesAutomated() {
        assertThat(queryOf(ContentLikeRepository.class, "countInWindow")).contains("l.automated = false");
    }

    @Test
    void organicBatchCountExcludesAutomated() {
        assertThat(queryOf(ContentLikeRepository.class, "countOrganicByPostIdIn")).contains("l.automated = false");
    }

    /** 展示口径（Feed 卡、后台列表默认模式）不得加过滤（FR-218「What stays counted on purpose」）。 */
    @Test
    void displayBatchCountKeepsAutomated() {
        assertThat(queryOf(ContentLikeRepository.class, "countByPostIdIn")).doesNotContain("automated");
    }

    /** 主页 Suka / 点赞人列表同为展示口径，不排除。 */
    @Test
    void sukaAndLikersKeepAutomated() {
        assertThat(queryOf(ContentLikeRepository.class, "sumLikesOnPublicPostsByAuthor")).doesNotContain("automated");
        assertThat(queryOf(ContentLikeRepository.class, "sumLikesOnOwnPostsByAuthor")).doesNotContain("automated");
        assertThat(queryOf(ContentLikeRepository.class, "findLikersPage")).doesNotContain("automated");
    }
}
