package com.tailtopia.shop.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：v1.4.2 shop Story 1.1 · Biteship 报价落库与复用（stub 模式，需 Docker postgres + redis，云端不跑）。
 *
 * <p>启动即验证 {@code ddl-auto=validate} 下 {@code shipping_quotes} / SKU 包裹参数 / 配送设置新列映射正确。
 */
class BiteshipQuoteIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShippingQuoteService quotes;
    @Autowired
    private ShippingQuoteCleanupJob cleanup;
    @Autowired
    private JdbcTemplate jdbc;

    /** 每个用例用不同包裹，避免与其他用例 / 历史数据的报价复用互相干扰。 */
    private static List<PackageItem> uniqueItems() {
        int w = 1000 + (int) (SEQ.incrementAndGet() % 100_000);
        return List.of(new PackageItem("Produk", 100_000L, 1, w, 20, 15, 10));
    }

    private int rows(String hash) {
        return jdbc.queryForObject("SELECT count(*) FROM shipping_quotes WHERE items_hash = ?",
                Integer.class, hash);
    }

    @Test
    @DisplayName("stub 下询价落库；30 分钟内第二次不新增行")
    void quotePersistedAndReused() {
        List<PackageItem> items = uniqueItems();
        QuoteResult first = quotes.quote("40115", items, null);
        assertThat(first.serviceable()).isTrue();
        assertThat(first.rates()).extracting(CourierRate::courierKey).containsExactly("jne:reg", "sicepat:reg");
        assertThat(first.lowestPrice()).isEqualTo(10_000L);
        assertThat(rows(first.itemsHash())).isEqualTo(1);

        QuoteResult second = quotes.quote("40115", items, null);
        assertThat(second.quoteToken()).isEqualTo(first.quoteToken());
        assertThat(rows(first.itemsHash())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT provider_mode FROM shipping_quotes WHERE public_token = ?",
                String.class, first.quoteToken())).isEqualTo("STUB");
    }

    @Test
    @DisplayName("不可配送（99999）落库 serviceable=false；99998 询价失败不落库")
    void noCourierAndFailure() {
        List<PackageItem> items = uniqueItems();
        QuoteResult r = quotes.quote("99999", items, null);
        assertThat(r.serviceable()).isFalse();
        assertThat(rows(r.itemsHash())).isEqualTo(1);

        List<PackageItem> other = uniqueItems();
        assertThatThrownBy(() -> quotes.quote("99998", other, null)).isInstanceOf(QuoteFailedException.class);
        assertThat(rows(PackageItems.itemsHash(other))).isZero();
    }

    @Test
    @DisplayName("findValid：过期返回空")
    void findValidExpiry() {
        QuoteResult r = quotes.quote("40115", uniqueItems(), null);
        assertThat(quotes.findValid(r.quoteToken(), Instant.now())).isPresent();
        assertThat(quotes.findValid(r.quoteToken(), r.expiresAt().plusSeconds(1))).isEmpty();
    }

    @Test
    @DisplayName("清理任务删除 2 天前的行，保留新行")
    void cleanupDeletesOldRows() {
        QuoteResult old = quotes.quote("40115", uniqueItems(), null);
        QuoteResult fresh = quotes.quote("40116", uniqueItems(), null);
        Instant past = Instant.now().minus(3, ChronoUnit.DAYS);
        jdbc.update("UPDATE shipping_quotes SET created_at = ?, expires_at = ? WHERE public_token = ?",
                java.sql.Timestamp.from(past), java.sql.Timestamp.from(past.plusSeconds(1800)),
                old.quoteToken());

        cleanup.run();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipping_quotes WHERE public_token = ?",
                Integer.class, old.quoteToken())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shipping_quotes WHERE public_token = ?",
                Integer.class, fresh.quoteToken())).isEqualTo(1);
    }
}
