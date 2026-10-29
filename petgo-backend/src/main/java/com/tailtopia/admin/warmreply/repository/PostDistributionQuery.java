package com.tailtopia.admin.warmreply.repository;

import com.tailtopia.admin.warmreply.dto.DistributionFilter;
import com.tailtopia.admin.warmreply.dto.DistributionRow;
import com.tailtopia.admin.warmreply.dto.DistributionSummary;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 帖子评论分布查询（V1.3.0 Story 4.1 AC2～AC4）：原生 SQL（需要 {@code FILTER} 聚合），列表与摘要共用同一段帖子过滤 CTE。
 * <ul>
 * <li>可见评论 = {@code comments.deleted_at IS NULL AND moderation_status = 'VISIBLE'}（一级 + 二级）。</li>
 * <li>「排除虚拟账号内容」= 帖子作者 {@code users.account_type = 'VIRTUAL'}（虚拟账号池）；真实身份池的帖照常参与。
 * 与 3-1 看板口径 {@code isSyntheticAccount()}（含 role=ADMIN）不是同一集合，本页按 PRD AB-20A 字面用 VIRTUAL。</li>
 * <li>已删帖（{@code deleted_at} 非空）不出现在任何状态筛选下（AC5）；「已下架」= 未删但 {@code status <> 'PUBLISHED'}。</li>
 * <li>物种 = {@code COALESCE(cp.species_override, u.account_species)}（不含宠物档案推导，与 B1 列表的 resolver 是已知口径差）；
 * 有效物种为 GENERAL / 空的帖对任何物种筛选都命中。</li>
 * <li>切日 {@code AT TIME ZONE 'Asia/Jakarta'}。评论数区间在外层 {@code WHERE}（同 HAVING 语义）。</li>
 * </ul>
 */
@Repository
public class PostDistributionQuery {

    private static final String BASE_CTE = """
            WITH base AS (
                SELECT cp.id, cp.author_id, cp.created_at, cp.status, cp.text,
                       u.nickname AS author_name,
                       (u.account_type = 'VIRTUAL') AS author_virtual
                FROM content_posts cp
                JOIN users u ON u.id = cp.author_id
                WHERE cp.deleted_at IS NULL
                  AND (:excludeVirtual = FALSE OR u.account_type <> 'VIRTUAL')
                  AND (:status = 'all' OR (:status = 'visible' AND cp.status = 'PUBLISHED') OR (:status = 'takendown' AND cp.status <> 'PUBLISHED'))
                  AND (cp.created_at AT TIME ZONE 'Asia/Jakarta')::date BETWEEN :fromDate AND :toDate
                  AND (:species IS NULL
                       OR COALESCE(cp.species_override, u.account_species) IS NULL
                       OR COALESCE(cp.species_override, u.account_species) = 'GENERAL'
                       OR COALESCE(cp.species_override, u.account_species) = :species)
            ),
            cc AS (
                SELECT c.post_id,
                       COUNT(*) AS total_visible,
                       COUNT(*) FILTER (WHERE cu.account_type = 'VIRTUAL') AS virtual_visible
                FROM comments c
                JOIN users cu ON cu.id = c.author_id
                WHERE c.deleted_at IS NULL AND c.moderation_status = 'VISIBLE'
                  AND c.post_id IN (SELECT id FROM base)
                GROUP BY c.post_id
            ),
            rows AS (
                SELECT b.*, COALESCE(cc.total_visible, 0) AS comment_count, COALESCE(cc.virtual_visible, 0) AS virtual_count
                FROM base b LEFT JOIN cc ON cc.post_id = b.id
                WHERE (:maxCount IS NULL OR COALESCE(cc.total_visible, 0) <= :maxCount)
            )
            """;

    private static final String LIST_SQL = BASE_CTE + """
            SELECT id, text, author_id, author_name, author_virtual, created_at, comment_count, virtual_count
            FROM rows
            ORDER BY comment_count ASC, created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """;

    private static final String SUMMARY_SQL = BASE_CTE + """
            SELECT COUNT(*) AS posts,
                   AVG(comment_count) AS avg_all,
                   AVG(comment_count - virtual_count) AS avg_real,
                   COUNT(*) FILTER (WHERE comment_count = 0) AS zero_posts,
                   SUM(virtual_count)::numeric / NULLIF(SUM(comment_count), 0) AS virtual_share
            FROM rows
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public PostDistributionQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static MapSqlParameterSource params(DistributionFilter f) {
        return new MapSqlParameterSource()
                .addValue("excludeVirtual", f.excludeVirtual())
                .addValue("status", f.status())
                .addValue("fromDate", f.from())
                .addValue("toDate", f.to())
                .addValue("species", f.species(), java.sql.Types.VARCHAR)
                .addValue("maxCount", f.maxCount(), java.sql.Types.INTEGER);
    }

    public List<DistributionRow> list(DistributionFilter f) {
        MapSqlParameterSource p = params(f).addValue("limit", DistributionFilter.PAGE_SIZE).addValue("offset", f.offset());
        return jdbc.query(LIST_SQL, p, (rs, i) -> {
            String text = rs.getString("text");
            String authorName = rs.getString("author_name");
            long authorId = rs.getLong("author_id");
            Timestamp created = rs.getTimestamp("created_at");
            return new DistributionRow(rs.getLong("id"), summarize(text), authorId,
                    authorName == null || authorName.isBlank() ? "#" + authorId : authorName,
                    rs.getBoolean("author_virtual"), created == null ? null : created.toInstant(),
                    rs.getLong("comment_count"), rs.getLong("virtual_count"));
        });
    }

    /** 列表 + 摘要一次取（同一只读事务、同一 WHERE）；总数 = 摘要的帖子数，不再单独 COUNT。 */
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public Result fetch(DistributionFilter f) {
        return new Result(list(f), summary(f));
    }

    /** 一次取数结果。 */
    public record Result(List<DistributionRow> rows, DistributionSummary summary) {
        public long total() {
            return summary.posts();
        }
    }

    /** 四格摘要：一条聚合 SQL（AC4）。 */
    public DistributionSummary summary(DistributionFilter f) {
        return jdbc.query(SUMMARY_SQL, params(f), rs -> {
            if (!rs.next()) {
                return DistributionSummary.EMPTY;
            }
            long posts = rs.getLong("posts");
            BigDecimal avgAll = rs.getBigDecimal("avg_all");
            BigDecimal avgReal = rs.getBigDecimal("avg_real");
            long zero = rs.getLong("zero_posts");
            BigDecimal share = rs.getBigDecimal("virtual_share");
            return new DistributionSummary(posts, avgAll, avgReal, zero, share);
        });
    }

    /** 正文前 40 字（AC3）；空正文显示占位。 */
    static String summarize(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String t = text.strip().replaceAll("\\s+", " ");
        // 按码点截断，别劈开 emoji 代理对
        return t.codePointCount(0, t.length()) <= 40 ? t : t.substring(0, t.offsetByCodePoints(0, 40)) + "…";
    }
}
