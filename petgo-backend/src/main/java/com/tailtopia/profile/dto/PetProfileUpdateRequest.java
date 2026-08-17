package com.tailtopia.profile.dto;

import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 编辑宠物档案请求（{@code PATCH /api/v1/pet-profiles/me}）。Story 2.8。
 *
 * <p>部分更新：全字段可空，仅非空字段被更新。owner 取自 JWT（仅改自己档案）。
 * cardToken 不变（编辑不重生成，已分享链接保持有效）。
 */
public record PetProfileUpdateRequest(
        @Size(max = 1024) String avatarUrl,
        @Size(max = 20, message = "宠物名不能超过 20 字") String name,
        @Size(max = 60) String breed,
        // 与创建端一致：允许生日=今天（@PastOrPresent），只拒未来。
        @PastOrPresent(message = "生日不能是未来") LocalDate birthday,
        // 性别（V1.1.6 Story 1.1）。⚠️ 只有 MALE/FEMALE 两值，**没有 UNKNOWN**
        // （身份证那套才是三值，两者独立不联动）。传 null = 不改动，
        // 沿用本请求「仅非空字段被更新」的统一语义 —— **不支持清空**，
        // 不要照抄手机号（FR-70）的「允许清空写 null」，那是另一条要求。
        @Pattern(regexp = "MALE|FEMALE", message = "性别只能是 MALE 或 FEMALE") String sex,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro) {
}
