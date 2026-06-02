package com.petgo.profile.service;

import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.dto.TimelineItemResponse;
import com.petgo.profile.dto.TimelinePageResponse;
import com.petgo.profile.service.HealthEventTimelineSource.HealthEventView;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 成长时间线聚合（Story 2.4 · B1）。合并「快乐时刻」（content service）与「健康事件」
 * （{@link HealthEventTimelineSource}，2.5 实现）按 createdAt 倒序游标分页。
 *
 * <p>模块边界：经 service 接口取数，**不直接 join content_posts / 健康事件表**。
 * 健康源 2.4 期无 bean → {@link ObjectProvider#getIfAvailable()} 返回 null，聚合对空源稳健。
 */
@Service
public class TimelineService {

    private static final int MAX_LIMIT = 50;

    private final ProfileService profileService;
    private final ContentService contentService;
    private final ObjectProvider<HealthEventTimelineSource> healthSource;

    public TimelineService(ProfileService profileService, ContentService contentService,
            ObjectProvider<HealthEventTimelineSource> healthSource) {
        this.profileService = profileService;
        this.contentService = contentService;
        this.healthSource = healthSource;
    }

    @Transactional(readOnly = true)
    public TimelinePageResponse getTimeline(long ownerId, String cursor, int limit) {
        // 需有档案；无则 404（前端据此渲染空态）。
        if (!profileService.hasProfile(ownerId)) {
            throw AppException.notFound("尚未创建宠物档案");
        }
        int pageSize = Math.min(Math.max(limit, 1), MAX_LIMIT);
        Instant before = parseCursor(cursor);
        // 各源多取一条用于跨源合并的稳健性（取 pageSize+1）。
        int fetch = pageSize + 1;

        List<TimelineItemResponse> merged = new ArrayList<>();
        for (GrowthMomentView g : contentService.findGrowthMoments(ownerId, before, fetch)) {
            merged.add(TimelineItemResponse.happyMoment(g.id(), g.createdAt(), g.imageUrls(), g.text()));
        }
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            for (HealthEventView h : health.recentHealthEvents(ownerId, before, fetch)) {
                merged.add(TimelineItemResponse.healthEvent(h.createdAt(), h.aiLevel(), h.symptomSummary()));
            }
        }

        merged.sort(Comparator.comparing(TimelineItemResponse::date).reversed());

        boolean hasMore = merged.size() > pageSize;
        List<TimelineItemResponse> page = hasMore ? merged.subList(0, pageSize) : merged;
        String nextCursor = hasMore ? page.get(page.size() - 1).date().toString() : null;
        // subList 是视图，复制成独立 list。
        return new TimelinePageResponse(List.copyOf(page), nextCursor, hasMore);
    }

    private static Instant parseCursor(String cursor) {
        if (cursor == null || cursor.isBlank()) {
            return Instant.now();
        }
        try {
            return Instant.parse(cursor);
        } catch (RuntimeException e) {
            throw AppException.validation("无效的分页游标");
        }
    }
}
