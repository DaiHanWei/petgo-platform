package com.tailtopia.shop.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.pay.domain.PayChannel;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shop.domain.ReturnPolicy;
import com.tailtopia.shop.order.domain.AddressSnapshot;
import com.tailtopia.shop.order.domain.CheckoutSource;
import com.tailtopia.shop.order.domain.CompletionSource;
import com.tailtopia.shop.order.domain.DeliverySource;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.dto.RefundDestinationRequest;
import com.tailtopia.shop.order.pricing.ShippingDiscountSource;
import com.tailtopia.shop.order.refund.CancelReason;
import com.tailtopia.shop.order.refund.RefundScope;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import com.tailtopia.shop.order.refund.ShopRefundStatus;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.repository.SkuInventoryRepository;
import com.tailtopia.shop.service.InventoryMovementService;
import com.tailtopia.support.ApiIntegrationTest;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * L1：v1.4.2 shop Story 4.1 —— 取消退款单全链路（需 Docker postgres + redis，云端不跑，待本地验收）。
 *
 * <p>订单以 O1 形直接经 JPA 落库（金额与 story AC2.8 表一致），SKU 走真实采购入库（退货入库按最近采购价）。
 * 成长值扣回与「3.2 下单路径造单」归 10.1 联调覆盖（本类造单不经付款，没有 {@code SPEND:SHOP} 流水）。
 */
class ShopOrderRefundIntegrationTest extends ApiIntegrationTest {

    @Autowired
    private ShopOrderRefundService refundService;
    @Autowired
    private ShopOrderRefundRepository refunds;
    @Autowired
    private ShopOrderRepository orders;
    @Autowired
    private ShopOrderLineRepository orderLines;
    @Autowired
    private SkuInventoryRepository inventory;
    @Autowired
    private InventoryMovementService movements;
    @Autowired
    private PawCoinWalletService wallet;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private com.tailtopia.support.VetTestSupport vets;

    private long actor;

    @BeforeEach
    void resolveActor() {
        actor = adminActorId();
        jdbc.update("UPDATE pawcoin_config SET compensation_premium_rate = 10, compensation_premium_cap = 0");
    }

    @org.junit.jupiter.api.AfterEach
    void restoreConfig() {
        jdbc.update("UPDATE pawcoin_config SET compensation_premium_rate = 0, compensation_premium_cap = 0");
    }

    private long seedSku(long price) {
        String pToken = "rp" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_products (public_token, name, brand, category, main_image_key,
                        species, detail_html, shelf_life_note, return_policy, is_active)
                VALUES (?, 'Produk', 'B', 'MAKANAN', 'k', 'DOG', '<p/>', 'n', 'RETURNABLE', true)
                """, pToken);
        Long pid = jdbc.queryForObject("SELECT id FROM shop_products WHERE public_token = ?", Long.class, pToken);
        String sToken = "rs" + SEQ.incrementAndGet();
        jdbc.update("""
                INSERT INTO shop_skus (public_token, product_id, spec_name, price, package_weight_g,
                        package_length_cm, package_width_cm, package_height_cm)
                VALUES (?, ?, '3 kg', ?, 1000, 20, 15, 10)""", sToken, pid, price);
        Long sid = jdbc.queryForObject("SELECT id FROM shop_skus WHERE public_token = ?", Long.class, sToken);
        jdbc.update("INSERT INTO sku_inventory (sku_id, actual, locked) VALUES (?, 0, 0)", sid);
        movements.receivePurchase(sid, 10, "PO-" + SEQ.incrementAndGet(), "供应商", price / 2,
                java.time.LocalDate.now(), actor);
        return sid;
    }

    private record O1(long userId, ShopOrder order, List<ShopOrderLine> lines) {
    }

    /** O1：A 100,000 × 2（适用）、B 50,000 × 1、C 30,000 × 3（适用）；满减 25,000；运费 28,000 − 15,000；保价 1,575。 */
    private O1 seedO1(boolean insured, long coin) {
        long uid = newUser().getId();
        long insurance = insured ? 1_575L : 0L;
        long payable = 328_000L + insurance;
        Instant now = Instant.now();
        ShopOrder o = ShopOrder.placeCheckout(new ShopOrder.PlaceSnapshot("rfo" + SEQ.incrementAndGet(),
                "TOKO-RF-" + SEQ.incrementAndGet(), now, uid,
                new AddressSnapshot("Budi", "+628123", "DKI Jakarta", "Jakarta Selatan", "Kebayoran", "Jl. 1",
                        "12160"), null, CheckoutSource.CART, 340_000L, 25_000L, 28_000L, 15_000L,
                ShippingDiscountSource.FREE_SHIPPING_RULE, insurance, insured, payable, null, null, "jne", "reg",
                "JNE", "REG", "2 - 3", null, coin > 0));
        o.applyPaymentSplit(coin > 0 && coin < payable ? PayChannel.MIXED
                : coin > 0 ? PayChannel.PAWCOIN : PayChannel.QRIS, coin, payable - coin);
        o.markPaid(now);
        o = orders.save(o);
        List<ShopOrderLine> lines = List.of(line(o, seedSku(100_000L), 100_000L, 2, true),
                line(o, seedSku(50_000L), 50_000L, 1, false), line(o, seedSku(30_000L), 30_000L, 3, true));
        return new O1(uid, o, lines);
    }

    private ShopOrderLine line(ShopOrder o, long skuId, long price, int qty, boolean eligible) {
        ShopOrderLine l = ShopOrderLine.of(o.getId(), skuId, "Produk", "3 kg", price, qty, ReturnPolicy.RETURNABLE);
        l.markDiscountEligible(eligible);
        return orderLines.save(l);
    }

    private ShopOrder reload(O1 c) {
        return orders.findByPublicToken(c.order().getPublicToken()).orElseThrow();
    }

    private long actual(long skuId) {
        return inventory.findBySkuId(skuId).orElseThrow().getActual();
    }

    private void persist(ShopOrder o) {
        orders.save(o);
    }

    @Test
    @DisplayName("部分取消 A×1：refunded_qty +1、累计 91,380 / 27,726、币到账、库存 +1、订单仍待发货、退款单待选去向")
    void partialCancel() {
        O1 c = seedO1(true, 100_000L);
        long skuA = c.lines().get(0).getSkuId();
        long before = actual(skuA);

        refundService.cancelLine(c.order().getPublicToken(), c.lines().get(0).getId(), 1, CancelReason.OUT_OF_STOCK,
                "缺 1 件", actor);

        ShopOrder o = reload(c);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.PENDING_SHIPMENT);
        assertThat(o.getRefundedTotal()).isEqualTo(91_380L);
        assertThat(o.getRefundedCoin()).isEqualTo(27_726L);
        assertThat(orderLines.findById(c.lines().get(0).getId()).orElseThrow().getRefundedQty()).isEqualTo(1);
        assertThat(actual(skuA)).isEqualTo(before + 1);
        assertThat(wallet.balanceOf(c.userId())).isEqualTo(27_726L);
        assertThat(refunds.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId())).singleElement().satisfies(r -> {
            assertThat(r.getScope()).isEqualTo(RefundScope.LINE);
            assertThat(r.getStatus()).isEqualTo(ShopRefundStatus.AWAITING_DESTINATION);
        });
    }

    @Test
    @DisplayName("依次取消到最后一件：最后一张为 WHOLE、订单 REFUNDING、refunded_total = T、refunded_coin = C")
    void partialUntilLast() {
        O1 c = seedO1(true, 100_000L);
        String t = c.order().getPublicToken();
        refundService.cancelLine(t, c.lines().get(0).getId(), 1, CancelReason.OUT_OF_STOCK, null, actor);
        refundService.cancelLine(t, c.lines().get(0).getId(), 1, CancelReason.OUT_OF_STOCK, null, actor);
        refundService.cancelLine(t, c.lines().get(1).getId(), 1, CancelReason.OUT_OF_STOCK, null, actor);
        refundService.cancelLine(t, c.lines().get(2).getId(), 2, CancelReason.OUT_OF_STOCK, null, actor);
        refundService.cancelLine(t, c.lines().get(2).getId(), 1, CancelReason.OUT_OF_STOCK, null, actor);

        ShopOrder o = reload(c);
        assertThat(o.getStatus()).isEqualTo(ShopOrderStatus.REFUNDING);
        assertThat(o.getRefundedTotal()).isEqualTo(329_575L);
        assertThat(o.getRefundedCoin()).isEqualTo(100_000L);
        List<ShopOrderRefund> rs = refunds.findByOrderIdOrderByCreatedAtAscIdAsc(o.getId());
        assertThat(rs).hasSize(5);
        assertThat(rs.get(4).getScope()).isEqualTo(RefundScope.WHOLE);
        assertThat(rs.get(4).getTotalRefund()).isEqualTo(41_987L);
    }

    @Test
    @DisplayName("平台原因整单 → 选 PawCoin：现金 + 补偿到账、COMPLETED、订单 REFUNDED")
    void platformWholeToPawcoin() throws Exception {
        O1 c = seedO1(true, 100_000L);
        var out = refundService.cancelWhole(c.order().getPublicToken(), CancelReason.OUT_OF_STOCK, "缺货", actor);
        String rt = out.refund().getPublicToken();

        mvc.perform(post("/api/v1/me/shop-orders/{t}/refunds/{r}/destination", c.order().getPublicToken(), rt)
                        .header("Authorization", userBearer(c.userId()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(new RefundDestinationRequest("TO_PAWCOIN", null, null,
                                null))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.compensationAmount").value(22_957));

        assertThat(wallet.balanceOf(c.userId())).isEqualTo(100_000L + 229_575L + 22_957L);
        assertThat(reload(c).getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/me/shop-orders/{t}", c.order().getPublicToken())
                        .header("Authorization", userBearer(c.userId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refunds[0].refundToken").value(rt))
                .andExpect(jsonPath("$.refunds[0].status").value("COMPLETED"));
    }

    @Test
    @DisplayName("选银行（OVO）→ PAYOUT_PENDING → 打款失败 → 改选 PawCoin → COMPLETED；另一单标记已打款幂等")
    void bankFailThenPawcoin() throws Exception {
        O1 c = seedO1(true, 100_000L);
        String rt = refundService.cancelWhole(c.order().getPublicToken(), CancelReason.OUT_OF_STOCK, null, actor)
                .refund().getPublicToken();
        refundService.chooseDestination(c.userId(), c.order().getPublicToken(), rt,
                new RefundDestinationRequest("TO_BANK", "OVO", "081234567890", "Budi"));
        assertThat(refunds.findByPublicToken(rt).orElseThrow().getStatus()).isEqualTo(ShopRefundStatus.PAYOUT_PENDING);
        // 🔒 加密落库：库里不是明文
        String enc = jdbc.queryForObject("SELECT payout_account_enc FROM shop_order_refunds WHERE public_token = ?",
                String.class, rt);
        assertThat(enc).isNotEqualTo("081234567890").doesNotContain("081234567890");

        // V1.4.2 shop-admin 5.2 · O-12：发起人（actor）不能标记打款结果 → 403，且违规审计独立提交不随回滚；换财务账号操作
        long finance = persistedSuperAdminId("finance");
        assertThatThrownBy(() -> refundService.markPayoutFailed(rt, "账号无效", actor))
                .isInstanceOfSatisfying(AppException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM admin_audit_logs WHERE action_type = "
                + "'SHOP_REFUND_DUTY_VIOLATION_BLOCKED' AND target_id = ?", Integer.class, rt)).isEqualTo(1);
        refundService.markPayoutFailed(rt, "账号无效", finance);
        refundService.chooseDestination(c.userId(), c.order().getPublicToken(), rt,
                new RefundDestinationRequest("TO_PAWCOIN", null, null, null));
        assertThat(refunds.findByPublicToken(rt).orElseThrow().getStatus()).isEqualTo(ShopRefundStatus.COMPLETED);
        assertThat(reload(c).getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);

        O1 d = seedO1(true, 100_000L);
        String rt2 = refundService.cancelWhole(d.order().getPublicToken(), CancelReason.OUT_OF_STOCK, null, actor)
                .refund().getPublicToken();
        refundService.chooseDestination(d.userId(), d.order().getPublicToken(), rt2,
                new RefundDestinationRequest("TO_BANK", "BCA", "1234567890", "Budi"));
        refundService.markPaidOut(rt2, "BCA-TXN-1", null, finance);
        refundService.markPaidOut(rt2, finance);
        assertThat(refunds.findByPublicToken(rt2).orElseThrow().getPayoutTxnNo()).isEqualTo("BCA-TXN-1");
        assertThat(reload(d).getStatus()).isEqualTo(ShopOrderStatus.REFUNDED);
        assertThat(refunds.findByPublicToken(rt2).orElseThrow().getPaidOutAt()).isNotNull();
    }

    @Test
    @DisplayName("V1.4.2 shop-admin 5.1 USER_FAULT：未入库取消 409；按行入库库存只加一次；收齐后取消出退款单 = 商品实付、不再回补、无补偿")
    void userFaultInbound() {
        O1 c = seedO1(true, 100_000L);
        ShopOrder o = reload(c);
        o.markShipped(Instant.now());
        o.decideReturnCause(com.tailtopia.shop.logistics.ReturnCause.USER_FAULT);
        persist(o);
        long skuA = c.lines().get(0).getSkuId();
        long before = actual(skuA);

        assertThatThrownBy(() -> refundService.cancelWhole(c.order().getPublicToken(),
                CancelReason.USER_FAULT_RETURNED, null, actor)).isInstanceOf(AppException.class);
        java.util.Map<Long, Integer> all = new java.util.LinkedHashMap<>();
        c.lines().forEach(l -> all.put(l.getId(), l.getQty() - l.getRefundedQty()));
        var in = refundService.confirmReturnedInbound(c.order().getPublicToken(), all, actor);
        assertThat(in.complete()).isTrue();
        assertThat(actual(skuA)).isEqualTo(before + 2);

        var out = refundService.cancelWhole(c.order().getPublicToken(), CancelReason.USER_FAULT_RETURNED, null, actor);
        assertThat(out.refund().getTotalRefund()).isEqualTo(315_000L);
        assertThat(actual(skuA)).as("库存只在入库时加一次").isEqualTo(before + 2);
        refundService.chooseDestination(c.userId(), c.order().getPublicToken(), out.refund().getPublicToken(),
                new RefundDestinationRequest("TO_PAWCOIN", null, null, null));
        assertThat(refunds.findByPublicToken(out.refund().getPublicToken()).orElseThrow().getCompensationAmount())
                .isZero();
    }

    @Test
    @DisplayName("SHIPPED 后丢件破损（未保价）协商额不回补；COMPLETED 后赔付订单仍 COMPLETED、refunds[] 出现 COMPENSATION")
    void lostDamagedAndCompensation() {
        O1 c = seedO1(false, 100_000L);
        ShopOrder o = reload(c);
        o.markShipped(Instant.now());
        persist(o);
        long skuA = c.lines().get(0).getSkuId();
        long before = actual(skuA);
        refundService.cancelWhole(c.order().getPublicToken(), CancelReason.LOST_DAMAGED, 120_000L, "丢件", actor);
        assertThat(actual(skuA)).isEqualTo(before);
        assertThat(reload(c).getRefundedTotal()).isEqualTo(120_000L);

        O1 d = seedO1(true, 100_000L);
        ShopOrder od = reload(d);
        od.markShipped(Instant.now());
        od.markDelivered(Instant.now(), DeliverySource.ADMIN_MARK);
        od.markCompleted(Instant.now(), CompletionSource.USER_CONFIRM);
        persist(od);
        refundService.compensate(d.order().getPublicToken(), null, "签收后破损", actor);
        ShopOrder after = reload(d);
        assertThat(after.getStatus()).isEqualTo(ShopOrderStatus.COMPLETED);
        assertThat(refundService.refundViewsOf(after)).singleElement()
                .satisfies(v -> assertThat(v.scope()).isEqualTo("COMPENSATION"));
    }

    @Test
    @DisplayName("别人的订单 404；兽医 token 403")
    void authz() throws Exception {
        O1 c = seedO1(true, 100_000L);
        String rt = refundService.cancelWhole(c.order().getPublicToken(), CancelReason.OUT_OF_STOCK, null, actor)
                .refund().getPublicToken();
        long other = newUser().getId();
        String body = json.writeValueAsString(new RefundDestinationRequest("TO_PAWCOIN", null, null, null));
        mvc.perform(post("/api/v1/me/shop-orders/{t}/refunds/{r}/destination", c.order().getPublicToken(), rt)
                        .header("Authorization", userBearer(other))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/me/shop-orders/{t}/refunds/{r}/destination", c.order().getPublicToken(), rt)
                        // PR#49 CI 修复：VET token 须对应 DB 里的 active vet 行（BannedVetFilter），
                        // 原先拿 userId 当 vetId → 过滤器先判 401，测不到角色拒绝 403
                        .header("Authorization", vetBearer(vets.newActiveVet("refund-authz").getId()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
    }
}
