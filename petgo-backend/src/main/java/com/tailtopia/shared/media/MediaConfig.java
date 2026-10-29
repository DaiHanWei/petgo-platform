package com.tailtopia.shared.media;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 启用媒体配置绑定（Story 2.1）。{@link MediaProperties} 前缀 {@code media}，env 注入。 */
@Configuration
@EnableConfigurationProperties(MediaProperties.class)
public class MediaConfig {

    /**
     * 视频 CDN 刷新器开关（专题线 video Story 1.4 · AD-11）。
     *
     * <p>🔴 <b>不用 {@code @ConditionalOnProperty}</b>：yml 写的是 {@code ${MEDIA_VIDEO_CDN_BASE_URL:}}，
     * 没配时值是空字符串，{@code @ConditionalOnProperty} 会把它当成「有」而装配真刷新器。用 {@code hasText} 判。
     */
    @Bean
    public CdnCacheRefresher cdnCacheRefresher(MediaProperties props) {
        return selectCdnCacheRefresher(props);
    }

    static CdnCacheRefresher selectCdnCacheRefresher(MediaProperties props) {
        return StringUtils.hasText(props.getVideo().getCdnBaseUrl())
                ? new AliyunCdnCacheRefresher(props)
                : new NoopCdnCacheRefresher();
    }
}
