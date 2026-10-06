package com.tailtopia.profile.dto;

import java.time.Instant;

/**
 * 护照签发所需的宠物摘要（V1.3.2 Story 1.2 · AD-6）。profile → passport 的跨模块只读投影，
 * passport 包不直接注入 {@code PetProfileRepository}。
 *
 * @param petId     宠物 id（仅服务端内部使用，不外露）
 * @param name      宠物名（护照页页眉）
 * @param petType   物种枚举名（CAT / DOG / OTHER）—— 决定护照号物种段
 * @param createdAt 建档时刻 —— KTP 号源「卡须晚于建档」的判据
 */
public record PetPassportSubject(long petId, String name, String petType, Instant createdAt) {
}
