package com.tailtopia.topic.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.topic.domain.Topic;
import com.tailtopia.topic.domain.TopicNameRules;
import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.repository.TopicRepository;
import com.tailtopia.topic.web.dto.TopicDetailResponse;
import com.tailtopia.topic.web.dto.TopicSummaryResponse;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 话题只读口（V1.4.2 social-topic · Story 4.1 · D-13）。4.1：帖子出参要用的几项；4.2：详情 / 联想 / 首页话题行 / 推荐 /
 * 「可访问」判定（话题页帖子与话题坑位经 {@link #requireVisible} / {@link #findVisible} 拿进程内 id）。
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

    /** 「可访问」= ACTIVE / HIDDEN（4.2 · AC0.2）；DISABLED 与不存在同一个 404。 */
    static final Set<TopicStatus> VISIBLE = EnumSet.of(TopicStatus.ACTIVE, TopicStatus.HIDDEN);

    /** 联想 / 首页话题行 / 推荐话题各自上限（4.2 · AC3 ~ AC5）。 */
    static final int SUGGEST_LIMIT = 20;
    static final int HOME_ROW_LIMIT = 5;
    static final int RELATED_LIMIT = 10;

    static final String NOT_FOUND = "话题不存在";

    private final NamedParameterJdbcTemplate jdbc;
    private final TopicRepository topics;

    public TopicQueryService(NamedParameterJdbcTemplate jdbc, TopicRepository topics) {
        this.jdbc = jdbc;
        this.topics = topics;
    }

    // ---------------------------------------------------------------- 4.2 · 话题页 / 联想 / 首页行 / 推荐

    /**
     * 路径名 → 可访问话题（4.2 · AC0）。先 {@link TopicNameRules#isValidName}（不通过直接 empty、<b>不查库</b>），
     * 再 {@link TopicNameRules#normalize} 后按标准名查 ACTIVE / HIDDEN。
     */
    @Transactional(readOnly = true)
    public Optional<Topic> findVisible(String rawName) {
        if (!TopicNameRules.isValidName(rawName)) {
            return Optional.empty();
        }
        return topics.findByNameNormAndStatusIn(TopicNameRules.normalize(rawName), VISIBLE);
    }

    /** 同 {@link #findVisible}，不可访问（非法名 / 不存在 / DISABLED）→ 同一个 404 ProblemDetail（不外泄状态）。 */
    @Transactional(readOnly = true)
    public Topic requireVisible(String rawName) {
        return findVisible(rawName).orElseThrow(() -> AppException.notFound(NOT_FOUND));
    }

    /**
     * 话题详情（AC1）。{@code description} 仅官方：{@code Accept-Language} 以 {@code id} 开头取印尼语、否则英语，
     * 所选为空退回另一语言，皆空 → null（省略）。
     */
    @Transactional(readOnly = true)
    public TopicDetailResponse detail(String rawName, String acceptLanguage) {
        Topic t = requireVisible(rawName);
        String description = t.isOfficial() ? pickDescription(t, acceptLanguage) : null;
        return new TopicDetailResponse(t.getNameNorm(), t.getNameDisplay(), t.isOfficial(), description);
    }

    static String pickDescription(Topic t, String acceptLanguage) {
        boolean indonesian = acceptLanguage != null
                && acceptLanguage.trim().toLowerCase(Locale.ROOT).startsWith("id");
        String first = indonesian ? t.getDescId() : t.getDescEn();
        String second = indonesian ? t.getDescEn() : t.getDescId();
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    /**
     * # 联想（AC3）。无 q / 空白 → ACTIVE 前 20（官方 → 热度 → 名字）；有 q → 标准名前缀匹配。
     * {@code q} 含非话题字符或超 30 → 空表、不查库（字符集判断只用 {@link TopicNameRules}，P-1）。
     */
    @Transactional(readOnly = true)
    public List<TopicSummaryResponse> suggest(String q) {
        if (q == null || q.isBlank()) {
            return topics.findSuggestions(PageRequest.of(0, SUGGEST_LIMIT)).stream()
                    .map(TopicSummaryResponse::of).toList();
        }
        String trimmed = q.strip();
        if (!TopicNameRules.isValidPrefix(trimmed)) {
            return List.of();
        }
        return topics.findSuggestionsByPrefix(likePrefix(TopicNameRules.normalize(trimmed)), SUGGEST_LIMIT)
                .stream().map(TopicSummaryResponse::of).toList();
    }

    /** {@code \ % _} 逐个以 {@code \} 转义再补 {@code %}（{@code _} 是合法话题字符，不转义就成了单字符通配）。 */
    static String likePrefix(String norm) {
        StringBuilder sb = new StringBuilder(norm.length() + 4);
        for (int i = 0; i < norm.length(); i++) {
            char c = norm.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                sb.append('\\');
            }
            sb.append(c);
        }
        return sb.append('%').toString();
    }

    /**
     * 首页话题行（AC4）：运营指定的按序号在前，不足 5 个按热度补（ACTIVE、未指定、热度 > 0）。
     * HIDDEN / DISABLED 不出现；一个都没有 → 空表（App 整行隐藏）。
     */
    @Transactional(readOnly = true)
    public List<TopicSummaryResponse> homeRow() {
        List<TopicSummaryResponse> out = new ArrayList<>();
        for (Topic t : topics.findFeatured()) {
            if (out.size() >= HOME_ROW_LIMIT) {
                break;
            }
            out.add(TopicSummaryResponse.ofHomeRow(t, true));
        }
        int remaining = HOME_ROW_LIMIT - out.size();
        if (remaining > 0) {
            for (Topic t : topics.findHotUnfeatured(PageRequest.of(0, remaining))) {
                out.add(TopicSummaryResponse.ofHomeRow(t, false));
            }
        }
        return List.copyOf(out);
    }

    /** 推荐话题（AC5）：当前话题须可访问（否则 404）；ACTIVE、非当前、热度 > 0，热度倒序前 10。 */
    @Transactional(readOnly = true)
    public List<TopicSummaryResponse> related(String rawName) {
        Topic current = requireVisible(rawName);
        return topics.findRelated(current.getId(), PageRequest.of(0, RELATED_LIMIT)).stream()
                .map(TopicSummaryResponse::of).toList();
    }

    // ---------------------------------------------------------------- 4.1 · 帖子出参

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
