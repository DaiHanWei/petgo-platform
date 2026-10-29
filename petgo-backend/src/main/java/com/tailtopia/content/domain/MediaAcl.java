package com.tailtopia.content.domain;

/**
 * 视频与封面对象的公开状态（专题线 video · AD-6）。
 *
 * <p>{@code media_acl_desired} 由 {@link ContentPost} 按 {@link ContentPost#desiredOf} 推导；
 * {@code media_acl_applied} 只由对账器（Story 1.4）以条件单列 UPDATE 写入。
 */
public enum MediaAcl {
    PUBLIC,
    PRIVATE
}
