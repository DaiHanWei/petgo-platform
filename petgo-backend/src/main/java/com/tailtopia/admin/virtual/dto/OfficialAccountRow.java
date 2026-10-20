package com.tailtopia.admin.virtual.dto;

/**
 * 「运营发布身份」页官方号区块的一行（V1.4.0 ① Story 4.4 · P-7）。只承载展示等级卡需要的字段——
 * 官方号没有启停 / 物种定位等虚拟号专属操作。
 */
public record OfficialAccountRow(long id, String nickname, String avatarUrl) {
}
