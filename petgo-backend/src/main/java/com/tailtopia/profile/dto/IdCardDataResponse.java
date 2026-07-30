package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.PetProfile;
import java.time.LocalDate;

/**
 * 宠物身份证数据（Story 6.1，FR-49A）。供 6.2 三风格证件卡（KTP/Paspor/Pelajar）前端渲染。
 *
 * <p>{@code generated} = 是否已分配流水号（老用户 / 未生成为 false → 前端渲染「尚未生成」引导态）。
 * {@code hdUnlocked} = 是否已付费解锁高清图（Story 6.3，驱动前端 paywall vs 直接下载）。
 * {@code serialId} 仅作展示编号（如「编号 #123」），<b>绝不</b>作分享 / 深链 / 快照的资源定位符（AC3）。
 * 不含 owner PII、不含健康数据。Jackson NON_NULL：null 字段省略。
 */
public record IdCardDataResponse(
        boolean generated,
        Long serialId,
        String name,
        String petType,
        String breed,
        LocalDate birthday,
        String avatarUrl,
        String intro,
        boolean hdUnlocked) {

    public static IdCardDataResponse from(PetProfile p, boolean hdUnlocked) {
        return new IdCardDataResponse(
                p.getSerialId() != null,
                p.getSerialId(),
                p.getName(),
                p.getPetType() == null ? null : p.getPetType().name(),
                p.getBreed(),
                p.getBirthday(),
                p.getAvatarUrl(),
                p.getIntro(),
                hdUnlocked);
    }
}
