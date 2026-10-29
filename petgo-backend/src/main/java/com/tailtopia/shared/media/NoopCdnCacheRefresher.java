package com.tailtopia.shared.media;

/**
 * 没配视频 CDN 时的刷新器：什么都不做（专题线 video Story 1.4 · AD-11）。
 *
 * <p>没有 CDN 时客户端直连 OSS 源站，对象 ACL 一改立即生效（NFR-6），不存在缓存要刷。
 * 不加 {@code @Component}：由 {@link MediaConfig#cdnCacheRefresher} 装配。
 */
public class NoopCdnCacheRefresher implements CdnCacheRefresher {

    @Override
    public void refresh(String url) {
        // 无 CDN，无缓存。
    }
}
