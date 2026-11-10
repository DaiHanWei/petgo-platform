package com.tailtopia.admin.shop.service;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.logistics.ReturnCause;
import com.tailtopia.shop.order.domain.ShopOrderStatus;
import com.tailtopia.shop.order.refund.CancelReason;

/**
 * 整单取消「原因类别 → 取消原因 / 处置动作」的服务端绑定（V1.4.2 shop-admin Story 5.1 · AD-9 · AC4）。纯函数。
 *
 * <p>类别决定退款、补偿、退券（{@link CancelReason} 的四个属性由类型推出），运营不能单独开关。不符一律 409：
 * <ul>
 *   <li>{@link Category#PLATFORM}：按来源映射 缺货 / 运单失败 / 揽收失败 / 快递原因退回（{@code return_cause = COURIER_FAULT}）；</li>
 *   <li>{@link Category#USER}：只接受 {@code return_cause = USER_FAULT} 的退回，且须已全部确认入库（D4）；</li>
 *   <li>{@link Category#LOST_DAMAGED}：只接受丢件破损来源；已签收 / 已完成走 {@code compensate}（订单状态不变）。</li>
 * </ul>
 * 退回待判定（{@code return_cause} 为空）不能取消；退回只在已发货时可取消（O-29：已签收只开重发，待发货只展示）。
 */
public final class CancelCategoryMapping {

    private CancelCategoryMapping() {
    }

    /** UI 三个原因类别。 */
    public enum Category {
        PLATFORM,
        USER,
        LOST_DAMAGED;

        public static Category parse(String raw) {
            if (raw != null) {
                for (Category c : values()) {
                    if (c.name().equalsIgnoreCase(raw.trim())) {
                        return c;
                    }
                }
            }
            throw AppException.validation("请选择原因类别").code("admin.err.v142.exception.categoryRequired");
        }
    }

    /** 落库动作：整单取消 / 揽收失败取消（先取消旧运单，事务外分流）/ 签收后赔付。 */
    public enum Action {
        CANCEL_WHOLE,
        PICKUP_CANCEL,
        COMPENSATE
    }

    public record Plan(Category category, CancelReason reason, Action action) {
    }

    /**
     * @param inboundDone {@code return_inbound_at} 非空
     * @throws AppException 409 类别与来源不符 / 退回待判定 / 用户原因未入库
     */
    public static Plan resolve(ExceptionSource source, Category category, ShopOrderStatus status,
            ReturnCause returnCause, boolean inboundDone) {
        if (source == null) {
            throw AppException.conflict("该订单不在异常订单队列").code("admin.err.v142.exception.notInQueue");
        }
        if (source == ExceptionSource.RETURNED) {
            if (returnCause == null) {
                throw AppException.conflict("退回原因待判定，不能取消").code("admin.err.v142.exception.returnUndecided");
            }
            if (status != ShopOrderStatus.SHIPPED) {
                throw AppException.conflict("该退回订单当前状态不能取消").code("admin.err.v142.exception.returnNotCancellable");
            }
        }
        switch (category) {
            case PLATFORM -> {
                CancelReason reason = switch (source) {
                    case OUT_OF_STOCK -> CancelReason.OUT_OF_STOCK;
                    case WAYBILL_FAILED -> CancelReason.WAYBILL_FAILED;
                    case PICKUP_FAILED -> CancelReason.PICKUP_FAILED;
                    case RETURNED -> returnCause == ReturnCause.COURIER_FAULT ? CancelReason.COURIER_FAULT : null;
                    case LOST_DAMAGED -> null;
                };
                if (reason == null) {
                    throw mismatch();
                }
                return new Plan(category, reason,
                        reason == CancelReason.PICKUP_FAILED ? Action.PICKUP_CANCEL : Action.CANCEL_WHOLE);
            }
            case USER -> {
                if (source != ExceptionSource.RETURNED || returnCause != ReturnCause.USER_FAULT) {
                    throw mismatch();
                }
                if (!inboundDone) {
                    throw AppException.conflict("退回商品尚未确认入库，不能提交用户原因的取消")
                            .code("admin.err.v142.exception.inboundRequired");
                }
                return new Plan(category, CancelReason.USER_FAULT_RETURNED, Action.CANCEL_WHOLE);
            }
            case LOST_DAMAGED -> {
                if (source != ExceptionSource.LOST_DAMAGED) {
                    throw mismatch();
                }
                return new Plan(category, CancelReason.LOST_DAMAGED,
                        status == ShopOrderStatus.SHIPPED ? Action.CANCEL_WHOLE : Action.COMPENSATE);
            }
            default -> throw mismatch();
        }
    }

    /** 部分取消的原因：只有待发货的平台原因来源（缺货 / 运单失败 / 揽收失败）可以部分取消。 */
    public static CancelReason lineReason(ExceptionSource source) {
        if (source == null) {
            throw AppException.conflict("该订单不在异常订单队列").code("admin.err.v142.exception.notInQueue");
        }
        return switch (source) {
            case OUT_OF_STOCK -> CancelReason.OUT_OF_STOCK;
            case WAYBILL_FAILED -> CancelReason.WAYBILL_FAILED;
            case PICKUP_FAILED -> CancelReason.PICKUP_FAILED;
            default -> throw mismatch();
        };
    }

    /** 某来源下哪些类别可选（页面单选的可用态，与 {@link #resolve} 同一规则）。 */
    public static boolean allowed(ExceptionSource source, Category category, ShopOrderStatus status,
            ReturnCause returnCause) {
        try {
            resolve(source, category, status, returnCause, true);
            return true;
        } catch (AppException e) {
            return false;
        }
    }

    private static AppException mismatch() {
        return AppException.conflict("原因类别与该订单的异常来源不符").code("admin.err.v142.exception.categoryMismatch");
    }
}
