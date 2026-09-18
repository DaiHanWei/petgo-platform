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

    // ---------- Story 4-1：部分结算（SHOP-FR-04 / AD-S6 / SD-6） ----------

    @Test
    @DisplayName("🔴🔴 三件选两件：订单只含 2 行、金额是 2 行合计、第三行**下单后仍在车里**、库存只锁 2 件")
    void placingOrderTakesOnlySelectedLines() {
        long uid = seedUser();
        String a = seedSku(10, 100_000L);
        String b = seedSku(10, 50_000L);
        String c = seedSku(10, 30_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, a, 2);   // 200.000
        carts.add(uid, b, 1);   //  50.000
        carts.add(uid, c, 3);   //  90.000（不买）

        carts.setSelected(uid, c, false);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);

        assertThat(orderLines.findByOrderIdOrderByIdAsc(o.getId())).hasSize(2);
        assertThat(o.getGoodsSubtotal())
                .as("订单金额必须是选中两行的合计，不是全车合计")
                .isEqualTo(250_000L);

        // 🔴 本 story 最容易漏的一处：清车循环若还遍历 cart.lines()，
        //    未选中的行会被连带删掉 —— 用户会发现购物车里的东西凭空消失且无从追回。
        var cart = carts.view(uid);
        assertThat(cart.lines()).hasSize(1);
        assertThat(cart.lines().getFirst().skuToken())
                .as("没勾的行是「这次不买」，不是「不要了」")
                .isEqualTo(c);

        // 🔴 库存只锁选中的：未选中行分毫不动。
        assertThat(inventory.findBySkuId(skuId(a)).orElseThrow().getLocked()).isEqualTo(2L);
        assertThat(inventory.findBySkuId(skuId(b)).orElseThrow().getLocked()).isEqualTo(1L);
        assertThat(inventory.findBySkuId(skuId(c)).orElseThrow().getLocked())
                .as("没勾的行库存分毫不动")
                .isZero();
    }

    @Test
    @DisplayName("🔴 车里有货但一件都没勾 → 422「请至少选择一件商品」，**与「购物车为空」区分开**")
    void nothingSelectedIsItsOwnError() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, sku, 2);
        carts.setAllSelected(uid, false);

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(AppException.class)
                .as("给他一句「购物车为空」，他会去找一辆并不空的空车")
                .hasMessageContaining("请至少选择一件商品")
                .hasMessageNotContainingAny("购物车为空");
    }

    @Test
    @DisplayName("空车仍走既有「购物车为空」分支，两条错误并存且文案不同")
    void trulyEmptyCartKeepsItsOwnMessage() {
        long uid = seedUser();
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("购物车为空");
    }

    @Test
    @DisplayName("整车失效仍报逐行明细，不报「购物车为空」也不报「请至少选择一件」")
    void wholeCartInvalidStillReportsLineDetails() {
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, sku, 2);
        listing.delist(jdbc.queryForObject(
                "SELECT product_id FROM shop_skus WHERE public_token = ?", Long.class, sku), ACTOR);

        // cart.lines() 空但 invalidLines 非空 —— 新的「一件没勾」判定不得吃掉这条既有语义。
        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(CheckoutUnavailableException.class);
    }

    @Test
    @DisplayName("🎯 整车失效【且全部没勾】→ 422，绝不落一张零商品却带运费的单")
    void wholeCartInvalidAndNothingSelectedNeverCreatesZeroLineOrder() {
        // 🔴 v1.3.0 shop-v2 复审 #2：这是三道检查同时漏掉的那条缝。
        //    ① 空车检查看的是「lines 与 invalidLines 都空」—— 这里 invalidLines 非空，放行；
        //    ② 原「一件没勾」判定写的是 selected.isEmpty() && !cart.lines().isEmpty()，
        //       整车失效时 lines() 恰好为空，判定自己失效；
        //    ③ collectUnavailable 按 !l.selected() 跳过没勾的失效行，收集结果为空。
        //    于是一路走到建单，落库一张 goodsSubtotal=0、却带全额运费的 PENDING_PAYMENT 单
        //    —— 用户被要求为「什么都没有」付运费，订单中心与对账口径同时被污染。
        long uid = seedUser();
        String sku = seedSku(10, 100_000L);
        // 🔴 运费门槛设成够不着，确保真有运费 —— 门槛为 0 时运费也是 0，
        //    那样即便 bug 复发，落库的也是一张「0 元」单，这条测试会假绿。
        zones.setFreeShippingThreshold(9_999_999L, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, sku, 2);
        listing.delist(jdbc.queryForObject(
                "SELECT product_id FROM shop_skus WHERE public_token = ?", Long.class, sku), ACTOR);
        carts.setAllSelected(uid, false);

        long ordersBefore = jdbc.queryForObject("SELECT count(*) FROM shop_orders", Long.class);

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(AppException.class)
                .hasMessageContaining("请至少选择一件商品");

        // 🎯 只断言抛异常不够：真正要守的是「没有单被落下来」。
        //    把 nothingSelected 改回原来的 && !cart.lines().isEmpty()，这一条必须红。
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shop_orders", Long.class))
                .as("🎯 零商品订单一张都不许落库")
                .isEqualTo(ordersBefore);
    }

    @Test
    @DisplayName("🔴 没勾的失效行不挡结算 —— 这正是「先删掉再买」那个老毛病的根")
    void unselectedInvalidLineDoesNotBlockCheckout() {
        long uid = seedUser();
        String good = seedSku(10, 100_000L);
        String dead = seedSku(10, 50_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, good, 1);
        carts.add(uid, dead, 1);
        listing.delist(jdbc.queryForObject(
                "SELECT product_id FROM shop_skus WHERE public_token = ?", Long.class, dead),
                ACTOR);
        carts.setSelected(uid, dead, false);

        ShopOrder o = checkout.placeOrder(uid, addr, null, null);

        assertThat(o.getGoodsSubtotal()).isEqualTo(100_000L);
        assertThat(carts.view(uid).invalidLines())
                .as("下架行还留在车里给用户看（失效行不静默消失）")
                .hasSize(1);
    }

    @Test
    @DisplayName("🔴 勾着的失效行照旧 409 逐行明细（选中集里有不可买的东西就得拦）")
    void selectedInvalidLineStillBlocks() {
        long uid = seedUser();
        String good = seedSku(10, 100_000L);
        String dead = seedSku(10, 50_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 0L);
        carts.add(uid, good, 1);
        carts.add(uid, dead, 1);
        listing.delist(jdbc.queryForObject(
                "SELECT product_id FROM shop_skus WHERE public_token = ?", Long.class, dead),
                ACTOR);

        assertThatThrownBy(() -> checkout.placeOrder(uid, addr, null, null))
                .isInstanceOf(CheckoutUnavailableException.class)
                .satisfies(e -> assertThat(((CheckoutUnavailableException) e).getLines())
                        .extracting(UnavailableLine::reason)
                        .containsExactly(UnavailableLine.REASON_DELISTED));
    }

    /**
     * 🔴🔴 <b>AC7 老版本兼容（SHOP-NFR-04）—— 本用例代表线上正在跑的老版本 App。</b>
     *
     * <p>老版本 App <b>从不调用</b>两个选择端点（它的界面上根本没有勾选框）。
     * 本用例全程只走加购 → 下单，断言结果与 Story 4-1 改动前逐项一致：
     * 全部行进订单、金额是全车合计、库存全锁、车被清空。
     *
     * <p><b>删掉这个测试方法，等于删掉了老版本兼容性保证</b> ——
     * 它一旦变红，说明某次改动让「没点过勾选框的客户端」行为发生了变化，
     * 而那些用户没有任何办法察觉或纠正。
     */
    @Test
    @DisplayName("🔴 AC7 老版本兼容：全程不调选择端点 → 行为与改动前逐项一致")
    void legacyClientNeverTouchingSelectionBehavesAsBefore() {
        long uid = seedUser();
        String a = seedSku(10, 100_000L);
        String b = seedSku(10, 50_000L);
        zones.setFreeShippingThreshold(0, ACTOR);
        String addr = seedAddress(uid, 20_000L);

        // ——— 老版本能做的全部动作：加购。没有 setSelected / setAllSelected。———
        carts.add(uid, a, 2);
        carts.add(uid, b, 3);

        var cart = carts.view(uid);
        assertThat(cart.lines()).allSatisfy(l -> assertThat(l.selected())
                .as("老版本看到的每一行都必须是选中态，否则它会漏单且用户毫无察觉")
                .isTrue());
        assertThat(cart.selectedSubtotal())
                .as("没点过勾选框 ⇒ 选中合计恒等于 subtotal")
                .isEqualTo(cart.subtotal());
        assertThat(cart.selectedCount()).isEqualTo(cart.itemCount());

        var preview = checkout.preview(uid, addr);
        assertThat(preview.cart().subtotal()).isEqualTo(350_000L);

        ShopOrder o = checkout.placeOrder(uid, addr, null, null);

        assertThat(orderLines.findByOrderIdOrderByIdAsc(o.getId()))
                .as("全部行进订单").hasSize(2);
        assertThat(o.getGoodsSubtotal()).as("金额是全车合计").isEqualTo(350_000L);
        assertThat(o.getShippingFee()).isEqualTo(20_000L);
        assertThat(inventory.findBySkuId(skuId(a)).orElseThrow().getLocked()).isEqualTo(2L);
        assertThat(inventory.findBySkuId(skuId(b)).orElseThrow().getLocked()).isEqualTo(3L);
        assertThat(carts.view(uid).lines()).as("车被清空").isEmpty();
    }
}
