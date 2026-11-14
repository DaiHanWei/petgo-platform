package com.tailtopia.entitlement.repository;

import com.tailtopia.entitlement.domain.CreditSource;
import com.tailtopia.entitlement.domain.CreditType;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 权益次数两张表的<b>唯一</b>写入点（D-15；{@code EntitlementWriteGuardTest} 守卫）。只被
 * {@code EntitlementCreditService} 使用。并发正确性靠条件语句（{@code WHERE used + n <= quantity}）与 advisory lock，
 * 不靠读后写。
 */
@Repository
public class EntitlementCreditDao {

    private final NamedParameterJdbcTemplate jdbc;

    public EntitlementCreditDao(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** 已发批（幂等回查用）。 */
    public record GrantedRow(long id, long userId, int quantity, Instant expiresAt) { }

    /** 候选批。 */
    public record CandidateRow(long id, CreditType type, int remaining, CreditSource source) { }

    /** 汇总行（按类型）。 */
    public record TypeRow(CreditType type, int remaining, Instant earliestExpiry) { }

    /** INSERT … ON CONFLICT (idem_key, credit_type) DO NOTHING RETURNING；冲突 → empty。 */
    public Optional<GrantedRow> insertIfAbsent(long userId, CreditType type, int quantity, Instant grantedAt,
            Instant expiresAt, CreditSource source, String sourceRef, String idemKey, String labelId, String labelEn,
            Long operatorAdminId, String reason) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", userId).addValue("type", type.wire()).addValue("qty", quantity)
                .addValue("granted", Timestamp.from(grantedAt)).addValue("expires", Timestamp.from(expiresAt))
                .addValue("src", source.name()).addValue("ref", sourceRef).addValue("key", idemKey)
                .addValue("lid", labelId).addValue("len", labelEn).addValue("op", operatorAdminId)
                .addValue("reason", reason);
        List<GrantedRow> rows = jdbc.query("""
                INSERT INTO entitlement_credits (user_id, credit_type, quantity, used, granted_at, expires_at,
                    source_type, source_ref, idem_key, label_id, label_en, operator_admin_id, reason)
                VALUES (:uid, :type, :qty, 0, :granted, :expires, :src, :ref, :key, :lid, :len, :op, :reason)
                ON CONFLICT (idem_key, credit_type) DO NOTHING
                RETURNING id, user_id, quantity, expires_at
                """, p, (rs, i) -> new GrantedRow(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("quantity"),
                rs.getTimestamp("expires_at").toInstant()));
        return rows.stream().findFirst();
    }

    public Optional<GrantedRow> findByIdem(String idemKey, CreditType type) {
        return jdbc.query("""
                SELECT id, user_id, quantity, expires_at FROM entitlement_credits
                 WHERE idem_key = :key AND credit_type = :type
                """, Map.of("key", idemKey, "type", type.wire()),
                (rs, i) -> new GrantedRow(rs.getLong("id"), rs.getLong("user_id"), rs.getInt("quantity"),
                        rs.getTimestamp("expires_at").toInstant())).stream().findFirst();
    }

    /** 每人一把事务级 advisory lock（D-25），随事务释放。 */
    public void lockUser(long userId) {
        jdbc.query("SELECT pg_advisory_xact_lock(hashtext('entitlement:' || :uid))", Map.of("uid", userId),
                rs -> null);
    }

    public boolean useKeyExists(String useKey) {
        Boolean b = jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM entitlement_credit_uses WHERE use_key = :k)", Map.of("k", useKey),
                Boolean.class);
        return Boolean.TRUE.equals(b);
    }

    /** 候选批 FOR UPDATE：先 {@code exact} 类型后其他，同组先到期先扣。 */
    public List<CandidateRow> lockCandidates(long userId, List<CreditType> types, CreditType exact, Instant now) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", userId).addValue("types", types.stream().map(CreditType::wire).toList())
                .addValue("exact", exact.wire()).addValue("now", Timestamp.from(now));
        return jdbc.query("""
                SELECT id, credit_type, quantity - used AS remaining, source_type
                  FROM entitlement_credits
                 WHERE user_id = :uid AND used < quantity AND expires_at > :now AND credit_type IN (:types)
                 ORDER BY CASE WHEN credit_type = :exact THEN 0 ELSE 1 END, expires_at, id
                   FOR UPDATE
                """, p, (rs, i) -> new CandidateRow(rs.getLong("id"),
                CreditType.fromWire(rs.getString("credit_type")).orElseThrow(),
                rs.getInt("remaining"), CreditSource.valueOf(rs.getString("source_type"))));
    }

    /** 条件扣减；影响行数 0 表示已不够（不应发生，锁内已校验）。 */
    public int incrementUsed(long creditId, int n) {
        return jdbc.update("""
                UPDATE entitlement_credits SET used = used + :n WHERE id = :id AND used + :n <= quantity
                """, Map.of("id", creditId, "n", n));
    }

    public void insertUse(long userId, long creditId, CreditType type, String useKind, long targetId, String useKey,
            int quantity, String petName, Instant at) {
        MapSqlParameterSource p = new MapSqlParameterSource()
                .addValue("uid", userId).addValue("cid", creditId).addValue("type", type.wire())
                .addValue("kind", useKind).addValue("target", targetId).addValue("key", useKey)
                .addValue("qty", quantity).addValue("pet", petName).addValue("at", Timestamp.from(at));
        jdbc.update("""
                INSERT INTO entitlement_credit_uses (user_id, credit_id, credit_type, use_kind, target_id, use_key,
                    quantity, pet_name, created_at)
                VALUES (:uid, :cid, :type, :kind, :target, :key, :qty, :pet, :at)
                """, p);
    }

    /** 可用（未用完且未过期）按类型聚合。 */
    public List<TypeRow> availableByType(long userId, Instant now) {
        return jdbc.query("""
                SELECT credit_type, SUM(quantity - used) AS remaining, MIN(expires_at) AS earliest
                  FROM entitlement_credits
                 WHERE user_id = :uid AND used < quantity AND expires_at > :now
                 GROUP BY credit_type
                """, Map.of("uid", userId, "now", Timestamp.from(now)),
                (rs, i) -> new TypeRow(CreditType.fromWire(rs.getString("credit_type")).orElseThrow(),
                        rs.getInt("remaining"), rs.getTimestamp("earliest").toInstant()));
    }
}
