package com.tailtopia.place;

import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;

/**
 * 场所单测共用夹具（2026-09-18 场所表对齐）。
 *
 * <p>照片改存 object_key（决策 D5）后，服务层要靠 {@link AliyunOssClient} 做 URL ↔ key。
 * 这里给一个 CDN 前缀为 {@code https://cdn} 的真实实例（纯字符串运算，不连 OSS）——
 * 既有用例里的照片地址都是 {@code https://cdn/…}，于是「URL → key → URL」原样往返，断言不用改。
 */
public final class PlaceTestSupport {

    public static final String CDN = "https://cdn";

    private PlaceTestSupport() {
    }

    public static AliyunOssClient oss() {
        MediaProperties props = new MediaProperties();
        props.getOss().setCdnBaseUrl(CDN);
        return new AliyunOssClient(props);
    }
}
