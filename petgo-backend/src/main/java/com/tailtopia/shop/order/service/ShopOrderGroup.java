package com.tailtopia.shop.order.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * 电商订单列表分组（v1.4.2 shop Story 6.2 AC1.2，D-18 / S-7）：<b>按 {@code displayStatus} 归组</b>（宽限期待支付归已取消）。
 *
 * <p>🔴 归组判据只有一处：{@link com.tailtopia.shop.order.domain.ShopOrder#displayStatusAt}（3.3）。本枚举的
 * {@link #ofDisplayStatus} 把展示状态映射到组；{@link #matchesSqlCondition} 是仓库 SQL 条件的逐字镜像，只供 L0 测试
 * 证明两者对同一组样例一致（{@code ShopOrderGroupingTest}），业务代码不得用它归组。
 *
 * <p>边界与 3.3 一致：{@code isPaymentExpiredAt} 为 {@code now > expires_at}，故待付款 = {@code expires_at >= now}，
 * 宽限期 = {@code expires_at < now}；{@code expires_at IS NULL} 的历史待付款单按待付款。
 */
public enum ShopOrderGroup {

    ALL("all"),
    PENDING_PAYMENT("pending_payment"),
    PENDING_SHIPMENT("pending_shipment"),
    TO_RECEIVE("to_receive"),
    COMPLETED("completed"),
    CANCELLED("cancelled");

    /** 「待收货」组的原始状态。 */
    public static final Set<ShopOrderStatus> TO_RECEIVE_STATUSES =
            EnumSet.of(ShopOrderStatus.SHIPPED, ShopOrderStatus.DELIVERED);

    /** 「已取消」组的原始状态（另加宽限期待支付）。 */
    public static final Set<ShopOrderStatus> CANCELLED_STATUSES =
            EnumSet.of(ShopOrderStatus.CANCELLED, ShopOrderStatus.REFUNDING, ShopOrderStatus.REFUNDED);

    private final String wire;

    ShopOrderGroup(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    /** 请求参数 → 分组；缺省 / 空白为 {@link #ALL}；非法值 422。 */
    public static ShopOrderGroup parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALL;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        for (ShopOrderGroup g : values()) {
            if (g.wire.equals(v)) {
                return g;
            }
        }
        throw AppException.validation("group 取值非法");
    }

    /** 展示状态 → 分组（不含 {@link #ALL}）。 */
    public static ShopOrderGroup ofDisplayStatus(ShopOrderStatus display) {
        return switch (display) {
            case PENDING_PAYMENT -> PENDING_PAYMENT;
            case PENDING_SHIPMENT -> PENDING_SHIPMENT;
            case SHIPPED, DELIVERED -> TO_RECEIVE;
            case COMPLETED -> COMPLETED;
            case CANCELLED, REFUNDING, REFUNDED -> CANCELLED;
        };
    }

    /** 仓库 SQL 条件的逐字镜像（仅 L0 对照用）。 */
    public boolean matchesSqlCondition(ShopOrderStatus status, Instant expiresAt, Instant now) {
        return switch (this) {
            case ALL -> true;
            case PENDING_PAYMENT -> status == ShopOrderStatus.PENDING_PAYMENT
                    && (expiresAt == null || !expiresAt.isBefore(now));
            case PENDING_SHIPMENT -> status == ShopOrderStatus.PENDING_SHIPMENT;
            case TO_RECEIVE -> TO_RECEIVE_STATUSES.contains(status);
            case COMPLETED -> status == ShopOrderStatus.COMPLETED;
            case CANCELLED -> CANCELLED_STATUSES.contains(status)
                    || (status == ShopOrderStatus.PENDING_PAYMENT && expiresAt != null && expiresAt.isBefore(now));
        };
    }
}
