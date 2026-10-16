package com.tailtopia.content.dto;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaType;
import java.util.List;

/**
 * 一条帖子对某个查看者的媒体投影（专题线 video Story 2.1 · AD-8 / 一致性规则 4）。
 *
 * <p>由 {@code PostMediaResolver} 按查看者算好（公开地址 / 作者签名地址），再交给各 DTO 的 {@code of()}
 * 组装 —— {@code mediaType} / {@code video} / 封面替换只在 DTO 工厂里落到响应上，服务层不各自拼。
 *
 * @param mediaType 媒体类型（恒有值）
 * @param video     视频信息；图文帖为 null
 * @param imageUrls 下发用的图片地址（视频帖为 [封面]，作者看未公开的帖时封面为签名 URL）；可空
 */
public record PostMedia(MediaType mediaType, VideoView video, List<String> imageUrls) {

    /** 图文帖（或无需解析地址的场景）：原样取库里的图片。 */
    public static PostMedia image(ContentPost p) {
        return new PostMedia(MediaType.IMAGE, null, p.getImageUrls());
    }

    /** 首图（卡片 / 顶置用）；无图为 null。 */
    public String firstImageUrl() {
        return (imageUrls == null || imageUrls.isEmpty()) ? null : imageUrls.get(0);
    }
}
