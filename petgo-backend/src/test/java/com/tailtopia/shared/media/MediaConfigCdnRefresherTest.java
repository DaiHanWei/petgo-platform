package com.tailtopia.shared.media;

import static org.assertj.core.api.Assertions.assertThat;

import com.aliyuncs.CommonRequest;
import org.junit.jupiter.api.Test;

/** L0：专题线 video Story 1.4 —— CDN 刷新器开关（hasText，空串 → Noop）与请求形状。 */
class MediaConfigCdnRefresherTest {

    private static CdnCacheRefresher select(String cdn) {
        MediaProperties p = new MediaProperties();
        p.getVideo().setCdnBaseUrl(cdn);
        return MediaConfig.selectCdnCacheRefresher(p);
    }

    @Test
    void emptyStringFromYmlDefaultMeansNoop() {
        // yml 写的是 ${MEDIA_VIDEO_CDN_BASE_URL:}：没配时就是空串 —— @ConditionalOnProperty 会误判成「有」。
        assertThat(select("")).isInstanceOf(NoopCdnCacheRefresher.class);
        assertThat(select("   ")).isInstanceOf(NoopCdnCacheRefresher.class);
        assertThat(select(null)).isInstanceOf(NoopCdnCacheRefresher.class);
    }

    @Test
    void configuredCdnMeansAliyun() {
        assertThat(select("https://v.cdn.test")).isInstanceOf(AliyunCdnCacheRefresher.class);
    }

    @Test
    void noopDoesNothing() {
        new NoopCdnCacheRefresher().refresh("https://x/video/1/a.mp4");
    }

    @Test
    void refreshRequestShape() {
        CommonRequest r = AliyunCdnCacheRefresher.request("https://v.cdn.test/video/1/a.mp4");
        assertThat(r.getSysDomain()).isEqualTo("cdn.aliyuncs.com");
        assertThat(r.getSysVersion()).isEqualTo("2018-05-10");
        assertThat(r.getSysAction()).isEqualTo("RefreshObjectCaches");
        assertThat(r.getSysQueryParameters())
                .containsEntry("ObjectPath", "https://v.cdn.test/video/1/a.mp4")
                .containsEntry("ObjectType", "File");
    }
}
