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
        Instant resolvedAt,
        /**
         * 关联电商订单的**展示号**（Story 3-3）。非电商工单为 {@code null}。
         *
         * <p>🔴 <b>它不是对 {@code related_order_id} / {@code related_order_type} 禁令的放宽</b>：
         * 那两个是内部标识（自增主键可枚举、且跨表撞号；枚举是内部实现细节），
         * 而这个是**用户在自己订单列表里天天看见的订单号**。
         *
         * <p>它同时解决两件事：非空即「本工单关联的是电商单」（App 据此决定显不显示
         * WhatsApp 入口），值即深链预填内容（省一次请求）。
         *
         * <p>🔒 只在关联单确实属于请求者时下发。
         */
        String relatedShopOrderNo) {
}
