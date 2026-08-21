package com.tailtopia.shop.review;

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
import com.tailtopia.shop.review.repository.ShopReviewRepository;
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
 * L1：商品评价建模与提交（Story 7.1 · 7.3 读侧，FR-106）。
 *
 * <p>🔴 本类的四条核心断言：
 * ① 仅 `COMPLETED` 可评；② 每个订单行只能评一次（库级唯一）；
 * ③ <b>同步过滤，命中即拦截不发布</b>（不是先发布后审核）；④ 无评价时不伪造。
 */
@TestPropertySource(properties = "petgo.shop.sku-cap=500")
class ShopReviewIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopReviewService reviews;
    @Autowired
    private ShopReviewRepository reviewRepo;
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

    // ---------- 造数 ----------

    private long seedUser() {
        long n = SEQ.incrementAndGet();
        jdbc.update("INSERT INTO users (nickname, status) VALUES (?, 'ACTIVE')", "rv" + n);
        return jdbc.queryForObject("SELECT id FROM users WHERE nickname = ?", Long.class, "rv" + n);
    }

    private String seedSku() {
        String pToken = "rvp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject(
                "SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "rvs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price)
                VALUES (?, ?, '3 kg', 100000)""", sToken, pid);
        Long sid = jdbc.queryForObject(
                "SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 100, 0)", sid);
        return sToken;
    }

    /** 把订单推到目标状态：completed=true 推到已完成，否则停在已签收。 */
    private Ctx order(boolean completed) {
        long uid = seedUser();
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "rv-topup:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Krv" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        String sku = seedSku();
        carts.add(uid, sku, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        if (completed) {
            fulfillment.confirmReceipt(uid, o.getPublicToken());
        } else {
            fulfillment.markDeliveredByAdmin(o.getPublicToken());
        }
        ShopOrder reloaded = orders.findByPublicToken(o.getPublicToken()).orElseThrow();
        ShopOrderLine line = orderLines.findByOrderIdOrderByIdAsc(reloaded.getId()).get(0);
        return new Ctx(uid, sku, reloaded, line);
    }

    private record Ctx(long userId, String skuToken, ShopOrder order, ShopOrderLine line) {
    }

    private long productIdOf(String skuToken) {
        return jdbc.queryForObject("SELECT product_id FROM shop_skus WHERE public_token = ?",
                Long.class, skuToken);
    }

    // ---------- 状态与唯一性 ----------

    @Test
    @DisplayName("已完成订单可评：1–5 星必填、文字与图选填，同步过滤后直接发布")
    void completedOrderCanBeReviewed() {
        Ctx c = order(true);
        ShopReview r = reviews.submit(c.userId(), c.order().getPublicToken(), c.line().getId(),
                5, "Bagus banget, anjing saya suka", List.of());

        assertThat(r.getRating()).isEqualTo((short) 5);
        assertThat(r.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.publishedFor(productIdOf(c.skuToken()), 10)).hasSize(1);
    }

    @Test
    @DisplayName("🔴 未完成订单不可评（已签收但没确认收货也不行）—— 没收到货的评价没有参考价值")
    void nonCompletedOrderCannotBeReviewed() {
        Ctx c = order(false);
        assertThatThrownBy(() -> reviews.submit(c.userId(), c.order().getPublicToken(),
                c.line().getId(), 5, "ok", null))
                .isInstanceOf(AppException.class).hasMessageContaining("订单完成后才能评价");
    }

    @Test
    @DisplayName("🔴 每个订单行只能评一次（库级唯一约束）")
    void oneReviewPerOrderLine() {
        Ctx c = order(true);
        reviews.submit(c.userId(), c.order().getPublicToken(), c.line().getId(), 5, "ok", null);

        assertThatThrownBy(() -> reviews.submit(c.userId(), c.order().getPublicToken(),
                c.line().getId(), 4, "lagi", null))
                .isInstanceOf(AppException.class).hasMessageContaining("已经评价过了");
    }

    @Test
    @DisplayName("🔴 换一张订单还能再评同一 SKU —— 唯一约束打在订单行上，不是打在 SKU 上")
    void sameSkuInAnotherOrderCanBeReviewedAgain() {
        Ctx first = order(true);
        reviews.submit(first.userId(), first.order().getPublicToken(), first.line().getId(), 5,
                "ok", null);
        // 同一用户再买同一 SKU
        carts.add(first.userId(), first.skuToken(), 1);
        String addr = addresses.list(first.userId()).get(0).getPublicToken();
        ShopOrder second = checkout.placeOrder(first.userId(), addr, null, null);
        payments.pay(first.userId(), second.getPublicToken(), null);
        fulfillment.ship(second.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.confirmReceipt(first.userId(), second.getPublicToken());
        ShopOrderLine line2 = orderLines.findByOrderIdOrderByIdAsc(
                orders.findByPublicToken(second.getPublicToken()).orElseThrow().getId()).get(0);

        ShopReview r2 = reviews.submit(first.userId(), second.getPublicToken(), line2.getId(), 4,
                "beli lagi", null);
        assertThat(r2.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.publishedFor(productIdOf(first.skuToken()), 10)).hasSize(2);
    }

    @Test
    @DisplayName("🔒 越权：别人的订单不能评 → 404（不泄漏 token 存在）")
    void strangerCannotReview() {
        Ctx c = order(true);
        long stranger = seedUser();
        assertThatThrownBy(() -> reviews.submit(stranger, c.order().getPublicToken(),
                c.line().getId(), 5, "ok", null))
                .isInstanceOf(AppException.class).hasMessageContaining("订单不存在");
    }

    // ---------- 校验 ----------

    @Test
    @DisplayName("星级必填且在 1–5；文字 ≤500；图片 ≤6")
    void inputValidation() {
        Ctx c = order(true);
        String token = c.order().getPublicToken();
        long lineId = c.line().getId();

        assertThatThrownBy(() -> reviews.submit(c.userId(), token, lineId, 0, null, null))
                .isInstanceOf(AppException.class).hasMessageContaining("1–5 星");
        assertThatThrownBy(() -> reviews.submit(c.userId(), token, lineId, 6, null, null))
                .isInstanceOf(AppException.class);
        assertThatThrownBy(() -> reviews.submit(c.userId(), token, lineId, 5,
                "x".repeat(501), null))
                .isInstanceOf(AppException.class).hasMessageContaining("500");
        assertThatThrownBy(() -> reviews.submit(c.userId(), token, lineId, 5, null,
                List.of("1", "2", "3", "4", "5", "6", "7")))
                .isInstanceOf(AppException.class).hasMessageContaining("6");

        // 只给星级也能提交（文字与图都选填）
        assertThat(reviews.submit(c.userId(), token, lineId, 4, null, null).getRating())
                .isEqualTo((short) 4);
    }

    // ---------- 🔴 同步过滤 ----------

    @Test
    @DisplayName("🔴 命中违规词 → 当场拦截【不发布】，不进人工队列，且不出现在详情页")
    void blockedContentIsNotPublished() {
        Ctx c = order(true);
        // stub 审核器按关键词规则打分；用命中 L1 硬拦截词库的文本
        ShopReview r = reviews.submit(c.userId(), c.order().getPublicToken(), c.line().getId(),
                1, blockedText(), null);

        assertThat(r.getReviewStatus())
                .as("🔴 先发布后审核意味着违规内容在被发现前对所有潜在买家可见")
                .isNotEqualTo(ReviewStatus.PUBLISHED);
        assertThat(reviews.publishedFor(productIdOf(c.skuToken()), 10)).isEmpty();
    }

    @Test
    @DisplayName("🔴 被拦截后改内容重提 → 覆盖原记录并可发布（不新建、不 409）")
    void resubmitAfterBlockOverwrites() {
        Ctx c = order(true);
        ShopReview blocked = reviews.submit(c.userId(), c.order().getPublicToken(),
                c.line().getId(), 1, blockedText(), null);
        assertThat(blocked.getReviewStatus()).isNotEqualTo(ReviewStatus.PUBLISHED);

        ShopReview ok = reviews.resubmit(c.userId(), blocked.getId(), 4, "Barangnya bagus", null);

        assertThat(ok.getReviewStatus()).isEqualTo(ReviewStatus.PUBLISHED);
        // 同一订单行仍只有一条评价
        assertThat(reviewRepo.findByShopOrderLineId(c.line().getId())).isPresent();
        assertThat(reviewRepo.findAll().stream()
                .filter(x -> x.getShopOrderLineId().equals(c.line().getId())).count())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("已发布的评价不可修改（首版不做编辑）")
    void publishedReviewCannotBeEdited() {
        Ctx c = order(true);
        ShopReview r = reviews.submit(c.userId(), c.order().getPublicToken(), c.line().getId(),
                5, "Bagus", null);
        assertThatThrownBy(() -> reviews.resubmit(c.userId(), r.getId(), 1, "ganti", null))
                .isInstanceOf(AppException.class).hasMessageContaining("不可修改");
    }

    // ---------- Story 7.3 读侧 ----------

    @Test
    @DisplayName("🔴 无评价 → 空列表、平均分为 null（不是 0），前端据此渲染空态；不伪造评价")
    void emptyStateIsHonest() {
        Ctx c = order(true);
        long productId = productIdOf(c.skuToken());

        assertThat(reviews.publishedFor(productId, 10)).isEmpty();
        assertThat(reviews.publishedCount(productId)).isZero();
        assertThat(reviews.averageRating(productId))
                .as("🔴 返回 0 会被渲染成「零分」").isNull();
    }

    @Test
    @DisplayName("详情页评价按时间倒序，且只出已发布的")
    void listIsNewestFirstAndPublishedOnly() {
        Ctx first = order(true);
        long productId = productIdOf(first.skuToken());
        reviews.submit(first.userId(), first.order().getPublicToken(), first.line().getId(), 5,
                "pertama", null);

        // 同商品的第二条（另一个用户），且再来一条被拦截的
        long uid2 = seedUser();
        Ctx second = orderForExistingSku(uid2, first.skuToken());
        reviews.submit(uid2, second.order().getPublicToken(), second.line().getId(), 3, "kedua",
                null);
        long uid3 = seedUser();
        Ctx third = orderForExistingSku(uid3, first.skuToken());
        reviews.submit(uid3, third.order().getPublicToken(), third.line().getId(), 1,
                blockedText(), null);

        var list = reviews.publishedFor(productId, 10);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).getContent()).isEqualTo("kedua");   // 倒序：新的在前
        assertThat(reviews.averageRating(productId)).isEqualTo(4.0);
    }

    // ---------- 辅助 ----------

    /** 让另一个用户买同一个 SKU 并完成订单。 */
    private Ctx orderForExistingSku(long uid, String skuToken) {
        rules.update(true, true, 1_000_000L, ACTOR);
        wallet.credit(uid, 500_000L, PawCoinTxnType.TOPUP, "TEST", null,
                "rv-topup2:" + uid + ":" + SEQ.incrementAndGet());
        zones.setFreeShippingThreshold(0, ACTOR);
        String kec = "Krv" + SEQ.incrementAndGet();
        zones.upsert(kec, "Jakarta Selatan", "DKI Jakarta", 0L, ACTOR);
        String addr = addresses.create(uid, new AddressFields("Budi", "08123456789", "DKI Jakarta",
                "Jakarta Selatan", kec, "Jl. Test No. 1", "12160", "Rumah")).getPublicToken();
        carts.add(uid, skuToken, 1);
        ShopOrder o = checkout.placeOrder(uid, addr, null, null);
        payments.pay(uid, o.getPublicToken(), null);
        fulfillment.ship(o.getPublicToken(), Carrier.JNE, "JP" + SEQ.incrementAndGet(), 0L);
        fulfillment.confirmReceipt(uid, o.getPublicToken());
        ShopOrder reloaded = orders.findByPublicToken(o.getPublicToken()).orElseThrow();
        return new Ctx(uid, skuToken,
                reloaded, orderLines.findByOrderIdOrderByIdAsc(reloaded.getId()).get(0));
    }

    /**
     * 一段会被既有审核机制拦下来的文本。
     *
     * <p>🔴 用<b>关键词规则引擎</b>认得的词，而不是随便写一句 —— 否则这条用例会因为
     * stub 打分恰好低于阈值而假绿。
     */
    private static String blockedText() {
        // `judi`（赌博）是 V47 种子里的 L1_BLOCK 硬拦截词
        return "mau main judi bareng?";
    }
}
