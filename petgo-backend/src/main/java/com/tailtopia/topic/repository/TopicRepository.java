package com.tailtopia.topic.repository;

import com.tailtopia.topic.domain.Topic;
import com.tailtopia.topic.domain.TopicStatus;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 话题读口（V1.4.2 social-topic · Story 4.1；4.2 补查询）。
 *
 * <p>⚠️ <b>不要</b>用 {@code findByNameNorm} + {@code save} 新建用户话题：并发时第二个事务撞唯一约束、整帖失败。
 * 新建一律走 {@code TopicService.attach} 的 {@code INSERT … ON CONFLICT (name_norm) DO NOTHING}（D-2）。
 */
public interface TopicRepository extends JpaRepository<Topic, Long> {

    Optional<Topic> findByNameNorm(String nameNorm);

    /** 按标准名取「可访问」话题（4.2 · AC0：{@code ACTIVE} / {@code HIDDEN}）。 */
    Optional<Topic> findByNameNormAndStatusIn(String nameNorm, Collection<TopicStatus> statuses);

    /**
     * 联想（无 q）：ACTIVE，官方在前 → 热度倒序 → 标准名升序（4.2 · AC3）。
     */
    @Query("""
            SELECT t FROM Topic t
            WHERE t.status = com.tailtopia.topic.domain.TopicStatus.ACTIVE
            ORDER BY CASE WHEN t.type = com.tailtopia.topic.domain.TopicType.OFFICIAL THEN 0 ELSE 1 END,
                     t.heatScore DESC, t.nameNorm ASC
            """)
    List<Topic> findSuggestions(Pageable pageable);

    /**
     * 联想（有 q）：标准名前缀匹配（{@code prefix} 已由调用方把 {@code \ % _} 转义并补 {@code %}），排序同上。
     * 走 {@code idx_topics_name_norm_prefix}（text_pattern_ops）。
     *
     * <p>原生 SQL：{@code ESCAPE '\'} 在 PG（standard_conforming_strings=on）里就是单个反斜杠；
     * 写进 HQL 字面量的反斜杠语义随 Hibernate 版本不稳，故不用 JPQL。
     */
    @Query(value = """
            SELECT t.* FROM topics t
            WHERE t.status = 'ACTIVE'
              AND t.name_norm LIKE :prefix ESCAPE '\\'
            ORDER BY CASE WHEN t.type = 'OFFICIAL' THEN 0 ELSE 1 END,
                     t.heat_score DESC, t.name_norm ASC
            LIMIT :limit
            """, nativeQuery = true)
    List<Topic> findSuggestionsByPrefix(@Param("prefix") String prefix, @Param("limit") int limit);

    /** 首页话题行：运营指定（{@code featured_order} 非空）且 ACTIVE，按序号升序（4.2 · AC4）。 */
    @Query("""
            SELECT t FROM Topic t
            WHERE t.featuredOrder IS NOT NULL
              AND t.status = com.tailtopia.topic.domain.TopicStatus.ACTIVE
            ORDER BY t.featuredOrder ASC
            """)
    List<Topic> findFeatured();

    /** 首页话题行补位：未指定、ACTIVE、热度 > 0，按热度倒序（4.2 · AC4）。 */
    @Query("""
            SELECT t FROM Topic t
            WHERE t.featuredOrder IS NULL
              AND t.status = com.tailtopia.topic.domain.TopicStatus.ACTIVE
              AND t.heatScore > 0
            ORDER BY t.heatScore DESC, t.id DESC
            """)
    List<Topic> findHotUnfeatured(Pageable pageable);

    /** 推荐话题：ACTIVE、非当前、热度 > 0，按热度倒序（4.2 · AC5）。 */
    @Query("""
            SELECT t FROM Topic t
            WHERE t.status = com.tailtopia.topic.domain.TopicStatus.ACTIVE
              AND t.id <> :excludeId
              AND t.heatScore > 0
            ORDER BY t.heatScore DESC, t.id DESC
            """)
    List<Topic> findRelated(@Param("excludeId") long excludeId, Pageable pageable);
}
