package com.tailtopia.profile.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
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
        // 允许生日=今天（当天出生合法；前端选择器 maxDate=now，与此对齐——bug：选今天被 @Past 拒 422）。
        @NotNull(message = "生日必填且需完整年月日") @PastOrPresent(message = "生日不能是未来") LocalDate birthday,
        @Size(max = 30, message = "介绍不能超过 30 字") String intro,
        /**
         * 🔒 体重（kg），Story 6.1。<b>选填 —— 建档流程可跳过</b>：
         * 设为必填会挡住既有建档转化，而建档转化在漏斗上比推荐精度重要得多。
         * 未填时 FR-107 降级为按物种推荐、FR-109 静默不触发。
         */
        @jakarta.validation.constraints.DecimalMin(value = "0.1", message = "体重需大于 0")
        @jakarta.validation.constraints.DecimalMax(value = "200", message = "体重不能超过 200 kg")
        java.math.BigDecimal weightKg,
        /** 绝育状态，选填：NEUTERED / INTACT / UNKNOWN。 */
        String neuterStatus) {

    /**
     * 便捷构造：不带体重与绝育状态。
     *
     * <p>两者<b>本就选填</b>（Story 6.1：建档可跳过），既有调用点不必逐个补 null ——
     * 也顺带说明了「不填」是一等情形，不是遗漏。
     */
    public PetProfileCreateRequest(String avatarUrl, String petType, String name, String breed,
            LocalDate birthday, String intro) {
        this(avatarUrl, petType, name, breed, birthday, intro, null, null);
    }
}
