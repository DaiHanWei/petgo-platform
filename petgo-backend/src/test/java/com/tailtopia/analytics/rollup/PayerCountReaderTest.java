package com.tailtopia.analytics.rollup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.sql.ResultSet;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;

/**
 * L0：业务库分子判据（analytics-pipeline Story 4.7 AC1 / AC8 的静态部分，D-13 逐表列出）——每条查询带时间区间、
 * join users 只算 USER + REAL、只取 user_id；各类判据关键片段；空区间不查库。真库比对见 {@code RatioRollupIntegrationTest}。
 */
class PayerCountReaderTest {

    private static final List<String> ALL = List.of(PayerCountReader.TOKO_BUYERS_SQL, PayerCountReader.KTP_SQL,
            PayerCountReader.TAILSONALITY_SQL, PayerCountReader.PASSPORT_SQL, PayerCountReader.PET_SLOT_SQL,
            PayerCountReader.AI_UNLOCK_SQL, PayerCountReader.VET_CONSULT_SQL, PayerCountReader.GIFT_SQL,
            PayerCountReader.TOPUP_SQL, PayerCountReader.NEW_USERS_SQL);

    @Test
    void everyQueryIsNarrowRangedAndFiltersRealUsers() {
        for (String sql : ALL) {
            assertThat(sql).as(sql).contains("u.role = 'USER' AND u.account_type = 'REAL'")
                    .contains(">= ?").contains("< ?")
                    .doesNotContain("SELECT *").doesNotContain("amount").doesNotContain("price")
                    .doesNotContain("now()").doesNotContain("CURRENT_DATE");
            if (!sql.equals(PayerCountReader.NEW_USERS_SQL)) {
                assertThat(sql).as(sql).contains("JOIN users u ON u.id = ");
            }
        }
        // 一律排除 Toko / 门店进货单（产品 #3 的六类与参照列都不碰 SHOP_ORDER / MERCHANT_PO）
        for (String sql : ALL) {
            assertThat(sql.toUpperCase(Locale.ROOT)).doesNotContain("SHOP_ORDER'", "MERCHANT_PO");
        }
    }

    @Test
    void criteriaPerTable() {
        assertThat(PayerCountReader.TOKO_BUYERS_SQL).contains("FROM shop_orders o", "o.paid_at >= ? AND o.paid_at < ?")
                .doesNotContain("status");
        // KTP：QRIS 按支付意图（updated_at），PawCoin 按购买行（purchased_at），UNION 去重
        assertThat(PayerCountReader.KTP_SQL).contains("p.purpose = 'ID_HD' AND p.status = 'PAID'",
                "p.updated_at >= ? AND p.updated_at < ?", " UNION ", "FROM id_card_hd_purchases h",
                "h.pay_channel = 'PAWCOIN'", "h.purchased_at >= ? AND h.purchased_at < ?")
                .doesNotContain("UNION ALL");
        assertThat(PayerCountReader.TAILSONALITY_SQL).contains("FROM keepsake_purchases k",
                "k.sku IN ('TAILSONALITY', 'TS_MATCH')", "k.status = 'PAID'", "k.paid_at >= ?")
                .doesNotContain("DUPLICATE_PAID", "ORPHAN_PAID");
        assertThat(PayerCountReader.PASSPORT_SQL).contains("k.sku IN ('PASSPORT_SNAP', 'BOARDING_PASS')",
                "k.status = 'PAID'");
        assertThat(PayerCountReader.PET_SLOT_SQL).contains("FROM pet_slot_purchases s", "s.status = 'PAID'",
                "s.kind IN ('RENT', 'RENEW')", "s.paid_at >= ?").doesNotContain("ADMIN_GRANT");
        assertThat(PayerCountReader.AI_UNLOCK_SQL).contains("FROM ai_consult_orders a", "a.status = 'COMPLETED'",
                "a.paid_at >= ?");
        assertThat(PayerCountReader.VET_CONSULT_SQL).contains("FROM consult_orders c", "c.paid_at >= ? AND c.paid_at < ?")
                .doesNotContain("status");
        assertThat(PayerCountReader.GIFT_SQL).contains("SELECT DISTINCT g.sender_id", "u.id = g.sender_id",
                "'DELIVERED', 'UNDELIVERED_REFUND_PENDING', 'UNDELIVERED_REFUNDED', 'REFUND_MANUAL'",
                "COALESCE(g.delivered_at, g.created_at) >= ?").doesNotContain("'PENDING'", "'EXPIRED'", "'FAILED'");
        assertThat(PayerCountReader.TOPUP_SQL).contains("p.purpose = 'PAWCOIN_TOPUP' AND p.status = 'PAID'",
                "p.updated_at >= ?");
        assertThat(PayerCountReader.NEW_USERS_SQL).startsWith("SELECT count(*) FROM users u")
                .contains("u.created_at >= ? AND u.created_at < ?");
    }

    @Test
    void emptyRangeDoesNotQuery() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        PayerCountReader r = new PayerCountReader(jdbc);
        Instant t = Instant.parse("2026-11-19T17:00:00Z");
        assertThat(r.tokoBuyers(t, t)).isEmpty();
        assertThat(r.ktpPayers(t, t)).isEmpty();
        assertThat(r.giftSenders(t, t.minusSeconds(1))).isEmpty();
        assertThat(r.newUsers(t, t)).isZero();
        verifyNoInteractions(jdbc);
    }

    /** 同一人多行只算一人（集合去重）；KTP 的两段 UNION 各绑一次区间。 */
    @Test
    void idsAreDeduplicatedAndKtpBindsBothRanges() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        ResultSet rs = mock(ResultSet.class);
        when(rs.getLong(1)).thenReturn(7L, 7L, 8L, 7L, 7L, 8L);
        doAnswer(inv -> {
            RowCallbackHandler h = inv.getArgument(1);
            for (int i = 0; i < 3; i++) {
                h.processRow(rs);
            }
            return null;
        }).when(jdbc).query(anyString(), any(RowCallbackHandler.class), any(Object[].class));
        PayerCountReader r = new PayerCountReader(jdbc);
        Instant from = Instant.parse("2026-11-18T17:00:00Z");
        Instant to = Instant.parse("2026-11-19T17:00:00Z");
        assertThat(r.vetConsultPayers(from, to)).isEqualTo(Set.of(7L, 8L));
        OffsetDateTime f = OffsetDateTime.ofInstant(from, ZoneOffset.UTC);
        OffsetDateTime t = OffsetDateTime.ofInstant(to, ZoneOffset.UTC);
        verify(jdbc).query(eq(PayerCountReader.VET_CONSULT_SQL), any(RowCallbackHandler.class), eq(f), eq(t));

        assertThat(r.ktpPayers(from, to)).isEqualTo(Set.of(7L, 8L));
        verify(jdbc).query(eq(PayerCountReader.KTP_SQL), any(RowCallbackHandler.class), eq(f), eq(t), eq(f), eq(t));
        verify(jdbc, never()).queryForObject(anyString(), eq(Long.class), any(Object[].class));
    }
}
