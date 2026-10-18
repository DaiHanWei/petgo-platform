package com.tailtopia.profile.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.profile.domain.PetProfile;
import java.time.Instant;
import java.time.LocalDate;

/**
 * 「我的宠物」列表项（V1.4.0 multi-pet · {@code GET /pet-profiles/me/list}）。
 *
 * <p>App「我的」页头像一排需要生日、Diary 角标、性格小标，列表一次带齐，不为每只宠物再拉 {@code /me}。
 */
public record PetListItemResponse(
        Long id,
        String name,
        String avatarUrl,
        String petType,
        String breed,
        LocalDate birthday,
        Instant createdAt,
        /** 宠物状态：本 story 恒为 {@link #STATUS_ACTIVE}（{@code pet_profiles.status} 在 Story 2.1 才落列）。 */
        String status,
        /** 是否默认宠物（= {@code DefaultPetPolicy.pick} 选中的那只，旧版 App 不带 petId 时落到它）。 */
        @JsonProperty("isDefault") boolean isDefault,
        String tailsonalityBadge,
        /** 该宠物 Diary 数（与 Diary Tab 统计 {@code happyMomentCount} 同一实现）。 */
        long diaryCount) {

    /** Story 2.1 前的恒定状态值；2.1 起改为读 {@code pet_profiles.status}。 */
    public static final String STATUS_ACTIVE = "ACTIVE";

    public static PetListItemResponse from(PetProfile p, boolean isDefault, String tailsonalityBadge,
            long diaryCount) {
        return new PetListItemResponse(
                p.getId(),
                p.getName(),
                p.getAvatarUrl(),
                p.getPetType() == null ? null : p.getPetType().name(),
                p.getBreed(),
                p.getBirthday(),
                p.getCreatedAt(),
                STATUS_ACTIVE,
                isDefault,
                tailsonalityBadge,
                diaryCount);
    }
}
