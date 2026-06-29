package com.tailtopia.profile.dto;

/**
 * 创建里程碑分享响应（P-35 分享链接）。只回不可枚举 {@code shareToken}；对外 URL 由客户端拼
 * （{@code kH5BaseUrl + /m/ + token}，与名片 {@code petCardShareUrl} 同款），后端不外露顺序 id / 主机名。
 */
public record MilestoneShareResponse(String shareToken) {
}
