package com.tailtopia.profile.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 编辑宠物档案请求（{@code PATCH /api/v1/pet-profiles/me}）。Story 2.8。
 *
 * <p>部分更新：全字段可空，仅非空字段被更新。owner 取自 JWT（仅改自己档案）。
 * cardToken 不变（编辑不重生成，已分享链接保持有效）。
 *
 * <p>Story 6.1 追加 {@code weightKg} / {@code neuterStatus}（FR-107 / FR-109 的输入）。
 * 🔒 体重是 PII 邻近的健康数据，<b>日志禁记</b>（NFR-5）。
 * 🔴 <b>可跳过、不设必填</b> —— 设必填会挡住既有建档转化。
 */
public record PetProfileUpdateRequest(
        @Size(max = 1024) String avatarUrl,
        @Size(max = 20, message = "宠物名不能超过 20 字") String name,
        @Size(max = 60) String breed,
        // 与创建端一致：允许生日=今天（@PastOrPresent），只拒未来。
        @PastOrPresent(message = "生日不能是未来") LocalDate birthday,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro,
        /** 🔒 体重（kg）。可空 = 不改动；用 {@code clearWeight} 显式清空。 */
        @DecimalMin(value = "0.1", message = "体重需大于 0")
        @DecimalMax(value = "200", message = "体重不能超过 200 kg") BigDecimal weightKg,
        /** NEUTERED / INTACT / UNKNOWN，可空 = 不改动。 */
        String neuterStatus) {

    /** 便捷构造：不改动体重与绝育状态（部分更新语义下 null = 不动）。 */
    public PetProfileUpdateRequest(String avatarUrl, String name, String breed,
            LocalDate birthday, String intro) {
        this(avatarUrl, name, breed, birthday, intro, null, null);
    }
}
