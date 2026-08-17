package com.tailtopia.shop.returns.dto;

import com.tailtopia.shop.returns.domain.ReturnLine;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import java.time.Instant;
import java.util.List;

/**
 * 退货进度（Story 5.9 退货进度页 · 5.8 退款方式页共用）。
 *
 * <p>🔴 <b>两段金额分列</b>（5.8）：PawCoin 段没有「退回真钱」这个选项 ——
 * <b>不是展示后再拒绝</b>，是根本不下发那个选项（不给用户产生预期再打破）。
 * 故本视图只有 {@code cashDestination}，没有 coinDestination。
 *
 * <p>🔴 <b>溢价比例与金额一律取自后端配置</b>，前端不得硬编码 ——
 * 原型里的 `+5%` / `Rp 1.500` 是示例值不是规格（D-8 的比例与上限仍待财务定）。
 */
public record ReturnProgressView(
        String returnToken,
        String orderToken,
        String status,
        String returnType,
        boolean fullReturn,
        /** 回程运费归属：PLATFORM / USER。前端在原因选项右侧直接标出（5.7 AC）。 */
        String returnShipBearer,
        /** 去程运费是否退回（= 是否整单退，C-12）。 */
        boolean outboundFeeRefundable,
        String rejectReason,
        /** S-10：驳回时的质检照片与处置方式。 */
        String inspectionPhotoKeys,
        String rejectDisposal,
        String returnShipBackTrackingNo,
        /** S-7：待寄回态的超时倒计时锚点（7 日未寄回则关闭）。 */
        Instant shipbackDeadline,
        String shipbackTrackingNo,
        /** 现金段去向；null = 用户还没选。🔴 PawCoin 段没有对应字段。 */
        String cashDestination,
        String payoutChannel,
        /** 两段金额与两种溢价，全部由服务端算好（AD-2 整数累计法）。 */
        long coinRefund,
        long cashRefund,
        long compensationPremium,
        long incentivePremium,
        long shipbackReimbursement,
        long grandTotal,
        Instant createdAt,
        List<Line> lines) {

    public record Line(String productName, String specName, int qty, long lineRefundAmount) {
    }

    public static ReturnProgressView of(ReturnRequest r, String orderToken,
            List<ReturnLine> returnLines, List<String> lineLabels,
            com.tailtopia.shop.returns.service.RefundExecutionService.Quote quote) {
        List<Line> ls = new java.util.ArrayList<>();
        for (int i = 0; i < returnLines.size(); i++) {
            ReturnLine rl = returnLines.get(i);
            String label = i < lineLabels.size() ? lineLabels.get(i) : "";
            int sep = label.indexOf(" · ");
            ls.add(new Line(sep < 0 ? label : label.substring(0, sep),
                    sep < 0 ? "" : label.substring(sep + 3), rl.getQty(),
                    rl.getLineRefundAmount()));
        }
        return new ReturnProgressView(
                r.getPublicToken(), orderToken, r.getStatus().name(), r.getReturnType().name(),
                r.isFullReturn(),
                r.getReturnShipBearer() == null ? null : r.getReturnShipBearer().name(),
                r.isOutboundFeeRefundable(), r.getRejectReason(), r.getInspectionPhotoKeys(),
                r.getRejectDisposal() == null ? null : r.getRejectDisposal().name(),
                r.getReturnShipBackTrackingNo(), r.getShipbackDeadline(),
                r.getShipbackTrackingNo(),
                r.getCashDestination() == null ? null : r.getCashDestination().name(),
                r.getPayoutChannel() == null ? null : r.getPayoutChannel().name(),
                quote == null ? 0 : quote.coinRefund(),
                quote == null ? 0 : quote.cashRefund(),
                quote == null ? 0 : quote.compensationPremium(),
                quote == null ? 0 : quote.incentivePremium(),
                quote == null ? 0 : quote.shipbackReimbursement(),
                quote == null ? 0 : quote.grandTotal(),
                r.getCreatedAt(), ls);
    }
}
