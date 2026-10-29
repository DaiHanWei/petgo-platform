package com.tailtopia.content.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;

/**
 * L0 守卫（V1.3.4 Story 1.1 · AC2.3）：主池与教育池的过滤只有一份代码。
 *
 * <p>🔴 两个查询都必须拼 {@link ContentPostRepository#RANK_POOL_FILTER}；有人给新池子另抄一份 WHERE，
 * 下次改过滤（加一条安全规则）时就会漏掉一个池子 —— 而那不会报错。
 */
class RankPoolFilterSharedTest {

    @Test
    void mainPoolUsesTheSharedFilter() throws NoSuchMethodException {
        Query q = ContentPostRepository.class
                .getMethod("findRankCandidatePool", boolean.class, Long.class, boolean.class, Pageable.class)
                .getAnnotation(Query.class);
        assertThat(q.value()).contains(ContentPostRepository.RANK_POOL_FILTER);
    }

    @Test
    void eduPoolUsesTheSharedFilter() throws NoSuchMethodException {
        Query q = ContentPostRepository.class
                .getMethod("findEduCandidatePool", boolean.class, Long.class, Instant.class, boolean.class,
                        Pageable.class)
                .getAnnotation(Query.class);
        assertThat(q.value()).contains(ContentPostRepository.RANK_POOL_FILTER)
                .contains("ContentType.KNOWLEDGE")
                .contains("p.createdAt >= :since");
    }

    @Test
    void sharedFilterCarriesEveryRule() {
        assertThat(ContentPostRepository.RANK_POOL_FILTER)
                .contains("p.deletedAt IS NULL")
                .contains("PostStatus.PUBLISHED")
                .contains("ContentVisibility.PUBLIC")
                .contains("ContentReport")
                .contains("UserHideRelation");
    }
}
