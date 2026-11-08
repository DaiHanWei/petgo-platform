package com.tailtopia.shop.coupon;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shop.coupon.domain.CouponAcquireType;
import com.tailtopia.shop.coupon.domain.CouponKind;
import com.tailtopia.shop.coupon.domain.CouponScopeType;
import com.tailtopia.shop.coupon.domain.CouponValidMode;
import com.tailtopia.shop.coupon.domain.ShopCoupon;
import com.tailtopia.shop.coupon.service.CouponAdminService;
import com.tailtopia.shop.coupon.service.CouponDraft;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** L1：v1.4.2 shop Story 2.2 AC4 / AC5 —— 新人券发放、幂等、游客活动接口（云端不跑）。 */
class NewbieCouponIntegrationTest extends ApiIntegrationTest {

    private static final String ENTRY = "/api/v1/me/shop/entry";
    private static final String CAMPAIGN = "/api/v1/shop/coupons/newbie-campaign";

    @Autowired private CouponAdminService admin;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void clearActiveNewbie() {
        // 共享测试库：先停掉其它测试遗留的生效新人券（2.1 保证同一时刻最多一张）
        jdbc.update("UPDATE shop_coupons SET status = 'STOPPED', stopped_at = now(), updated_at = now() "
                + "WHERE acquire_type = 'NEWBIE' AND status = 'ACTIVE'");
    }

    private ShopCoupon newbieCoupon() {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.FREE_SHIPPING, "Baru IT", "New IT", 15_000L, 0L,
                CouponScopeType.ALL, null, CouponAcquireType.NEWBIE, now.minus(Duration.ofMinutes(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, 7, null, 1), null);
    }

    @Test
    void entryIssuesOnceAndCampaignBeforeAndAfter() throws Exception {
        long uid = newUser().getId();
        mvc.perform(get(CAMPAIGN)).andExpect(status().isNoContent());
        mvc.perform(post(ENTRY).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newbieCouponIssued").value(false));
        ShopCoupon c = newbieCoupon();
        mvc.perform(get(CAMPAIGN)).andExpect(status().isOk())
                .andExpect(jsonPath("$.campaignToken").value(c.getPublicToken()))
                .andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(post(ENTRY).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newbieCouponIssued").value(true));
        mvc.perform(post(ENTRY).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newbieCouponIssued").value(false));
    }

    @Test
    void paidOrderDisqualifies() throws Exception {
        newbieCoupon();
        long uid = newUser().getId();
        long n = SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_orders (public_token, display_no, user_id, status, goods_subtotal,
                        shipping_fee, shipping_discount, total_amount,
                        ship_receiver_name, ship_receiver_phone, ship_provinsi,
                        ship_kota_kabupaten, ship_kecamatan, ship_address_line, ship_kode_pos,
                        paid_at, created_at, updated_at)
                VALUES (?, ?, ?, 'PENDING_SHIPMENT', 100000, 0, 0, 100000,
                        'Budi', '08123', 'DKI', 'Jaksel', 'Kebayoran', 'Jl. 1', '12110', now(), now(), now())""",
                "nbit" + n, "TOKO-NB-" + n % 100_000_000, uid);
        mvc.perform(post(ENTRY).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.newbieCouponIssued").value(false));
    }

    @Test
    void vetTokenCannotEnter() throws Exception {
        mvc.perform(post(ENTRY).header("Authorization", activeVetBearer())).andExpect(status().isForbidden());
    }
}
