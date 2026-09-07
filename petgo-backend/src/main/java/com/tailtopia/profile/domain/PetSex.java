package com.tailtopia.profile.domain;

/**
 * 宠物性别（V1.1.6 Story 1.1，落库 varchar，枚举名即存储值/API 契约值）。
 *
 * <p><b>选填</b>：DB 列可空，{@code null} = 未填。存量宠物一律 {@code null}，不回填。
 * 前端对 null 已有「请选择」占位态。
 *
 * <p>⚠️ <b>只有两个值，没有 UNKNOWN</b>。身份证那套（{@code IdCard.gender}）是
 * {@code MALE/FEMALE/UNKNOWN} 三值，两者是**独立字段、永不联动**：身份证的性别是建卡时
 * 冻结的快照，且**参与身份码生成**（{@link com.tailtopia.profile.service.CardNumberService}
 * → {@code TT+DDMMYY+SP+XXXX}）。改档案性别若联动，已发出的身份码会与卡面对不上。
 * <b>不要为了「统一」把两者归一化</b>——命名与取值域不同正是防误改的保险。
 *
 * <p>⚠️ 更新语义沿用 {@code PATCH /api/v1/pet-profiles/me} 的既有口径
 * <b>「仅非空字段被更新」</b>，故<b>不支持清空</b>（选了改不回未填）。这与同属架构 AD-17
 * 的手机号<b>不同</b>——手机号明确要求允许清空写 null，性别没有该要求，
 * 为它单开特例会破坏该接口的统一语义。
 */
public enum PetSex {
    MALE,
    FEMALE
}
