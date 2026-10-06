package com.tailtopia.place.dto;

import java.util.List;

/**
 * 场所打卡请求（{@code POST /api/v1/places/{token}/checkins}，V1.3.2 Story 1.1 · AC2）。
 *
 * <p>🛡 <b>坐标只用于本次到场判定</b>：不落库、不进日志、不进埋点（AD-4）。
 * 本 record 刻意<b>不覆写 {@code toString}</b> 以外的任何东西 —— 但也<b>绝不能被 log</b>
 * （record 默认 {@code toString} 会把经纬度原样打出来）。
 *
 * <p>字段校验放在服务层（{@code PlaceCheckinService}）而不是 Bean Validation：
 * 校验失败的 ProblemDetail 必须是固定文案，不能回显坐标值。
 *
 * @param latitude  纬度（原始精度，客户端不做归一）
 * @param longitude 经度
 * @param petIds    本次一起打卡的宠物（本版本 = 当前唯一宠物），须全部属于本人
 */
public record PlaceCheckinRequest(Double latitude, Double longitude, List<Long> petIds) {
}
