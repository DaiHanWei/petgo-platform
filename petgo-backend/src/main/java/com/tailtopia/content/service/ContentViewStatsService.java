package com.tailtopia.content.service;

import com.tailtopia.content.repository.ContentPostViewRepository;
import com.tailtopia.shared.schedule.ScheduleWindow;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 内容浏览统计（2026-08-31）。
 *
 * <p>口径（产品拍板）：<b>打开详情页记一次；作者本人不计</b>（作者判定在调用方，
 * 详情响应里现成有 {@code isAuthor}，这里不再查一遍）。信息流曝光、站外分享页都不算。
 *
 * <p>观看者键：登录用户 {@code u:<userId>}；游客 {@code a:<匿名会话id>}（沿用信息流
 * {@code X-Anon-Session} 机制）。🛡 游客没带匿名会话头（老版本 App）则<b>整次不记</b> ——
 * 揉进一个共享桶会把「人数」多算或少算，宁可次数少记一点也不给出失真的人数。
 *
 * <p>🛡 记录是 {@code @Async} 的且吞异常：浏览计数丢一次无关紧要，
 * 但它绝不能拖慢详情加载、更不能让详情页 500。
 */
@Service
public class ContentViewStatsService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ContentViewStatsService.class);

    /** 与 DB 列宽（40）对齐：'a:' 前缀 + 最多 38 字符会话 id。正常客户端生成 24 位，超长即截。 */
    private static final int MAX_ANON_LEN = 38;

    private final ContentPostViewRepository views;

    /** 「今天（WIB）」的时钟来源；生产为系统时钟，单测注入固定时钟。 */
    private final Clock clock;

    @Autowired
    public ContentViewStatsService(ContentPostViewRepository views) {
        this(views, Clock.systemUTC());
    }

    ContentViewStatsService(ContentPostViewRepository views, Clock clock) {
        this.views = views;
        this.clock = clock;
    }

    /**
     * 记一次浏览（详情接口成功返回后调用；作者本人的打开由调用方先行排除）。
     *
     * @param viewerId      登录用户 id；游客 null
     * @param anonSessionId 游客匿名会话 id（可空；登录用户忽略）
     */
    @Async
    public void recordView(long postId, Long viewerId, String anonSessionId) {
        String key = viewerKey(viewerId, anonSessionId);
        if (key == null) {
            return;
        }
        // 🛡 两句各走仓储方法自己的事务（本方法不开外层事务），各自 try：
        // 一句失败（例如按天表异常）不会把另一句一起回滚 —— 后台「浏览次数 / 人数」口径不受话题热度采集影响。
        try {
            views.upsertView(postId, key, Instant.now());
        } catch (RuntimeException e) {
            // 🛡 只记日志：统计写入失败不值得让任何人感知（异步线程里抛了也没人接）。
            log.warn("浏览记录写入失败 postId={}", postId, e);
        }
        try {
            // V1.4.2 social-topic Story 4.1（D-3 · FR-155）：按天去重（话题热度用）。丢了无所谓，绝不外抛。
            // 「按天」一律 WIB 日期（P-6），不另写 ZoneId。
            views.insertViewDay(postId, LocalDate.now(clock.withZone(ScheduleWindow.WIB)), key);
        } catch (RuntimeException e) {
            log.warn("按天浏览写入失败 postId={} cause={}", postId, e.getClass().getSimpleName());
        }
    }

    /**
     * 一批内容的浏览统计（次数 + 人数），后台列表 / 导出用。
     *
     * <p>🔴 整页一次批量取 —— 与点赞数、物种推导、限流状态同一条纪律（逐行就是 N+1）。
     * ⚠️ 没被看过的帖不在 Map 里，取值方自己兜底成 0。
     */
    @Transactional(readOnly = true)
    public Map<Long, ViewStat> statsFor(Collection<Long> postIds) {
        if (postIds == null || postIds.isEmpty()) {
            return Map.of();
        }
        return views.statsByPostIdIn(postIds).stream()
                .collect(Collectors.toMap(ContentPostViewRepository.PostViewStat::getPostId,
                        s -> new ViewStat(s.getViewTotal(), s.getViewerTotal())));
    }

    /** 一条内容的浏览统计：{@code views} 次数、{@code viewers} 人数。 */
    public record ViewStat(long views, long viewers) {
    }

    /**
     * 观看者键；无法识别身份 → {@code null}（不记）。
     *
     * <p>匿名会话 id 按信息流同款白名单清洗（{@code FeedRankCacheKey} 的口径）——
     * 这是要落库的外部输入，清洗后为空视同没带。
     */
    private static String viewerKey(Long viewerId, String anonSessionId) {
        if (viewerId != null) {
            return "u:" + viewerId;
        }
        if (anonSessionId == null) {
            return null;
        }
        String cleaned = anonSessionId.replaceAll("[^A-Za-z0-9_-]", "");
        if (cleaned.isEmpty()) {
            return null;
        }
        if (cleaned.length() > MAX_ANON_LEN) {
            cleaned = cleaned.substring(0, MAX_ANON_LEN);
        }
        return "a:" + cleaned;
    }
}
