package com.tailtopia.profile.domain;

/** 宠物类型修改来源（V1.5.0 pet-species · D-5）：用户改 / FR-208 存量迁移 / 后台运营代改。落库 varchar。 */
public enum PetSpeciesChangeSource {
    USER,
    MIGRATION,
    ADMIN
}
