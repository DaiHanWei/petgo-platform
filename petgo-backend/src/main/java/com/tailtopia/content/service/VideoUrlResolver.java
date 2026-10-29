package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.media.SignedUrlService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 视频帖地址拼接（专题线 video Story 1.4 · AD-8 / AD-11）。Epic 2（DTO 下发）、Epic 3（后台预览）都用它。
 *
 * <ul>
 *   <li>{@link #publicUrl}：配了 {@code media.video.cdn-base-url} 用 CDN，否则用 OSS 源站（{@code media.oss.cdn-base-url}）；</li>
 *   <li>{@link #signedUrl} / {@link #forAdmin}：私有状态下（作者看审核中的帖、后台预览）用签名 URL，
 *       有效期 max(15 分钟, 时长 × 3)。</li>
 * </ul>
 * 只接受视频帖 —— 调用方必须先判 {@code mediaType}（一致性规则 5）。签名 URL 不进日志。
 */
@Component
public class VideoUrlResolver {

    static final Duration MIN_SIGNED_TTL = Duration.ofMinutes(15);

    private final AliyunOssClient ossClient;
    private final SignedUrlService signedUrls;
    private final MediaProperties props;

    public VideoUrlResolver(AliyunOssClient ossClient, SignedUrlService signedUrls, MediaProperties props) {
        this.ossClient = ossClient;
        this.signedUrls = signedUrls;
        this.props = props;
    }

    /** 视频公开地址（帖子公开且 ACL 已生效时下发）。 */
    public String publicUrl(ContentPost post) {
        String key = videoKey(post);
        String cdn = props.getVideo().getCdnBaseUrl();
        if (StringUtils.hasText(cdn)) {
            String base = cdn.strip();
            while (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            return base + "/" + (key.startsWith("/") ? key.substring(1) : key);
        }
        return ossClient.publicUrl(key);
    }

    /** 视频签名地址。 */
    public String signedUrl(ContentPost post, Duration ttl) {
        return signedUrls.sign(props.getOss().getPublicBucket(), videoKey(post), ttl);
    }

    /** 签名有效期：max(15 分钟, 时长 × 3)，保证看完一遍再拖几次进度也不过期。 */
    public Duration signedTtl(ContentPost post) {
        requireVideo(post);
        Integer ms = post.getVideoDurationMs();
        Duration byLength = Duration.ofMillis(ms == null ? 0L : ms * 3L);
        return byLength.compareTo(MIN_SIGNED_TTL) > 0 ? byLength : MIN_SIGNED_TTL;
    }

    /** 后台预览（审核详情、内容抽屉）：不管帖子当前公开与否，一律签名。 */
    public String forAdmin(ContentPost post) {
        return signedUrl(post, signedTtl(post));
    }

    /** 封面对象 key（{@code imageUrls[0]} 反解）；反解不出 → empty。 */
    public Optional<String> coverObjectKey(ContentPost post) {
        requireVideo(post);
        List<String> urls = post.getImageUrls();
        if (urls == null || urls.isEmpty()) {
            return Optional.empty();
        }
        return ossClient.objectKeyOfPublicUrl(urls.get(0));
    }

    private static String videoKey(ContentPost post) {
        requireVideo(post);
        return post.getVideoObjectKey();
    }

    private static void requireVideo(ContentPost post) {
        if (post.getMediaType() != MediaType.VIDEO) {
            throw new IllegalArgumentException("不是视频帖 postId=" + post.getId());
        }
    }
}
