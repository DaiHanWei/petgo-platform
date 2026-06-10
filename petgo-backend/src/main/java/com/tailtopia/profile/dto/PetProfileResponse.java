package com.petgo.profile.dto;

import com.petgo.profile.domain.PetProfile;
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
                p.getCreatedAt());
    }
}
