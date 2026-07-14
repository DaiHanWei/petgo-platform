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
 * @param csatScore     CSAT 分（可空）
 * @param csatComment   CSAT 评论（可空）
 * @param createdAt     建单时间
 * @param resolvedAt    结案时间（可空）
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
        Short csatScore,
        String csatComment,
        Instant createdAt,
        Instant resolvedAt) {
}
