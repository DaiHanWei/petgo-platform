package com.tailtopia.shared.media;

/**
 * 腾讯 IM 媒体取流端口（Story 2.5 定义，Epic 5 实现）。
 *
 * <p>{@link ImToOssArchiver} 经此从 IM 取图字节，复制到私密桶②。2.5 期无实现 bean →
 * 归档仅在有图且 fetcher 可用时执行（L2）；无图时归档为空操作。
 */
public interface ImMediaFetcher {

    /** 按 IM 媒体引用取原图字节。 */
    byte[] fetch(String imImageRef);
}
