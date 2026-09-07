package com.tailtopia.shop.returns.dto;

import java.time.Instant;
import java.util.List;

/**
 * 退货申请页的整页数据（Story 5.7）。
 *
 * <p>🔴 <b>{@code activeRequestToken} 非空时订单详情页的退货入口必须置灰</b>（UX-DR3 / C-12），
 * 并提示「已有退货申请处理中」—— 让用户点进来再被 409 挡住是最差的一种告知方式。
 */
public record ReturnEligibilityView(
        String orderToken,
        boolean eligible,
        /** 不可申请的原因（窗口已过 / 状态不对 / 已有进行中申请）。 */
        String ineligibleReason,
        /** 已有进行中的申请 token；非空即应置灰入口（UX-DR3）。 */
        String activeRequestToken,
        /** 退货窗口截止（签收 +7 日，服务端算好下发）。 */
        Instant returnWindowEndsAt,
        List<ReturnableLineView> lines,
        /** S-7 退货收件地址（用户自寄；🔴 不出现上门取件）。 */
        ReturnAddressView returnAddress) {

    /** 后台配置的退货收件地址（AB-11C 增配项）。 */
    public record ReturnAddressView(String receiverName, String receiverPhone, String addressText) {
    }
}
