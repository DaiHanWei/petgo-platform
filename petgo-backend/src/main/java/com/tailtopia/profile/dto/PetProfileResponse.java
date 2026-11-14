package com.tailtopia.profile.dto;

import com.tailtopia.profile.domain.PetProfile;
import com.tailtopia.profile.health.PregnancyFlag;
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
        // 性别（V1.1.6 Story 1.1）。未填 → null，经 Jackson NON_NULL **整个键不出现**。
        String sex,
        String intro,
        String cardToken,
        boolean isSystemDefaultName,
        /** 🔒 体重（kg），Story 6.1。null = 用户还没填 —— 前端据此展示补全引导卡。 */
        java.math.BigDecimal weightKg,
        String neuterStatus,
        Instant createdAt,
        /**
         * Tailsonality 角色小标（V1.3.2 Story 3.3 · AC4）：佩戴 + 已解锁时为该结果的 4 字母（如 {@code ENTJ}），
         * 否则 null（NON_NULL 省略）。只给 4 字母，不给结果 token。
         */
        String tailsonalityBadge,
        // ===== V1.5.0 健康记录提醒 Story 1.1（架构增量 §6.2，FR-162）=====
        /** 家里有猫（只对 DOG 有意义）；null = 没填。 */
        Boolean catInHome,
        /** 怀孕 / 哺乳；sex=MALE 恒 false。 */
        boolean pregnantNursing,
        /** 自动关闭日（WIB）= 设为「是」那天 + 4 个日历月；未设为「是」→ null。 */
        LocalDate pregnantAutoOffDate) {

    /** 兼容构造（V1.5.0 之前的 14 字段形状）：健康状态字段取默认。 */
    public PetProfileResponse(Long id, String avatarUrl, String petType, String name, String breed,
            LocalDate birthday, String sex, String intro, String cardToken, boolean isSystemDefaultName,
            java.math.BigDecimal weightKg, String neuterStatus, Instant createdAt, String tailsonalityBadge) {
        this(id, avatarUrl, petType, name, breed, birthday, sex, intro, cardToken, isSystemDefaultName, weightKg,
                neuterStatus, createdAt, tailsonalityBadge, null, false, null);
    }

    /** 无小标（创建路径等）：恒 null。 */
    public static PetProfileResponse from(PetProfile p) {
        return from(p, null);
    }

    /** 带角色小标（{@code TailsonalityBadgeQuery.badgeOf}）。 */
    public static PetProfileResponse from(PetProfile p, String tailsonalityBadge) {
        return new PetProfileResponse(
                p.getId(),
                p.getAvatarUrl(),
                p.getPetType() == null ? null : p.getPetType().name(),
                p.getName(),
                p.getBreed(),
                p.getBirthday(),
                p.getSex() == null ? null : p.getSex().name(),
                p.getIntro(),
                p.getCardToken(),
                // 内容审核 story 4：宠物名是否为违规重置的系统默认编码名。
                p.isSystemDefaultName(),
                p.getWeightKg(),
                p.getNeuterStatus() == null ? null : p.getNeuterStatus().name(),
                p.getCreatedAt(),
                tailsonalityBadge,
                p.getCatInHome(),
                p.isPregnantNursingEffective(),
                pregnantAutoOffDate(p));
    }

    /** 怀孕标记的自动关闭日（{@link PregnancyFlag#autoOffDate}）；未设为「是」→ null。 */
    private static LocalDate pregnantAutoOffDate(PetProfile p) {
        return p.isPregnantNursingEffective() ? PregnancyFlag.autoOffDate(p.getPregnantSetAt()) : null;
    }
}
