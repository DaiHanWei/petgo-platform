package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** L0：对外 URL 拼接 + E4 服务端 EXIF 兜底样式（纯函数）。 */
class AliyunOssClientTest {

    private AliyunOssClient client() {
        MediaProperties props = new MediaProperties();
        props.getOss().setCdnBaseUrl("https://cdn.petgo.example");
        props.getOss().setPublicBucket("petgo-public");
        props.getOss().setPrivateBucket("petgo-private");
        return new AliyunOssClient(props);
    }

    @Test
    void publicUrlJoinsCdnBaseAndKey() {
        assertThat(client().publicUrl("public/42/x.jpg"))
                .isEqualTo("https://cdn.petgo.example/public/42/x.jpg");
    }

    @Test
    void publicUrlHandlesLeadingSlash() {
        assertThat(client().publicUrl("/public/42/x.jpg"))
                .isEqualTo("https://cdn.petgo.example/public/42/x.jpg");
    }

    @Test
    void exifStrippedUrlAppendsProcessStyle() {
        String url = client().publicExifStrippedUrl("public/42/x.jpg");
        assertThat(url).startsWith("https://cdn.petgo.example/public/42/x.jpg");
        assertThat(url).contains("x-oss-process=image/");
    }
}
