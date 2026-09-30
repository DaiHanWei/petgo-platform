package com.tailtopia.place.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.place.domain.PlaceType;
import java.time.LocalDate;

/**
 * 打卡成功响应（V1.3.2 Story 1.1 · AC2.8），{@code 201}。
 *
 * <p>🔴 <b>没有距离值、没有坐标</b>（AD-4：防试探边界 + 坐标不外流）；对外只有 token。
 *
 * @param checkinToken 本次打卡的不可枚举标识（Story 1.5 发帖关联用）
 * @param placeToken   打卡落在的场所 token —— MERGED 场所打卡时是<b>保留方</b>的 token
 * @param placeName    场所名
 * @param placeType    场所类型（客户端按它选默认章面）
 * @param visitDate    打卡的 WIB 自然日
 * @param isNewStamp   本次写入前该宠物在该场所（当前 place_id）无任何打卡 → 新章（C2）；否则 C2b
 * @param visitCount   写入后该宠物在该场所的打卡总数（= 章的次数）
 * @param passportNo   宠物护照号（Story 1.2：首次打卡时同一事务内签发）
 * @param stampCount   写入后该宠物的章数 = 不同当前 place_id 数（Story 1.2；无分母）
 */
public record PlaceCheckinResponse(
        String checkinToken,
        String placeToken,
        String placeName,
        PlaceType placeType,
        LocalDate visitDate,
        // 显式钉键名：record 的 boolean 访问器叫 isNewStamp()，不钉的话序列化器可能把它当 bean 属性 newStamp。
        @JsonProperty("isNewStamp") boolean isNewStamp,
        long visitCount,
        String passportNo,
        int stampCount) {
}
