package com.tailtopia.shop.logistics;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * 一次 Biteship 报价落库（v1.4.2 shop Story 1.1，D-9；表 {@code shipping_quotes}，V20261105_1610）。
 * 与旧运费结果 {@code shop.shipping.dto.ShippingQuote} 区分命名。
 */
@Entity
@Table(name = "shipping_quotes")
public class ShippingQuoteRecord {

    /** {@code response} JSONB 的形状：规范化后的快递列表（价格升序）+ 不可配送原码。 */
    public record Payload(List<CourierRate> rates, Integer noCourierCode) {
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "public_token", nullable = false, length = 32, updatable = false)
    private String publicToken;

    /** 首个请求者，仅审计；复用不看它。 */
    @Column(name = "user_id")
    private Long userId;

    @Column(name = "provider_mode", nullable = false, length = 8, updatable = false)
    private String providerMode;

    @Column(name = "origin_postal_code", nullable = false, length = 5, updatable = false)
    private String originPostalCode;

    @Column(name = "dest_postal_code", nullable = false, length = 5, updatable = false)
    private String destPostalCode;

    @Column(name = "items_hash", nullable = false, length = 64, updatable = false)
    private String itemsHash;

    @Column(name = "serviceable", nullable = false)
    private boolean serviceable;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "response", nullable = false)
    private Payload response;

    @Column(name = "lowest_price")
    private Long lowestPrice;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    protected ShippingQuoteRecord() {
    }

    public static ShippingQuoteRecord create(String publicToken, Long userId, String providerMode,
            String originPostalCode, String destPostalCode, String itemsHash,
            List<CourierRate> rates, Integer noCourierCode, Instant createdAt, Instant expiresAt) {
        ShippingQuoteRecord r = new ShippingQuoteRecord();
        r.publicToken = publicToken;
        r.userId = userId;
        r.providerMode = providerMode;
        r.originPostalCode = originPostalCode;
        r.destPostalCode = destPostalCode;
        r.itemsHash = itemsHash;
        r.serviceable = !rates.isEmpty();
        r.response = new Payload(List.copyOf(rates), r.serviceable ? null : noCourierCode);
        r.lowestPrice = rates.isEmpty() ? null : rates.get(0).price();
        r.createdAt = createdAt;
        r.expiresAt = expiresAt;
        return r;
    }

    /** 转为对外结果（{@code rates} 已按价格升序存）。 */
    public QuoteResult toResult() {
        return new QuoteResult(publicToken, serviceable,
                response == null || response.rates() == null ? List.of() : response.rates(),
                lowestPrice, destPostalCode, itemsHash, expiresAt);
    }

    public Long getId() {
        return id;
    }

    public String getPublicToken() {
        return publicToken;
    }

    public Long getUserId() {
        return userId;
    }

    public String getProviderMode() {
        return providerMode;
    }

    public String getOriginPostalCode() {
        return originPostalCode;
    }

    public String getDestPostalCode() {
        return destPostalCode;
    }

    public String getItemsHash() {
        return itemsHash;
    }

    public boolean isServiceable() {
        return serviceable;
    }

    public Payload getResponse() {
        return response;
    }

    public Long getLowestPrice() {
        return lowestPrice;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }
}
