package com.tailtopia.admin.moderation.service;

import com.tailtopia.admin.moderation.dto.TicketStatusBucket;
import com.tailtopia.admin.moderation.dto.TicketType;
import com.tailtopia.admin.moderation.dto.UnifiedTicketRow;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 统一工单队列查询（Story 3.1，AB-3D）。**三个业务类别、四张源表，读时联合。**
 *
 * <h2>为什么是「读时联合」而不是建一张索引表</h2>
 * 不新增统一索引表、不双写、存量零回填、不加缓存（AD-7）。各类工单的状态<b>仍由各自源表权威持有</b>——
 * 一旦双写，「工单在哪张表里是真的」立刻变成一个需要对账的问题，而后台 QPS 极低，联合查询直接落库足够。
 *
 * <h2>优先级公式（AC4）</h2>
 * <pre>
 *   分 = 举报人数 + 高频举报人数
 *   举报人数     = 举报过该对象的**不同账号数**（去重）
 *   高频举报人数 = 其中对该对象累计举报 ≥5 次（含 5）的账号数
 * </pre>
 * <b>单个举报人对分数的贡献因此上限为 2 分</b>（1 基础 + 1 高频）—— 这不是巧合，是公式的设计目的：
 * 一个人刷 100 次也就 2 分，十个人各报一次是 10 分。<b>众怒排在纠缠前面。</b>
 * 校验用例（有 L1 测试逐一钉死）：甲3+乙7 → 3；甲5+乙6 → 4；10 人各 1 次 → 10；1 人 100 次 → 2。
 *
 * <p>分数<b>实时算、不落库快照</b>。排序：分倒序，<b>同分按最早一次举报时间升序</b>（先报的先处理）。
 *
 * <h2>⚠️ 账号标识字段那一类没有举报人</h2>
 * 它的分数按各表自己的 {@code priority} 映射：<b>{@code HIGH → 10} / {@code NORMAL → 2}</b>（C-102）。
 * 锚点取自公式自身的两个例子：HIGH（≥0.8 高置信违规）等同于十人举报的众怒，NORMAL 等同于一个纠缠的举报者。
 * <b>{@code NORMAL} 绝不能映射成 0</b> —— 它是这两张表的 DEFAULT 值、绝大多数记录都是它，
 * 映射成 0 会让这类工单几乎全部永远沉底没人看。
 *
 * <h2>⚠️ 别照抄既有举报队列的 N+1</h2>
 * {@code AdminModerationService.queue} 在循环里逐条查内容摘要与举报数，靠 50 条硬上限勉强撑住。
 * 本视图是四表联合 + 优先级实时计算，照抄会更糟。这里<b>一条 SQL 出结果、排序分页都在库内做</b>，
 * 「人数 / 次数 / 高频人数」由 Story 2.1 建的 {@code idx_account_report_entries_report_reporter}
 * 一次聚合得出。
 *
 * <h2>⚠️ PII 红线</h2>
 * {@code name_moderation_records.submitted_value}（送审名称原文）与 {@code avatar_reviews.avatar_url}
 * <b>可以展示</b>（那正是运营要看的证据），但<b>严禁写入任何日志</b>。本类不打印任何行内容。
 */
@Service
public class UnifiedTicketQueryService {

    /** 账号标识字段工单的分数映射（AC6，C-102）。抽成常量便于日后调整。 */
    static final int IDENTITY_SCORE_HIGH = 10;

    /** ⚠️ 不得改成 0：NORMAL 是那两张表的 DEFAULT 值，改 0 等于让这类工单集体沉底。 */
    static final int IDENTITY_SCORE_NORMAL = 2;

    /** 「高频举报人」的门槛：对同一对象累计举报 ≥ 这个数（含）。 */
    static final int FREQUENT_REPORTER_THRESHOLD = 5;

    /**
     * 四表联合的 CTE。
     *
     * <p>⚠️ 账号标识字段两表<b>只收 MANUAL_PENDING 与两个终态</b>：
     * {@code SCORING / QUEUED / AUTO_PASSED / SUPERSEDED / FAILED_TO_QUEUE} 一律不进运营队列
     * （自动过的、被新提交顶掉作废的、还没评分完的，都不是「等人处理」的东西）。
     *
     * <p>⚠️ 两表的终态<b>不同构</b>，映射在 SQL 里固化：
     * <ul>
     *   <li>名称：{@code RESOLVED_VIOLATION → 已处理}、{@code RESOLVED_PASS → 无需处置}</li>
     *   <li>头像：只有一个 {@code RESOLVED}，靠 {@code verdict='VIOLATION'} 区分同样两档</li>
     * </ul>
     * 时间锚点也不同名（名称 {@code submitted_at} / 头像 {@code created_at}），各自 AS 成 earliest_at。
     */
    private static final String UNIFIED_CTE = """
            WITH per_reporter AS (
                SELECT report_id, reporter_id, COUNT(*) AS cnt, MIN(created_at) AS first_at
                  FROM account_report_entries
                 GROUP BY report_id, reporter_id
            ),
            account_agg AS (
                SELECT report_id,
                       COUNT(*)                                   AS reporter_count,
                       SUM(cnt)                                   AS report_count,
                       COUNT(*) FILTER (WHERE cnt >= %d)          AS frequent_count,
                       MIN(first_at)                              AS earliest_at
                  FROM per_reporter
                 GROUP BY report_id
            ),
            unified AS (
                -- ① 内容举报：**按帖聚合**（12 个人举报同一条帖是一条工单，不是 12 条）
                SELECT 'CONTENT_REPORT'::text                     AS ticket_type,
                       cr.post_id                                 AS source_id,
                       NULL::text                                 AS sub_type,
                       cp.author_id                               AS target_user_id,
                       CASE WHEN bool_or(cr.status = 'PENDING')  THEN 'PENDING'
                            WHEN bool_or(cr.status = 'RESOLVED') THEN 'RESOLVED'
                            ELSE 'NO_ACTION' END                  AS status_bucket,
                       COUNT(DISTINCT cr.reporter_id)::bigint     AS reporter_count,
                       COUNT(*)::bigint                           AS report_count,
                       0::bigint                                  AS frequent_count,
                       -- content_reports 的唯一键是 (reporter, post)，同一人报不了第二次
                       -- ⇒ 次数恒等于人数、高频恒为 0，分数就是举报人数。
                       COUNT(DISTINCT cr.reporter_id)::bigint     AS score,
                       MIN(cr.created_at)                         AS earliest_at,
                       LEFT(COALESCE(cp.text, ''), 60)            AS preview
                  FROM content_reports cr
                  JOIN content_posts cp ON cp.id = cr.post_id
                 GROUP BY cr.post_id, cp.author_id, cp.text

                UNION ALL

                -- ② 用户举报：一个被举报账号一条工单，分数 = 人数 + 高频人数
                SELECT 'ACCOUNT_REPORT'::text,
                       ar.id,
                       NULL::text,
                       ar.target_user_id,
                       CASE ar.status WHEN 'PENDING'  THEN 'PENDING'
                                      WHEN 'RESOLVED' THEN 'RESOLVED'
                                      ELSE 'NO_ACTION' END,
                       COALESCE(agg.reporter_count, 0)::bigint,
                       COALESCE(agg.report_count, 0)::bigint,
                       COALESCE(agg.frequent_count, 0)::bigint,
                       (COALESCE(agg.reporter_count, 0) + COALESCE(agg.frequent_count, 0))::bigint,
                       COALESCE(agg.earliest_at, ar.first_reported_at),
                       NULL::text
                  FROM account_reports ar
                  LEFT JOIN account_agg agg ON agg.report_id = ar.id

                UNION ALL

                -- ③ 账号标识字段 · 名称（昵称 / 宠物名）
                SELECT 'ACCOUNT_IDENTITY'::text,
                       nmr.id,
                       nmr.target_type,
                       CASE WHEN nmr.target_type = 'NICKNAME' THEN nmr.target_ref_id
                            ELSE pp.owner_id END,
                       CASE nmr.status WHEN 'MANUAL_PENDING'     THEN 'PENDING'
                                       WHEN 'RESOLVED_VIOLATION' THEN 'RESOLVED'
                                       ELSE 'NO_ACTION' END,
                       0::bigint, 0::bigint, 0::bigint,
                       (CASE nmr.priority WHEN 'HIGH' THEN %d ELSE %d END)::bigint,
                       nmr.submitted_at,
                       nmr.submitted_value
                  FROM name_moderation_records nmr
                  LEFT JOIN pet_profiles pp
                         ON nmr.target_type = 'PET_NAME' AND pp.id = nmr.target_ref_id
                 WHERE nmr.status IN ('MANUAL_PENDING', 'RESOLVED_VIOLATION', 'RESOLVED_PASS')

                UNION ALL

                -- ④ 账号标识字段 · 头像（用户头像 / 宠物头像）
                SELECT 'ACCOUNT_IDENTITY'::text,
                       avr.id,
                       avr.subject_type,
                       CASE WHEN avr.subject_type = 'USER_AVATAR' THEN avr.subject_id
                            ELSE pp2.owner_id END,
                       CASE WHEN avr.status = 'MANUAL_PENDING'                       THEN 'PENDING'
                            WHEN avr.status = 'RESOLVED' AND avr.verdict = 'VIOLATION' THEN 'RESOLVED'
                            ELSE 'NO_ACTION' END,
                       0::bigint, 0::bigint, 0::bigint,
                       (CASE avr.priority WHEN 'HIGH' THEN %d ELSE %d END)::bigint,
                       avr.created_at,
                       avr.avatar_url
                  FROM avatar_reviews avr
                  LEFT JOIN pet_profiles pp2
                         ON avr.subject_type = 'PET_AVATAR' AND pp2.id = avr.subject_id
                 WHERE avr.status IN ('MANUAL_PENDING', 'RESOLVED')
            )
            """.formatted(FREQUENT_REPORTER_THRESHOLD,
            IDENTITY_SCORE_HIGH, IDENTITY_SCORE_NORMAL,
            IDENTITY_SCORE_HIGH, IDENTITY_SCORE_NORMAL);

    private final JdbcTemplate jdbc;

    public UnifiedTicketQueryService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 查一页工单。
     *
     * @param type     类别筛选（null = 全部三类）
     * @param status   状态筛选（null = 全部三态）
     * @param search   按<b>被举报账号</b>检索：纯数字按 userId 精确匹配，否则按昵称模糊匹配。
     *                 处理一条账号举报时能立刻查到「这个人之前是不是被报过、被处置过几次」
     */
    @Transactional(readOnly = true)
    public Page<UnifiedTicketRow> search(TicketType type, TicketStatusBucket status, String search,
            Pageable pageable) {
        List<Object> args = new ArrayList<>();
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        if (type != null) {
            where.append(" AND u.ticket_type = ?");
            args.add(type.name());
        }
        if (status != null) {
            where.append(" AND u.status_bucket = ?");
            args.add(status.name());
        }
        String keyword = search == null ? null : search.trim();
        if (keyword != null && !keyword.isEmpty()) {
            if (keyword.chars().allMatch(Character::isDigit)) {
                where.append(" AND u.target_user_id = ?");
                args.add(Long.parseLong(keyword));
            } else {
                where.append(" AND usr.nickname ILIKE ?");
                args.add("%" + keyword + "%");
            }
        }

        String joins = """
                  FROM unified u
                  LEFT JOIN users usr ON usr.id = u.target_user_id
                  LEFT JOIN (SELECT target_user_id, COUNT(*) AS c
                               FROM account_disposals GROUP BY target_user_id) d
                         ON d.target_user_id = u.target_user_id
                """;

        Long total = jdbc.queryForObject(
                UNIFIED_CTE + " SELECT COUNT(*)" + joins + where, Long.class, args.toArray());

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageable.getPageSize());
        pageArgs.add(pageable.getOffset());
        // ⚠️ 排序固定在 SQL 里：分倒序 + 同分最早优先。全后台都是后端硬编码排序，没有点表头排序的实现。
        List<UnifiedTicketRow> rows = jdbc.query(
                UNIFIED_CTE + """
                         SELECT u.ticket_type, u.source_id, u.sub_type, u.target_user_id,
                                usr.nickname AS target_nickname,
                                (usr.deleted_at IS NOT NULL) AS target_deleted,
                                u.status_bucket, u.reporter_count, u.report_count, u.frequent_count,
                                u.score, u.earliest_at, u.preview,
                                COALESCE(d.c, 0) AS disposal_count
                        """ + joins + where
                        + " ORDER BY u.score DESC, u.earliest_at ASC LIMIT ? OFFSET ?",
                ROW_MAPPER, pageArgs.toArray());

        return new PageImpl<>(rows, pageable, total == null ? 0 : total);
    }

    private static final RowMapper<UnifiedTicketRow> ROW_MAPPER = (ResultSet rs, int i) -> {
        // ⚠️ `wasNull()` 说的是**最近一次读的那一列**，所以必须紧挨着 getLong 取，
        // 不能挪到构造器参数列表里去——那里中间还夹着别的列的读取。
        long rawTargetId = rs.getLong("target_user_id");
        Long targetUserId = rs.wasNull() ? null : rawTargetId;
        return new UnifiedTicketRow(
                TicketType.valueOf(rs.getString("ticket_type")),
                rs.getLong("source_id"),
                rs.getString("sub_type"),
                targetUserId,
                rs.getString("target_nickname"),
                rs.getBoolean("target_deleted"),
                TicketStatusBucket.valueOf(rs.getString("status_bucket")),
                rs.getLong("reporter_count"),
                rs.getLong("report_count"),
                rs.getLong("frequent_count"),
                rs.getLong("score"),
                toInstant(rs, "earliest_at"),
                rs.getString("preview"),
                rs.getLong("disposal_count"));
    };

    private static java.time.Instant toInstant(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp ts = rs.getTimestamp(column);
        return ts == null ? null : ts.toInstant();
    }
}
