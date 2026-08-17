package com.tailtopia.order.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderDetailView;
import com.tailtopia.order.dto.OrderDisplayNo;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderRefundStage;
import com.tailtopia.order.dto.OrderStatusColor;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.dto.OrderType;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.refund.domain.ApprovalStatus;
import com.tailtopia.pay.refund.domain.RefundRequest;
import com.tailtopia.pay.refund.repository.RefundRequestRepository;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrder;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.order.service.ShopOrderCardService;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 订单中心聚合（Story 5.1，FR-54/UX-DR7）。跨 3 类订单源（兽医 {@code consult_orders} / AI {@code ai_consult_orders}
 * COMPLETED / 充值 {@code payment_intents.PAWCOIN_TOPUP} PAID）按 {@code created_at} 倒序合并为统一卡片契约 +
 * 游标分页 + 类型筛选 + PawCoin 余额汇总。**仅已付/持久订单（A-5：consult_requests 不入）**；HD 属 Epic 6 预留不查。
 *
 * <p>V1 低量（≤500 DAU）→ 跨源 in-memory 合并：各源查 {@code created_at < cursor} 取 {@code limit+1}，归并取 top。
 */
@Service
public class OrderCenterService {

    private final ConsultOrderRepository consultOrders;
    private final AiConsultOrderRepository aiOrders;
    private final PaymentIntentRepository intents;
    private final PawCoinWalletService wallet;
    private final RefundRequestRepository refunds;
    private final PetProfileRepository pets;
    // ── Story 3.9 追加的第 4 个数据源（电商）。既有 6 个依赖一行未动。
    private final ShopOrderRepository shopOrders;
    private final ShopOrderCardService shopCards;

    public OrderCenterService(ConsultOrderRepository consultOrders, AiConsultOrderRepository aiOrders,
            PaymentIntentRepository intents, PawCoinWalletService wallet,
            RefundRequestRepository refunds, PetProfileRepository pets,
            ShopOrderRepository shopOrders, ShopOrderCardService shopCards) {
        this.consultOrders = consultOrders;
        this.aiOrders = aiOrders;
        this.intents = intents;
        this.wallet = wallet;
        this.refunds = refunds;
        this.pets = pets;
        this.shopOrders = shopOrders;
        this.shopCards = shopCards;
    }

    /**
     * 订单列表（本人）。{@code type} 为空聚合 3 源；指定则仅该源（ID_HD 无源→空）。{@code cursor} 为末条
     * createdAt epochMillis（首页 null）；返回按 createdAt 倒序、含 nextCursor/hasMore/pawcoinBalance。
     */
    @Transactional(readOnly = true)
    public OrderPage listOrders(long userId, String type, String cursor, int limit) {
        OrderType filter = parseType(type);
        Instant before = cursor == null || cursor.isBlank()
                ? Instant.now()
                : Instant.ofEpochMilli(parseCursor(cursor));
        PageRequest page = PageRequest.of(0, limit + 1); // 各源多取 1 判 hasMore

        List<OrderSummaryView> merged = new ArrayList<>();
        if (filter == null || filter == OrderType.VET_CONSULT) {
            for (ConsultOrder o : consultOrders
                    .findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(userId, before, page)) {
                merged.add(mapVet(o));
            }
        }
        if (filter == null || filter == OrderType.AI_UNLOCK) {
            for (AiConsultOrder o : aiOrders.findByUserIdAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                    userId, AiConsultOrderStatus.COMPLETED, before, page)) {
                merged.add(mapAi(o));
            }
        }
        if (filter == null || filter == OrderType.PAWCOIN_TOPUP) {
            for (PaymentIntent i : intents.findByUserIdAndPurposeAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                    userId, PaymentPurpose.PAWCOIN_TOPUP, PaymentStatus.PAID, before, page)) {
                merged.add(mapTopup(i));
            }
            // 待支付充值（未过期 PENDING）也入订单中心，供用户「继续充值」（bug 20260720-313）。
            // 60min 窗口超时后 scanner 置 EXPIRED → 自然移出列表；终态 FAILED/EXPIRED 不入。
            for (PaymentIntent i : intents.findByUserIdAndPurposeAndStatusAndCreatedAtLessThanOrderByCreatedAtDesc(
                    userId, PaymentPurpose.PAWCOIN_TOPUP, PaymentStatus.PENDING, before, page)) {
                merged.add(mapTopupPending(i));
            }
        }
        // 🔴 Story 3.9：第 4 个分支【追加在 if 链末尾】，既有三个分支与四个映射器一行未改（AD-11 / 契约 O-1）。
        //    电商订单与虚拟商品订单在同一列表按时间倒序混排 —— 用户心智里「我的订单」就是一个地方（FR-101）。
        if (filter == null || filter == OrderType.ECOMMERCE) {
            for (ShopOrder o : shopOrders.findByUserIdAndCreatedAtLessThanOrderByCreatedAtDesc(
                    userId, before, page)) {
                merged.add(mapShop(o));
            }
        }
        // ID_HD：无源，Epic 6 接入（filter==ID_HD → merged 为空）。

        // 跨源归并：createdAt 倒序（tiebreak orderToken 稳定序，防同刻翻页抖动）。
        merged.sort(Comparator.comparing(OrderSummaryView::createdAt).reversed()
                .thenComparing(OrderSummaryView::orderToken));

        boolean hasMore = merged.size() > limit;
        List<OrderSummaryView> pageItems = hasMore ? merged.subList(0, limit) : merged;
        String nextCursor = hasMore
                ? String.valueOf(pageItems.get(pageItems.size() - 1).createdAt().toEpochMilli())
                : null;
        return new OrderPage(List.copyOf(pageItems), nextCursor, hasMore, wallet.balanceOf(userId));
    }

    /**
     * 订单详情（Story 5.3）。按 token 跨 3 源定位（token 全局唯一无源前缀，依次试）+ owner 校验（非 owner/不存在→404 防枚举）。
     * 兽医富化 pet（已删→petDeleted 占位 FR-54D）+ session + 退款子阶段；AI 附 triageTaskId；充值附 coins。
     */
    @Transactional(readOnly = true)
    public OrderDetailView getDetail(long userId, String orderToken) {
        Optional<ConsultOrder> vet = consultOrders.findByOrderToken(orderToken);
        if (vet.isPresent() && vet.get().getUserId() == userId) {
            return vetDetail(vet.get());
        }
        Optional<AiConsultOrder> ai = aiOrders.findByOrderToken(orderToken);
        if (ai.isPresent() && ai.get().getUserId() == userId
                && ai.get().getStatus() == AiConsultOrderStatus.COMPLETED) {
            return aiDetail(ai.get());
        }
        Optional<PaymentIntent> top = intents.findByPublicToken(orderToken);
        if (top.isPresent() && top.get().getUserId() == userId
                && top.get().getPurpose() == PaymentPurpose.PAWCOIN_TOPUP
                && (top.get().getStatus() == PaymentStatus.PAID
                        || top.get().getStatus() == PaymentStatus.PENDING)) {
            return topupDetail(top.get());
        }
        // 🔴 Story 3.9 追加：形状与上面三段一致（先按 token 查，再校验 owner），
        //    越权与不存在同为 404 —— 这是防枚举，不是权限提示。
        Optional<ShopOrder> shop = shopOrders.findByPublicToken(orderToken);
        if (shop.isPresent() && shop.get().getUserId() == userId) {
            return shopDetail(shop.get());
        }
        throw AppException.notFound("订单不存在");
    }

    private OrderDetailView vetDetail(ConsultOrder o) {
        String statusCode = vetStatusCode(o);
        OrderStatusColor color = vetStatusColor(o.getStatus());
        // 宠物已删（FR-54D）：硬删后 findById 空 → petDeleted 占位，订单仍返 200。
        PetProfile pet = pets.findById(o.getPetProfileId()).orElse(null);
        boolean petDeleted = pet == null;
        // 退款子阶段（REFUNDING/refund_rejected 时派生）。
        OrderRefundStage stage = null;
        Long refundNet = null;
        if ("COMPLETED_REFUND_REJECTED".equals(statusCode)) {
            stage = OrderRefundStage.REJECTED;
        } else if (o.getStatus() == ConsultOrderStatus.REFUNDING) {
            RefundRequest r = refunds.findByOrderId(o.getId()).orElse(null);
            stage = refundStageOf(r);
            if (r != null && r.getNetAmount() > 0) {
                refundNet = r.getNetAmount();
            }
        }
        return new OrderDetailView(OrderType.VET_CONSULT.name(), o.getOrderToken(),
                OrderDisplayNo.of(OrderDisplayNo.VET_CONSULT, o.getId(), o.getCreatedAt()), statusCode, color.name(),
                o.getAmount(), o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCreatedAt(), o.getPaidAt(),
                petDeleted ? null : pet.getName(),
                petDeleted || pet.getPetType() == null ? null : pet.getPetType().name(),
                petDeleted ? null : pet.getAvatarUrl(),
                petDeleted,
                o.getSessionStartedAt(), o.getSessionEndedAt(),
                stage == null ? null : stage.name(), refundNet, null, null, o.getConsultSessionId());
    }

    private OrderDetailView aiDetail(AiConsultOrder o) {
        return new OrderDetailView(OrderType.AI_UNLOCK.name(), o.getOrderToken(),
                OrderDisplayNo.of(OrderDisplayNo.AI_UNLOCK, o.getId(), o.getCreatedAt()), "COMPLETED",
                OrderStatusColor.SUCCESS.name(), o.getAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCreatedAt(), o.getPaidAt(),
                null, null, null, false, null, null, null, null, null, o.getTriageTaskId(), null);
    }

    private OrderDetailView topupDetail(PaymentIntent i) {
        // 待支付充值（bug 20260720-313）：PENDING + WARN；币未到账 → coins 不显（仅 PAID 显）。
        boolean paid = i.getStatus() == PaymentStatus.PAID;
        String statusCode = paid ? "PAID" : "PENDING";
        String color = (paid ? OrderStatusColor.SUCCESS : OrderStatusColor.WARN).name();
        return new OrderDetailView(OrderType.PAWCOIN_TOPUP.name(), i.getPublicToken(),
                OrderDisplayNo.of(OrderDisplayNo.TOPUP, i.getId(), i.getCreatedAt()), statusCode,
                color, i.getAmount(),
                i.getChannel() == null ? null : i.getChannel().name(),
                i.getCreatedAt(), null,
                null, null, null, false, null, null, null, null, paid ? i.getAmount() : null, null, null);
    }

    /** 退款子阶段派生（by approval_status）。 */
    private static OrderRefundStage refundStageOf(RefundRequest r) {
        if (r == null || r.getApprovalStatus() == null) {
            return OrderRefundStage.AWAITING_METHOD; // 未填收款
        }
        return switch (r.getApprovalStatus()) {
            case PENDING_APPROVAL -> OrderRefundStage.AWAITING_APPROVAL;
            case APPROVED -> OrderRefundStage.AWAITING_PAYOUT;
            case PROCESSING -> OrderRefundStage.PROCESSING;
            case DONE, REJECTED -> OrderRefundStage.PROCESSING; // 理论不在 REFUNDING 出现，防御
        };
    }

    // ---- 映射（statusColor 后端权威；退款中 REFUNDING→INFO 蓝非红）----

    private OrderSummaryView mapVet(ConsultOrder o) {
        String statusCode = vetStatusCode(o);
        return new OrderSummaryView(OrderType.VET_CONSULT.name(), o.getOrderToken(),
                OrderDisplayNo.of(OrderDisplayNo.VET_CONSULT, o.getId(), o.getCreatedAt()), statusCode,
                vetStatusColor(o.getStatus()).name(), o.getAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(), o.getCreatedAt());
    }

    /** 兽医订单 statusCode（含 refund_rejected 子变体）。 */
    private static String vetStatusCode(ConsultOrder o) {
        return switch (o.getStatus()) {
            case IN_PROGRESS -> "IN_PROGRESS";
            case COMPLETED -> o.isRefundRejected() ? "COMPLETED_REFUND_REJECTED" : "COMPLETED";
            case REFUNDING -> "REFUNDING";
            case REFUNDED -> "REFUNDED";
        };
    }

    /** 兽医 statusColor：进行中/退款中→INFO（蓝非红 UX-DR2）；完成/已退款→SUCCESS。 */
    private static OrderStatusColor vetStatusColor(ConsultOrderStatus status) {
        return switch (status) {
            case IN_PROGRESS, REFUNDING -> OrderStatusColor.INFO;
            case COMPLETED, REFUNDED -> OrderStatusColor.SUCCESS;
        };
    }

    private OrderSummaryView mapAi(AiConsultOrder o) {
        // 仅 COMPLETED 入订单中心。
        return new OrderSummaryView(OrderType.AI_UNLOCK.name(), o.getOrderToken(),
                OrderDisplayNo.of(OrderDisplayNo.AI_UNLOCK, o.getId(), o.getCreatedAt()), "COMPLETED",
                OrderStatusColor.SUCCESS.name(), o.getAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(), o.getCreatedAt());
    }

    private OrderSummaryView mapTopup(PaymentIntent i) {
        // 仅 PAID 入订单中心（充值凭证）。对外 token 用 public_token。
        return new OrderSummaryView(OrderType.PAWCOIN_TOPUP.name(), i.getPublicToken(),
                OrderDisplayNo.of(OrderDisplayNo.TOPUP, i.getId(), i.getCreatedAt()), "PAID",
                OrderStatusColor.SUCCESS.name(), i.getAmount(),
                i.getChannel() == null ? null : i.getChannel().name(), i.getCreatedAt());
    }

    private OrderSummaryView mapTopupPending(PaymentIntent i) {
        // 待支付充值（bug 20260720-313）：WARN 徽章 + PENDING 状态，前端据此展示「继续充值」入口。
        return new OrderSummaryView(OrderType.PAWCOIN_TOPUP.name(), i.getPublicToken(),
                OrderDisplayNo.of(OrderDisplayNo.TOPUP, i.getId(), i.getCreatedAt()), "PENDING",
                OrderStatusColor.WARN.name(), i.getAmount(),
                i.getChannel() == null ? null : i.getChannel().name(), i.getCreatedAt());
    }

    // ---- Story 3.9 电商（独立映射方法，不与既有四个映射器纠缠）----

    /**
     * 电商订单卡片。
     *
     * <p>🔴 卡片要给出「买了什么」：首个商品名 + 规格 + 主图 + 件数，
     * 否则一列订单卡看起来全都一样，用户找不到自己要的那一单（FR-101）。
     */
    private OrderSummaryView mapShop(ShopOrder o) {
        // 取首图/首个商品名的三表串查封装在 shop 模块（ShopOrderCardService），
        // 这里只多一个依赖 —— 275 行的共享聚合器每多注入一个仓储就多一次撞车机会。
        ShopOrderCardService.CardInfo card = shopCards.of(o.getId());
        return new OrderSummaryView(OrderType.ECOMMERCE.name(), o.getPublicToken(),
                OrderDisplayNo.of(OrderDisplayNo.ECOMMERCE, o.getId(), o.getCreatedAt()),
                o.getStatus().name(), shopStatusColor(o.getStatus()).name(), o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(), o.getCreatedAt(),
                card.thumbnailUrl(), card.itemTitle(), card.itemCount());
    }

    private OrderDetailView shopDetail(ShopOrder o) {
        // 电商订单的完整详情（行、地址、倒计时）走 Story 3.8 的专用页面；
        // 这里只补齐订单中心统一契约需要的那几项，形状与既有三个 detail 一致。
        return new OrderDetailView(OrderType.ECOMMERCE.name(), o.getPublicToken(),
                OrderDisplayNo.of(OrderDisplayNo.ECOMMERCE, o.getId(), o.getCreatedAt()),
                o.getStatus().name(), shopStatusColor(o.getStatus()).name(), o.getTotalAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCreatedAt(), null,
                null, null, null, false, null, null, null, null, null, null, null);
    }

    /**
     * 电商状态色。
     *
     * <p>🔴 待支付用 WARN（有事要做），进行中的履约段用 INFO（蓝非红，不制造焦虑 UX-DR2），
     * 完成/取消用 SUCCESS/UNKNOWN 之外的既有语义 —— <b>取消不是错误</b>，用红色会让用户
     * 以为出了问题。
     */
    private static OrderStatusColor shopStatusColor(ShopOrderStatus status) {
        return switch (status) {
            case PENDING_PAYMENT -> OrderStatusColor.WARN;
            case PENDING_SHIPMENT, SHIPPED, DELIVERED, REFUNDING -> OrderStatusColor.INFO;
            case COMPLETED, CANCELLED, REFUNDED -> OrderStatusColor.SUCCESS;
        };
    }

    private static OrderType parseType(String type) {
        if (type == null || type.isBlank()) {
            return null;
        }
        try {
            return OrderType.valueOf(type);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("订单类型非法");
        }
    }

    private static long parseCursor(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            throw AppException.validation("游标非法");
        }
    }
}
