package com.tailtopia.order.service;

import com.tailtopia.consult.domain.ConsultOrder;
import com.tailtopia.consult.domain.ConsultOrderStatus;
import com.tailtopia.consult.repository.ConsultOrderRepository;
import com.tailtopia.order.dto.OrderPage;
import com.tailtopia.order.dto.OrderStatusColor;
import com.tailtopia.order.dto.OrderSummaryView;
import com.tailtopia.order.dto.OrderType;
import com.tailtopia.pay.domain.PaymentIntent;
import com.tailtopia.pay.domain.PaymentPurpose;
import com.tailtopia.pay.domain.PaymentStatus;
import com.tailtopia.pay.repository.PaymentIntentRepository;
import com.tailtopia.pay.service.PawCoinWalletService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.triage.domain.AiConsultOrder;
import com.tailtopia.triage.domain.AiConsultOrderStatus;
import com.tailtopia.triage.repository.AiConsultOrderRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

    public OrderCenterService(ConsultOrderRepository consultOrders, AiConsultOrderRepository aiOrders,
            PaymentIntentRepository intents, PawCoinWalletService wallet) {
        this.consultOrders = consultOrders;
        this.aiOrders = aiOrders;
        this.intents = intents;
        this.wallet = wallet;
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

    // ---- 映射（statusColor 后端权威；退款中 REFUNDING→INFO 蓝非红）----

    private OrderSummaryView mapVet(ConsultOrder o) {
        String statusCode;
        OrderStatusColor color;
        switch (o.getStatus()) {
            case IN_PROGRESS -> {
                statusCode = "IN_PROGRESS";
                color = OrderStatusColor.INFO;
            }
            case COMPLETED -> {
                statusCode = o.isRefundRejected() ? "COMPLETED_REFUND_REJECTED" : "COMPLETED";
                color = OrderStatusColor.SUCCESS;
            }
            case REFUNDING -> {
                statusCode = "REFUNDING";
                color = OrderStatusColor.INFO; // 退款处理中 → info 蓝，非 error 红（UX-DR2）
            }
            case REFUNDED -> {
                statusCode = "REFUNDED";
                color = OrderStatusColor.SUCCESS;
            }
            default -> {
                statusCode = o.getStatus().name();
                color = OrderStatusColor.INFO;
            }
        }
        return new OrderSummaryView(OrderType.VET_CONSULT.name(), o.getOrderToken(), statusCode,
                color.name(), o.getAmount(), o.getPayChannel() == null ? null : o.getPayChannel().name(),
                o.getCreatedAt());
    }

    private OrderSummaryView mapAi(AiConsultOrder o) {
        // 仅 COMPLETED 入订单中心。
        return new OrderSummaryView(OrderType.AI_UNLOCK.name(), o.getOrderToken(), "COMPLETED",
                OrderStatusColor.SUCCESS.name(), o.getAmount(),
                o.getPayChannel() == null ? null : o.getPayChannel().name(), o.getCreatedAt());
    }

    private OrderSummaryView mapTopup(PaymentIntent i) {
        // 仅 PAID 入订单中心（充值凭证）。对外 token 用 public_token。
        return new OrderSummaryView(OrderType.PAWCOIN_TOPUP.name(), i.getPublicToken(), "PAID",
                OrderStatusColor.SUCCESS.name(), i.getAmount(),
                i.getChannel() == null ? null : i.getChannel().name(), i.getCreatedAt());
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
