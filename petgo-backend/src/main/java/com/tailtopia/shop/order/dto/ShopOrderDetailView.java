package com.tailtopia.shop.order.dto;

import com.tailtopia.pay.domain.PaymentFailureCategory;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.shop.order.domain.Shipment;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderLine;
import com.tailtopia.shop.order.service.ShopOrderPaymentService;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 电商订单详情（Story 3.8 待支付态 · Story 4.1 履约态）。
 *
 * <p>🔴 <b>{@code expiresAt} 是服务端时刻，客户端倒计时只据此渲染</b>（AD-8）——
 * 用客户端本地计时判定，改一下手机时间就能无限延长锁库存的时间。
 *
 * <p>🔴 三段金额分开下发：{@code coinAmount} / {@code cashAmount} 为 null 表示非混合支付。
 *
 * <p>🔴 <b>Story 4.1 只加字段、不改既有字段语义</b>（Epic 3 交接约定）：
 * {@code deliveredAt} 即「签收」时刻（SPEC-5），{@code returnWindowEndsAt} 由它 +7 日算出、
 * 服务端下发 —— 让 App 自己加 7 天，时区与设备时钟一错，用户就会看到一个和后端不一致的截止日。
 */
public record ShopOrderDetailView(
        String orderToken,
        /**
         * 对外展示号 {@code TOKO-yyyyMMdd-XXXXXX}（Story 4-3 · SHOP-FR-29）。
         *
         * <p>🔴 <b>{@code orderToken} 不删</b>：它仍是 App 路由与 API 的查询键。
         * 变的是<b>给人看的那一个</b> —— 此前本页展示的就是 22 位 {@code orderToken}，
         * 而订单中心列表/详情展示的是 {@code TOKO-…}，同一张单两个字符串，
         * 用户报给客服的号后台还搜不到。
         */
        String displayNo,
        String status,
        long goodsSubtotal,
        long shippingFee,
        long shippingDiscount,
        long totalAmount,
        String payChannel,
        Long coinAmount,
        Long cashAmount,
        Instant expiresAt,
        Instant createdAt,
        String paymentIntentToken,
        ShipTo shipTo,
        List<Line> lines,
        // ---------- Story 4.1 履约态 ----------
        Instant shippedAt,
        /** 🔴 「签收」时刻（SPEC-5）。退货窗口以此起算。 */
        Instant deliveredAt,
        Instant completedAt,
        /** 退货窗口截止（签收 +7 日）。未签收为 null。 */
        Instant returnWindowEndsAt,
        /** S-2 一单多包：逐条列出，各自标明送达状态。 */
        List<Package> packages,
        /**
         * 🔴 <b>整单归因来源</b>（Story 9.2）：供客户端 {@code toko_order_payment_succeeded}
         * 携带 {@code attribution_source}，与服务端行级归因<b>互为校验</b>。
         *
         * <p>⚠️ <b>权威值始终在服务端</b>（{@code shop_order_lines.entry_source}）。
         * 客户端事件会被广告拦截与丢包吃掉；两套一比就知道端上丢了多少，
         * 偏差过大即说明客户端埋点有丢失，<b>以服务端为准</b>。
         *
         * <p>取值见 {@link #attributionSourceOf(List)}。
         */
        String attributionSource,
        // ---------- Story 1-1 支付可感知（AD-S9(a)） ----------
        /**
         * 支付意图状态（{@code PENDING/PAID/FAILED/EXPIRED} 的 UPPER_SNAKE 字面量）。
         * 无支付单（纯 PawCoin 单）为 null。
         */
        String paymentStatus,
        /**
         * 支付失败类别（{@link PaymentFailureCategory} 的 UPPER_SNAKE 字面量）。未失败为 null。
         *
         * <p>🔒 分类落在后端一处，<b>不让 App 解析 {@code gateway_meta}</b>——meta 是网关回调原文，
         * 随时可能含 PII。它一个字节都不进本 DTO。
         */
        String paymentFailureCategory,
        // ---------- v1.4.2 shop Story 3.3 付款窗口（S-7） ----------
        /**
         * 对用户展示的状态：宽限期（订单窗已过、尚未关单）的待支付单为 {@code CANCELLED}，其余同 {@code status}
         * （{@link ShopOrder#displayStatusAt} 唯一实现）。
         */
        String displayStatus,
        /** 现在还能不能点「去支付」：与 {@code /pay} 放行同一判定（{@code ShopOrderPaymentService#canPay}）。 */
        boolean canPay,
        /** 付款截止时刻 = 订单 {@code expires_at}（App 倒计时；不是码失效时刻）。 */
        Instant paymentDeadline,
        // ---------- v1.4.2 shop Story 4.1 取消退款单（D-15 / C-9，末尾追加） ----------
        /**
         * 取消退款单（按创建时间升序，无则空数组）。🔒 收款账号只给末 4 位，内部 id 不出现。6.2 复用本字段。
         */
        List<ShopOrderRefundView> refunds,
        // ---------- v1.4.2 shop Story 4.2 迟到款处置（D-11 / D-19 / C-9，末尾追加；存量订单均为 null，NON_NULL 不下发） ----------
        /** 本单是被重新下单的原单时 = 新单 token（App F20「查看新订单」）。 */
        String reorderedToOrderToken,
        /** 本单是重新下单所建新单时 = 原单展示号 {@code display_no}（App F21「由订单 {no} 重新下单」）。 */
        String reorderedFromOrderNo,
        /** 本单是重新下单所建新单时 = 原单 token（C-9：新单可点回原单）。 */
        String reorderedFromOrderToken,
        /** 取消后到账（{@code late_paid_*}）及处理结果；无迟到款为 null。 */
        LatePayment latePayment,
        // ---------- v1.4.2 shop Story 5.1 物流（D-19 物流部分 / F7，末尾追加） ----------
        /** 最新活动运单的物流块；无运单或运单全被取消为 null（NON_NULL 不下发）。 */
        Logistics logistics,
        // ---------- v1.4.2 shop Story 6.2 详情收口（D-19 / F4 ~ F21，末尾追加；只搬运订单头快照与退款单金额，P-1） ----------
        /** 订单头 {@code paid_at}；未付款为 null。 */
        Instant paidAt,
        /** 订单头 {@code cancelled_at}（3.2 列）；存量 / 未取消为 null。 */
        Instant cancelledAt,
        /** 订单头 {@code cancel_reason}（{@code CancelReason} 名）；F12 / F13 状态头据此写原因。 */
        String cancelReason,
        /** 金额明细（订单头快照，不重算）。 */
        Amounts amounts,
        /** 支付方式两段及各自状态（{@link PaymentMethodsView}）；两段都无金额信息时为 null。 */
        PaymentMethodsView paymentMethods,
        /**
         * 部分取消的行（{@code refunded_qty > 0}，I5）；仅 {@code ShopOrderDetailAssembler} 装配（旧重载为 null）。
         * 无部分取消为空数组。
         */
        List<CancelledLine> cancelledLines,
        /** 已取消订单用过的券的退回结果（{@code CouponReturnStatus} 名）；未取消 / 未用券 / 旧重载为 null。 */
        String couponReturn) {

    /**
     * 金额明细（v1.4.2 shop Story 6.2 AC3，F4 / F7）：<b>只搬运订单头快照，不重算</b>（P-1）。
     *
     * @param goodsAmount            {@code goods_subtotal}
     * @param shippingDiscountSource {@code NONE / FREE_SHIPPING_RULE / COUPON}；存量单为 null
     * @param payable                {@code total_amount}（应付）
     */
    public record Amounts(long goodsAmount, long discountCouponAmount, long shippingFee, long shippingDiscount,
            String shippingDiscountSource, long insuranceFee, long payable, String courierName,
            String courierServiceName) {

        static Amounts of(ShopOrder o) {
            return new Amounts(o.getGoodsSubtotal(), o.getDiscountCouponAmount(), o.getShippingFee(),
                    o.getShippingDiscount(),
                    o.getShippingDiscountSource() == null ? null : o.getShippingDiscountSource().name(),
                    o.getInsuranceFee(), o.getTotalAmount(), o.getCourierName(), o.getCourierServiceName());
        }
    }

    /**
     * 部分取消行（v1.4.2 shop Story 6.2 AC3，F17）。
     *
     * @param lineIndex    对应 {@code lines} 数组下标
     * @param cancelledQty = 订单行 {@code refunded_qty}
     * @param refundAmount 该行 {@code LINE} 级退款单 {@code total_refund} 之和（可为 0）
     */
    public record CancelledLine(int lineIndex, String productName, String specName, int cancelledQty,
            long refundAmount) {
    }

    /**
     * 装配器补充（v1.4.2 shop Story 6.2）：行级退款合计与券退回结果。旧重载传 {@link #NONE}，
     * {@code cancelledLines} / {@code couponReturn} 随之为 null。
     *
     * @param lineRefundTotals 订单行 id → 该行 {@code LINE} 级退款合计
     */
    public record Extras(Map<Long, Long> lineRefundTotals, String couponReturn,
            /** analytics-pipeline Story 3.4：skuId → 行的商品 / SKU 公开 token；null 时 {@code lines} 不带 token。 */
            Map<Long, LineTokens> lineTokensBySkuId) {

        public static final Extras NONE = new Extras(null, null, null);

        /** 6.2 原签名（不带行 token）。 */
        public Extras(Map<Long, Long> lineRefundTotals, String couponReturn) {
            this(lineRefundTotals, couponReturn, null);
        }
    }

    /**
     * 订单行的公开 token（analytics-pipeline Story 3.4）：App 在 {@code toko_order_pay_tapped} 的 {@code items[]}
     * 里带 {@code product_token}，埋点漏斗「进入支付」一步才能按商品关联（D-13：业务库与埋点一律用 public token 关联）。
     *
     * @param productToken {@code shop_products.public_token}
     * @param skuToken     {@code shop_skus.public_token}
     */
    public record LineTokens(String productToken, String skuToken) {
    }

    /**
     * 物流块（v1.4.2 shop Story 5.1 AC13）：取<b>最新活动运单</b>（未被取代、未取消）。
     *
     * @param provider           {@code MANUAL} | {@code BITESHIP}
     * @param courierName        Biteship：订单 {@code courier_name} 快照；手工：承运商展示名
     * @param courierServiceName Biteship：订单 {@code courier_service_name} 快照；手工：null
     * @param waybillId          Biteship：{@code waybill_id}（可空，尚未分配）；手工：{@code tracking_no}
     * @param trackingUrl        手工：承运商官网；Biteship：{@code tracking_url}（可空）
     * @param statusKey          手工恒 null（不显示物流状态）；Biteship：{@code user_status_key}
     * @param exception          Story 5.2：{@link ShopOrder#hasUserVisibleDeliveryIssue()} 为真 → {@code DELIVERY_ISSUE}，否则 null
     *                           （揽收失败、停滞、未确认的退回、订单已不在待收货一律 null；{@code statusKey} 不受影响）
     * @param exceptionType      同上为真时 {@code RETURNED | LOST | DAMAGED}（C-6），否则 null
     */
    public record Logistics(String provider, String courierName, String courierServiceName, String waybillId,
            String trackingUrl, String statusKey, String exception, String exceptionType) {

        /** 用户侧配送异常（5.2 AC5.1）。 */
        public static final String DELIVERY_ISSUE = "DELIVERY_ISSUE";

        /** 最新活动运单 → 物流块；无活动运单 → null。 */
        public static Logistics of(ShopOrder o, List<Shipment> shipments) {
            Shipment latest = shipments.stream().filter(Shipment::isActive)
                    .max(java.util.Comparator.comparing(Shipment::getId,
                            java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                    .orElse(null);
            if (latest == null) {
                return null;    // 5.2 AC5.3：无运单时不另造对象（用户侧本就不提示揽收失败）
            }
            boolean issue = o.hasUserVisibleDeliveryIssue();
            String exception = issue ? DELIVERY_ISSUE : null;
            String exceptionType = issue ? o.getLogisticsException().name() : null;
            if (latest.isBiteship()) {
                return new Logistics(latest.getProvider().name(), o.getCourierName(), o.getCourierServiceName(),
                        latest.getWaybillId(), latest.trackingUrl(), latest.getUserStatusKey(), exception,
                        exceptionType);
            }
            return new Logistics(latest.getProvider().name(), latest.carrierDisplayName(), null,
                    latest.getTrackingNo(), latest.trackingUrl(), null, exception, exceptionType);
        }
    }

    /**
     * 迟到款（v1.4.2 shop Story 4.2 AC7.3）。
     *
     * @param resolution {@code null}（待处理）| {@code REORDERED} | {@code REFUNDED}
     */
    public record LatePayment(long amount, Instant paidAt, String resolution) {

        static LatePayment of(ShopOrder o) {
            return o.getLatePaidAmount() == null ? null
                    : new LatePayment(o.getLatePaidAmount(), o.getLatePaidAt(), o.getLatePaymentResolution());
        }
    }

    /** 重新下单的前后关联（{@code LatePaymentService#linksOf} 装配）；无关联为 {@link #NONE}。 */
    public record ReorderLinks(String reorderedToOrderToken, String reorderedFromOrderNo,
            String reorderedFromOrderToken) {

        public static final ReorderLinks NONE = new ReorderLinks(null, null, null);
    }

    /**
     * 旧版 App（&lt; 1.4.2）兼容视图（1.4.2 上线前旧版兼容检查）：
     * <ul>
     *   <li>{@code status} 改为 {@code displayStatus}——旧包只读 {@code status}，宽限期内否则「列表已取消、详情仍可支付」；</li>
     *   <li>{@code packages} 去掉已取消 / 已被重发取代的运单——旧包一律取 {@code packages.first}，否则重发后显示作废单号。</li>
     * </ul>
     * 新版不走这里（新版读 {@code displayStatus} / {@code logistics}）。
     */
    public ShopOrderDetailView forLegacyClient() {
        List<Package> current = packages == null ? null : packages.stream()
                .filter(p -> !p.superseded() && !"CANCELLED".equals(p.status()))
                .toList();
        return new ShopOrderDetailView(orderToken, displayNo, displayStatus == null ? status : displayStatus,
                goodsSubtotal, shippingFee, shippingDiscount, totalAmount, payChannel, coinAmount, cashAmount,
                expiresAt, createdAt, paymentIntentToken, shipTo, lines, shippedAt, deliveredAt, completedAt,
                returnWindowEndsAt, current, attributionSource, paymentStatus, paymentFailureCategory, displayStatus,
                canPay, paymentDeadline, refunds, reorderedToOrderToken, reorderedFromOrderNo, reorderedFromOrderToken,
                latePayment, logistics, paidAt, cancelledAt, cancelReason, amounts, paymentMethods, cancelledLines,
                couponReturn);
    }

    /**
     * 包裹（S-2）。
     *
     * <p>🔴 <b>{@code trackingUrl} 是承运商官网地址，App 只做跳转</b> ——
     * 不接承运商 API、不在 App 内渲染物流轨迹（FR-103）。
     */
    public record Package(String carrier, String carrierName, String trackingNo,
            String trackingUrl, String status, Instant shippedAt, Instant deliveredAt,
            // v1.4.2 shop Story 5.1（末尾追加）：来源与是否被快递原因重发取代
            String provider, boolean superseded) {

        static Package of(Shipment s) {
            return new Package(s.getCarrier(), s.carrierDisplayName(), s.getTrackingNo(), s.trackingUrl(),
                    s.getStatus().name(), s.getShippedAt(), s.getDeliveredAt(), s.getProvider().name(),
                    s.getSupersededAt() != null);
        }
    }

    /** 收货信息快照（下单时定格，不随地址簿改动）。🔒 含 PII，只下发给订单主人。 */
    public record ShipTo(String receiverName, String receiverPhone, String provinsi,
            String kotaKabupaten, String kecamatan, String addressLine, String kodePos) {
    }

    /**
     * 订单行。{@code returnPolicy} 是下单时定格的承诺（FR-104，退货时按它执行）。
     *
     * <p>{@code mainImageUrl}：商品主图 CDN 全 URL，2026-09-03 追加。无图为 null，端上走占位图。
     * ⚠️ 它是**读时派生**的（见 {@code ShopLineImageResolver}），不是下单快照 ——
     * 运营事后换主图，这里会跟着变。用途是「认出是哪件」，不是留证。
     */
    public record Line(String productName, String specName, long unitPrice, int qty,
            long lineTotal, String returnPolicy, String mainImageUrl,
            // analytics-pipeline Story 3.4（末尾追加，只增不改；旧版 App 忽略）：读时派生的公开 token，
            // 取不到（SKU 被物理删除 / 旧重载未传）为 null，NON_NULL 不下发。不是 PII。
            String productToken, String skuToken) {

        /** Story 3.4 前的 7 参签名（不带 token）。 */
        public Line(String productName, String specName, long unitPrice, int qty, long lineTotal,
                String returnPolicy, String mainImageUrl) {
            this(productName, specName, unitPrice, qty, lineTotal, returnPolicy, mainImageUrl, null, null);
        }
    }

    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            Map<Long, String> imageUrlBySkuId) {
        return of(o, lines, List.of(), imageUrlBySkuId, null);
    }

    /**
     * @param imageUrlBySkuId skuId → 主图 URL，由 {@code ShopLineImageResolver} 批量取。
     *     🔴 <b>刻意做成必传</b>：给个「不带图」的重载，下一个人照着写就又是一页占位图 ——
     *     下单后全链路无图（2026-09-03 stag 回归 P2）正是这么来的。没有图就传 {@code Map.of()}，
     *     那是一个显式的选择。
     */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId) {
        return of(o, lines, shipments, imageUrlBySkuId, null);
    }

    /**
     * Story 1-1：带支付意图的重载，多下发 {@code paymentStatus} / {@code paymentFailureCategory}。
     *
     * <p>🔴 <b>上面两个旧重载刻意保留</b>（委托本方法并传 {@code intent = null}）：它们在测试里被直接
     * 调用 5 次，改签名等于把无关的 5 处一起拖下水。
     *
     * @param intent 该订单的支付意图；纯 PawCoin 单（无 {@code payment_intent_token}）传 {@code null}，
     *     两个新字段随之为 null
     */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId, PaymentIntent intent) {
        return of(o, lines, shipments, imageUrlBySkuId, intent, Instant.now());
    }

    /** v1.4.2 shop Story 3.3：显式传入判定时刻（L0 注入时刻用）。无退款单（{@code refunds = []}）。 */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId, PaymentIntent intent, Instant now) {
        return of(o, lines, shipments, imageUrlBySkuId, intent, now, List.of());
    }

    /** v1.4.2 shop Story 4.1：带取消退款单（{@code ShopOrderRefundService#refundViewsOf}）；无重下单关联。 */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId, PaymentIntent intent, Instant now,
            List<ShopOrderRefundView> refunds) {
        return of(o, lines, shipments, imageUrlBySkuId, intent, now, refunds, ReorderLinks.NONE);
    }

    /** v1.4.2 shop Story 4.2：带重新下单关联（{@code LatePaymentService#linksOf}）；{@code latePayment} 取自订单本身。 */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId, PaymentIntent intent, Instant now,
            List<ShopOrderRefundView> refunds, ReorderLinks links) {
        return of(o, lines, shipments, imageUrlBySkuId, intent, now, refunds, links, Extras.NONE);
    }

    /** v1.4.2 shop Story 6.2：详情收口（{@code ShopOrderDetailAssembler} 唯一调用），带行级退款合计与券退回结果。 */
    public static ShopOrderDetailView of(ShopOrder o, List<ShopOrderLine> lines,
            List<Shipment> shipments, Map<Long, String> imageUrlBySkuId, PaymentIntent intent, Instant now,
            List<ShopOrderRefundView> refunds, ReorderLinks links, Extras extras) {
        Extras ex = extras == null ? Extras.NONE : extras;
        ReorderLinks rl = links == null ? ReorderLinks.NONE : links;
        var ship = o.shipTo();
        PaymentFailureCategory failureCategory = PaymentFailureCategory.of(intent);
        return new ShopOrderDetailView(
                o.getPublicToken(),
                o.getDisplayNo(),
                o.getStatus().name(),
                o.getGoodsSubtotal(),
                o.getShippingFee(),
                o.getShippingDiscount(),
                o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCoinAmount(),
                o.getCashAmount(),
                o.getExpiresAt(),
                o.getCreatedAt(),
                o.getPaymentIntentToken(),
                new ShipTo(ship.receiverName(), ship.receiverPhone(), ship.provinsi(),
                        ship.kotaKabupaten(), ship.kecamatan(), ship.addressLine(),
                        ship.kodePos()),
                lines.stream()
                        .map(l -> {
                            LineTokens tk = ex.lineTokensBySkuId() == null ? null
                                    : ex.lineTokensBySkuId().get(l.getSkuId());
                            return new Line(l.getProductName(), l.getSpecName(), l.getUnitPrice(),
                                    l.getQty(), l.getLineTotal(),
                                    l.getReturnPolicy() == null ? null : l.getReturnPolicy().name(),
                                    imageUrlBySkuId.get(l.getSkuId()),
                                    tk == null ? null : tk.productToken(), tk == null ? null : tk.skuToken());
                        })
                        .toList(),
                o.getShippedAt(),
                o.getDeliveredAt(),
                o.getCompletedAt(),
                o.returnWindowEndsAt(),
                shipments.stream()
                        .sorted(java.util.Comparator.comparing(Shipment::getId,
                                java.util.Comparator.nullsFirst(java.util.Comparator.naturalOrder())))
                        .map(Package::of)
                        .toList(),
                attributionSourceOf(lines),
                intent == null || intent.getStatus() == null ? null : intent.getStatus().name(),
                failureCategory == null ? null : failureCategory.name(),
                o.displayStatusAt(now).name(),
                ShopOrderPaymentService.canPay(o, intent, now),
                o.getExpiresAt(),
                refunds == null ? List.of() : List.copyOf(refunds),
                rl.reorderedToOrderToken(),
                rl.reorderedFromOrderNo(),
                rl.reorderedFromOrderToken(),
                LatePayment.of(o),
                Logistics.of(o, shipments),
                o.getPaidAt(),
                o.getCancelledAt(),
                o.getCancelReason() == null ? null : o.getCancelReason().name(),
                Amounts.of(o),
                PaymentMethodsView.of(o, now),
                ex.lineRefundTotals() == null ? null : cancelledLinesOf(lines, ex.lineRefundTotals()),
                ex.couponReturn());
    }

    /** {@code refunded_qty > 0} 的行（I5：部分取消复用该列，不新增 {@code cancelled_qty}）。 */
    static List<CancelledLine> cancelledLinesOf(List<ShopOrderLine> lines, Map<Long, Long> lineRefundTotals) {
        List<CancelledLine> out = new java.util.ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            ShopOrderLine l = lines.get(i);
            if (l.getRefundedQty() > 0) {
                out.add(new CancelledLine(i, l.getProductName(), l.getSpecName(), l.getRefundedQty(),
                        l.getId() == null ? 0L : lineRefundTotals.getOrDefault(l.getId(), 0L)));
            }
        }
        return List.copyOf(out);
    }

    /**
     * 整单归因来源：<b>全部行同源取该源；多源取 {@code mixed}；无源取 {@code unknown}</b>。
     *
     * <p>🔴 <b>多源不能挑第一条充数</b>：一单里既有复购卡进来的、又有自己逛进来的，
     * 报成其中任一个都会让 AB-13B 的分子分母各错一次 —— 而 AB-13B 是裁决
     * A-16（复购引擎是否成立）的唯一依据。宁可标成 {@code mixed} 让它落到单独一档。
     *
     * <p>🔒 出参恒为受控词表值，不含 PII。
     */
    public static String attributionSourceOf(List<ShopOrderLine> lines) {
        String seen = null;
        for (ShopOrderLine l : lines) {
            String src = l.getEntrySource();
            if (src == null || src.isBlank()) {
                continue;
            }
            if (seen == null) {
                seen = src;
            } else if (!seen.equals(src)) {
                return "mixed";
            }
        }
        return seen == null ? "unknown" : seen;
    }
}
