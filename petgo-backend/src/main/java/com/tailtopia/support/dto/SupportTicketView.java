package com.tailtopia.support.dto;

import java.time.Instant;
import java.util.List;

/**
 * 工单**用户视图**（Story 4.1，AB-5 隐私契约红线）。
 *
 * <p><b>绝不含</b>内部字段：{@code ticket_internal_notes}、{@code handled_by}、{@code cs_rating}、
 * {@code csat_deadline}、{@code related_order_id}（内部 id）。用户只见自己可见的工单信息。
 * 附件以 {@code objectKey} 返回（OPEN-2）；展示时的现签 URL 留 4-2（用户）/4-4（admin），
 * 复用 shared/media {@code SignedUrlService}——本 story 不耦合 OSS 凭证。
 */
public record SupportTicketView(
        String ticketToken,
        String subject,
        String body,
        String contactType,
        String contactValue,
        boolean needContactCustomer,
        boolean contactedCustomer,
        String status,
        List<String> labels,
        List<String> attachmentObjectKeys,
        Short csatScore,
        String csatComment,
        Instant createdAt,
        Instant updatedAt,
        Instant resolvedAt) {
}
