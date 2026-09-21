package com.tailtopia.place.dto;

import com.tailtopia.place.domain.Place;

/**
 * 标记成功的响应（V1.3.0 batch-b1 Story 1.3 · AC6）。
 *
 * <p>🔴 **只回不可枚举 token**，不回自增 id（AD-1 Rule 3）。客户端拿它跳详情页（Story 1.5）。
 *
 * <p>⚠️ 刻意不回整个场所实体：那会让「创建」与「详情」两处各有一套投影，
 * 迟早分叉（而分叉的表现是详情页字段比创建返回的多/少，没人知道哪个才对）。
 * 需要完整信息就按 token 请求详情。
 *
 * @param token 场所的不可枚举对外标识
 */
public record PlaceCreatedResponse(String token) {

    public static PlaceCreatedResponse from(Place p) {
        return new PlaceCreatedResponse(p.getPublicToken());
    }
}
