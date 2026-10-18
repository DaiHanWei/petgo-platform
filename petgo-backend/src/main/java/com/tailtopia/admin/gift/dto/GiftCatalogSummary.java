package com.tailtopia.admin.gift.dto;

/** 摘要条三格（全目录口径，不随筛选变）：在线 / 其中专属（在线且专属）/ 已下线。 */
public record GiftCatalogSummary(long online, long exclusive, long offline) {
}
