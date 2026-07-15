package com.tailtopia.profile.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.auth.domain.User;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1（需 Docker postgres）。Story 6.1 号池分配 / 回收 / 并发不撞号（AC1）。
 *
 * <p>从 1 自增由迁移 {@code CREATE SEQUENCE pet_serial_seq START 1}（静态可核，L0）保证；共享测试库序列
 * 已被前序运行推进，故此处不断言精确值，而验行为：连续分配单调递增 + 释放回池优先复用 + 并发全互异。
 */
class SerialAllocationIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private SerialAllocationService serialAllocation;
    @Autowired
    private PetProfileRepository petProfiles;
    @Autowired
    private ProfileDeletionService profileDeletion;
    @Autowired
    private JdbcTemplate jdbc;

    private long n() {
        return SEQ.incrementAndGet();
    }

    @Test
    void allocatesMonotonicWhenPoolEmpty() {
        jdbc.update("DELETE FROM pet_serial_pool");
        long a = serialAllocation.allocate();
        long b = serialAllocation.allocate();
        assertThat(b).isGreaterThan(a);
    }

    @Test
    void releasedSerialIsRecycledFirst() {
        jdbc.update("DELETE FROM pet_serial_pool");
        long a = serialAllocation.allocate();
        long b = serialAllocation.allocate(); // b > a，池空时都来自序列
        assertThat(b).isGreaterThan(a);

        serialAllocation.release(a); // a 回池
        long c = serialAllocation.allocate(); // 优先复用池中最小号 → a
        assertThat(c).isEqualTo(a);

        // 池已空，下一个又回到序列（> b）。
        long d = serialAllocation.allocate();
        assertThat(d).isGreaterThan(b);
    }

    @Test
    void concurrentAllocationsAreAllDistinct() throws InterruptedException {
        int threads = 20;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Set<Long> results = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    results.add(serialAllocation.allocate());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        // 并发不撞号：advisory 锁串行化分配 → 全部互异。
        assertThat(results).hasSize(threads);
    }

    @Test
    void deletingProfileReleasesSerialBackToPool() {
        jdbc.update("DELETE FROM pet_serial_pool");
        User owner = newUser();
        long serial = serialAllocation.allocate();
        PetProfile pet = PetProfile.create(owner.getId(), PetType.CAT, "Momo", null, null, null, null,
                "tok-" + n());
        pet.assignSerial(serial);
        petProfiles.save(pet);

        // 级联删除（复用注销 / 主动删档同一路径）→ 号回收入池，同事务原子。
        profileDeletion.deleteByUserId(owner.getId());

        List<Long> inPool = jdbc.queryForList("SELECT serial_id FROM pet_serial_pool", Long.class);
        assertThat(inPool).contains(serial);

        // 复用验证：把池收敛到只剩该号，下次分配即返回它。
        jdbc.update("DELETE FROM pet_serial_pool WHERE serial_id <> ?", serial);
        assertThat(serialAllocation.allocate()).isEqualTo(serial);
    }
}
