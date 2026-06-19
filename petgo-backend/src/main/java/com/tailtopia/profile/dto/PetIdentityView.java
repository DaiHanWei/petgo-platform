package com.tailtopia.profile.dto;

/**
 * 宠物身份只读投影（兽医工作台用）。供 consult 域经 {@code PetProfileQueryService} 取会话宠物身份摘要，
 * 不让 consult 直访 {@code PetProfileRepository} 或 JPA 实体（保持模块边界）。
 *
 * <p>{@code species} 为 {@code PetType} 名（CAT/DOG/OTHER）。{@code ageMonths} 由生日折算的整月龄
 * （生日缺失则 null）。**不含性别**——V1 建档不收集性别，前端工作台对其兜底隐藏。
 */
public record PetIdentityView(
        String name,
        String species,
        Integer ageMonths) {
}
