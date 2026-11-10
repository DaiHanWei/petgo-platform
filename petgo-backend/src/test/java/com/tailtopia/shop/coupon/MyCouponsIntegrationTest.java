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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** L1：v1.4.2 shop Story 2.2 AC8 —— 我的券两个页签、分页游标、计数与 summary（云端不跑）。 */
class MyCouponsIntegrationTest extends ApiIntegrationTest {

    @Autowired private CouponAdminService admin;

    private ShopCoupon claimCoupon(int perUser) {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.DISCOUNT, "Diskon IT", "Discount IT", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, 7, null, perUser), null);
    }

    @Test
    void availablePagesAndCounts() throws Exception {
        long uid = newUser().getId();
        ShopCoupon c = claimCoupon(25);
        for (int i = 0; i < 21; i++) {
            mvc.perform(post("/api/v1/me/coupons/claim").header("Authorization", userBearer(uid))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"couponToken\":\"" + c.getPublicToken() + "\",\"source\":\"HOME\"}"))
                    .andExpect(status().isOk());
        }
        String first = mvc.perform(get("/api/v1/me/coupons").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(20))
                .andExpect(jsonPath("$.availableCount").value(21))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        String cursor = json.readTree(first).get("nextCursor").asText();
        mvc.perform(get("/api/v1/me/coupons").param("cursor", cursor).header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1));
        mvc.perform(get("/api/v1/me/coupons").param("tab", "used").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/me/coupons/summary").header("Authorization", userBearer(uid)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.availableCount").value(21));
    }

    @Test
    void vetTokenCannotReadCoupons() throws Exception {
        mvc.perform(get("/api/v1/me/coupons").header("Authorization", activeVetBearer())).andExpect(status().isForbidden());
        mvc.perform(get("/api/v1/shop/coupons/for-you").header("Authorization", activeVetBearer()))
                .andExpect(status().isForbidden());
    }
}
