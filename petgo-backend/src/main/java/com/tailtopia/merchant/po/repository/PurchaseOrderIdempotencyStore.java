package com.tailtopia.merchant.po.repository;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * 建单幂等记录（契约 5.4、AD-17）。
 *
 * <p>走 JDBC 不映射实体：{@code idempotency_key CHAR(22)} / {@code request_digest CHAR(64)} 是契约定的定长类型，
 * Hibernate 把 {@code String} 映成 {@code varchar}，{@code ddl-auto=validate} 会对 {@code bpchar} 报类型不符。
 */
@Repository
public class PurchaseOrderIdempotencyStore {

    private final JdbcTemplate jdbc;

    public PurchaseOrderIdempotencyStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 同门店同 key 的建单串行化：事务级 advisory 锁，随事务结束释放。
     * 两个同 key 请求并发到达时，后到者等前者提交后再查记录 —— 拿到原单而不是撞主键。
     */
    public void lock(long merchantStoreId, String idempotencyKey) {
        jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))",
                "merchant-po:" + merchantStoreId + ":" + idempotencyKey);
    }

    public Optional<Record> find(long merchantStoreId, String idempotencyKey) {
        List<Record> rows = jdbc.query(
                "SELECT request_digest, purchase_order_id FROM purchase_order_idempotency "
                        + "WHERE merchant_store_id = ? AND idempotency_key = ?",
                (rs, i) -> new Record(rs.getString("request_digest"), rs.getLong("purchase_order_id")),
                merchantStoreId, idempotencyKey);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(long merchantStoreId, String idempotencyKey, String requestDigest, long purchaseOrderId) {
        jdbc.update("INSERT INTO purchase_order_idempotency "
                        + "(merchant_store_id, idempotency_key, request_digest, purchase_order_id) VALUES (?, ?, ?, ?)",
                merchantStoreId, idempotencyKey, requestDigest, purchaseOrderId);
    }

    /** 清理保留期之前的记录（≥ 7 天，AD-17）。 */
    public int deleteCreatedBefore(java.time.Instant before) {
        return jdbc.update("DELETE FROM purchase_order_idempotency WHERE created_at < ?",
                java.sql.Timestamp.from(before));
    }

    public record Record(String requestDigest, long purchaseOrderId) {
    }
}
