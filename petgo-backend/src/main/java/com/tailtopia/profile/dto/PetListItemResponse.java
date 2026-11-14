package com.tailtopia.profile.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.service.PetSpeciesCooldown;
import com.tailtopia.profile.domain.PetStatus;
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
        /** 宠物状态 {@code ACTIVE} / {@code READ_ONLY}（Story 2.1 起读 {@code pet_profiles.status}）。 */
        String status,
        /** 用户是否在名额管理页选为「保持正常」（Story 2.1 · {@code pet_profiles.keep_active}）。 */
        boolean keepActive,
        /** 是否默认宠物（= {@code DefaultPetPolicy.pick} 选中的那只，旧版 App 不带 petId 时落到它）。 */
        @JsonProperty("isDefault") boolean isDefault,
        String tailsonalityBadge,
        /** 该宠物 Diary 数（与 Diary Tab 统计 {@code happyMomentCount} 同一实现）。 */
        long diaryCount,
        /** 细分类型（V1.5.0 pet-species · D-10）；{@code petType} 仍是大类三值（旧版 App 契约）。 */
        String species,
        /** 类型冷却中才有值（WIB 日期，最早可再改的那天）；未改过 / 已过冷却 → null（NON_NULL 省略）。 */
        LocalDate speciesChangeableFrom) {

    /** 正常状态的线上取值（= {@link PetStatus#ACTIVE}）；Story 2.1 起 {@code status} 读真实列。 */
    public static final String STATUS_ACTIVE = PetStatus.ACTIVE.name();

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
                p.getStatus() == null ? STATUS_ACTIVE : p.getStatus().name(),
                p.isKeepActive(),
                isDefault,
                tailsonalityBadge,
                diaryCount,
                p.getPetSpecies() == null ? null : p.getPetSpecies().name(),
                PetSpeciesCooldown.changeableFromOrNull(p, PetSpeciesCooldown.todayWib()));
    }
}
