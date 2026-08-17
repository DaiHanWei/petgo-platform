package com.tailtopia.shop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tailtopia.pay.domain.PawCoinTxnType;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.address.domain.AddressFields;
import com.tailtopia.shop.address.service.ShippingAddressService;
import com.tailtopia.shop.cart.service.CartService;
import com.tailtopia.shop.order.domain.Carrier;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.AdminShopPawcoinRulesService;
import com.tailtopia.shop.order.service.CheckoutService;
import com.tailtopia.shop.order.service.ShopOrderFulfillmentService;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import com.tailtopia.shop.review.domain.ReviewStatus;
import com.tailtopia.shop.review.domain.ShopReview;
import com.tailtopia.shop.review.service.ShopReviewService;
import com.tailtopia.shop.shipping.service.AdminShippingZoneService;
import com.tailtopia.support.ApiIntegrationTest;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * L1：Epic 7 全链路联调（Story 7.4）。
 *
 * <p>走的链路：<b>下单 → 完成 → 评价 → 出现在商品详情页 → 再评被拒 → 违规内容被拦且可改后重提</b>。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class Epic7ChainIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopReviewService reviews;
    @Autowired
    private CheckoutService checkout;
    @Autowired
    private ShopOrderPaymentService payments;
    @Autowired
    private ShopOrderFulfillmentService fulfillment;
    @Autowired
    private CartService carts;
    @Autowired
    private ShippingAddressService addresses;
    @Autowired
    private AdminShippingZoneService zones;
    @Autowired
    private AdminShopPawcoinRulesService rules;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private JdbcTemplate jdbc;

    private static final long ACTOR = 1L;

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "e7" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "e7" + n);
    }

    private String seedSku() {
        String pToken = "e7p" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "e7s" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', 100000)""", sToken, pid);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        return sToken;
    }

    /** 走完整链路把订单推到 COMPLETED。 */
    private Ctx completedOrder(String skuToken) {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e7-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ke7" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, skuToken, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.confirmReceipt(uid, o.getPublicToken());
        ShopOrder reloaded = orders.findByPublicToken(o.getPublicToken()).orElseThrow();
        ShopOrderLine line = orderLines.findByOrderIdOrderByIdAsc(reloaded.getId()).get(0);
        return new Ctx(uid, reloaded, line);
    }

    private record Ctx(long userId, ShopOrder order, ShopOrderLine line) {
    }

    private long productIdOf(String skuToken) {
        return jdbc.queryForObject("SELECT product_id FROM shop_skus WHERE public_token = ?",
                Long.class, skuToken);
    }

    @Test
    @DisplayName("🔴 全程：完成订单 → 评价 → 出现在详情页 → 再评同一 SKU 被唯一约束拒")
    void reviewAppearsOnProductAndCannotBeSubmittedTwice() {
        String sku = seedSku();
        long productId = productIdOf(sku);
        Ctx c = completedOrder(sku);

        // 评价前：空态，且平均分为 null（🔴 不是 0）
        assertThat(reviews.publishedFor(productId, 10)).isEmpty();
        assertThat(reviews.averageRating(productId)).isNull();

        ShopReview r = reviews.submit(c.userId(), c.order().getPublicToken(), c.line().getId(),
                5, "Anjing saya suka banget", List.of());
        assertThat(r.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);

        // 出现在该商品详情页
        var list = reviews.publishedFor(productId, 10);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).getRating()).isEqualTo((short) 5);
        assertThat(reviews.averageRating(productId)).isEqualTo(5.0);

        // 🔴 再次尝试评价同一订单行 → 被唯一约束拒
        assertThatThrownBy(() -> reviews.submit(c.userId(), c.order().getPublicToken(),
                c.line().getId(), 1, "lagi", null))
                .isInstanceOf(AppException.class).hasMessageContaining("已经评价过了");
    }

    @Test
    @DisplayName("🔴 违规评价被三方过滤拦截【不发布】，改后重提可发布")
    void blockedReviewIsNotPublishedAndCanBeFixed() {
        String sku = seedSku();
        long productId = productIdOf(sku);
        Ctx c = completedOrder(sku);

        // `judi`（赌博）是 V47 种子里的 L1_BLOCK 硬拦截词
        ShopReview blocked = reviews.submit(c.userId(), c.order().getPublicToken(),
                c.line().getId(), 1, "mau main judi bareng?", null);

        assertThat(blocked.getReviewStatus()).isNotEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.publishedFor(productId, 10))
                .as("🔴 不走先发布后审核 —— 违规内容一刻都不该对潜在买家可见").isEmpty();

        // 停留编辑态、改后重提
        ShopReview fixed = reviews.resubmit(c.userId(), blocked.getId(), 4, "Barangnya bagus",
                null);
        assertThat(fixed.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.publishedFor(productId, 10)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 未完成订单不可评（链路上真实推到已签收也不行）")
    void onlyCompletedOrdersAreReviewable() {
        String sku = seedSku();
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "e7-topup2:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Ke7" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.markDeliveredByAdmin(o.getPublicToken());   // 已签收但未确认收货
        long lineId = orderLines.findByOrderIdOrderByIdAsc(
                orders.findByPublicToken(o.getPublicToken()).orElseThrow().getId()).get(0).getId();

        assertThatThrownBy(() -> reviews.submit(uid, o.getPublicToken(), lineId, 5, "ok", null))
                .isInstanceOf(AppException.class).hasMessageContaining("订单完成后才能评价");
    }

    @Test
    @DisplayName("多人评价同一商品 → 详情页倒序聚合，平均分正确")
    void multipleReviewersAggregate() {
        String sku = seedSku();
        long productId = productIdOf(sku);

        Ctx a = completedOrder(sku);
        reviews.submit(a.userId(), a.order().getPublicToken(), a.line().getId(), 5, "mantap",
                null);
        Ctx b = completedOrder(sku);
        reviews.submit(b.userId(), b.order().getPublicToken(), b.line().getId(), 3, "biasa aja",
                null);

        var list = reviews.publishedFor(productId, 10);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getContent()).isEqualTo("biasa aja");   // 倒序
        assertThat(reviews.averageRating(productId)).isEqualTo(4.0);
        assertThat(reviews.publishedCount(productId)).isEqualTo(2);
    }
}
