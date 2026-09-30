package com.tailtopia.place.dto;

import com.tailtopia.place.domain.PlaceAvailability;

/**
 * 帖子详情里的「打卡场所条」（V1.3.2 Story 1.5 · AC5 · AD-10）。
 *
 * @param token  场所 token（按打卡<b>当前</b> place_id 取 → 合并后是保留方）
 * @param name   场所名
 * @param status ACTIVE / UNAVAILABLE（下架 / 软删 / 异常 MERGED 一律 UNAVAILABLE，1.2 同一判定）
 */
public record CheckinPlaceView(String token, String name, PlaceAvailability status) {
}
