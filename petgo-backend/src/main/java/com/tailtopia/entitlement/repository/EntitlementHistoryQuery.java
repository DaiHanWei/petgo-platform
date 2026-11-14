package com.tailtopia.entitlement.repository;

import com.tailtopia.entitlement.dto.EntitlementHistoryPage;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 权益记录只读查询（V1.5.0 gift-pack Story 4-4 · AC3 · D-28）：发放（{@code entitlement_credits}）与使用
 * （{@code entitlement_credit_uses}，按 {@code use_key} 聚合）两源归并，keyset 游标 {@code (at 微秒, kindRank, id)}，
 * 写法照 {@code OrderCenterCursor}（同刻组的取舍编进同一条 SQL）。
 */
@Repository
public class EntitlementHistoryQuery {

    public static final int PAGE_SIZE = 20;

    private static final String SQL = """
            SELECT * FROM (
                SELECT 'GRANT' AS kind, 0 AS kind_rank, c.id AS id, c.created_at AS at, c.credit_type, c.quantity,
                       c.source_type, s.tier AS pack_tier, c.label_id, c.label_en,
                       CAST(NULL AS VARCHAR) AS use_kind, CAST(NULL AS VARCHAR) AS pet_name
                  FROM entitlement_credits c
                  LEFT JOIN starter_pack_purchases s
                         ON c.source_type = 'PACK' AND s.user_id = c.user_id AND CAST(s.id AS VARCHAR) = c.source_ref
                 WHERE c.user_id = :uid AND c.created_at <= :beforeTs
                UNION ALL
                SELECT 'USE', 1, min(u.id), min(u.created_at), (array_agg(u.credit_type ORDER BY u.id))[1],
                       CAST(sum(u.quantity) AS INT), NULL, NULL, NULL, NULL, min(u.use_kind), min(u.pet_name)
                  FROM entitlement_credit_uses u
                 WHERE u.user_id = :uid AND u.created_at <= :beforeTs
                 GROUP BY u.use_key
            ) t
            WHERE t.at < :beforeTs
               OR (t.at = :beforeTs AND (t.kind_rank > :rank OR (t.kind_rank = :rank AND t.id < :id)))
            ORDER BY t.at DESC, t.kind_rank ASC, t.id DESC
            LIMIT :lim
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public EntitlementHistoryQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 游标 {@code (at, kindRank, id)}；首页 null。 */
    public record Cursor(Instant at, int kindRank, long id) {

        public String encode() {
            long micros = at.getEpochSecond() * 1_000_000L + at.getNano() / 1_000L;
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString((micros + ":" + kindRank + ":" + id).getBytes(StandardCharsets.UTF_8));
        }

        /** 非法 → null（调用方 422）。 */
        public static Cursor decodeOrNull(String token) {
            try {
                String[] p = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8).split(":");
                if (p.length != 3) {
                    return null;
                }
                long micros = Long.parseLong(p[0]);
                return new Cursor(Instant.ofEpochSecond(Math.floorDiv(micros, 1_000_000L),
                        Math.floorMod(micros, 1_000_000L) * 1_000L), Integer.parseInt(p[1]), Long.parseLong(p[2]));
            } catch (RuntimeException e) {
                return null;
            }
        }
    }

    /** 一行 + 它的游标键。 */
    public record Row(EntitlementHistoryPage.Item item, int kindRank, long id) {
    }

    /** 取 {@code limit} 条（调用方多取 1 判有无下一页）。 */
    public List<Row> page(long userId, Cursor after, int limit) {
        Instant beforeTs = after == null ? Instant.now().plusSeconds(60) : after.at();
        MapSqlParameterSource p = new MapSqlParameterSource("uid", userId)
                .addValue("beforeTs", OffsetDateTime.ofInstant(beforeTs, ZoneOffset.UTC))
                .addValue("rank", after == null ? -1 : after.kindRank())
                .addValue("id", after == null ? Long.MAX_VALUE : after.id())
                .addValue("lim", limit);
        List<Row> out = new ArrayList<>();
        jdbc.query(SQL, p, rs -> {
            boolean grant = "GRANT".equals(rs.getString("kind"));
            Integer tier = (Integer) rs.getObject("pack_tier");
            out.add(new Row(new EntitlementHistoryPage.Item(rs.getString("kind"), rs.getString("credit_type"),
                    rs.getInt("quantity"), rs.getObject("at", OffsetDateTime.class).toInstant(),
                    grant ? rs.getString("source_type") : null, tier, rs.getString("label_id"),
                    rs.getString("label_en"), rs.getString("use_kind"), rs.getString("pet_name")),
                    rs.getInt("kind_rank"), rs.getLong("id")));
        });
        return out;
    }
}
