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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

/** L1：v1.4.2 shop Story 2.2 AC2 / AC10 —— 领券端到端（需 Docker postgres + redis，云端不跑）。 */
class CouponClaimIntegrationTest extends ApiIntegrationTest {

    private static final String CLAIM = "/api/v1/me/coupons/claim";

    @Autowired private CouponAdminService admin;

    private ShopCoupon claimCoupon(Integer total, int perUser) {
        Instant now = Instant.now();
        return admin.create(new CouponDraft(CouponKind.DISCOUNT, "Diskon IT", "Discount IT", 10_000L, 50_000L,
                CouponScopeType.ALL, null, CouponAcquireType.CLAIM, now.minus(Duration.ofDays(1)),
                now.plus(Duration.ofDays(10)), CouponValidMode.RELATIVE, null, null, 7, total, perUser), null);
    }

    private String body(String token, String source) {
        return "{\"couponToken\":\"" + token + "\",\"source\":\"" + source + "\"}";
    }

    @Test
    void claimThenAlreadyClaimed() throws Exception {
        ShopCoupon c = claimCoupon(null, 1);
        long uid = newUser().getId();
        mvc.perform(post(CLAIM).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "home")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.couponToken").value(c.getPublicToken()))
                .andExpect(jsonPath("$.claimed").value(true))
                .andExpect(jsonPath("$.id").doesNotExist());
        mvc.perform(post(CLAIM).header("Authorization", userBearer(uid))
                        .contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "HOME")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://petgo/errors/coupon-already-claimed"));
    }

    @Test
    void illegalSourceIs422() throws Exception {
        ShopCoupon c = claimCoupon(null, 1);
        mvc.perform(post(CLAIM).header("Authorization", userBearer(newUser().getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "PUSH")))
                .andExpect(status().isUnprocessableEntity());
    }

    @Test
    void concurrentLastOneOnlyOneWins() throws Exception {
        ShopCoupon c = claimCoupon(1, 1);
        long u1 = newUser().getId();
        long u2 = newUser().getId();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> results = new ArrayList<>();
        for (long uid : new long[] {u1, u2}) {
            results.add(pool.submit(() -> {
                go.await();
                return mvc.perform(post(CLAIM).header("Authorization", userBearer(uid))
                                .contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "DETAIL")))
                        .andReturn().getResponse().getStatus();
            }));
        }
        go.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : results) {
            statuses.add(f.get(30, TimeUnit.SECONDS));
        }
        pool.shutdown();
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
    }

    @Test
    void vetTokenIs403AndGuestIs401() throws Exception {
        ShopCoupon c = claimCoupon(null, 1);
        mvc.perform(post(CLAIM).header("Authorization", vetBearer(1L))
                        .contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "HOME")))
                .andExpect(status().isForbidden());
        mvc.perform(post(CLAIM).contentType(MediaType.APPLICATION_JSON).content(body(c.getPublicToken(), "HOME")))
                .andExpect(status().isUnauthorized());
    }
}
