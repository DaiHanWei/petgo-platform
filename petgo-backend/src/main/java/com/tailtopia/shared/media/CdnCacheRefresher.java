package com.tailtopia.shared.media;

/**
 * 视频 CDN 缓存刷新（专题线 video Story 1.4 · AD-11）。只由 {@code MediaAclReconciler} 调用。
 *
 * <p>实现由 {@link MediaConfig#cdnCacheRefresher} 按 {@code media.video.cdn-base-url} 是否有值选择：
 * 有值 → {@link AliyunCdnCacheRefresher}；空 → {@link NoopCdnCacheRefresher}。
 */
public interface CdnCacheRefresher {

    /** 刷新一个文件地址的缓存（公开 → 私有、私有 → 公开都要刷，避免 CDN 缓存了 403 或旧内容）。 */
    void refresh(String url);
}
