package com.tailtopia.shop.coupon;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;

/** L1：v1.4.2 shop Story 2.2 AC3 —— 兑换码与防试码（真 Redis；云端不跑）。 */
class CouponRedeemIntegrationTest extends ApiIntegrationTest {

    private static final String REDEEM = "/api/v1/me/coupons/redeem";

    @Autowired private CouponAdminService admin;
    @Autowired private StringRedisTemplate redis;

    private ShopCoupon codeCoupon(Integer total, int perUser) {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.DISCOUNT, "Kode IT", "Code IT", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CODE, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, 7, total, perUser), null);
    }

    private org.springframework.test.web.servlet.ResultActions redeem(long uid, String code) throws Exception {
        return mvc.perform(post(REDEEM).header("Authorization", userBearer(uid))
                .contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"" + code + "\"}"));
    }

    @Test
    void tenFailuresLockForAnHour() throws Exception {
        long uid = newUser().getId();
        for (int i = 0; i < 10; i++) {
            redeem(uid, "NOPE" + SEQ.incrementAndGet()).andExpect(status().isNotFound());
        }
        Long ttl = redis.getExpire("rl:coupon:redeem-lock:" + uid);
        assertThat(ttl).isNotNull().isBetween(3500L, 3600L);
        assertThat(redis.hasKey("rl:coupon:redeem-fail:" + uid)).isFalse();
        ShopCoupon c = codeCoupon(null, 1);
        String code = "GOOD" + (SEQ.incrementAndGet() % 1_000_000);
        admin.createUniversalCode(c.getPublicToken(), code, null);
        redeem(uid, code).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/coupon-redeem-locked"));
    }

    @Test
    void universalCodeRespectsPerUserLimitAndTotal() throws Exception {
        ShopCoupon c = codeCoupon(2, 1);
        String code = "UNI" + (SEQ.incrementAndGet() % 1_000_000);
        admin.createUniversalCode(c.getPublicToken(), code, null);
        long u1 = newUser().getId();
        redeem(u1, code.toLowerCase()).andExpect(status().isOk()).andExpect(jsonPath("$.isNew").value(true));
        redeem(u1, code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/coupon-already-claimed"));
        redeem(newUser().getId(), code).andExpect(status().isOk());
        redeem(newUser().getId(), code).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/coupon-sold-out"));
    }

    @Test
    void singleUseCodeSecondUserIsCodeUsed() throws Exception {
        ShopCoupon c = codeCoupon(null, 1);
        List<String> codes = admin.generateSingleUseCodes(c.getPublicToken(), 1, null);
        redeem(newUser().getId(), codes.get(0)).andExpect(status().isOk());
        redeem(newUser().getId(), codes.get(0)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/coupon-code-used"));
    }
}
