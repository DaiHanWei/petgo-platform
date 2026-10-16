package com.tailtopia.content.service;

import com.tailtopia.content.domain.ContentPost;
import com.tailtopia.content.domain.MediaAcl;
import com.tailtopia.content.domain.MediaType;
import com.tailtopia.content.repository.ContentPostRepository;
import com.tailtopia.shared.media.AliyunOssClient;
import com.tailtopia.shared.media.CdnCacheRefresher;
import com.tailtopia.shared.media.MediaProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 视频文件公开状态对账器（专题线 video Story 1.4 · AD-6 · 一致性规则 1）。
 *
 * <p>帖子状态方法只负责算出 {@code media_acl_desired}；<b>真正改 OSS ACL、刷 CDN 的只有这里</b>。
 * 每条：视频 ACL → 封面 ACL → CDN 刷新 → 条件单列 UPDATE 回写 applied（影响 0 行 = desired 被并发改了，下一轮再来）。
 *
 * <h2>🔴 本类不加 {@code @Transactional}</h2>
 * 全是外网调用；每次回写是仓库方法自己的短事务（一致性规则 7）。
 *
 * <p>告警只走日志（项目约定）：卡住超过 {@code media.video.acl-stuck-after-seconds} → ERROR
 * {@code video_acl_reconcile_stuck}；对象不存在 → ERROR {@code video_acl_object_missing} 并强制收敛。
 * 日志只记 postId / desired / 异常类名，不记任何 URL 与凭证。
 */
@Component
public class MediaAclReconciler {

    private static final Logger log = LoggerFactory.getLogger(MediaAclReconciler.class);

    private final ContentPostRepository posts;
    private final AliyunOssClient ossClient;
    private final CdnCacheRefresher cdn;
    private final VideoUrlResolver urls;
    private final MediaProperties props;
    private final Clock clock;

    @Autowired
    public MediaAclReconciler(ContentPostRepository posts, AliyunOssClient ossClient, CdnCacheRefresher cdn,
            VideoUrlResolver urls, MediaProperties props) {
        this(posts, ossClient, cdn, urls, props, Clock.systemUTC());
    }

    MediaAclReconciler(ContentPostRepository posts, AliyunOssClient ossClient, CdnCacheRefresher cdn,
            VideoUrlResolver urls, MediaProperties props, Clock clock) {
        this.posts = posts;
        this.ossClient = ossClient;
        this.cdn = cdn;
        this.urls = urls;
        this.props = props;
        this.clock = clock;
    }

    /** 定时对账一批。单条失败不影响其他条。 */
    @Scheduled(cron = "${media.video.acl-reconcile-cron:0 * * * * *}")
    public void run() {
        if (!ossClient.hasCredentials()) {
            log.debug("OSS 未配凭证，跳过视频文件公开对账（仅本地/测试）");
            return;
        }
        List<ContentPost> batch = posts.findMediaAclPending(
                PageRequest.of(0, props.getVideo().getAclReconcileBatch()));
        for (ContentPost p : batch) {
            reconcile(p);
        }
    }

    /**
     * 对一条帖子立即对账（发布后由 Controller 在事务外调用；下架 / 恢复入口在 Story 3.1 接入）。
     *
     * @return true = 已一致（或本来就无事可做）；false = 未完成（交给定时任务）
     */
    public boolean reconcileOne(long postId) {
        if (!ossClient.hasCredentials()) {
            return false;
        }
        Optional<ContentPost> found = posts.findById(postId);
        if (found.isEmpty() || !needsReconcile(found.get())) {
            return true;
        }
        return reconcile(found.get());
    }

    private static boolean needsReconcile(ContentPost p) {
        return p.getMediaType() == MediaType.VIDEO && p.getMediaAclDesired() != null
                && p.getMediaAclDesired() != p.getMediaAclApplied();
    }

    /** @return true = 本条已回写一致 */
    private boolean reconcile(ContentPost p) {
        MediaAcl desired = p.getMediaAclDesired();
        boolean publicRead = desired == MediaAcl.PUBLIC;
        boolean done = false;
        try {
            String bucket = props.getOss().getPublicBucket();
            Optional<String> coverKey = urls.coverObjectKey(p);
            boolean videoFound = ossClient.setObjectAcl(bucket, p.getVideoObjectKey(), publicRead);
            boolean coverFound = videoFound && coverKey.isPresent()
                    && ossClient.setObjectAcl(bucket, coverKey.get(), publicRead);
            if (!videoFound || !coverFound) {
                // F21 下文件不会被删：缺对象只可能是上传没完成或数据异常。收敛，避免每分钟无限重试刷 ERROR。
                log.error("video_acl_object_missing postId={} desired={} video={} cover={}",
                        p.getId(), desired, videoFound ? "ok" : "missing", coverFound ? "ok" : "missing");
                done = writeBack(p.getId(), desired);
                return done;
            }
            cdn.refresh(urls.publicUrl(p));
            done = writeBack(p.getId(), desired);
            return done;
        } catch (RuntimeException e) {
            log.warn("video_acl_reconcile_failed postId={} desired={} cause={}",
                    p.getId(), desired, e.getClass().getSimpleName());
            // 视频那步可能已经改成功、封面那步才抛：OSS 状态可能已偏离 applied，记漂移保证下一轮会回来。
            markDrifted(p.getId(), desired);
            return false;
        } finally {
            if (!done) {
                warnIfStuck(p, desired);
            }
        }
    }

    /** 条件回写；0 行（desired 被并发改了）时记漂移，见 {@link ContentPostRepository#markMediaAclDrifted}。 */
    private boolean writeBack(long postId, MediaAcl attempted) {
        if (posts.markMediaAclApplied(postId, attempted.name()) > 0) {
            return true;
        }
        markDrifted(postId, attempted);
        return false;
    }

    private void markDrifted(long postId, MediaAcl attempted) {
        try {
            posts.markMediaAclDrifted(postId, attempted.name());
        } catch (RuntimeException e) {
            log.warn("video_acl_drift_mark_failed postId={} cause={}", postId, e.getClass().getSimpleName());
        }
    }

    private void warnIfStuck(ContentPost p, MediaAcl desired) {
        Instant since = p.getMediaAclDesiredAt();
        if (since == null) {
            return;
        }
        long age = Duration.between(since, clock.instant()).getSeconds();
        if (age > props.getVideo().getAclStuckAfterSeconds()) {
            log.error("video_acl_reconcile_stuck postId={} desired={} stuckSeconds={}", p.getId(), desired, age);
        }
    }
}
