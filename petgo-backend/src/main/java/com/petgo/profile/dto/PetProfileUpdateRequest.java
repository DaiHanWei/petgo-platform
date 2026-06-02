package com.petgo.profile.dto;

import jakarta.validation.constraints.Past;
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
        @Past(message = "生日须早于今天") LocalDate birthday,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro) {
}
