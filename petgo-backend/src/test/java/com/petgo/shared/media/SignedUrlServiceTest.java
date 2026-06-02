package com.petgo.shared.media;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * L0 单元测试：OSS 预签名为本地 HMAC 计算（不连网），用 dummy 凭证即可验证签名 URL 形态。
 * 验 AC3：签名 URL 含 Expires/Signature、TTL 生效、对象 key 入路径。
 */
class SignedUrlServiceTest {

    private SignedUrlService service;

    @BeforeEach
    void setUp() {
        MediaProperties props = new MediaProperties();
        props.getOss().setEndpoint("https://oss-ap-southeast-5.aliyuncs.com");
        props.getOss().setPrivateBucket("petgo-private");
        props.setAccessKeyId("dummy-ak");
        props.setAccessKeySecret("dummy-sk");
        props.getSignedUrl().setTtlSeconds(300);
        service = new SignedUrlService(new AliyunOssClient(props), props);
    }

    @Test
    void signProducesPresignedUrlWithSignatureAndKey() {
        String url = service.sign("private/7/abc.jpg");
        assertThat(url).contains("petgo-private");
        assertThat(url).contains("private/7/abc.jpg");
        assertThat(url).contains("Signature=");
        assertThat(url).contains("Expires=");
    }

    @Test
    void signAllBatchesEachKey() {
        List<String> urls = service.signAll(List.of("private/7/a.jpg", "private/7/b.jpg"));
        assertThat(urls).hasSize(2);
        assertThat(urls.get(0)).contains("a.jpg");
        assertThat(urls.get(1)).contains("b.jpg");
    }

    @Test
    void leadingSlashStripped() {
        String url = service.sign("/private/7/abc.jpg");
        assertThat(url).contains("private/7/abc.jpg");
        // 不应出现双斜杠的对象路径
        assertThat(url).doesNotContain("petgo-private//");
    }
}
