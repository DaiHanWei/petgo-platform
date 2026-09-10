package com.tailtopia.admin.virtual.dto;

/**
 * B9 区块一「虚拟账号」摘要条三格（V1.3.0 Story 8.3 · AC1）：虚拟账号数 · 启用中 · 累计已发布。
 *
 * <p>⚠️ 三个数都取自**同一份列表**，不另查一遍：分开查的话跨秒时「启用中」可能比「总数」还大，
 * 而运营看到加不起来的数第一反应是数据坏了。
 *
 * @param total     虚拟账号总数（含已停用）
 * @param enabled   启用中的个数
 * @param published 累计已发布内容数（各号 {@code publishedCount} 之和）
 */
public record VirtualAccountSummary(long total, long enabled, long published) {
}
