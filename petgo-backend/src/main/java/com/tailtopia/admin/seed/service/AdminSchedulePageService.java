package com.tailtopia.admin.seed.service;

import com.tailtopia.admin.seed.domain.SeedBatchRow;
import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.auth.dto.AuthorView;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.shared.error.AppException;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排期发布页签的读取（V1.3.0 Story 7.5 · B5）。
 *
 * <p>Story 13.5 的独立「排期管理」页退役，内容并入批量内容页第二页签 ——
 * 本类承接原 {@code AdminContentScheduleController.list} 的查询，并补上摘要条、状态 / 日期筛选与分页。
 *
 * <h2>🛡 默认口径逐字不变：含失败行</h2>
 * 不带状态筛选时列的仍是 {@code SCHEDULED + FAILED}（{@link #LISTED}）。到点失败的行不自动消失、
 * 也不自动重试 —— 它留在列表里就是为了让运营看见并处理。**别把默认改成只看待发布。**
 *
 * <h2>已发布行怎么进来</h2>
 * 只有在状态筛选**显式选了「已发布」**时才列（AC2「已发布行只读」）。已发布是终态且行数只增不减，
 * 混进默认视图会把待处理的那几条淹掉；单选时有分页兜着，不会一次拉全表。
 */
@Service
public class AdminSchedulePageService {

    /** 每页条数（AC2）。 */
    public static final int PAGE_SIZE = 20;

    /**
     * 不带状态筛选时列出的状态。⚠️ 含 FAILED（见类注释）。
     *
     * <p>🛡 与 {@code AdminContentScheduleController.LISTED} 是**同一份**：曾经批量内容页与排期页
     * 各写一份状态清单，迟早出现「这边有那边没有」的行。
     */
    public static final List<SeedBatchRowStatus> LISTED =
            List.of(SeedBatchRowStatus.SCHEDULED, SeedBatchRowStatus.FAILED);

    /** 🛡 与 11-1/11-2/11-3、Excel 导入四处一致：运营心里的墙上时间是 WIB。 */
    public static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /**
     * 摘要条三格（AC2）。
     *
     * <p>⚠️ 只跟着**发布账号**筛选走，不跟状态 / 日期筛选：三格本身就是按状态分的，
     * 再乘一次状态筛选只会让其中两格恒为 0 —— 那时摘要条不再是「整体还剩多少没发」，
     * 而是「我刚点的那个筛选选了几条」，运营会看不出账号维度的积压。
     */
    public record ScheduleSummary(long pending, long publishedToday, long failed) {
    }

    /** 一页排期 + 有无下一页。 */
    public record SchedulePage(List<SeedBatchRow> rows, boolean hasNext, int page, long total) {
    }

    private static final String SUMMARY_SQL = """
            SELECT COUNT(*) FILTER (WHERE r.status = 'SCHEDULED')                             AS pending,
                   COUNT(*) FILTER (WHERE r.status = 'PUBLISHED'
                                      AND (r.updated_at AT TIME ZONE 'Asia/Jakarta')::date = :today) AS published_today,
                   COUNT(*) FILTER (WHERE r.status = 'FAILED')                                AS failed
            FROM seed_batch_rows r
            WHERE
            """ + "(:authorId IS NULL OR r.author_user_id = :authorId)\n";

    private final SeedBatchRowRepository rows;
    private final AccountQueryService accountQuery;
    private final NamedParameterJdbcTemplate jdbc;

    public AdminSchedulePageService(SeedBatchRowRepository rows, AccountQueryService accountQuery,
            NamedParameterJdbcTemplate jdbc) {
        this.rows = rows;
        this.accountQuery = accountQuery;
        this.jdbc = jdbc;
    }

    /**
     * 一页排期。默认按**计划时间升序**（AC2）—— 运营看的是「接下来该发什么」，
     * 最近要发的排最前。计划时间为空的行（失败行可能被清过）排在最后。
     */
    @Transactional(readOnly = true)
    public SchedulePage search(Long authorId, String status, LocalDate date, int page) {
        Sort sort = Sort.by(Sort.Order.asc("scheduledAt").nullsLast(), Sort.Order.asc("id"));
        int wanted = Math.max(page, 0);
        Specification<SeedBatchRow> spec = spec(authorId, status, date);
        Page<SeedBatchRow> found = rows.findAll(spec, PageRequest.of(wanted, PAGE_SIZE, sort));
        long total = found.getTotalElements();
        int lastPage = total == 0 ? 0 : (int) ((total - 1) / PAGE_SIZE);
        // 页码越界（取消掉最后一页最后一条后按原页码重拉，或有人手改 URL）→ 回退最后一页。
        if (wanted > lastPage) {
            wanted = lastPage;
            found = rows.findAll(spec, PageRequest.of(wanted, PAGE_SIZE, sort));
        }
        return new SchedulePage(found.getContent(), wanted < lastPage, wanted, total);
    }

    /**
     * ⚠️ 「今日已发」用的是 {@code updated_at}，**不是发布时刻** —— 表上没有 {@code published_at} 列。
     *
     * <p>已发布行日后被任何写操作碰一下（补图、改物种、重跑）都会刷新 {@code updated_at}，
     * 于是它可能从「昨天已发」跳进今天、或反过来掉出今天。这是**近似口径**：当天巡检够用，
     * 别把它当权威数字（要精确得加列 + 迁移，超出本 story 范围，已记入 Completion Notes 待拍板）。
     */
    @Transactional(readOnly = true)
    public ScheduleSummary summary(Long authorId) {
        MapSqlParameterSource params = new MapSqlParameterSource()
                .addValue("authorId", authorId, java.sql.Types.BIGINT)
                .addValue("today", LocalDate.now(WIB));
        return jdbc.query(SUMMARY_SQL, params, rs -> rs.next()
                ? new ScheduleSummary(rs.getLong("pending"), rs.getLong("published_today"),
                        rs.getLong("failed"))
                : new ScheduleSummary(0, 0, 0));
    }

    /** 单行（抽屉 / 处置后的 oob 行）；不存在 → 404。 */
    @Transactional(readOnly = true)
    public SeedBatchRow row(long rowId) {
        return rows.findById(rowId)
                .orElseThrow(() -> AppException.notFound("排期不存在")
                        .code("admin.err.schedule.notFound"));
    }

    /** 发布账号显示名：整页一次取（逐行查就是 N+1）。注销账号回落匿名投影。 */
    @Transactional(readOnly = true)
    public Map<Long, AuthorView> authorViews(List<SeedBatchRow> page) {
        if (page.isEmpty()) {
            return Map.of();
        }
        return accountQuery.findAuthorViews(
                page.stream().map(SeedBatchRow::getAuthorUserId).distinct().toList());
    }

    /**
     * 筛选：发布账号（12-1 的「移出发布身份前」提示会带 authorId 跳进来，**必须保留**）
     * · 状态 · 计划时间那一天（按 WIB 的一天，不是 UTC 的一天）。
     */
    private static Specification<SeedBatchRow> spec(Long authorId, String status, LocalDate date) {
        SeedBatchRowStatus parsed = parseStatus(status);
        return (root, cq, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            if (parsed == null) {
                ps.add(root.get("status").in(LISTED));
            } else {
                ps.add(cb.equal(root.get("status"), parsed));
            }
            if (authorId != null) {
                ps.add(cb.equal(root.get("authorUserId"), authorId));
            }
            if (date != null) {
                Instant from = date.atStartOfDay(WIB).toInstant();
                Instant to = date.plusDays(1).atStartOfDay(WIB).toInstant();
                // 左闭右开：整点落在两天交界时不会被两天各数一次。
                ps.add(cb.greaterThanOrEqualTo(root.get("scheduledAt"), from));
                ps.add(cb.lessThan(root.get("scheduledAt"), to));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 宽松解析：值不对（有人手改 URL）当作没筛，不为此让整页 500。 */
    private static SeedBatchRowStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return SeedBatchRowStatus.valueOf(status.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 筛选栏可选的状态（顺序即界面顺序）。 */
    public static List<SeedBatchRowStatus> filterableStatuses() {
        return List.of(SeedBatchRowStatus.SCHEDULED, SeedBatchRowStatus.FAILED,
                SeedBatchRowStatus.PUBLISHED);
    }
}
