package com.tailtopia.shop.shipping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.shipping.dto.ShippingQuote;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.shop.shipping.service.ShippingQuoteService;
import com.tailtopia.support.ApiIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：服务范围配置与运费试算（Story 2.2 + 2.3，FR-99 / C-14）。
 */
class ShippingQuoteIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private AdminShippingZoneService admin;
    @Autowired
    private ShippingQuoteService quotes;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private String zone(long fee) {
        String k = "Kec" + SEQ.incrementAndGet();
        admin.upsert(k, "Jakarta Selatan", "DKI Jakarta", fee, ACTOR);
        return k;
    }

    private void threshold(long v) {
        admin.setFreeShippingThreshold(v, ACTOR);
    }

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "ship" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "ship" + n);
    }

    // ---------- 2.2 配置 ----------

    @Test
    @DisplayName("配置区域后运费试算取到正确值（一维表：Kecamatan → 运费）")
    void configuredZoneYieldsFee() {
        String k = zone(20_000L);
        threshold(0);

        ShippingQuote q = quotes.quote(k, 100_000L);
        assertThat(q.fee()).isEqualTo(20_000L);
        assertThat(q.total()).isEqualTo(20_000L);
        assertThat(q.discount()).isZero();
    }

    @Test
    @DisplayName("🔴 停用区域用 active=false 而非删行（历史订单运费需可追溯），停用后即不可配送")
    void deactivatedZoneIsUnserviceableButRowRemains() {
        String k = zone(20_000L);
        assertThat(quotes.isServiceable(k)).isTrue();

        admin.setActive(k, false, ACTOR);

        assertThat(quotes.isServiceable(k)).isFalse();
        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM shipping_zones WHERE kecamatan = ?", Integer.class, k);
        assertThat(rows).as("行必须还在——删了就查不到历史运费").isEqualTo(1);
    }

    @Test
    @DisplayName("🔴 运费表没有「配送方式」维度（C-14 已降为一维）")
    void feeTableHasNoDeliveryMethodDimension() {
        Integer cols = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                 WHERE table_name = 'shipping_zones'
                   AND column_name IN ('delivery_method','shipping_method','method')
                """, Integer.class);
        assertThat(cols)
                .as("留一个恒等于 REGULER 的列只会让人误以为多档已支持")
                .isZero();
    }

    // ---------- 2.3 试算 ----------

    @Test
    @DisplayName("🔴 达免运门槛 → 抵扣是一条【负数行】，不是把运费改成 0")
    void freeShippingIsANegativeLineNotZeroFee() {
        String k = zone(20_000L);
        threshold(150_000L);

        ShippingQuote q = quotes.quote(k, 150_000L);

        // 结算页要让用户看见「原本 20.000，因满额免运 −20.000」——
        // 直接显示 0 会让用户不知道自己省了钱，免运门槛也就失去拉高客单价的作用
        assertThat(q.fee()).as("原运费仍要看得见").isEqualTo(20_000L);
        assertThat(q.discount()).isEqualTo(-20_000L);
        assertThat(q.total()).isZero();
        assertThat(q.lines()).hasSize(2);
        assertThat(q.lines().get(1).code()).isEqualTo(ShippingQuote.LINE_FREE_SHIPPING);
        assertThat(q.lines().get(1).amount()).isNegative();
        // total 恒等于各行之和
        assertThat(q.lines().stream().mapToLong(ShippingQuote.QuoteLine::amount).sum())
                .isEqualTo(q.total());
    }

    @Test
    @DisplayName("未达门槛 → 正常收运费，只有一行")
    void belowThresholdChargesFee() {
        String k = zone(20_000L);
        threshold(150_000L);

        ShippingQuote q = quotes.quote(k, 149_999L);
        assertThat(q.total()).isEqualTo(20_000L);
        assertThat(q.lines()).hasSize(1);
    }

    @Test
    @DisplayName("🔴 门槛为 0 表示【不做免运】，不是「0 元即免运」")
    void zeroThresholdMeansNoFreeShipping() {
        String k = zone(20_000L);
        threshold(0);

        ShippingQuote q = quotes.quote(k, 999_999L);
        assertThat(q.total()).as("门槛 0 时再大金额也不免运").isEqualTo(20_000L);
        assertThat(q.discount()).isZero();
    }

    @Test
    @DisplayName("🔴 超范围返回明确的「暂不配送至该区域」，不是笼统报错")
    void outOfRangeGivesSpecificError() {
        threshold(0);
        assertThatThrownBy(() -> quotes.quote("Nowhere" + SEQ.incrementAndGet(), 100_000L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("暂不配送至");
        // 用户需要知道「是这个地址送不到」，否则只会反复重试同一个地址
    }

    // ---------- 🔴 两个校验时机的差异 ----------

    @Test
    @DisplayName("🔴 保存超范围地址【成功】，校验只在试算/结算时发生（FR-99）")
    void savingOutOfRangeAddressSucceedsButQuotingFails() {
        long uid = seedUser();
        String unserviceable = "Kec" + SEQ.incrementAndGet();   // 从未配置过 → 不在服务范围

        // 保存：不校验范围 —— 用户可能下个月搬过去、或先存着等平台开通
        var saved = addresses.create(uid, new AddressFields(
                "Budi", "08123456789", "DKI Jakarta", "Jakarta Selatan",
                unserviceable, "Jl. Test No. 1", "12160", "Rumah"));
        assertThat(saved.getKecamatan()).isEqualTo(unserviceable);

        // 试算：这里才阻断
        assertThatThrownBy(() -> quotes.quote(unserviceable, 100_000L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("暂不配送至");
    }

    @Test
    @DisplayName("空 Kecamatan → 提示选地址而非「暂不配送」（两种失败原因不该混为一谈）")
    void blankKecamatanIsADifferentError() {
        assertThatThrownBy(() -> quotes.quote("  ", 100_000L))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请选择收货地址");
    }
}
