package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

/**
 * 创建宠物档案请求（{@code POST /api/v1/pet-profiles}）。服务端校验权威。
 *
 * <p>{@code ownerId} 绝不在此 DTO——一律取自 JWT，防越权伪造他人档案。
 *
 * <p>必填/选填边界（决策 F6 + R2/AC3）：
 * <ul>
 *   <li><b>必填</b>：{@code petType}（CAT/DOG/OTHER，创建后不可改）/ {@code name}（≤20）/
 *       {@code birthday}（完整年月日 {@code date}，年份用于年龄计算与里程碑触发）。</li>
 *   <li><b>选填</b>：{@code avatarUrl}（缺省展示侧占位）/ {@code breed}（自由文本纯展示）/
 *       {@code intro}（≤30）。</li>
 * </ul>
 *
 * @param avatarUrl 头像 URL（经 Story 2.1 客户端压缩/剥 EXIF/直传公开桶后得到，选填）
 * @param petType   宠物类型（必填，枚举 CAT/DOG/OTHER；服务端解析校验合法性）
 * @param name      宠物名 ≤20，必填
 * @param breed     品种 ≤60，选填
 * @param birthday  生日（完整年月日 {@code date}，必填且不晚于今天；只月日/非法日期反序列化即拒）
 * @param intro     一句话介绍 ≤30，选填
 */
public record PetProfileCreateRequest(
        @Size(max = 1024) String avatarUrl,
        @NotBlank(message = "宠物类型必选") String petType,
        @NotBlank(message = "宠物名不能为空") @Size(max = 20, message = "宠物名不能超过 20 字") String name,
        @Size(max = 60) String breed,
        @NotNull(message = "生日必填且需完整年月日") @Past(message = "生日须早于今天") LocalDate birthday,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro) {
}
