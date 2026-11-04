package com.tailtopia.topic.service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 话题只读口（V1.4.2 social-topic · Story 4.1 · D-13）。本 story 只实现帖子出参要用的几项，其余查询 4.2 补。
 *
 * <p>依赖方向同 {@link TopicService}：{@code FeedService / ContentDetailService / ContentService → TopicQueryService}，
 * 本类不注入任何 content service。
 *
 * <p>🔴 对外只出<b>标准名</b>；{@code topics.id} 只在进程内事件里出现（{@link #activeTopicIdsOf}）。
 */
@Service
public class TopicQueryService {

    // 帖子出参可渲染为链接的状态：HIDDEN 照常下发，DISABLED 不下发（两条 SQL 同一口径）。
    private static final String NAMES_FOR = """
            SELECT ct.post_id, t.name_norm
            FROM content_topics ct
            JOIN topics t ON t.id = ct.topic_id
            WHERE ct.post_id IN (:ids) AND t.status IN ('ACTIVE', 'HIDDEN')
            ORDER BY ct.post_id, t.name_norm
            """;

    private static final String ATTACHED_OF = """
            SELECT t.id, t.name_norm, t.type
            FROM content_topics ct
            JOIN topics t ON t.id = ct.topic_id
            WHERE ct.post_id = :postId AND t.status IN ('ACTIVE', 'HIDDEN')
            ORDER BY t.id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public TopicQueryService(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 一页帖子各自的话题标准名（仅 {@code ACTIVE} / {@code HIDDEN}）。<b>一页一次查询</b>（同 {@code mentionsFor} /
     * {@code decorationTags} 的「整页一次」纪律，逐条就是 N+1）。
     *
     * @return postId → 标准名列表；没有话题的帖<b>不在 Map 里</b>。空集合直接返回空 Map、不发查询
     */
    @Transactional(readOnly = true)
    public Map<Long, List<String>> topicNamesFor(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        Map<Long, List<String>> out = new LinkedHashMap<>();
        jdbc.query(NAMES_FOR, new MapSqlParameterSource("ids", postIds), rs -> {
            out.computeIfAbsent(rs.getLong("post_id"), k -> new java.util.ArrayList<>())
                    .add(rs.getString("name_norm"));
        });
        out.replaceAll((k, v) -> List.copyOf(v));
        return out;
    }

    /**
     * 一条帖子当前的话题关联（非 DISABLED），{@code created} 恒 false。供幂等重放回发帖响应 {@code topics}、
     * 挂起帖过审时发事件 {@code topicIds}。
     */
    @Transactional(readOnly = true)
    public List<AttachedTopic> attachedTopicsOf(long postId) {
        return List.copyOf(jdbc.query(ATTACHED_OF, new MapSqlParameterSource("postId", postId),
                (rs, n) -> new AttachedTopic(rs.getLong("id"), rs.getString("name_norm"),
                        "OFFICIAL".equals(rs.getString("type")), false)));
    }

    /** 一条帖子当前关联的非 DISABLED 话题 id（挂起帖过审事件用，进程内）。 */
    @Transactional(readOnly = true)
    public List<Long> activeTopicIdsOf(long postId) {
        return attachedTopicsOf(postId).stream().map(AttachedTopic::topicId).toList();
    }
}
