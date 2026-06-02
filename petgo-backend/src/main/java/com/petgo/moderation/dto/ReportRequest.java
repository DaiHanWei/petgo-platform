package com.petgo.moderation.dto;

import com.petgo.moderation.domain.ReportReason;
import jakarta.validation.constraints.NotNull;

/**
 * 举报提交请求（Story 3.7）。单选类型必填；reporter 取自 JWT，不在 DTO。
 */
public record ReportRequest(@NotNull(message = "请选择举报类型") ReportReason reasonType) {
}
