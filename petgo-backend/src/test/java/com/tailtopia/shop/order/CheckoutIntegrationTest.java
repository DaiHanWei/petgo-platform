package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.admin.shop.service.AdminShopListingService;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.CheckoutUnavailableException;
import com.tailtopia.shop.order.dto.UnavailableLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
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
import org.springframework.test.context.TestPropertySource;

/** L1：结算与下单（Story 3.4）——把购物车/地址/运费/库存/拆分串起来。 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class CheckoutIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private CheckoutService checkout;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopListingService listing;
    @Autowired
    private SkuInventoryRepository inventory;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "co" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "co" + n);
    }

    /** 已上架 + 有库存的 SKU。 */
    private String seedSku(long stock, long price) {
        String pToken = "op" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n',
                        'NO_RETURN_AFTER_OPEN', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "os" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', ?)""", sToken, pid, price);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, ?, 0)",
                sid, stock);
        return sToken;
    }

    private long skuId(String token) {
        return jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, token);
    }

    /** 造地址并保证其 Kecamatan 可配送。 */
    private String seedAddress(long uid, long fee) {
        String kec = "Kec" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", fee, ACTOR);
        return addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
    }

    // ---------- 主流程 ----------

    @Test
    @DisplayName("🔗 下单成功：订单 PENDING_PAYMENT + 库存被锁 + 购物车清空 + 归因落库")
    void placeOrderLocksStockAndRecordsAttribution() {
        long uid = seedUser();
        String sku = seedSku(10, 285_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, sku, 2);

        ShopOrder o = checkout.placeOrder(uid, addr, "TOKO_ALL_FEATURED", null);

        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_PAYMENT);
        assertThat(o.getGoodsSubtotal()).isEqualTo(570_000L);
        assertThat(o.getShippingFee()).isEqualTo(20_000L);
        assertThat(o.getTotalAmount()).isEqualTo(590_000L);

        // 🔴 库存被锁定而非扣减（此时尚未付款）
        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getLocked()).isEqualTo(2L);
        assertThat(inv.getActual()).as("下单不扣实际库存").isEqualTo(10L);

        // 🔴 归因随订单行落库（AB-13B 的服务端权威依据）
        var lines = orderLines.findByOrderIdOrderByIdAsc(o.getId());
        assertThat(lines).hasSize(1);
        assertThat(lines.getFirst().getEntrySource()).isEqualTo("TOKO_ALL_FEATURED");

        // 已下单的行从车里移除
        assertThat(carts.view(uid).lines()).isEmpty();
    }

    @Test
    @DisplayName("🔴 地址超服务范围 → 阻断下单（保存地址时不校验，这里才拦）")
    void outOfRangeAddressBlocksCheckout() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        carts.add(uid, sku, 1);
        // 地址落在从未配置的 Kecamatan
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789",
                "DKI Jakarta", "Jakarta Selatan", "Nowhere" + SEQ.incrementAndGet(),
                "Jl. X", "12160", null)).getPublicToken();

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, "TOKO", null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("暂不配送至");
    }

    // ---------- 🔴 第二次库存校验：逐行报，不整单打回 ----------

    @Test
    @DisplayName("🔴 库存不足时报出【具体哪个 SKU】及可售量，不是笼统的一句话")
    void insufficientStockReportsWhichSku() {
        long uid = seedUser();
        String ok = seedSku(10, 100_000L);
        String tight = seedSku(5, 200_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, ok, 2);
        carts.add(uid, tight, 5);

        // 加购后、结算前，别人买走了 3 件
        jdbc.update("UPDATE sku_inventory SET actual = 2 WHERE sku_id = ?", skuId(tight));

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, "TOKO", null))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> {
                    var lines = ((CheckoutUnavailableException) e).getLines();
                    assertThat(lines).hasSize(1);
                    UnavailableLine l = lines.getFirst();
                    assertThat(l.skuToken()).isEqualTo(tight);
                    assertThat(l.reason()).isEqualTo(UnavailableLine.REASON_INSUFFICIENT_STOCK);
                    assertThat(l.available()).isEqualTo(2L);
                    assertThat(l.requested()).isEqualTo(5);
                });

        // 🔴 整单未建、库存一件没锁 —— 失败必须干净
        assertThat(inventory.findBySkuId(skuId(ok)).orElseThrow().getLocked()).isZero();
    }

    @Test
    @DisplayName("🔴 多行都有问题时一次报全 —— 让用户在一车商品里逐个试错是把成本转嫁给他")
    void allProblemLinesReportedAtOnce() {
        long uid = seedUser();
        String a = seedSku(1, 100_000L);
        String b = seedSku(1, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, a, 1);
        carts.add(uid, b, 1);
        jdbc.update("UPDATE sku_inventory SET actual = 0 WHERE sku_id IN (?, ?)",
                skuId(a), skuId(b));

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, "TOKO", null))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> assertThat(((CheckoutUnavailableException) e).getLines())
                        .hasSize(2));
    }

    // ---------- 🔒 并发：只有库存数量的订单能建成 ----------

    @Test
    @DisplayName("🔒 20 人抢 5 件：恰 5 单建成，locked 恰 5，绝不超卖")
    void concurrentCheckoutNeverOversells() throws Exception {
        String sku = seedSku(5, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        int threads = 20;

        // 每人一辆车、一个地址
        long[] users = new long[threads];
        String[] addrs = new String[threads];
        for (int i = 0; i < threads; i++) {
            users[i] = seedUser();
            addrs[i] = seedAddress(users[i], 20_000L);
            carts.add(users[i], sku, 1);
        }

        AtomicInteger ok = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            final int idx = i;
            pool.submit(() -> {
                try {
                    start.await();
                    checkout.placeOrder(users[idx], addrs[idx], "TOKO", null);
                    ok.incrementAndGet();
                } catch (Exception e) {
                    rejected.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertThat(done.await(60, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(ok.get()).as("成功建单数恰为库存数").isEqualTo(5);
        assertThat(rejected.get()).isEqualTo(15);

        var inv = inventory.findBySkuId(skuId(sku)).orElseThrow();
        assertThat(inv.getLocked()).isEqualTo(5L);
        assertThat(inv.available()).isZero();
        assertThat(inv.getLocked()).isLessThanOrEqualTo(inv.getActual());
    }

    // ---------- 支付拆分固化 ----------

    @Test
    @DisplayName("余额为 0 → channel = QRIS，拆分列为纯现金（不阻断下单）")
    void zeroBalanceProducesPureCashOrder() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, sku, 1);

        ShopOrder o = checkout.placeOrder(uid, addr, "TOKO", null);
        assertThat(o.getPayChannel()).isEqualTo(PayChannel.QRIS);
        assertThat(o.getCoinAmount()).isZero();
        assertThat(o.getCashAmount()).isEqualTo(o.getTotalAmount());
    }

    @Test
    @DisplayName("🔴 DB 强制：订单上的 coin + cash 必须等于 total_amount")
    void orderSplitSumEnforcedByDb() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, "TOKO", null);

        boolean rejected;
        try {
            jdbc.update("UPDATE shop_orders SET coin_amount = 1, cash_amount = 1 WHERE id = ?",
                    o.getId());
            rejected = false;
        } catch (Exception e) {
            rejected = true;
        }
        assertThat(rejected).as("ck_shop_orders_split_sum 必须拦住对不平的拆分").isTrue();
    }
}
