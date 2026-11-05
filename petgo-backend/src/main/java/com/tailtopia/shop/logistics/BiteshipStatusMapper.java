package com.tailtopia.shop.logistics;

import java.util.Locale;

/**
 * Biteship 运单状态 → 用户侧状态 key + 订单动作（v1.4.2 shop Story 5.1 AC8.1，D-12 映射表）。纯函数。
 *
 * <table>
 *   <tr><th>Biteship status</th><th>userStatusKey</th><th>OrderAction</th></tr>
 *   <tr><td>confirmed / scheduled / allocated / picking_up</td><td>awaiting_pickup</td><td>NONE</td></tr>
 *   <tr><td>picked</td><td>picked</td><td>SHIP</td></tr>
 *   <tr><td>in_transit / on_hold</td><td>in_transit</td><td>SHIP（兜底）</td></tr>
 *   <tr><td>dropping_off</td><td>out_for_delivery</td><td>SHIP（兜底）</td></tr>
 *   <tr><td>delivered</td><td>delivered</td><td>DELIVER</td></tr>
 *   <tr><td>courier_not_found</td><td>null（不变）</td><td>FLAG_PICKUP_FAILED</td></tr>
 *   <tr><td>return_in_transit / returned</td><td>null（不变）</td><td>FLAG_RETURNED</td></tr>
 *   <tr><td>rejected / disposed / cancelled</td><td>null（不变）</td><td>ALERT_OPS</td></tr>
 *   <tr><td>其他未知值</td><td>null（不变）</td><td>ALERT_OPS（原值照存 provider_status）</td></tr>
 * </table>
 */
public final class BiteshipStatusMapper {

    private BiteshipStatusMapper() {
    }

    /** 订单侧动作。 */
    public enum OrderAction {
        NONE,
        SHIP,
        DELIVER,
        FLAG_PICKUP_FAILED,
        FLAG_RETURNED,
        ALERT_OPS
    }

    /**
     * @param userStatusKey 用户侧状态；null = 不改已存值
     * @param known         是否 15 个已知值之一（未知值调用方 WARN）
     */
    public record Mapping(String userStatusKey, OrderAction action, boolean known) {
    }

    public static Mapping map(String status) {
        String s = status == null ? "" : status.trim().toLowerCase(Locale.ROOT);
        return switch (s) {
            case "confirmed", "scheduled", "allocated", "picking_up" ->
                    new Mapping("awaiting_pickup", OrderAction.NONE, true);
            case "picked" -> new Mapping("picked", OrderAction.SHIP, true);
            case "in_transit", "on_hold" -> new Mapping("in_transit", OrderAction.SHIP, true);
            case "dropping_off" -> new Mapping("out_for_delivery", OrderAction.SHIP, true);
            case "delivered" -> new Mapping("delivered", OrderAction.DELIVER, true);
            case "courier_not_found" -> new Mapping(null, OrderAction.FLAG_PICKUP_FAILED, true);
            case "return_in_transit", "returned" -> new Mapping(null, OrderAction.FLAG_RETURNED, true);
            case "rejected", "disposed", "cancelled" -> new Mapping(null, OrderAction.ALERT_OPS, true);
            default -> new Mapping(null, OrderAction.ALERT_OPS, false);
        };
    }
}
