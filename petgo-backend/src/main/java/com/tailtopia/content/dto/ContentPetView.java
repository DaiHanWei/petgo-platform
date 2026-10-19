package com.tailtopia.content.dto;

/**
 * 内容详情里的一只关联宠物（V1.4.0 multi-pet Story 3.1 · AC8，供 App F6 / F6b / F6c 胶囊）。
 *
 * <p>{@code petId} 与他人主页 / 访客视图既有寻址口径一致（{@code /api/v1/pets/{petId}/visitor/*}），不新增对外标识。
 * ⚠️ 字段<b>只追加</b>：③ 送礼会在末尾追加各自的礼物汇总，本 story 不预埋。
 *
 * @param petType 宠物类型枚举名（CAT / DOG / OTHER …）
 * @param birthday 生日（可空；V1.4.0 L2 第二轮追加 —— 详情页送礼 A7 选宠物行显示年龄。与访客视图
 *                 {@code VisitorProfileResponse.birthday} 同口径对外，供客户端算年龄；null → NON_NULL 省略）
 */
public record ContentPetView(long petId, String name, String avatarUrl, String petType,
        java.time.LocalDate birthday) {

    /** 追加 {@code birthday} 前的四参构造（既有调用 / 测试不变，生日缺省）。 */
    public ContentPetView(long petId, String name, String avatarUrl, String petType) {
        this(petId, name, avatarUrl, petType, null);
    }
}
