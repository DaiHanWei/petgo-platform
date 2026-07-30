package com.tailtopia.triage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.support.ApiIntegrationTest;
import com.tailtopia.triage.dto.FreeQuotaView;
import com.tailtopia.triage.service.FreeQuotaService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（需 Docker postgres+redis）。上下文启动即验 Flyway V63 + {@code validate}
 * （{@code user_monthly_free_quota} ↔ {@link com.tailtopia.triage.domain.UserMonthlyFreeQuota} 契约一致）。
 *
 * <p>核心 AC2：默认 limit=1（{@code petgo.triage.default-free-quota} 默认值）下 N 线程并发 {@code tryConsume}
 * <b>恰 1 次成功、used_count=1、不超发</b>；耗尽后再 consume=false；AC5：{@code GET /me/free-quota} JWT 门控 + 返回体。
 */
class FreeQuotaConcurrencyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private FreeQuotaService freeQuota;

    @Test
    void concurrentTryConsumeNeverOversellsAtLimitOne() throws Exception {
        long userId = newUser().getId();
        assertThat(freeQuota.status(userId).limit()).isEqualTo(1); // 默认 env 值
        assertThat(freeQuota.status(userId).remaining()).isEqualTo(1);

        // 20 线程并发各消耗 1 次，limit=1 → 恰 1 次成功、19 次落败，used_count 绝不超 1。
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    if (freeQuota.tryConsume(userId)) {
                        success.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 竞争落败正常
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(success.get()).isEqualTo(1);
        FreeQuotaView after = freeQuota.status(userId);
        assertThat(after.used()).isEqualTo(1);
        assertThat(after.remaining()).isEqualTo(0);
        // 耗尽后再 consume 仍 false（幂等不再扣）。
        assertThat(freeQuota.tryConsume(userId)).isFalse();
        assertThat(freeQuota.status(userId).used()).isEqualTo(1);
    }

    @Test
    void freeQuotaEndpointRequiresJwtAndReturnsStatus() throws Exception {
        long userId = newUser().getId();
        freeQuota.tryConsume(userId); // 用掉 1 次

        // 无 JWT → 401。
        mvc.perform(get("/api/v1/me/free-quota"))
                .andExpect(status().isUnauthorized());

        // 带 USER Bearer → 200 + 字段（limit=1, used=1, remaining=0）。
        mvc.perform(get("/api/v1/me/free-quota").header("Authorization", userBearer(userId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.limit").value(1))
                .andExpect(jsonPath("$.used").value(1))
                .andExpect(jsonPath("$.remaining").value(0))
                .andExpect(jsonPath("$.period").isNotEmpty());
    }
}
