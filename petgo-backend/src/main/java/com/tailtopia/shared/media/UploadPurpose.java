package com.tailtopia.shared.media;

/**
 * 通用上传（{@code POST /api/v1/media/upload-url}）的可选用途（专题线 video Story 1.2 · AD-4）。
 *
 * <p>不带用途 = 普通图片上传，行为不变。
 *
 * <ul>
 *   <li>{@link #VIDEO_COVER}：视频帖封面。落在公开域前缀下（发帖时按前缀做归属校验），但对象签成
 *       {@code private}，发布后由对账器改为公开；只发给会员等级 ≥ 10 的账号。</li>
 * </ul>
 */
public enum UploadPurpose {
    VIDEO_COVER
}
