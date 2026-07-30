package com.tailtopia.support.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 建工单请求（Story 4.1）。用户投诉：主题（可空）/ 正文 / 自填联系方式 / 标签 / ≤5 附件 objectKey。
 * userId 取自 JWT，不在 DTO。
 *
 * <p>{@code contactType} / {@code labels} 用 String 传入，由 {@code SupportTicketService} 解析枚举——
 * 非法值统一落 422（RFC 9457），而非 Bean 校验的 400（story AC3：contact_type 非法 422）。
 * 附件 ≤5 校验亦在 service（422），不在 Bean 层。
 */
public record CreateTicketRequest(
        @Size(max = 200, message = "主题过长") String subject,
        @NotBlank(message = "请填写投诉内容") @Size(max = 4000, message = "投诉内容过长") String body,
        @NotBlank(message = "请选择联系方式类型") String contactType,
        @NotBlank(message = "请填写联系方式") @Size(max = 255, message = "联系方式过长") String contactValue,
        Boolean needContact,
        String relatedOrderToken,
        List<String> labels,
        List<String> attachmentObjectKeys) {
}
