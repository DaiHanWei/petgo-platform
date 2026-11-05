package com.tailtopia.mention.repository;

import com.tailtopia.mention.domain.MentionRelation;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * @ 候选的<b>范围查询</b>（V1.4.2 social-topic Story 5.1 · D-15 · FR-157）。
 *
 * <p>范围 = 「我关注 ∪ 关注我 ∪ 互动候选」三类，<b>查询时并集</b>（关注不写候选表，不物化）：
 * <ul>
 *   <li>{@link #quickList}：不带关键词的快捷列表 —— 只有「我关注的」+「互动候选」两类
 *       （<b>不含粉丝</b>，PRD 规则细节 / 拍板点 ①）；</li>
 *   <li>{@link #search}：带关键词 —— 三类都在，昵称包含匹配。</li>
 * </ul>
 * 排序：类别优先级（{@link MentionRelation} 声明顺序）→ 各自时间倒序 → tie-breaker 倒序
 * （关注：{@code followee_id / follower_id}；互动：{@code mention_candidates.id}，
 * 与 {@code MentionCandidateRepository.trimToNewest} 同一把尺子）。同一个人只出现一次，取靠前的一类。
 *
 * <h2>🔴 这不是全局用户搜索</h2>
 * 两条 SQL 的人都只来自上面三类，<b>范围外的人永远搜不到</b>（昵称完全匹配也不行）。
 * 全局搜索归 1.5.4。参数只有 {@code ownerId} + 关键词 + 上限，没有「任意 userId 列表 / 全局开关」。
 *
 * <h2>⚠️ 为什么排除条件写在 SQL 里、而不是走 {@code social.read} 端口（有意例外）</h2>
 * 上限 30 / 20 必须在排除<b>之后</b>仍成立 —— 先取 30 再按端口剔拉黑会掉到 30 以下。
 * 与 {@code UserFollowRepository.findSuggestionIds} / {@code findStripUserIds} 同一类例外；
 * 原生 SQL 只读 {@code user_follows} / {@code user_hide_relations} / {@code users}，
 * 不引用 {@code social.repository} 的任何类（模块边界）。
 * <ul>
 *   <li>隐藏关系口径与 {@code UserHideRelationReader.hiddenEitherWay} <b>逐字一致</b>：
 *       <b>双向、不分来源</b>（拉黑与举报隐藏都算）；</li>
 *   <li>账号有效口径与 {@code AccountQueryService.isActive} 一致：{@code deleted_at IS NULL AND status = 'ACTIVE'}
 *       （注销 + 封号都排除；不看 {@code enabled}，拍板点 ④）；</li>
 *   <li>自己永不出现。</li>
 * </ul>
 * 改其中任何一条，先核 {@code hiddenEitherWay} / {@code isActive}，L1 {@code MentionScopeIntegrationTest} 钉着。
 *
 * <p>🛡 不缓存（每次查库）；不记日志（关键词是人名片段，属 PII 边缘）。
 */
@Repository
public class MentionScopeDao {

    /** 一行候选：用户 id + 命中的关系（已按优先级取最靠前的一类）。 */
    public record ScopedCandidate(long userId, MentionRelation relation) {
    }

    /** 排除条件（两条 SQL 共用；口径见类注释）。 */
    private static final String EXCLUSIONS = """
             WHERE u.id <> :me
               AND u.deleted_at IS NULL
               AND u.status = 'ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM user_hide_relations h
                                WHERE (h.holder_id = :me AND h.target_id = u.id)
                                   OR (h.holder_id = u.id AND h.target_id = :me))
            """;

    private static final String FOLLOWING_SCOPE = """
                SELECT f.followee_id AS uid, 1 AS rk, f.created_at AS at, f.followee_id AS tb
                  FROM user_follows f WHERE f.follower_id = :me
            """;

    private static final String FOLLOWER_SCOPE = """
                SELECT f.follower_id AS uid, 2 AS rk, f.created_at AS at, f.follower_id AS tb
                  FROM user_follows f WHERE f.followee_id = :me
            """;

    private static final String INTERACTED_SCOPE = """
                SELECT c.candidate_id AS uid, 3 AS rk, c.last_interacted_at AS at, c.id AS tb
                  FROM mention_candidates c WHERE c.owner_id = :me
            """;

    private static final String BEST = """
            best AS (
                SELECT DISTINCT ON (uid) uid, rk, at, tb FROM scope ORDER BY uid, rk, at DESC, tb DESC
            )
            SELECT b.uid, b.rk
              FROM best b
              JOIN users u ON u.id = b.uid
            """;

    private static final String ORDER_AND_LIMIT = """
             ORDER BY b.rk, b.at DESC, b.tb DESC
             LIMIT :limit
            """;

    /** 快捷列表：我关注的 + 互动候选（无粉丝、无关键词）。公开仅供 L0 守卫断言 SQL 原文。 */
    public static final String QUICK_LIST_SQL = "WITH scope AS (\n"
            + FOLLOWING_SCOPE
            + "    UNION ALL\n"
            + INTERACTED_SCOPE
            + "),\n"
            + BEST
            + EXCLUSIONS
            + ORDER_AND_LIMIT;

    /**
     * 带关键词：三类范围内按昵称包含匹配（不区分大小写）。昵称口径同 {@code AccountQueryService.toAuthorView}。
     * ⚠️ {@code ESCAPE '\'}：PG（standard_conforming_strings=on）里就是单个反斜杠。
     */
    public static final String SEARCH_SQL = "WITH scope AS (\n"
            + FOLLOWING_SCOPE
            + "    UNION ALL\n"
            + FOLLOWER_SCOPE
            + "    UNION ALL\n"
            + INTERACTED_SCOPE
            + "),\n"
            + BEST
            + EXCLUSIONS
            + "   AND COALESCE(u.nickname, u.display_name) ILIKE :pattern ESCAPE '\\'\n"
            + ORDER_AND_LIMIT;

    private final NamedParameterJdbcTemplate jdbc;

    public MentionScopeDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 快捷列表：一条查询，排序 / 去重 / 排除全在 SQL 内。 */
    public List<ScopedCandidate> quickList(long ownerId, int limit) {
        return jdbc.query(QUICK_LIST_SQL,
                new MapSqlParameterSource("me", ownerId).addValue("limit", limit),
                (rs, i) -> new ScopedCandidate(rs.getLong("uid"), MentionRelation.ofRank(rs.getInt("rk"))));
    }

    /**
     * 范围内查询：一条查询。
     *
     * @param likePattern 调用方已转义并包好 {@code %…%} 的模式，<b>不得为 null</b>
     *                    （PG 推不出 null 参数的类型；空关键词请走 {@link #quickList}）
     */
    public List<ScopedCandidate> search(long ownerId, String likePattern, int limit) {
        if (likePattern == null) {
            throw new IllegalArgumentException("likePattern 不得为 null，空关键词走 quickList");
        }
        return jdbc.query(SEARCH_SQL,
                new MapSqlParameterSource("me", ownerId).addValue("pattern", likePattern)
                        .addValue("limit", limit),
                (rs, i) -> new ScopedCandidate(rs.getLong("uid"), MentionRelation.ofRank(rs.getInt("rk"))));
    }
}
