package com.tailtopia.consult;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.consult.domain.ConsultRequest;
import com.tailtopia.consult.domain.ConsultRequestState;
import com.tailtopia.consult.repository.ConsultRequestRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * L1（需 Docker postgres+redis）。上下文启动即验 Flyway V66 + {@code validate}（三表↔实体契约一致）。
 *
 * <p>核心 H-4：{@code state} 单列 compare-and-set。N 线程并发 {@code tryAccept} 同一 QUEUEING 请求
 * <b>恰 1 成功</b>（先到先得、杜绝双写）；accept 与 deleteIfQueueing 并发<b>互斥</b>（一方成一方 0）。
 */
class ConsultRequestCasIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ConsultRequestRepository requests;
    @Autowired
    private PlatformTransactionManager txManager;

    private ConsultRequest seedQueueing() {
        long userId = newUser().getId();
        return requests.save(ConsultRequest.queue(userId, 1L,
                "req-" + SEQ.incrementAndGet(), Instant.now().plus(Duration.ofMinutes(1))));
    }

    @Test
    void concurrentTryAcceptExactlyOneWins() throws Exception {
        long id = seedQueueing().getId();
        Instant payDeadline = Instant.now().plus(Duration.ofSeconds(90));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger wins = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            final long vetId = 100 + i;
            pool.submit(() -> {
                try {
                    start.await();
                    Integer r = tx.execute(s -> requests.tryAccept(id, vetId, payDeadline));
                    if (r != null && r == 1) {
                        wins.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 竞争落败
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(wins.get()).isEqualTo(1); // 先到先得，恰一次
        ConsultRequest after = requests.findById(id).orElseThrow();
        assertThat(after.getState()).isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY);
        assertThat(after.getVetId()).isNotNull();
        assertThat(after.getPayDeadlineAt()).isNotNull();
    }

    @Test
    void acceptAndCancelAreMutuallyExclusive() throws Exception {
        long id = seedQueueing().getId();
        Instant payDeadline = Instant.now().plus(Duration.ofSeconds(90));
        TransactionTemplate tx = new TransactionTemplate(txManager);

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger acceptWon = new AtomicInteger();
        AtomicInteger cancelWon = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        pool.submit(() -> {
            try {
                start.await();
                Integer r = tx.execute(s -> requests.tryAccept(id, 200L, payDeadline));
                if (r != null && r == 1) acceptWon.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        pool.submit(() -> {
            try {
                start.await();
                Integer r = tx.execute(s -> requests.deleteIfQueueing(id));
                if (r != null && r == 1) cancelWon.incrementAndGet();
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        });
        start.countDown();
        done.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        // 单列 CAS：接单与取消对同一 QUEUEING 行恰一方生效（H-4）。
        assertThat(acceptWon.get() + cancelWon.get()).isEqualTo(1);
        if (cancelWon.get() == 1) {
            assertThat(requests.findById(id)).isEmpty(); // 取消胜 → 行已删
        } else {
            assertThat(requests.findById(id).orElseThrow().getState())
                    .isEqualTo(ConsultRequestState.ACCEPTED_AWAIT_PAY); // 接单胜 → 已接单
        }
    }
}
