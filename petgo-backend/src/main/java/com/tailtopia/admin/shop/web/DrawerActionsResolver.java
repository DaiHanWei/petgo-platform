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
        SHIPPED_INFO,
        // V1.4.2 shop-admin Story 6.2（H8 ~ H12）
        DECIDE_RETURN,
        RESHIP,
        RETURN_INBOUND,
        MARK_LOST_DAMAGED,
        CLOSE_CLAIM,
        CLEAR_STALLED
    }

    /** 判定所需的订单事实。 */
    public record Facts(ShopOrderStatus status, String waybillFailReason, boolean waybillFailed,
            LogisticsException logisticsException, boolean activeBiteship, boolean activeAny, boolean hasCourierCode,
            boolean shortageFlagged, com.tailtopia.shop.logistics.ReturnCause returnCause, boolean inboundDone,
            boolean claimPending) {

        /** 6.1 形态（无退回原因 / 入库 / 待索赔事实）。 */
        public Facts(ShopOrderStatus status, String waybillFailReason, boolean waybillFailed,
                LogisticsException logisticsException, boolean activeBiteship, boolean activeAny, boolean hasCourierCode,
                boolean shortageFlagged) {
            this(status, waybillFailReason, waybillFailed, logisticsException, activeBiteship, activeAny, hasCourierCode,
                    shortageFlagged, null, false, false);
        }
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
        // V1.4.2 shop-admin Story 6.2：退回判定（H8，已发货 / 已签收；待发货只展示，O-29）→ 快递原因重发（H9）/ 用户原因按行入库
        LogisticsException le = f.logisticsException();
        boolean delivered = st == ShopOrderStatus.DELIVERED;
        // 复审修：只给已发货 —— 退回入库与用户原因取消都要求 SHIPPED，已签收判了用户原因会走进死路（已签收只展示横幅）
        if (le == LogisticsException.RETURNED && shipped) {
            if (f.returnCause() == null) {
                a.add(Action.DECIDE_RETURN);
            } else if (f.returnCause() == com.tailtopia.shop.logistics.ReturnCause.COURIER_FAULT) {
                a.add(Action.RESHIP);
            } else if (!f.inboundDone()) {
                a.add(Action.RETURN_INBOUND);
            }
        }
        // H10：已发货 / 已签收 / 已完成可标丢件破损（退回、已标丢件破损的不再给入口）
        // 复审修：只在无异常或停滞时给（confirmLogisticsException 对揽收失败等其他异常一律 409）
        if ((shipped || delivered || st == ShopOrderStatus.COMPLETED)
                && (le == null || le == LogisticsException.STALLED)) {
            a.add(Action.MARK_LOST_DAMAGED);
        }
        if (f.claimPending()) {
            a.add(Action.CLOSE_CLAIM);   // H11：任何订单状态可结案
        }
        if (le == LogisticsException.STALLED) {
            a.add(Action.CLEAR_STALLED);   // H12
        }
        return new Result(Set.copyOf(a), unknown, waybillFailed, pickupFailed);
    }
}
