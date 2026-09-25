package com.tailtopia.place.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 为场所补充照片（V1.3.0 batch-b1 Story 1.9 · AC1）。上传者取自 JWT，不在 DTO。
 *
 * <p>🔴 **仅图片、无视频**（CROSS-STORY F4）：这里收的是**既有直传链路**产出的公开桶
 * 图片 URL —— 那条链路本身只出图片，所以这里不需要（也不该）再判一次"是不是视频"：
 * 判了反而会让人以为存在一条能传视频的路。
 *
 * <p>单次最多 9 张（与标记表单同一上限）；**整个场所**的总数上限也是 9，在 service 里校验 ——
 * 那一条是跨请求的，DTO 校验不到。
 */
public record PlacePhotoContributeRequest(
        @NotEmpty(message = "请至少选择一张照片")
        @Size(max = 9, message = "最多 9 张照片")
        List<@NotBlank(message = "照片地址不能为空") @Size(max = 1024) String> photoUrls) {
}
