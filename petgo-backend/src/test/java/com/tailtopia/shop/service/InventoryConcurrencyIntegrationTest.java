package com.tailtopia.shop.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1 并发：库存防超卖（Story 1.2 AC2，🔒 安全攸关）。
 *
 * <p>这是本 Story <b>唯一能真正证明防超卖成立</b>的测试——L0 只能证明「影响 0 行时抛错」，
 * 证明不了「并发下不会有两个线程同时通过条件」。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis（{@code ApiIntegrationTest} 无 Testcontainers）。
 */
class InventoryConcurrencyIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private InventoryService inventory;
    @Autowired
    private SkuInventoryRepository repo;
    @Autowired
    private JdbcTemplate jdbc;

    /** 造一个商品 + 一个 SKU + 指定实际库存，返回 sku_id。 */
    private long seedSku(long actual) {
        String pToken = "cc" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'x', 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        String sToken = "cs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                SELECT ?, id, '3 kg', 285000 FROM shop_products WHERE public_token = ?
                """, sToken, pToken);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                skuId, actual);
        return skuId;
    }

    @Test
    @DisplayName("🔒 50 线程抢 10 件：成功恰 10 次，locked 恰 10，绝不超卖")
    void fiftyThreadsCompeteForTenUnits() throws Exception {
        long skuId = seedSku(10);
        int threads = 50;

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger soldOut = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    inventory.lock(skuId, 1L);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    soldOut.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        // 🔴 核心断言：成功次数恰为库存数，一件都不能多
        assertThat(ok.get()).isEqualTo(10);
        assertThat(soldOut.get()).isEqualTo(40);

        var row = repo.findBySkuId(skuId).orElseThrow();
        assertThat(row.getLocked()).isEqualTo(10L);
        assertThat(row.getActual()).isEqualTo(10L);
        assertThat(row.available()).isZero();
    }

    @Test
    @DisplayName("🔒 并发按不同数量抢：总锁定量不得超过实际库存")
    void concurrentVaryingQuantitiesNeverOversell() throws Exception {
        long skuId = seedSku(20);
        int threads = 40;

        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger lockedTotal = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            int qty = (i % 3) + 1;   // 1 / 2 / 3 件
            pool.submit(() -> {
                try {
                    start.await();
                    inventory.lock(skuId, qty);
                    lockedTotal.addAndGet(qty);
                } catch (Exception ignored) {
                    // 售罄是预期结果
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        var row = repo.findBySkuId(skuId).orElseThrow();
        assertThat(row.getLocked()).isEqualTo((long) lockedTotal.get());
        // 🔴 不变式：锁定量绝不超过实际库存，可售绝不为负
        assertThat(row.getLocked()).isLessThanOrEqualTo(row.getActual());
        assertThat(row.available()).isGreaterThanOrEqualTo(0L);
    }

    @Test
    @DisplayName("四条原语的完整流转：入库 → 锁定 → 出库 / 释放")
    void fullLifecycle() {
        long skuId = seedSku(0);

        inventory.restock(skuId, 10);
        assertThat(repo.findBySkuId(skuId).orElseThrow().available()).isEqualTo(10L);

        inventory.lock(skuId, 4);
        var afterLock = repo.findBySkuId(skuId).orElseThrow();
        assertThat(afterLock.getLocked()).isEqualTo(4L);
        assertThat(afterLock.available()).isEqualTo(6L);

        inventory.commit(skuId, 3);           // 出库 3
        var afterCommit = repo.findBySkuId(skuId).orElseThrow();
        assertThat(afterCommit.getActual()).isEqualTo(7L);
        assertThat(afterCommit.getLocked()).isEqualTo(1L);
        assertThat(afterCommit.available()).isEqualTo(6L);

        inventory.release(skuId, 1);          // 释放剩余 1
        var afterRelease = repo.findBySkuId(skuId).orElseThrow();
        assertThat(afterRelease.getLocked()).isZero();
        assertThat(afterRelease.available()).isEqualTo(7L);
    }

    @Test
    @DisplayName("🔴 DB CHECK 兜底：即便绕过应用层，也无法造出 locked > actual 或负库存")
    void dbCheckIsTheLastLine() {
        long skuId = seedSku(5);

        assertThat(updateFails("UPDATE sku_inventory SET locked = 99 WHERE sku_id = ?", skuId))
                .as("locked > actual 必须被 DB 拒绝").isTrue();
        assertThat(updateFails("UPDATE sku_inventory SET actual = -1 WHERE sku_id = ?", skuId))
                .as("actual 为负必须被 DB 拒绝").isTrue();
        assertThat(updateFails("UPDATE sku_inventory SET locked = -1 WHERE sku_id = ?", skuId))
                .as("locked 为负必须被 DB 拒绝").isTrue();
    }

    private boolean updateFails(String sql, long skuId) {
        try {
            jdbc.update(sql, skuId);
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
