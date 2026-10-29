package com.tailtopia.content.domain;

/**
 * 帖子媒体类型（专题线 video · AD-2）。落库 {@code content_posts.media_type}（varchar + CHECK）。
 *
 * <p>视频帖的 {@code image_urls} 恰好是 1 张封面（AD-1）—— 凡是把 {@code image_urls} 当照片处理的代码，
 * 都必须先判本字段（一致性规则 5）。
 */
public enum MediaType {
    IMAGE,
    VIDEO
}
