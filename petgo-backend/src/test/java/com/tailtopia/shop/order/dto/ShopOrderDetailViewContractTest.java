package com.tailtopia.shop.order.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * L0 契约金标：订单详情 wire（Story 1-1 AC4 · C5 三处同改之「契约 test」腿）。
 *
 * <p>三方同步点：
 * <ul>
 *   <li>后端 record —— {@link ShopOrderDetailView}</li>
 *   <li>App DTO —— {@code petgo_app/lib/features/shop/domain/shop_order_detail.dart}
 *       （{@code ShopOrderDetail.fromJson}）</li>
 *   <li>App mock —— 🔴 <b>本仓已不存在</b>：mock 子系统整体删除于 {@code 8e85b40d}，C5 的「四处」在本仓实为三处</li>
 * </ul>
 *
 * <p>🔴 <b>mapper 必须配 {@code NON_NULL}</b>（{@code application.yml:235}
 * {@code spring.jackson.default-property-inclusion: non_null}）——用裸 {@code ObjectMapper}
 * 会钉出一份线上永远不会出现的 22 键 JSON：真实响应把 null 字段整键省略。
 * 钉错形状的契约测试是绿的，但它保护的是一个不存在的契约。
 */
class ShopOrderDetailViewContractTest {

    private final JsonMapper json = JsonMapper.builder()
            .changeDefaultPropertyInclusion(
                    incl -> incl.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @SuppressWarnings("unchecked")
    private Map<String, Object> wire(Object dto) {
        return json.convertValue(dto, Map.class);
    }

    /**
     * 顶层键集（顺序即 record 组件顺序）。
     *
     * <p>⚠️ <b>惯例是新字段末尾追加</b>，插中间会让按位置构造的调用方静默错位。
     * Story 4-3 的 {@code displayNo} 是<b>刻意的例外</b>：它在语义上就该紧挨 {@code orderToken}
     * （一个是内部查询键、一个是给人看的号，成对出现才读得懂）。
     * 破例的前提是本仓只有<b>两个</b>按位置构造的调用点（{@code ShopOrderDetailView.of} 与本类），
     * 且两个都在本次 diff 里一起改了。
     * 🔴 但「两个 String 挨在一起」正是编译器抓不住的那种错位 ——
     * 所以另加了 {@link #displayNoAndOrderTokenAreNotSwapped()} 专门钉这一处。
     */
    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
            "orderToken", "displayNo", "status", "goodsSubtotal", "shippingFee", "shippingDiscount",
            "totalAmount", "payChannel", "coinAmount", "cashAmount", "expiresAt", "createdAt",
            "paymentIntentToken", "shipTo", "lines", "shippedAt", "deliveredAt", "completedAt",
            "returnWindowEndsAt", "packages", "attributionSource",
            "paymentStatus", "paymentFailureCategory",
            // v1.4.2 shop Story 3.3（S-7）：末尾追加
            "displayStatus", "canPay", "paymentDeadline",
            // v1.4.2 shop Story 4.1（D-15 / C-9）：末尾追加
            "refunds",
            // v1.4.2 shop Story 4.2（D-11 / D-19 / C-9）：末尾追加
            "reorderedToOrderToken", "reorderedFromOrderNo", "reorderedFromOrderToken", "latePayment",
            // v1.4.2 shop Story 5.1（D-19 物流 / F7）：末尾追加
            "logistics",
            // v1.4.2 shop Story 6.2（D-19 详情收口）：末尾追加
            "paidAt", "cancelledAt", "cancelReason", "amounts", "paymentMethods", "cancelledLines", "couponReturn");

    /** v1.4.2 shop Story 6.2：金额明细键集（全字段非 null 时）。 */
    private static final Set<String> AMOUNTS_KEYS = Set.of("goodsAmount", "discountCouponAmount", "shippingFee",
            "shippingDiscount", "shippingDiscountSource", "insuranceFee", "payable", "courierName",
            "courierServiceName");

    /** v1.4.2 shop Story 6.2：支付方式键集（两段都有时）。 */
    private static final Set<String> PAYMENT_METHODS_KEYS = Set.of("coinAmount", "coinStatus", "cashAmount",
            "cashStatus");

    /** v1.4.2 shop Story 6.2：部分取消行键集。 */
    private static final Set<String> CANCELLED_LINE_KEYS = Set.of("lineIndex", "productName", "specName",
            "cancelledQty", "refundAmount");

    /** v1.4.2 shop Story 4.1 AC6.1 + C-9：退款单条目键集（全字段非 null 时）。 */
    private static final Set<String> REFUND_KEYS = Set.of(
            "refundToken", "scope", "reason", "status", "totalRefund", "coinRefund", "cashRefund",
            "compensationAmount", "payoutFee", "payoutChannel", "payoutAccountLast4", "canChooseDestination",
            "payoutFailed", "paidOutAt", "createdAt", "cashDestination", "destinationChosenAt", "compensate",
            "compensationIfPawcoin");

    /** 已选银行并打款完成的退款单（账号 1234567890 / 户名 Budi Santoso 为加密列明文）。 */
    static ShopOrderRefundView paidOutBankRefund() {
        com.tailtopia.shop.order.refund.ShopOrderRefund r = com.tailtopia.shop.order.refund.ShopOrderRefund.create(
                "rf-tok-1", 1L, new com.tailtopia.shop.order.pricing.RefundQuote(
                        com.tailtopia.shop.order.refund.RefundScope.WHOLE, null, null,
                        com.tailtopia.shop.order.refund.CancelReason.OUT_OF_STOCK, 315_000L, 13_000L, 1_575L,
                        329_575L, 100_000L, 229_575L, false),
                7L, Instant.parse("2026-11-05T10:00:00Z"));
        r.chooseToBank(com.tailtopia.pay.refund.domain.PayoutChannel.OVO, "1234567890", "Budi Santoso",
                Instant.parse("2026-11-05T11:00:00Z"));
        r.markPaidOut(Instant.parse("2026-11-06T11:00:00Z"));
        return ShopOrderRefundView.of(r, 0L);
    }

    /** 全字段非 null 的样本 —— 只有它能把「字段集」钉全（NON_NULL 下 null 字段整键消失）。 */
    private ShopOrderDetailView fullyPopulated(String paymentStatus, String failureCategory) {
        Instant t = Instant.parse("2026-09-16T10:00:00Z");
        return new ShopOrderDetailView(
                "ord-tok-1", "TOKO-20260916-7M4KQ2", "COMPLETED", 100_000L, 15_000L, 5_000L, 110_000L, "MIXED",
                10_000L, 100_000L, t, t, "pi-tok-1",
                new ShopOrderDetailView.ShipTo("Budi", "0811", "DKI", "Jakarta Selatan",
                        "Kebayoran", "Jl. Melati 1", "12110"),
                List.of(new ShopOrderDetailView.Line("Royal Canin 2kg", "2kg", 100_000L, 1,
                        100_000L, "NO_RETURN", "https://cdn.example/x.jpg")),
                t, t, t, t,
                List.of(new ShopOrderDetailView.Package("JNE", "JNE", "JN1", "https://jne/JN1",
                        "DELIVERED", t, t, "BITESHIP", false)),
                "unknown", paymentStatus, failureCategory, "COMPLETED", false, t, List.of(paidOutBankRefund()),
                "new-tok-1", "TOKO-20260915-AAAAAA", "orig-tok-1",
                new ShopOrderDetailView.LatePayment(229_575L, t, "REORDERED"),
                new ShopOrderDetailView.Logistics("BITESHIP", "JNE", "Reguler", "JN1", "https://jne/JN1",
                        "delivered", "RETURNED", "returned"),
                t, t, "OUT_OF_STOCK",
                new ShopOrderDetailView.Amounts(100_000L, 5_000L, 15_000L, 5_000L, "COUPON", 1_000L, 110_000L,
                        "JNE", "Reguler"),
                new PaymentMethodsView(10_000L, "PAID", 100_000L, "PAID"),
                List.of(new ShopOrderDetailView.CancelledLine(0, "Royal Canin 2kg", "2kg", 1, 100_000L)),
                "EXPIRED");
    }

    @Test
    @DisplayName("旧版 App 兼容视图：status = displayStatus；包裹去掉已取消 / 已被取代的运单；其余字段不变")
    void legacyClientView() {
        ShopOrderDetailView full = fullyPopulated("PAID", null);
        Instant t = Instant.parse("2026-09-16T10:00:00Z");
        var old = new ShopOrderDetailView.Package("JNE", "JNE", "OLD", null, "SHIPPED", t, null, "BITESHIP", true);
        var cancelled = new ShopOrderDetailView.Package("JNE", "JNE", "CXL", null, "CANCELLED", null, null,
                "BITESHIP", false);
        var cur = new ShopOrderDetailView.Package("JNE", "JNE", "CUR", null, "SHIPPED", t, null, "BITESHIP", false);
        ShopOrderDetailView v = withPackagesAndStatus(full, List.of(old, cancelled, cur), "PENDING_PAYMENT", "CANCELLED")
                .forLegacyClient();
        assertThat(v.status()).isEqualTo("CANCELLED");
        assertThat(v.displayStatus()).isEqualTo("CANCELLED");
        assertThat(v.packages()).extracting(ShopOrderDetailView.Package::trackingNo).containsExactly("CUR");
        assertThat(v.orderToken()).isEqualTo(full.orderToken());
        assertThat(v.logistics()).isEqualTo(full.logistics());
        assertThat(v.couponReturn()).isEqualTo(full.couponReturn());
    }

    private static ShopOrderDetailView withPackagesAndStatus(ShopOrderDetailView f,
            List<ShopOrderDetailView.Package> pk, String status, String display) {
        return new ShopOrderDetailView(f.orderToken(), f.displayNo(), status, f.goodsSubtotal(), f.shippingFee(),
                f.shippingDiscount(), f.totalAmount(), f.payChannel(), f.coinAmount(), f.cashAmount(), f.expiresAt(),
                f.createdAt(), f.paymentIntentToken(), f.shipTo(), f.lines(), f.shippedAt(), f.deliveredAt(),
                f.completedAt(), f.returnWindowEndsAt(), pk, f.attributionSource(), f.paymentStatus(),
                f.paymentFailureCategory(), display, f.canPay(), f.paymentDeadline(), f.refunds(),
                f.reorderedToOrderToken(), f.reorderedFromOrderNo(), f.reorderedFromOrderToken(), f.latePayment(),
                f.logistics(), f.paidAt(), f.cancelledAt(), f.cancelReason(), f.amounts(), f.paymentMethods(),
                f.cancelledLines(), f.couponReturn());
    }

    @Test
    @DisplayName("顶层字段集恰好 39 个（5.1 加 logistics，6.2 加 7 个），displayNo / paymentStatus / paymentFailureCategory / displayStatus / canPay / paymentDeadline 在列")
    void fullOrderHasExactlyTheContractFields() {
        assertThat(wire(fullyPopulated("FAILED", "GATEWAY_DECLINED")).keySet())
                .isEqualTo(TOP_LEVEL_KEYS);
    }

    @Test
    @DisplayName("🔴 Story 4-3：displayNo 与 orderToken 没有被位置错位串掉")
    void displayNoAndOrderTokenAreNotSwapped() {
        // 两个相邻的 String 组件，编译器换过来也一样过 —— 而线上的后果是
        // 用户看到 22 位内部 token（正是本 story 要修的那个毛病），
        // 同时 App 拿 TOKO-… 去当路由键请求详情，直接 404。
        Map<String, Object> w = wire(fullyPopulated("PAID", null));

        assertThat(w.get("orderToken")).isEqualTo("ord-tok-1");
        assertThat(w.get("displayNo")).isEqualTo("TOKO-20260916-7M4KQ2");
        assertThat(String.valueOf(w.get("displayNo")))
                .as("展示号必须是给人看的那一个：TOKO 前缀 + WIB 日期 + 6 位 Crockford")
                .matches("^TOKO-\\d{8}-[0-9A-HJKMNP-TV-Z]{6}$");
        assertThat(String.valueOf(w.get("orderToken")))
                .as("内部 token 不是展示号，它不该长成 TOKO-… 的样子")
                .doesNotStartWith("TOKO-");
    }

    @Test
    @DisplayName("🔒 响应不含 gatewayMeta / gateway_meta / gatewayRef 任何形态")
    void neverLeaksGatewayMeta() {
        String out = json.writeValueAsString(fullyPopulated("FAILED", "GATEWAY_DECLINED"));
        assertThat(out)
                .doesNotContain("gatewayMeta")
                .doesNotContain("gateway_meta")
                .doesNotContain("gatewayRef");
    }

    @Test
    @DisplayName("枚举线是 UPPER_SNAKE 字面量，不是 ordinal 数字")
    void enumWireFormatIsUpperSnakeLiteral() {
        Map<String, Object> w = wire(fullyPopulated("FAILED", "GATEWAY_DECLINED"));
        assertThat(w.get("paymentStatus")).isEqualTo("FAILED");
        assertThat(w.get("paymentFailureCategory")).isEqualTo("GATEWAY_DECLINED");

        String out = json.writeValueAsString(fullyPopulated("EXPIRED", "USER_CANCELLED"));
        assertThat(out)
                .contains("\"paymentStatus\":\"EXPIRED\"")
                .contains("\"paymentFailureCategory\":\"USER_CANCELLED\"");
    }

    @Test
    @DisplayName("纯 PawCoin 单（无支付意图）→ 两个键在线上整键省略，App 按缺键读成 null")
    void pureCoinOrderOmitsBothKeysOnTheWire() {
        Map<String, Object> w = wire(fullyPopulated(null, null));

        // NON_NULL：不是「键在、值为 null」，而是键根本不出现。
        // App 侧 j['paymentStatus']?.toString() 对缺键与 null 同样得到 null —— 两端在此对齐。
        assertThat(w).doesNotContainKeys("paymentStatus", "paymentFailureCategory");
        // 其余键一个不少 —— 省的只能是这两个。
        assertThat(w.keySet()).containsExactlyInAnyOrderElementsOf(TOP_LEVEL_KEYS.stream()
                .filter(k -> !k.equals("paymentStatus") && !k.equals("paymentFailureCategory"))
                .toList());
    }

    @Test
    @DisplayName("失败但非用户取消：paymentFailureCategory 在，paymentStatus 也在")
    void failedOrderCarriesBothKeys() {
        Map<String, Object> w = wire(fullyPopulated("FAILED", "EXPIRED"));
        assertThat(w).containsEntry("paymentStatus", "FAILED")
                .containsEntry("paymentFailureCategory", "EXPIRED");
    }

    @Test
    @DisplayName("v1.4.2 shop 3.3：宽限期待支付单 → displayStatus=CANCELLED、canPay=false、paymentDeadline=expiresAt")
    void gracePeriodFieldsOnTheWire() {
        com.tailtopia.shop.order.domain.ShopOrder o = com.tailtopia.shop.order.domain.ShopOrder.place(
                "ord-g", "TOKO-20260916-7M4KQ3", Instant.parse("2026-09-16T10:00:00Z"), 9L, 200_000L, 20_000L,
                0L, new com.tailtopia.shop.order.domain.AddressSnapshot("Budi", "+628123", "DKI", "Jaksel",
                        "Kebayoran", "Jl. 1", "12160"));
        org.springframework.test.util.ReflectionTestUtils.setField(o, "createdAt",
                Instant.parse("2026-09-16T10:00:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(o, "expiresAt",
                Instant.parse("2026-09-16T11:00:00Z"));
        ShopOrderDetailView v = ShopOrderDetailView.of(o, List.of(), List.of(), Map.of(), null,
                Instant.parse("2026-09-16T11:02:00Z"));
        Map<String, Object> w = wire(v);
        assertThat(w).containsEntry("status", "PENDING_PAYMENT")
                .containsEntry("displayStatus", "CANCELLED")
                .containsEntry("canPay", false)
                .containsKey("paymentDeadline");
        assertThat(v.paymentDeadline()).isEqualTo(Instant.parse("2026-09-16T11:00:00Z"));

        ShopOrderDetailView inWindow = ShopOrderDetailView.of(o, List.of(), List.of(), Map.of(), null,
                Instant.parse("2026-09-16T10:05:00Z"));
        assertThat(inWindow.displayStatus()).isEqualTo("PENDING_PAYMENT");
        assertThat(inWindow.canPay()).isTrue();
    }

    @Test
    @DisplayName("v1.4.2 shop 4.1：refunds[] 条目键名齐全；无明文账号 / 户名；payoutAccountLast4 只有 4 位；无内部 id")
    @SuppressWarnings("unchecked")
    void refundsOnTheWire() {
        Map<String, Object> w = wire(fullyPopulated("PAID", null));
        List<Map<String, Object>> refunds = (List<Map<String, Object>>) w.get("refunds");
        assertThat(refunds).hasSize(1);
        Map<String, Object> r = refunds.get(0);
        assertThat(r.keySet()).isEqualTo(REFUND_KEYS);
        assertThat(r).containsEntry("refundToken", "rf-tok-1").containsEntry("scope", "WHOLE")
                .containsEntry("reason", "OUT_OF_STOCK").containsEntry("status", "COMPLETED")
                .containsEntry("payoutChannel", "OVO").containsEntry("cashDestination", "TO_BANK")
                .containsEntry("canChooseDestination", false).containsEntry("payoutFailed", false);
        assertThat(String.valueOf(r.get("payoutAccountLast4"))).isEqualTo("7890").hasSize(4);
        assertThat(((Number) r.get("payoutFee")).longValue()).isEqualTo(2_500L);

        String out = json.writeValueAsString(fullyPopulated("PAID", null));
        assertThat(out).doesNotContain("1234567890").doesNotContain("Budi Santoso")
                .doesNotContain("orderLineId").doesNotContain("orderId\"").doesNotContain("payoutAccount\"");
    }

    @Test
    @DisplayName("v1.4.2 shop 4.1：无退款单 → refunds 为空数组（键在、不省略）")
    void emptyRefundsIsEmptyArray() {
        com.tailtopia.shop.order.domain.ShopOrder o = com.tailtopia.shop.order.domain.ShopOrder.place(
                "ord-e", "TOKO-20260916-7M4KQ4", Instant.parse("2026-09-16T10:00:00Z"), 9L, 200_000L, 20_000L,
                0L, new com.tailtopia.shop.order.domain.AddressSnapshot("Budi", "+628123", "DKI", "Jaksel",
                        "Kebayoran", "Jl. 1", "12160"));
        Map<String, Object> w = wire(ShopOrderDetailView.of(o, List.of(), List.of(), Map.of(), null,
                Instant.parse("2026-09-16T10:05:00Z")));
        assertThat(w).containsEntry("refunds", List.of());
    }

    private static com.tailtopia.shop.order.domain.ShopOrder plainOrder(String token) {
        return com.tailtopia.shop.order.domain.ShopOrder.place(
                token, "TOKO-20260916-7M4KQ5", Instant.parse("2026-09-16T10:00:00Z"), 9L, 200_000L, 20_000L,
                0L, new com.tailtopia.shop.order.domain.AddressSnapshot("Budi", "+628123", "DKI", "Jaksel",
                        "Kebayoran", "Jl. 1", "12160"));
    }

    @Test
    @DisplayName("v1.4.2 shop 4.2：存量订单（无迟到款、无重下单关联）→ 四个新键整键省略")
    void plainOrderOmitsReorderKeys() {
        Map<String, Object> w = wire(ShopOrderDetailView.of(plainOrder("ord-p"), List.of(), List.of(), Map.of(),
                null, Instant.parse("2026-09-16T10:05:00Z"), List.of()));
        assertThat(w).doesNotContainKeys("reorderedToOrderToken", "reorderedFromOrderNo",
                "reorderedFromOrderToken", "latePayment");
    }

    @Test
    @DisplayName("v1.4.2 shop 4.2：原单带 reorderedToOrderToken 与 latePayment{amount,paidAt,resolution}；新单带原单展示号与 token")
    @SuppressWarnings("unchecked")
    void reorderLinksAndLatePaymentOnTheWire() {
        com.tailtopia.shop.order.domain.ShopOrder original = plainOrder("ord-o");
        org.springframework.test.util.ReflectionTestUtils.setField(original, "latePaidAmount", 229_575L);
        org.springframework.test.util.ReflectionTestUtils.setField(original, "latePaidAt",
                Instant.parse("2026-09-16T11:10:00Z"));
        org.springframework.test.util.ReflectionTestUtils.setField(original, "latePaymentResolution", "REORDERED");
        Map<String, Object> w = wire(ShopOrderDetailView.of(original, List.of(), List.of(), Map.of(), null,
                Instant.parse("2026-09-16T12:00:00Z"), List.of(),
                new ShopOrderDetailView.ReorderLinks("ord-n", null, null)));
        assertThat(w).containsEntry("reorderedToOrderToken", "ord-n")
                .doesNotContainKeys("reorderedFromOrderNo", "reorderedFromOrderToken");
        Map<String, Object> late = (Map<String, Object>) w.get("latePayment");
        assertThat(late.keySet()).containsExactlyInAnyOrder("amount", "paidAt", "resolution");
        assertThat(((Number) late.get("amount")).longValue()).isEqualTo(229_575L);
        assertThat(late).containsEntry("resolution", "REORDERED");

        // 待处理的迟到款：resolution 为 null → 键省略，amount / paidAt 仍在
        org.springframework.test.util.ReflectionTestUtils.setField(original, "latePaymentResolution", null);
        Map<String, Object> pending = (Map<String, Object>) wire(ShopOrderDetailView.of(original, List.of(),
                List.of(), Map.of(), null, Instant.parse("2026-09-16T12:00:00Z"), List.of())).get("latePayment");
        assertThat(pending.keySet()).containsExactlyInAnyOrder("amount", "paidAt");

        Map<String, Object> n = wire(ShopOrderDetailView.of(plainOrder("ord-n"), List.of(), List.of(), Map.of(),
                null, Instant.parse("2026-09-16T12:00:00Z"), List.of(),
                new ShopOrderDetailView.ReorderLinks(null, "TOKO-20260916-7M4KQ5", "ord-o")));
        assertThat(n).containsEntry("reorderedFromOrderNo", "TOKO-20260916-7M4KQ5")
                .containsEntry("reorderedFromOrderToken", "ord-o")
                .doesNotContainKeys("reorderedToOrderToken", "latePayment");
    }

    @Test
    @DisplayName("v1.4.2 shop 6.2：amounts / paymentMethods / cancelledLines 内嵌键名；couponReturn / cancelReason 为枚举字面量")
    @SuppressWarnings("unchecked")
    void detailCloseoutFieldsOnTheWire() {
        Map<String, Object> w = wire(fullyPopulated("PAID", null));
        assertThat(((Map<String, Object>) w.get("amounts")).keySet()).isEqualTo(AMOUNTS_KEYS);
        assertThat(((Map<String, Object>) w.get("paymentMethods")).keySet()).isEqualTo(PAYMENT_METHODS_KEYS);
        List<Map<String, Object>> cl = (List<Map<String, Object>>) w.get("cancelledLines");
        assertThat(cl).hasSize(1);
        assertThat(cl.get(0).keySet()).isEqualTo(CANCELLED_LINE_KEYS);
        assertThat(w).containsEntry("couponReturn", "EXPIRED").containsEntry("cancelReason", "OUT_OF_STOCK");
        // 原有键一个不少（只追加不破坏）
        assertThat(w.keySet()).contains("orderToken", "displayNo", "status", "goodsSubtotal", "shippingFee",
                "shippingDiscount", "totalAmount", "payChannel", "coinAmount", "cashAmount", "expiresAt", "createdAt",
                "paymentIntentToken", "shipTo", "lines", "shippedAt", "deliveredAt", "completedAt",
                "returnWindowEndsAt", "packages", "attributionSource", "paymentStatus");
    }

    @Test
    @DisplayName("v1.4.2 shop 6.2：存量订单（新列为空）经旧重载 → amounts 默认 0、shippingDiscountSource / cancelReason / couponReturn / cancelledLines 整键省略")
    @SuppressWarnings("unchecked")
    void stockOrderNewFieldsDefaultOrOmitted() {
        Map<String, Object> w = wire(ShopOrderDetailView.of(plainOrder("ord-s"), List.of(), List.of(), Map.of(),
                null, Instant.parse("2026-09-16T10:05:00Z")));
        assertThat(w).doesNotContainKeys("paidAt", "cancelledAt", "cancelReason", "cancelledLines", "couponReturn",
                "paymentMethods");
        Map<String, Object> a = (Map<String, Object>) w.get("amounts");
        assertThat(a).doesNotContainKeys("shippingDiscountSource", "courierName", "courierServiceName");
        assertThat(((Number) a.get("discountCouponAmount")).longValue()).isZero();
        assertThat(((Number) a.get("insuranceFee")).longValue()).isZero();
        assertThat(((Number) a.get("goodsAmount")).longValue()).isEqualTo(200_000L);
        assertThat(((Number) a.get("payable")).longValue()).isEqualTo(220_000L);
    }
}
