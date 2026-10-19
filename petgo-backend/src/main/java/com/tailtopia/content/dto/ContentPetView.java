package com.tailtopia.content.dto;

/**
 * 内容详情里的一只关联宠物（V1.4.0 multi-pet Story 3.1 · AC8，供 App F6 / F6b / F6c 胶囊）。
 *
 * <p>{@code petId} 与他人主页 / 访客视图既有寻址口径一致（{@code /api/v1/pets/{petId}/visitor/*}），不新增对外标识。
 * ⚠️ 字段<b>只追加</b>：③ 送礼会在末尾追加各自的礼物汇总，本 story 不预埋。
 *
 * @param petType 宠物类型枚举名（CAT / DOG / OTHER …）
 */
public record ContentPetView(long petId, String name, String avatarUrl, String petType) {
}
