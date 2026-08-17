package com.tailtopia.admin.shop.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.domain.InventoryMovement;
import com.tailtopia.shop.domain.InventoryMovementType;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.LocalDate;
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
 * L1 集成：库存管理与采购入库（Story 1.4，AB-10C）。上下文启动即验 Flyway V104 + validate。
 *
 * <p>🔒 核心断言：<b>报损上限是可售库存、盘点下限是锁定库存</b>——这两条是本 Story 唯一能破坏
 * {@code locked <= actual} 不变式的地方（决策 S-3：真正的超卖来源是盘点/报损，不是并发）。
 *
 * <p>⚠️ 需真实 PostgreSQL + Redis（{@code ApiIntegrationTest} 无 Testcontainers）。
 */
class AdminShopInventoryEndpointIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private InventoryMovementService service;
    @Autowired
    private SkuInventoryRepository inventory;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    /** 造一个商品 + 一个 SKU + 指定 actual/locked，返回 sku_id。 */
    private long seedSku(long actual, long locked) {
        String pToken = "iv" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'x', 'y', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        String sToken = "is" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                SELECT ?, id, '3 kg', 285000 FROM shop_products WHERE public_token = ?
                """, sToken, pToken);
        Long skuId = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, ?)",
                skuId, actual, locked);
        return skuId;
    }

    /** 造一条采购入库历史（供退货入库带出单价用）。 */
    private void seedPurchaseHistory(long skuId, long costPrice) {
        service.receivePurchase(skuId, 1L, "PO-SEED", "供应商", costPrice, LocalDate.now(), ACTOR);
    }

    private long actualOf(long skuId) {
        return inventory.findBySkuId(skuId).orElseThrow().getActual();
    }

    // ---------- AC2 / V104 ----------

    @Test
    @DisplayName("V104 已应用：inventory_movements 表与一致性 CHECK 生效")
    void migrationApplied() {
        Integer cnt = jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.tables
                 WHERE table_schema = 'public' AND table_name = 'inventory_movements'
                """, Integer.class);
        assertThat(cnt).isEqualTo(1);

        long skuId = seedSku(0, 0);
        // 🔴 after ≠ before + delta 必须被 DB 拒绝——让「流水与实际对不上」根本写不进去
        assertThat(insertFails("""
                INSERT INTO inventory_movements (sku_id, movement_type, qty_delta,
                        actual_before, actual_after, reason, operator_account_id)
                VALUES (?, 'DAMAGE', -5, 10, 9, '手写不一致', 1)
                """, skuId)).as("delta 不一致必须被 DB 拒绝").isTrue();

        // 🔴 S-9：入库类缺采购单号或单价必须被拒
        assertThat(insertFails("""
                INSERT INTO inventory_movements (sku_id, movement_type, qty_delta,
                        actual_before, actual_after, operator_account_id)
                VALUES (?, 'PURCHASE_INBOUND', 5, 0, 5, 1)
                """, skuId)).as("入库缺采购单号/单价必须被 DB 拒绝").isTrue();

        // 🔴 报损/盘点缺原因必须被拒
        assertThat(insertFails("""
                INSERT INTO inventory_movements (sku_id, movement_type, qty_delta,
                        actual_before, actual_after, operator_account_id)
                VALUES (?, 'DAMAGE', -1, 5, 4, 1)
                """, skuId)).as("报损缺原因必须被 DB 拒绝").isTrue();
    }

    // ---------- AC3 采购入库 / 退货入库 ----------

    @Test
    @DisplayName("采购入库：actual 增加且流水留痕（含操作人与前后值）")
    void purchaseIncreasesActualAndRecordsMovement() {
        long skuId = seedSku(0, 0);

        service.receivePurchase(skuId, 10L, "PO-001", "供应商A", 190_000L, LocalDate.now(), ACTOR);

        assertThat(actualOf(skuId)).isEqualTo(10L);
        InventoryMovement m = service.recentMovements(skuId, 1).getFirst();
        assertThat(m.getMovementType()).isEqualTo(InventoryMovementType.PURCHASE_INBOUND);
        assertThat(m.getActualBefore()).isZero();
        assertThat(m.getActualAfter()).isEqualTo(10L);
        assertThat(m.getCostPrice()).isEqualTo(190_000L);
        assertThat(m.getOperatorAccountId()).isEqualTo(ACTOR);
    }

    @Test
    @DisplayName("🔴 S-9 拒收货：记为退货入库批次，单号填原订单号，单价自动取最近一次采购价")
    void returnInboundFollowsS9() {
        long skuId = seedSku(0, 0);
        seedPurchaseHistory(skuId, 188_000L);

        service.receiveReturn(skuId, 2L, "ORD-REJECTED-1", LocalDate.now(), ACTOR);

        InventoryMovement m = service.recentMovements(skuId, 1).getFirst();
        assertThat(m.getMovementType()).isEqualTo(InventoryMovementType.RETURN_INBOUND);
        assertThat(m.getPurchaseNo()).isEqualTo("ORD-REJECTED-1");
        assertThat(m.getCostPrice()).isEqualTo(188_000L);
        assertThat(actualOf(skuId)).isEqualTo(3L);   // 1 (seed) + 2
    }

    @Test
    @DisplayName("🔴 无采购历史的 SKU 退货入库 → 拒绝，绝不以 0 成本入库")
    void returnInboundRejectedWithoutPurchaseHistory() {
        long skuId = seedSku(0, 0);

        assertThatThrownBy(() ->
                service.receiveReturn(skuId, 2L, "ORD-X", LocalDate.now(), ACTOR))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("尚无采购入库记录");

        assertThat(actualOf(skuId)).isZero();
        assertThat(service.recentMovements(skuId, 10)).isEmpty();
    }

    // ---------- AC4 报损边界：上限是可售 ----------

    @Test
    @DisplayName("🔒 actual=10 locked=7：报损 3 件成功（恰好等于可售）")
    void writeOffExactlyAvailableSucceeds() {
        long skuId = seedSku(10, 7);

        service.writeOff(skuId, 3L, "仓库进水", ACTOR);

        assertThat(actualOf(skuId)).isEqualTo(7L);
        assertThat(inventory.findBySkuId(skuId).orElseThrow().available()).isZero();
    }

    @Test
    @DisplayName("🔒 actual=10 locked=7：报损 4 件被拒（会使 locked > actual，等于把已卖的货销账）")
    void writeOffBeyondAvailableRejected() {
        long skuId = seedSku(10, 7);

        assertThatThrownBy(() -> service.writeOff(skuId, 4L, "仓库进水", ACTOR))
                .isInstanceOf(AppException.class);

        assertThat(actualOf(skuId)).as("被拒后库存一分不动").isEqualTo(10L);
        assertThat(service.recentMovements(skuId, 10)).as("被拒不落流水").isEmpty();
    }

    // ---------- AC4 盘点边界：下限是 locked ----------

    @Test
    @DisplayName("🔒 actual=10 locked=7：盘到 7 成功（恰好等于锁定量）")
    void stocktakeToLockedSucceeds() {
        long skuId = seedSku(10, 7);

        service.stocktake(skuId, 7L, "月度盘点", ACTOR);

        assertThat(actualOf(skuId)).isEqualTo(7L);
        InventoryMovement m = service.recentMovements(skuId, 1).getFirst();
        assertThat(m.getActualBefore()).isEqualTo(10L);
        assertThat(m.getActualAfter()).isEqualTo(7L);
        assertThat(m.getQtyDelta()).isEqualTo(-3L);
    }

    @Test
    @DisplayName("🔒 actual=10 locked=7：盘到 6 被拒（低于锁定量）")
    void stocktakeBelowLockedRejected() {
        long skuId = seedSku(10, 7);

        assertThatThrownBy(() -> service.stocktake(skuId, 6L, "月度盘点", ACTOR))
                .isInstanceOf(AppException.class);

        assertThat(actualOf(skuId)).isEqualTo(10L);
        assertThat(service.recentMovements(skuId, 10)).isEmpty();
    }

    @Test
    @DisplayName("盘盈：盘到 15 成功，流水 delta 为正")
    void stocktakeUpwards() {
        long skuId = seedSku(10, 0);

        service.stocktake(skuId, 15L, "盘盈", ACTOR);

        assertThat(actualOf(skuId)).isEqualTo(15L);
        assertThat(service.recentMovements(skuId, 1).getFirst().getQtyDelta()).isEqualTo(5L);
    }

    // ---------- 🔒 并发：报损不得突破不变式 ----------

    @Test
    @DisplayName("🔒 20 线程各报损 1 件、可售仅 5：成功恰 5，且 locked 一件不少")
    void concurrentWriteOffNeverBreaksInvariant() throws Exception {
        long skuId = seedSku(12, 7);      // 可售 = 5
        int threads = 20;

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        AtomicInteger unexpected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    start.await();
                    service.writeOff(skuId, 1L, "并发报损", ACTOR);
                    ok.incrementAndGet();
                } catch (AppException e) {
                    rejected.incrementAndGet();      // 可售不足，是预期结果
                } catch (Exception e) {
                    unexpected.incrementAndGet();    // 落到 DB CHECK 才被拒 → 应用层条件失效
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ok.get()).as("成功次数恰为可售库存").isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(15);
        // 🔴 判别性断言（沿用 Story 1.2 的教训）：失败必须全部来自应用层条件，
        //    不得有请求越过它、靠 DB CHECK 兜底——否则本测试无法区分两层谁在起作用。
        assertThat(unexpected.get())
                .as("不得有请求越过应用层条件、靠 DB CHECK 兜底")
                .isZero();

        var row = inventory.findBySkuId(skuId).orElseThrow();
        assertThat(row.getActual()).isEqualTo(7L);
        assertThat(row.getLocked()).as("锁定量一件不少——已卖出的货没被报损掉").isEqualTo(7L);
        assertThat(row.available()).isZero();
    }

    // ---------- 端点：权限门控 ----------

    @Test
    @DisplayName("未登录访问库存管理页 → 重定向登录，不泄露任何库存数据")
    void inventoryPageRequiresLogin() throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request
                        .MockMvcRequestBuilders.get("/admin/shop/inventory"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .status().is3xxRedirection());
    }

    private boolean insertFails(String sql, Object... args) {
        try {
            jdbc.update(sql, args);
            return false;
        } catch (Exception e) {
            return true;
        }
    }
}
