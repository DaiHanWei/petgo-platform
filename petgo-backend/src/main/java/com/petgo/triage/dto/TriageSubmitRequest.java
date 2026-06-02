package com.petgo.triage.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 分诊提交请求（Story 4.1）。{@code symptomText} 与图片至少其一由前端保证；图片为私密桶对象 key
 * （≤3，不可枚举），<b>非签名 URL</b>。{@code petId} 预留存档绑定（本故事不实现存档流）。
 *
 * @param symptomText      症状文字（有上限，健康数据）
 * @param imageObjectKeys  私密桶②对象 key 列表（≤3）
 * @param petId            存档绑定宠物 id（可空，预留）
 */
public record TriageSubmitRequest(
        @Size(max = 2000, message = "症状描述不能超过 2000 字") String symptomText,
        @Size(max = 3, message = "最多 3 张图片") List<@Size(max = 512) String> imageObjectKeys,
        Long petId) {
}
