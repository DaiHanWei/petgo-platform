package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 宠物档案响应（已授权资源可带数字 {@code id}；对外名片路径只用 {@code cardToken}）。
 * Jackson NON_NULL：null 字段省略；时间 ISO-8601 UTC。
 */
public record PetProfileResponse(
        Long id,
        String avatarUrl,
        String petType,
        String name,
        String breed,
        LocalDate birthday,
        String intro,
        String cardToken,
        boolean isSystemDefaultName,
        /** 🔒 体重（kg），Story 6.1。null = 用户还没填 —— 前端据此展示补全引导卡。 */
        java.math.BigDecimal weightKg,
        String neuterStatus,
        Instant createdAt) {

    public static PetProfileResponse from(PetProfile p) {
        return new PetProfileResponse(
                p.getId(),
                p.getAvatarUrl(),
                p.getPetType() == null ? null : p.getPetType().name(),
                p.getName(),
                p.getBreed(),
                p.getBirthday(),
                p.getIntro(),
                p.getCardToken(),
                // 内容审核 story 4：宠物名是否为违规重置的系统默认编码名。
                p.isSystemDefaultName(),
                p.getWeightKg(),
                p.getNeuterStatus() == null ? null : p.getNeuterStatus().name(),
                p.getCreatedAt());
    }
}
