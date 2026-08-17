package com.tailtopia.shop.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.logging.LogSanitizer;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.domain.ShippingAddress;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.shipping.dto.ShippingQuote;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.shop.shipping.service.RegionQueryService;
import com.tailtopia.shop.shipping.service.ShippingQuoteService;
import com.tailtopia.support.ApiIntegrationTest;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：Epic 2 全链路联调（Story 2.5）。
 *
 * <p>链路：<b>后台配 Kecamatan 与运费 → 用户存地址 → 试算出运费</b>；
 * 再把地址换到范围外 → 试算被拦。Epic 3 的结算页直接取用这条链路。
 */
class Epic2ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShippingZoneService admin;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private ShippingQuoteService quotes;
    @Autowired
    private RegionQueryService regions;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e2c" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e2c" + n);
    }

    private AddressFields at(String kecamatan) {
        return new AddressFields("Budi", "08123456789", "DKI Jakarta", "Jakarta Selatan",
                kecamatan, "Jl. Melawai IV No. 12", "12160", "Rumah");
    }

    @Test
    @DisplayName("🔗 全链路：后台配范围与运费 → 存地址 → 试算出正确运费")
    void fullChainConfigToQuote() {
        long uid = seedUser();
        String inRange = "Kec" + SEQ.incrementAndGet();
        admin.upsert(inRange, "Jakarta Selatan", "DKI Jakarta", 22_000L, ACTOR);
        admin.setFreeShippingThreshold(0, ACTOR);

        ShippingAddress a = addresses.create(uid, at(inRange));
        ShippingQuote q = quotes.quote(a.getKecamatan(), 100_000L);

        assertThat(q.fee()).isEqualTo(22_000L);
        assertThat(q.total()).isEqualTo(22_000L);
    }

    @Test
    @DisplayName("🔗 地址改到范围外 → 试算返回「暂不配送至该区域」（保存仍成功）")
    void movingAddressOutOfRangeBlocksQuoteButNotSave() {
        long uid = seedUser();
        String inRange = "Kec" + SEQ.incrementAndGet();
        String outOfRange = "Kec" + SEQ.incrementAndGet();
        admin.upsert(inRange, "Jakarta Selatan", "DKI Jakarta", 22_000L, ACTOR);
        admin.setFreeShippingThreshold(0, ACTOR);

        ShippingAddress a = addresses.create(uid, at(inRange));
        assertThat(quotes.quote(a.getKecamatan(), 100_000L).fee()).isEqualTo(22_000L);

        // 改到未配置的区域：保存成功（FR-99），试算被拦
        ShippingAddress moved = addresses.update(uid, a.getPublicToken(), at(outOfRange));
        assertThat(moved.getKecamatan()).isEqualTo(outOfRange);
        assertThatThrownBy(() -> quotes.quote(moved.getKecamatan(), 100_000L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("暂不配送至");
    }

    @Test
    @DisplayName("🔗 达免运门槛 → 抵扣负数行正确出现，total 归零")
    void freeShippingDiscountAppearsInChain() {
        long uid = seedUser();
        String k = "Kec" + SEQ.incrementAndGet();
        admin.upsert(k, "Jakarta Selatan", "DKI Jakarta", 22_000L, ACTOR);
        admin.setFreeShippingThreshold(200_000L, ACTOR);

        ShippingAddress a = addresses.create(uid, at(k));
        ShippingQuote q = quotes.quote(a.getKecamatan(), 200_000L);

        assertThat(q.discount()).isEqualTo(-22_000L);
        assertThat(q.total()).isZero();
        assertThat(q.lines()).hasSize(2);
    }

    @Test
    @DisplayName("🔗 区域树含 inactive 区域 —— 用户能选到「已录入但暂不送达」的地方")
    void regionTreeIncludesInactiveSoUsersCanPreSaveAddresses() {
        String k = "Kec" + SEQ.incrementAndGet();
        admin.upsert(k, "Jakarta Selatan", "DKI Jakarta", 22_000L, ACTOR);
        admin.setActive(k, false, ACTOR);

        boolean found = regions.tree().provinsi().stream()
                .flatMap(p -> p.kota().stream())
                .flatMap(kota -> kota.kecamatan().stream())
                .anyMatch(kec -> kec.name().equals(k) && !kec.serviceable());

        assertThat(found)
                .as("藏掉 inactive 会让「先存着等开通」变得不可能（FR-99 允许保存超范围地址）")
                .isTrue();
    }

    // ---------- 🔒 PII 护栏 ----------

    @Test
    @DisplayName("🔒 Epic 2 的三项 PII 在日志中全部被打码，kecamatan 保留可见")
    void addressPiiNeverReachesLogs() {
        String body = """
                {"receiverName":"Budi Santoso","receiverPhone":"+628123456789",
                 "addressLine":"Jl. Melawai IV No. 12","kodePos":"12160",
                 "kecamatan":"Kebayoran Baru","provinsi":"DKI Jakarta"}
                """;
        String out = new LogSanitizer()
                .sanitize(body.getBytes(StandardCharsets.UTF_8), "application/json");

        assertThat(out)
                .doesNotContain("Budi Santoso")
                .doesNotContain("+628123456789")
                .doesNotContain("Jl. Melawai IV No. 12");
        // 🔴 kecamatan / provinsi 不是 PII —— 它们是运费与服务范围的判定粒度，排障必须看得见。
        //    把它们也打码会让「为什么这单算出这个运费」变成无法回溯的问题。
        assertThat(out).contains("Kebayoran Baru").contains("DKI Jakarta");
    }

    @Test
    @DisplayName("🔒 试算的错误信息只含 Kecamatan，不含任何地址 PII")
    void quoteErrorCarriesNoPii() {
        assertThatThrownBy(() -> quotes.quote("Nowhere" + SEQ.incrementAndGet(), 1L))
                .isInstanceOf(AppException.class)
                .satisfies(e -> assertThat(e.getMessage())
                        .doesNotContain("Budi").doesNotContain("+62").doesNotContain("Jl."));
    }
}
