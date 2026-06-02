package com.petgo.auth.domain;

/**
 * 宠物状态（FR-0F，落库 varchar）。A 我有宠物 / B 暂无但计划养 / C 宠物爱好者。
 * 本 Story 仅建可空字段；实际写入在 Story 1.6。
 */
public enum PetStatus {
    A,
    B,
    C
}
