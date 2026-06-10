package com.tailtopia.profile.domain;

/**
 * 宠物类型（FR-11 / 决策 F6，落库 varchar，枚举名即存储值/API 契约值）。
 *
 * <p>必选结构化字段，**创建后不可修改**（{@code PetProfileUpdateRequest} 不含此字段，
 * 服务端无任何变更路径）。决定 FR-42 里程碑清单（CAT/DOG 30 项、OTHER 15 项），
 * 但里程碑本体属 mini-epic，本字段仅承载类型本身。
 */
public enum PetType {
    CAT,
    DOG,
    OTHER
}
