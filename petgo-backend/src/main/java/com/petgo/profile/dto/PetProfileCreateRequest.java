package com.petgo.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 创建宠物档案请求（{@code POST /api/v1/pet-profiles}）。服务端校验权威。
 *
 * <p>{@code ownerId} 绝不在此 DTO——一律取自 JWT，防越权伪造他人档案。
 *
 * @param avatarUrl 头像 URL（经 Story 2.1 客户端压缩/剥 EXIF/直传公开桶后得到，可空）
 * @param name      宠物名 ≤20，必填
 * @param breed     品种 ≤60，可空
 * @param birthday  生日（不晚于今天）
 * @param intro     一句话介绍 ≤30，可空
 */
public record PetProfileCreateRequest(
        @Size(max = 1024) String avatarUrl,
        @NotBlank(message = "宠物名不能为空") @Size(max = 20, message = "宠物名不能超过 20 字") String name,
        @Size(max = 60) String breed,
        @Past(message = "生日须早于今天") LocalDate birthday,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro) {
}
