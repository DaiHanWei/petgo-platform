package com.tailtopia.admin.shop.web;

import com.tailtopia.shop.order.domain.LogisticsException;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * 订单履约抽屉吸底操作的<b>唯一判定</b>（V1.4.2 shop-admin Story 6.1 · AD-14 · H2 ~ H7 / H13）。纯函数，表驱动单测。
 *
 * <ul>
 *   <li>待发货、无运单、无失败：有快递编码快照 → {@link Action#BITESHIP_CREATE}；存量单（无快递编码，必 409）→ {@link Action#MANUAL_FORM}；</li>
 *   <li>待发货且有活动 Biteship 运单 → {@link Action#MARK_SHIPPED}（H3）；</li>
 *   <li>运单失败（待发货、无活动运单）→ 三选一：重新提交 / 手工填写 / 联系用户后取消（H5）；{@code UNKNOWN_RESULT:} 时重提须先核实（AC6）；</li>
 *   <li>揽收失败（待发货或已发货）→ 同三选一，均先取消旧运单（H6，O-18）；</li>
 *   <li>已发货且无活动 Biteship 运单、无揽收失败 → 可再登记手工包裹（一单多包，H7）；</li>
 *   <li>已发货 → 整单 / 逐包裹手工送达（H13）；已发货 / 已签收 / 已完成显示发货来源（H4）。</li>
 * </ul>
 */
public final class DrawerActionsResolver {

    private DrawerActionsResolver() {
    }

    public enum Action {
        BITESHIP_CREATE,
        MARK_SHIPPED,
        WAYBILL_RETRY,
        PICKUP_RETRY,
        MANUAL_FORM,
        PICKUP_MANUAL,
        CONTACT_CANCEL,
        FLAG_SHORTAGE,
        MARK_DELIVERED,
        SHIPPED_INFO
    }

    /** 判定所需的订单事实。 */
    public record Facts(ShopOrderStatus status, String waybillFailReason, boolean waybillFailed,
            LogisticsException logisticsException, boolean activeBiteship, boolean activeAny, boolean hasCourierCode,
            boolean shortageFlagged) {
    }

    /**
     * @param unknownResult 运单失败原因为 {@code UNKNOWN_RESULT:}：重提前须去 Biteship 控制台核实（防止出两张运单）
     * @param waybillFailed 显示 H5 错误横幅
     * @param pickupFailed  显示 H6 错误横幅
     */
    public record Result(Set<Action> actions, boolean unknownResult, boolean waybillFailed, boolean pickupFailed) {

        public boolean has(String action) {
            return actions.contains(Action.valueOf(action));
        }
    }

    public static Result resolve(Facts f) {
        Set<Action> a = EnumSet.noneOf(Action.class);
        ShopOrderStatus st = f.status();
        boolean pending = st == ShopOrderStatus.PENDING_SHIPMENT;
        boolean shipped = st == ShopOrderStatus.SHIPPED;
        boolean pickupFailed = f.logisticsException() == LogisticsException.PICKUP_FAILED && (pending || shipped);
        boolean waybillFailed = f.waybillFailed() && pending && !f.activeBiteship() && !pickupFailed;
        boolean unknown = waybillFailed && f.waybillFailReason() != null && f.waybillFailReason().startsWith("UNKNOWN_RESULT:");

        if (pickupFailed) {
            a.add(Action.PICKUP_RETRY);
            a.add(Action.PICKUP_MANUAL);
            a.add(Action.CONTACT_CANCEL);
        } else if (waybillFailed) {
            if (f.hasCourierCode()) {
                a.add(Action.WAYBILL_RETRY);
            }
            a.add(Action.MANUAL_FORM);
            a.add(Action.CONTACT_CANCEL);
        } else if (pending) {
            if (f.activeBiteship()) {
                a.add(Action.MARK_SHIPPED);
            } else if (!f.activeAny()) {
                a.add(f.hasCourierCode() ? Action.BITESHIP_CREATE : Action.MANUAL_FORM);
            }
        } else if (shipped && !f.activeBiteship()) {
            a.add(Action.MANUAL_FORM);   // 一单多包：已发货后再登记手工包裹（不与活动 Biteship 运单并存）
        }
        if (pending && !f.shortageFlagged()) {
            a.add(Action.FLAG_SHORTAGE);
        }
        if (shipped) {
            a.add(Action.MARK_DELIVERED);
        }
        if (shipped || st == ShopOrderStatus.DELIVERED || st == ShopOrderStatus.COMPLETED) {
            a.add(Action.SHIPPED_INFO);
        }
        return new Result(Set.copyOf(a), unknown, waybillFailed, pickupFailed);
    }
}
