package com.tailtopia.place.dto;

import com.tailtopia.place.domain.PlaceTag;
import com.tailtopia.place.domain.PlaceType;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 标记场所请求（{@code POST /api/v1/places}，V1.3.0 batch-b1 Story 1.3 · AC1）。
 * 标记人取自 JWT，不在 DTO 里（不信任客户端）。
 *
 * <p>校验口径与前端表单**逐条对齐**（FR-112.1 全集）：
 * 名称必填 ≤80 · 类型单选 7 类全给 · 标签多选 ≥1 且 6 个全给 · 文字地址必填 ·
 * 照片 1–9 张 · 描述选填 ≤200。
 *
 * <p>⚠️ UI 稿 A5 的类型与标签是**示意省略**，不是真实清单（UX-DR4）。
 *
 * <h2>🔴 没有、也不要加「编辑场所」的 Request</h2>
 * 本版<b>用户不可修改场所</b>（2026-09-15 拍板）：前端前置告知、服务端**不提供任何编辑端点**，
 * 纠错只能走后台 AB-17A（下架 / 合并重复）。只做前端置灰等于留个半开的口子 ——
 * 有人直接调接口就改了（既有先例：宠物档案的「物种创建后不可修改」也是两层）。
 *
 * @param name        场所名，必填 ≤80
 * @param type        类型，单选必填
 * @param tags        宠物友好标签，≥1 个
 * @param latitude    纬度（-90~90）
 * @param longitude   经度（-180~180）
 * @param addressText 文字地址，必填 ≤255。**平台不做地理编码、不校验它与坐标是否一致**（AD-1 Rule 5）
 * @param description 描述，选填 ≤200
 * @param photoUrls   照片公开桶 CDN URL，1–9 张（经既有直传链路得到）
 */
public record PlaceCreateRequest(
        @NotBlank(message = "场所名不能为空")
        @Size(max = 80, message = "场所名不能超过 80 字")
        String name,

        @NotNull(message = "请选择场所类型")
        PlaceType type,

        // ≥1：宠物友好标签是这个功能的信息核心，一个都不选的话列表项上什么都显示不出来。
        @NotEmpty(message = "请至少选择一个宠物友好标签")
        @Size(max = 6, message = "宠物友好标签最多 6 个")
        List<PlaceTag> tags,

        @NotNull(message = "缺少位置")
        @DecimalMin(value = "-90", message = "纬度超出范围")
        @DecimalMax(value = "90", message = "纬度超出范围")
        Double latitude,

        @NotNull(message = "缺少位置")
        @DecimalMin(value = "-180", message = "经度超出范围")
        @DecimalMax(value = "180", message = "经度超出范围")
        Double longitude,

        @NotBlank(message = "文字地址不能为空")
        @Size(max = 255, message = "文字地址不能超过 255 字")
        String addressText,

        @Size(max = 200, message = "描述不能超过 200 字")
        String description,

        // 🔴 1–9 张，**下界是 1 不是 0**：没有照片的场所条目在列表里只有一个占位方块，
        // 对「这地方能不能带狗去」这个判断毫无帮助。
        // ⚠️ 元素上的 @NotBlank 不能省：`[""]` 能过「≥1 张」这条规则，结果是列表里一条
        // photoCount=1 却只有占位图的场所 —— 而用户不可编辑，改不了。
        @NotEmpty(message = "请至少上传一张照片")
        @Size(max = 9, message = "最多 9 张照片")
        List<@NotBlank(message = "照片地址不能为空") @Size(max = 1024) String> photoUrls) {

    /** 去重后的标签（客户端重复提交同一个标签时不落重复值）。保持首次出现的顺序。 */
    public List<PlaceTag> distinctTags() {
        return tags == null ? List.of() : tags.stream().distinct().toList();
    }
}
