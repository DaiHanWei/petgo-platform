package com.tailtopia.content.dto;

/**
 * 创建/复用单条内容分享链接的响应（Story 9.3）。
 *
 * <p>只回 token —— 完整 URL 由客户端按 H5 子域拼（与名片 {@code /p/}、里程碑 {@code /m/} 同约定）。
 */
public record ContentShareLinkResponse(String shareToken) {
}
