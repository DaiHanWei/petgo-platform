package com.tailtopia.profile.domain;

/**
 * 宠物大类（FR-11 / 决策 F6，落库 varchar，枚举名即存储值/API 契约值）。
 *
 * <p>V1.5.0 pet-species 起降为「大类」：由细分类型 {@link PetSpecies#group()} 推出，
 * 🔴 只经 {@code PetProfile.applySpecies} 改（{@code PetSpeciesWriteGuardTest} 守）。健康提醒、送礼物种排序、
 * 首页推荐分池、运营人群等业务逻辑继续按大类走。里程碑清单归属另见 {@code PetProfile.milestoneTrack}。
 */
public enum PetType {
    CAT,
    DOG,
    OTHER
}
