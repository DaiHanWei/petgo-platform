package com.tailtopia.growth.service;

import com.tailtopia.growth.domain.RuleCode;
import com.tailtopia.growth.web.dto.GrowthLedgerPage;
import com.tailtopia.growth.web.dto.GrowthLedgerRow;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import com.tailtopia.shared.paging.KeysetCursor;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 本人 EXP 明细（V1.4.0 ① Story 1.4 · AD-16）：keyset（{@code created_at DESC, id DESC}），每页 20。
 *
 * <p>{@code detailCode}：{@code SPEND_*} → 流水 ref_type（支付用途）；{@code POST_PUBLISHED} → 帖子类型（一页一次批量查询，
 * 帖已物理删除则 null）；其余 null。<b>只给本人看</b>：调用方只传 JWT sub。
 */
@Service
@Transactional(readOnly = true)
public class GrowthLedgerPageQuery {

    public static final int PAGE_SIZE = 20;

    static final String PAGE_SQL = "SELECT id, rule_code, delta, reason, note, ref_type, ref_id, created_at "
            + "FROM growth_ledger WHERE user_id = :u AND (created_at, id) < (:ts, :id) "
            + "ORDER BY created_at DESC, id DESC LIMIT :limit";

    static final String POST_TYPES_SQL = "SELECT id, type FROM content_posts WHERE id IN (:ids)";

    private static final Set<String> SPEND_RULES = Set.of(RuleCode.SPEND_DIGITAL.name(), RuleCode.SPEND_SHOP.name());

    private final NamedParameterJdbcTemplate jdbc;

    public GrowthLedgerPageQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 一页明细。非法游标 → 400。 */
    public GrowthLedgerPage page(long userId, String cursor) {
        KeysetCursor from = KeysetCursor.decodeOrNull(cursor);
        if (from == null && cursor != null && !cursor.isBlank()) {
            // 400（不是 validation 的 422）：游标是请求参数格式错误。
            throw new AppException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "无效的分页游标");
        }
        if (from == null) {
            from = KeysetCursor.firstPage();
        }
        List<Raw> raws = fetch(userId, from, PAGE_SIZE + 1);
        boolean hasMore = raws.size() > PAGE_SIZE;
        List<Raw> page = hasMore ? raws.subList(0, PAGE_SIZE) : raws;
        String next = hasMore ? new KeysetCursor(page.get(page.size() - 1).createdAt, page.get(page.size() - 1).id)
                .encode() : null;
        return new GrowthLedgerPage(toRows(page), next);
    }

    /** 最近 {@code n} 条（等级页预览）。 */
    public List<GrowthLedgerRow> recent(long userId, int n) {
        return toRows(fetch(userId, KeysetCursor.firstPage(), n));
    }

    private List<Raw> fetch(long userId, KeysetCursor from, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("u", userId)
                .addValue("ts", Timestamp.from(from.createdAt()))
                .addValue("id", from.id())
                .addValue("limit", limit);
        return jdbc.query(PAGE_SQL, p, (rs, i) -> new Raw(rs.getLong("id"), rs.getString("rule_code"),
                rs.getInt("delta"), rs.getString("reason"), rs.getString("note"), rs.getString("ref_type"),
                rs.getString("ref_id"), rs.getTimestamp("created_at").toInstant()));
    }

    private List<GrowthLedgerRow> toRows(List<Raw> raws) {
        Map<Long, String> postTypes = postTypes(raws);
        List<GrowthLedgerRow> rows = new ArrayList<>(raws.size());
        for (Raw r : raws) {
            rows.add(new GrowthLedgerRow(r.ruleCode, r.delta, r.reason, r.note, detailCode(r, postTypes),
                    r.createdAt));
        }
        return rows;
    }

    private Map<Long, String> postTypes(List<Raw> raws) {
        List<Long> ids = new ArrayList<>();
        for (Raw r : raws) {
            Long postId = postId(r);
            if (postId != null) {
                ids.add(postId);
            }
        }
        Map<Long, String> types = new HashMap<>();
        if (ids.isEmpty()) {
            return types;
        }
        for (Map<String, Object> row : jdbc.queryForList(POST_TYPES_SQL, Map.of("ids", ids))) {
            types.put(((Number) row.get("id")).longValue(), (String) row.get("type"));
        }
        return types;
    }

    private static Long postId(Raw r) {
        if (!RuleCode.POST_PUBLISHED.name().equals(r.ruleCode) || r.refId == null) {
            return null;
        }
        try {
            return Long.parseLong(r.refId);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String detailCode(Raw r, Map<Long, String> postTypes) {
        if (SPEND_RULES.contains(r.ruleCode)) {
            return r.refType;
        }
        Long postId = postId(r);
        return postId == null ? null : postTypes.get(postId);
    }

    private record Raw(long id, String ruleCode, int delta, String reason, String note, String refType,
            String refId, Instant createdAt) {
    }
}
