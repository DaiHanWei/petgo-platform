package com.tailtopia.admin.topic.service;

import com.tailtopia.topic.domain.TopicStatus;
import com.tailtopia.topic.domain.TopicType;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 后台话题表读写（V1.4.2 Story 6.1）。全部走 SQL（与 4.1 {@code TopicService} 同一做法）：
 *
 * <ul>
 *   <li>{@code Topic} 实体是只读投影（无 setter，4.1 约定用户话题只经原生 UPSERT 新建），后台写操作同样不经实体；</li>
 *   <li>同一事务里读写都走 JDBC，不会读到 JPA 一级缓存里的旧行；</li>
 *   <li>🔴 {@code featured_order} 有<b>立即检查</b>的部分唯一索引 {@code uq_topics_featured_order}
 *       + {@code ck_topics_featured_order BETWEEN 1 AND 5}（不能取负过渡）：前移 / 互换一律由
 *       {@link AdminTopicService} 逐行调用 {@link #setFeaturedOrder}，本类不提供「一条 UPDATE 全体 -1」。</li>
 * </ul>
 *
 * <p>业务规则（校验、R-5、并发判定、审计）全部在 {@link AdminTopicService}；本类只有 SQL。
 */
@Component
public class AdminTopicStore {

    /** 列表每页条数（AC2.1）。 */
    public static final int PAGE_SIZE = 20;

    /** 首页话题行最多个数（AC5.5；与 {@code ck_topics_featured_order} 上限一致）。 */
    public static final int FEATURED_MAX = 5;

    static final String LOCK_FEATURED_SQL = "SELECT pg_advisory_xact_lock(hashtext('topics_featured_order'))";

    private static final String COLUMNS = """
            id, name_display, name_norm, type, status, desc_id, desc_en, post_count, heat_score,
            featured_order, disabled_at, created_at""";

    /** 默认排序（AC2.5）：热度倒序 → 创建时间倒序 → id 倒序（heat_score NOT NULL，NULLS LAST 无需写）。 */
    private static final String ORDER_BY = " ORDER BY heat_score DESC, created_at DESC, id DESC";

    /**
     * 实时帖子数（AC7.2）：口径与 {@code TopicHeatJob.RECOMPUTE_SQL} 的 {@code vis} 逐条一致 ——
     * PUBLIC + PUBLISHED + 未删 + 作者未注销且 ACTIVE。
     */
    static final String LIVE_POST_COUNT_SQL = """
            SELECT COUNT(*)
            FROM content_topics ct
            JOIN content_posts p ON p.id = ct.post_id
            JOIN users u ON u.id = p.author_id AND u.deleted_at IS NULL AND u.status = 'ACTIVE'
            WHERE ct.topic_id = :id
              AND p.deleted_at IS NULL AND p.status = 'PUBLISHED' AND p.visibility = 'PUBLIC'
            """;

    /** 新建官方话题：与 App 发帖并发时由唯一约束兜底，DO NOTHING 后调用方重查判定（D-2 同口径）。 */
    static final String INSERT_OFFICIAL_SQL = """
            INSERT INTO topics (name_display, name_norm, type, status, desc_id, desc_en)
            VALUES (:display, :norm, 'OFFICIAL', 'ACTIVE', :descId, :descEn)
            ON CONFLICT (name_norm) DO NOTHING
            """;

    /** 升级：只升级仍是 USER 且未禁用的行（R-5）；0 行 = 期间状态变了，调用方重查判定。 */
    static final String UPGRADE_SQL = """
            UPDATE topics SET type = 'OFFICIAL', name_display = :display, desc_id = :descId, desc_en = :descEn,
                   updated_at = :now
            WHERE id = :id AND type = 'USER' AND status IN ('ACTIVE', 'HIDDEN')
            """;

    private static final RowMapper<TopicView> ROW = AdminTopicStore::map;

    private final NamedParameterJdbcTemplate jdbc;

    public AdminTopicStore(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---------------------------------------------------------------- 读

    public Optional<TopicView> findById(long id) {
        List<TopicView> rows = jdbc.query("SELECT " + COLUMNS + " FROM topics WHERE id = :id",
                new MapSqlParameterSource("id", id), ROW);
        return rows.stream().findFirst();
    }

    public Optional<TopicView> findByNorm(String norm) {
        List<TopicView> rows = jdbc.query("SELECT " + COLUMNS + " FROM topics WHERE name_norm = :norm",
                new MapSqlParameterSource("norm", norm), ROW);
        return rows.stream().findFirst();
    }

    /** 一页列表（多取 1 行判定是否有下一页）。 */
    public TopicPage page(TopicFilter filter, int page) {
        int p = Math.max(page, 0);
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        params.addValue("limit", PAGE_SIZE + 1).addValue("offset", (long) p * PAGE_SIZE);
        List<TopicView> rows = jdbc.query("SELECT " + COLUMNS + " FROM topics" + where + ORDER_BY
                + " LIMIT :limit OFFSET :offset", params, ROW);
        boolean hasNext = rows.size() > PAGE_SIZE;
        return new TopicPage(hasNext ? List.copyOf(rows.subList(0, PAGE_SIZE)) : List.copyOf(rows), p, hasNext);
    }

    /** 该话题在当前筛选与默认排序下所在页（0 起）；不在筛选结果里 → empty。 */
    public Optional<Integer> pageOf(long id, TopicFilter filter) {
        MapSqlParameterSource params = new MapSqlParameterSource();
        String where = where(filter, params);
        params.addValue("id", id);
        List<Long> rn = jdbc.queryForList("SELECT rn FROM (SELECT id, ROW_NUMBER() OVER (" + ORDER_BY.strip()
                + ") AS rn FROM topics" + where + ") x WHERE x.id = :id", params, Long.class);
        return rn.stream().findFirst().map(r -> (int) ((r - 1) / PAGE_SIZE));
    }

    /** 摘要条（AC2.3）：全量口径，不随筛选变。 */
    public TopicSummary summary() {
        return jdbc.queryForObject("""
                SELECT COUNT(*) AS total,
                       COUNT(*) FILTER (WHERE type = 'OFFICIAL') AS official,
                       COUNT(*) FILTER (WHERE featured_order IS NOT NULL) AS featured,
                       COUNT(*) FILTER (WHERE status = 'HIDDEN') AS hidden,
                       COUNT(*) FILTER (WHERE status = 'DISABLED') AS disabled
                FROM topics
                """, new MapSqlParameterSource(), (rs, n) -> new TopicSummary(rs.getLong("total"),
                rs.getLong("official"), rs.getInt("featured"), FEATURED_MAX, rs.getLong("hidden"),
                rs.getLong("disabled")));
    }

    public int livePostCount(long topicId) {
        Integer n = jdbc.queryForObject(LIVE_POST_COUNT_SQL, new MapSqlParameterSource("id", topicId), Integer.class);
        return n == null ? 0 : n;
    }

    public int featuredCount() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM topics WHERE featured_order IS NOT NULL",
                new MapSqlParameterSource(), Integer.class);
        return n == null ? 0 : n;
    }

    /** 序号大于 {@code pos} 的已放入话题，<b>按序号升序</b>（前移须逐行从小到大做）。 */
    public List<FeaturedSlot> featuredAfter(int pos) {
        return jdbc.query("""
                SELECT id, featured_order FROM topics
                WHERE featured_order > :pos ORDER BY featured_order ASC
                """, new MapSqlParameterSource("pos", pos),
                (rs, n) -> new FeaturedSlot(rs.getLong("id"), rs.getInt("featured_order")));
    }

    public Optional<Long> idAtFeaturedOrder(int order) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM topics WHERE featured_order = :o",
                new MapSqlParameterSource("o", order), Long.class);
        return ids.stream().findFirst();
    }

    // ---------------------------------------------------------------- 写

    /** 首页话题行改动的串行化锁（事务级，随事务释放；AC5.7 / AC8.5）。 */
    public void lockFeatured() {
        jdbc.getJdbcTemplate().execute(LOCK_FEATURED_SQL);
    }

    /** @return true = 本次插入；false = 已有同名（并发方先插入），调用方重查 */
    public boolean insertOfficial(String display, String norm, String descId, String descEn) {
        return jdbc.update(INSERT_OFFICIAL_SQL, new MapSqlParameterSource()
                .addValue("display", display).addValue("norm", norm)
                .addValue("descId", descId).addValue("descEn", descEn)) == 1;
    }

    /** @return true = 已升级；false = 该行已不是可升级的用户话题 */
    public boolean upgrade(long id, String display, String descId, String descEn, Instant now) {
        return jdbc.update(UPGRADE_SQL, new MapSqlParameterSource()
                .addValue("id", id).addValue("display", display)
                .addValue("descId", descId).addValue("descEn", descEn)
                .addValue("now", ts(now))) == 1;
    }

    public void updateContent(long id, String display, String descId, String descEn, Instant now) {
        jdbc.update("""
                UPDATE topics SET name_display = :display, desc_id = :descId, desc_en = :descEn, updated_at = :now
                WHERE id = :id
                """, new MapSqlParameterSource().addValue("id", id).addValue("display", display)
                .addValue("descId", descId).addValue("descEn", descEn).addValue("now", ts(now)));
    }

    /**
     * 改状态（乐观守卫：只在当前状态仍是 {@code expected} 时生效）。
     * ⚠️ 调用前必须已把 {@code featured_order} 清空（{@code ck_topics_featured_active}：
     * 在首页话题行里的话题只能是 ACTIVE）。
     *
     * @return 影响行数；0 = 期间已被别人改了状态（并发恢复 / 禁用、双击），调用方抛 stale
     */
    public int updateStatus(long id, TopicStatus expected, TopicStatus status, Instant disabledAt, Instant now) {
        return jdbc.update("""
                UPDATE topics SET status = :status, disabled_at = :disabledAt, updated_at = :now
                WHERE id = :id AND status = :expected
                """, new MapSqlParameterSource().addValue("id", id).addValue("status", status.name())
                .addValue("expected", expected.name())
                .addValue("disabledAt", ts(disabledAt)).addValue("now", ts(now)));
    }

    /** 单行改序号（{@code null} = 移出）。逐行调用，见类注释。 */
    public void setFeaturedOrder(long id, Integer order) {
        jdbc.update("UPDATE topics SET featured_order = :o, updated_at = now() WHERE id = :id",
                new MapSqlParameterSource().addValue("id", id).addValue("o", order, java.sql.Types.INTEGER));
    }

    /**
     * 按 id 升序给首页话题行里的全部行加行锁（在 advisory 锁之后调用）。
     * {@code TopicHeatJob} 的集合 UPDATE 会锁到这些行；双方都按稳定顺序加锁，避免逐行改序号时与之交叉死锁。
     */
    public void lockFeaturedRows() {
        jdbc.queryForList("SELECT id FROM topics WHERE featured_order IS NOT NULL ORDER BY id FOR UPDATE",
                new MapSqlParameterSource(), Long.class);
    }

    // ---------------------------------------------------------------- 内部

    private static String where(TopicFilter f, MapSqlParameterSource params) {
        List<String> conds = new ArrayList<>();
        if (f != null && f.type() != null) {
            conds.add("type = :type");
            params.addValue("type", f.type().name());
        }
        if (f != null && f.status() != null) {
            conds.add("status = :status");
            params.addValue("status", f.status().name());
        }
        if (f != null && f.q() != null) {
            // 包含匹配；用户输入里的 \ % _ 一律转义成字面量（AC2.2）。
            conds.add("name_norm LIKE :q ESCAPE '\\'");
            params.addValue("q", "%" + escapeLike(f.q()) + "%");
        }
        return conds.isEmpty() ? "" : " WHERE " + String.join(" AND ", conds);
    }

    static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static Timestamp ts(Instant t) {
        return t == null ? null : Timestamp.from(t);
    }

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        java.time.OffsetDateTime odt = rs.getObject(col, java.time.OffsetDateTime.class);
        return odt == null ? null : odt.withOffsetSameInstant(ZoneOffset.UTC).toInstant();
    }

    private static TopicView map(ResultSet rs, int n) throws SQLException {
        int fo = rs.getInt("featured_order");
        Integer featured = rs.wasNull() ? null : fo;
        return new TopicView(rs.getLong("id"), rs.getString("name_display"), rs.getString("name_norm"),
                TopicType.valueOf(rs.getString("type")), TopicStatus.valueOf(rs.getString("status")),
                rs.getString("desc_id"), rs.getString("desc_en"), rs.getInt("post_count"),
                rs.getBigDecimal("heat_score"), featured, instant(rs, "disabled_at"), instant(rs, "created_at"));
    }

    /** 一页列表。 */
    public record TopicPage(List<TopicView> rows, int page, boolean hasNext) {
    }

    /** 摘要条五格（首页话题行显示「N / 5」）。 */
    public record TopicSummary(long total, long official, int featured, int featuredMax, long hidden,
            long disabled) {
    }

    /** 首页话题行的一格。 */
    public record FeaturedSlot(long id, int order) {
    }
}
