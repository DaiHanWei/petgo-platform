package com.tailtopia.shop.logistics;

import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** {@code shipping_quotes} 仓储（v1.4.2 shop Story 1.1）。 */
public interface ShippingQuoteRecordRepository extends JpaRepository<ShippingQuoteRecord, Long> {

    /** 复用查找：同键、未过期的最新一行（走 {@code idx_shipping_quotes_reuse}）。 */
    Optional<ShippingQuoteRecord>
            findFirstByDestPostalCodeAndItemsHashAndOriginPostalCodeAndProviderModeAndExpiresAtAfterOrderByExpiresAtDesc(
                    String destPostalCode, String itemsHash, String originPostalCode,
                    String providerMode, Instant now);

    Optional<ShippingQuoteRecord> findByPublicToken(String publicToken);

    /**
     * 删除 {@code created_at < threshold} 的报价，单次最多 {@code limit} 行。
     *
     * <p>🔴 Story 3.2 加 {@code shop_orders.shipping_quote_id} 后，须在子查询追加
     * {@code AND NOT EXISTS (SELECT 1 FROM shop_orders o WHERE o.shipping_quote_id = q.id)}（被订单引用的不删）。
     */
    @Modifying
    @Transactional
    @Query(value = "DELETE FROM shipping_quotes WHERE id IN ("
            + "SELECT q.id FROM shipping_quotes q WHERE q.created_at < :threshold "
            + "ORDER BY q.id LIMIT :limit)", nativeQuery = true)
    int deleteCreatedBefore(@Param("threshold") Instant threshold, @Param("limit") int limit);
}
