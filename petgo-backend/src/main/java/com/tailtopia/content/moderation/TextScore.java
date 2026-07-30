package com.tailtopia.content.moderation;

/**
 * 文本审核评分（归一化 0–1）。{@code topLabel} 为最高风险标签（DRUGS/PORN/...，无则 null）。
 * 阿里云 green20220302 返回 confidence 0–100，客户端归一到 0–1。
 */
public record TextScore(double riskScore, String topLabel) {
}
