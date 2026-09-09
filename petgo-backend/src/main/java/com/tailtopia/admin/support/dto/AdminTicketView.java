package com.tailtopia.admin.support.dto;

import java.time.Instant;
import java.util.List;

/**
 * 后台客服工单视图（Story 4.7，列表 + 详情）。admin（已授 {@code support.handle}）可见联系方式/正文以便处理；
 * 用户端视图 {@code SupportTicketView} 另有隐私裁剪，二者不共用。
 *
 * @param ticketToken   工单 token
 * @param subject       标题
 * @param body          正文
 * @param contactType   联系方式类型（EMAIL/WHATSAPP）
 * @param contactValue  联系方式值（admin 处理用）
 * @param needContact   用户是否要求联系
 * @param contacted     是否已联系
 * @param status        状态（OPEN/IN_PROGRESS/RESOLVED/CLOSED）
 * @param labels        标签枚举名
 * @param attachmentCount 附件数
 * @param attachmentUrls 附件短 TTL 签名 URL（仅详情签发；列表恒空，bug 20260728-387）
 * @param relatedOrderToken 关联订单 token（可空；AB-5B 退款判定前置，bug 20260728-384）
 * @param refundToken   该订单退款单 token（可空，未发起退款则 null）
 * @param refundNeedDecision 退款需求判定态（PENDING/APPROVED/REJECTED，可空）
 * @param csatScore     CSAT 分（可空）
 * @param csatComment   CSAT 评论（可空）
 * @param createdAt     建单时间
 * @param resolvedAt    结案时间（可空）
 * @param orderAmount   关联订单金额（IDR，可空；V1.3.0 Story 2.7 订单摘要卡）
 * @param orderStatus   关联订单状态（可空）
 * @param orderPaidAt   关联订单支付时间（可空）
 * @param refundRejectReason 退款需求驳回原因（D-36，可空）
 */
public record AdminTicketView(
        String ticketToken,
        String subject,
        String body,
        String contactType,
        String contactValue,
        boolean needContact,
        boolean contacted,
        String status,
        List<String> labels,
        int attachmentCount,
        List<String> attachmentUrls,
        String relatedOrderToken,
        String refundToken,
        String refundNeedDecision,
        Short csatScore,
        String csatComment,
        Instant createdAt,
        Instant resolvedAt,
        Long orderAmount,
        String orderStatus,
        Instant orderPaidAt,
        String refundRejectReason) {

    /** 未结案（可关联 / 判定 / 结案）。 */
    public boolean open() {
        return "OPEN".equals(status) || "IN_PROGRESS".equals(status);
    }

    /** 判定区可用：未结案 且 已关联订单 且 判定态为空或 PENDING（防呆 F7-4）。 */
    public boolean refundDecidable() {
        return open() && relatedOrderToken != null
                && (refundNeedDecision == null || "PENDING".equals(refundNeedDecision));
    }
}
