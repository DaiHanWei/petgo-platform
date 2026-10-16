package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaAcl;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.dto.PostMedia;
import com.tailtopia.content.dto.VideoView;
import com.tailtopia.shared.media.MediaProperties;
import com.tailtopia.shared.media.SignedUrlService;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * 帖子媒体投影（专题线 video Story 2.1 · AD-8）：决定一条帖子对某个查看者下发什么地址。
 *
 * <ul>
 *   <li>图文帖：原样（与改动前逐字节一致）；</li>
 *   <li>视频帖、文件已公开或查看者不是作者：视频走视频分发地址（{@link VideoUrlResolver#publicUrl}），封面原地址；</li>
 *   <li>视频帖、<b>查看者是作者本人</b>且文件未公开（{@code desired} 或 {@code applied} 为 PRIVATE：审核中、
 *       预处置中、ACL 尚未生效）：视频与封面都换成签名 URL，有效期 max(15 分钟, 时长 × 3)。</li>
 * </ul>
 *
 * <p>只做本地签名计算，不发网络请求。签名 URL 不进日志（NFR-4）。
 */
@Component
public class PostMediaResolver {

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(PostMediaResolver.class);

    private final VideoUrlResolver videoUrls;
    private final SignedUrlService signedUrls;
    private final MediaProperties props;

    public PostMediaResolver(VideoUrlResolver videoUrls, SignedUrlService signedUrls, MediaProperties props) {
        this.videoUrls = videoUrls;
        this.signedUrls = signedUrls;
        this.props = props;
    }

    /**
     * @param viewerId 查看者（游客 / 匿名分享页为 null）
     */
    public PostMedia resolve(ContentPost p, Long viewerId) {
        if (p.getMediaType() != MediaType.VIDEO) {
            return PostMedia.image(p);
        }
        boolean authorSeesPrivate = viewerId != null && viewerId.equals(p.getAuthorId())
                && (p.getMediaAclDesired() != MediaAcl.PUBLIC || p.getMediaAclApplied() != MediaAcl.PUBLIC);
        if (!authorSeesPrivate) {
            return new PostMedia(MediaType.VIDEO, view(p, videoUrls.publicUrl(p)), p.getImageUrls());
        }
        try {
            Duration ttl = videoUrls.signedTtl(p);
            String video = videoUrls.signedUrl(p, ttl);
            List<String> images = p.getImageUrls();
            Optional<String> coverKey = videoUrls.coverObjectKey(p);
            if (coverKey.isPresent()) {
                images = List.of(signedUrls.sign(props.getOss().getPublicBucket(), coverKey.get(), ttl));
            }
            return new PostMedia(MediaType.VIDEO, view(p, video), images);
        } catch (RuntimeException e) {
            // 签名失败（凭证异常等）不能把作者的整页列表 / 详情拖成 502：降级为公开地址（可能暂时 403），
            // 只记 postId 与异常类名（code review）。
            log.warn("video_sign_failed postId={} cause={}", p.getId(), e.getClass().getSimpleName());
            return new PostMedia(MediaType.VIDEO, view(p, videoUrls.publicUrl(p)), p.getImageUrls());
        }
    }

    private static VideoView view(ContentPost p, String url) {
        return new VideoView(url, p.getVideoDurationMs(), p.getVideoWidth(), p.getVideoHeight());
    }
}
