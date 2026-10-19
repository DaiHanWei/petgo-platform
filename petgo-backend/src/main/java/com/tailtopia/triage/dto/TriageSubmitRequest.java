package com.tailtopia.triage.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 分诊提交请求（Story 4.1）。<b>文字必填、图片选填</b>（Story 4.3 AC5 · R2 · F-AC5）：{@code symptomText}
 * 非空即通过，{@code imageObjectKeys} 允许空/缺省（可仅凭文字提交）；图片为私密桶对象 key（≤3，不可枚举），
 * <b>非签名 URL</b>。{@code petId} = 所选宠物（V1.4.0 multi-pet Story 4.1：校验归属 + P-1 上下文 + 存档归属；可空 = 不带上下文）。
 *
 * @param symptomText      症状文字（<b>必填</b>，有上限，健康数据）
 * @param imageObjectKeys  私密桶②对象 key 列表（<b>选填</b>，≤3）
 * @param petId            所选宠物 id（可空；不属于本人 → 404）
 */
public record TriageSubmitRequest(
        @NotBlank(message = "请填写症状描述")
        @Size(max = 2000, message = "症状描述不能超过 2000 字") String symptomText,
        @Size(max = 3, message = "最多 3 张图片") List<@Size(max = 512) String> imageObjectKeys,
        Long petId) {
}
