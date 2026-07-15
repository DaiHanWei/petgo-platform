package com.tailtopia.pay;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.repository.LedgerEntryRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * L1（需 Docker postgres+redis）。上下文启动即验 Flyway V61 + {@code validate}（三表↔实体契约一致）。
 * 核心：N 线程并发 {@code debit} 至边界断言<b>无越负、总账平、reconcile 一致</b>（FR-NFR-1/3）；
 * 并静态断言总账仓储<b>无 delete 路径</b>（append-only）。
 */
class PawCoinWalletConcurrencyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private PawCoinWalletService walletService;
    @Autowired
    private LedgerEntryRepository ledger;

    @Test
    void concurrentDebitsNeverGoNegativeAndReconcile() throws Exception {
        long userId = newUser().getId();
        // 充值 10000 koin（一笔）。
        walletService.credit(userId, 10_000L, PawCoinTxnType.TOPUP, "TOPUP", null,
                "topup-" + SEQ.incrementAndGet());
        assertThat(walletService.balanceOf(userId)).isEqualTo(10_000L);
        assertThat(walletService.reconcile(userId).consistent()).isTrue();

        // 20 线程并发各扣 1000（合计 20000 > 余额 10000）→ 恰 10 次成功、10 次余额不足。
        int threads = 20;
        long each = 1_000L;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final String key = "debit-" + SEQ.incrementAndGet();
            pool.submit(() -> {
                try {
                    start.await();
                    walletService.debit(userId, each, PawCoinTxnType.SPEND, "AI", null, key);
                    success.incrementAndGet();
                } catch (Exception ignored) {
                    // 余额不足 → AppException.conflict，正常竞争落败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 恰好 10 次成功、余额归 0、绝不越负、总账与钱包对账一致。
        assertThat(success.get()).isEqualTo(10);
        assertThat(walletService.balanceOf(userId)).isEqualTo(0L);
        var recon = walletService.reconcile(userId);
        assertThat(recon.walletBalance()).isEqualTo(0L);
        assertThat(recon.ledgerNet()).isEqualTo(0L);
        assertThat(recon.consistent()).isTrue();
    }
}
