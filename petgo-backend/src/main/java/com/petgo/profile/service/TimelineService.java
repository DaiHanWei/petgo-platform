package com.petgo.profile.service;

import com.petgo.content.service.ContentService;
import com.petgo.content.service.GrowthMomentView;
import com.petgo.profile.domain.PetProfile;
import com.petgo.profile.domain.PetType;
import com.petgo.profile.dto.ArchiveStatsResponse;
import com.petgo.profile.dto.CalendarMonthResponse;
import com.petgo.profile.dto.DayDetailResponse;
import com.petgo.profile.dto.TimelineItemResponse;
import com.petgo.profile.dto.TimelinePageResponse;
import com.petgo.profile.service.HealthEventTimelineSource.HealthEventView;
import com.petgo.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
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
            merged.add(TimelineItemResponse.happyMoment(
                    g.id(), g.createdAt(), g.eventDate(), g.imageUrls(), g.text()));
        }
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            for (HealthEventView h : health.recentHealthEvents(ownerId, before, fetch)) {
                merged.add(TimelineItemResponse.healthEvent(h.createdAt(), h.aiLevel(), h.symptomSummary()));
            }
        }

        // AC6（F9）：时间线按 eventDate（快乐时刻）/createdAt 日（健康事件）倒序，同日 createdAt 倒序兜底。
        merged.sort(Comparator.comparing(TimelineItemResponse::effectiveDate)
                .thenComparing(TimelineItemResponse::date).reversed());

        boolean hasMore = merged.size() > pageSize;
        List<TimelineItemResponse> page = hasMore ? merged.subList(0, pageSize) : merged;
        // 游标仍以 createdAt（date）翻页，与各源 createdAt 拉取一致。
        String nextCursor = hasMore ? page.get(page.size() - 1).date().toString() : null;
        // subList 是视图，复制成独立 list。
        return new TimelinePageResponse(List.copyOf(page), nextCursor, hasMore);
    }

    /**
     * 日历月视图（Story 2.4 R2 · F9）：按 {@code event_date} 聚合当月有记录日。
     * 每日首图取该 event_date 下**最早 created_at** 快乐时刻首图；并入当日健康事件 🏥 角标。
     */
    @Transactional(readOnly = true)
    public CalendarMonthResponse getCalendarMonth(long ownerId, int year, int month) {
        requireProfile(ownerId);
        YearMonth ym = YearMonth.of(year, month);
        LocalDate from = ym.atDay(1);
        LocalDate to = ym.atEndOfMonth();

        // day -> cell（TreeMap 保证 day 升序）。
        Map<Integer, CalendarMonthResponse.DayCell> byDay = new TreeMap<>();
        // 快乐时刻已按 event_date 升、created_at 升排序 → 每日首次出现即最早 created_at。
        for (GrowthMomentView g : contentService.findGrowthMomentsInMonth(ownerId, from, to)) {
            if (g.eventDate() == null) {
                continue; // 防御：非 GROWTH_MOMENT 不应出现，但 eventDate 必非空
            }
            int day = g.eventDate().getDayOfMonth();
            CalendarMonthResponse.DayCell existing = byDay.get(day);
            if (existing == null) {
                byDay.put(day, new CalendarMonthResponse.DayCell(day, g.firstImageUrl(), true, false));
            }
            // 同日后续记录不覆盖首图（已是最早 created_at）。
        }

        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            Instant rangeFrom = from.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant rangeTo = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (HealthEventView h : health.healthEventsInRange(ownerId, rangeFrom, rangeTo)) {
                int day = h.createdAt().atZone(ZoneOffset.UTC).toLocalDate().getDayOfMonth();
                CalendarMonthResponse.DayCell c = byDay.get(day);
                if (c == null) {
                    byDay.put(day, new CalendarMonthResponse.DayCell(day, null, false, true));
                } else if (!c.hasHealthEvent()) {
                    byDay.put(day, new CalendarMonthResponse.DayCell(
                            day, c.firstImageUrl(), c.hasHappyMoment(), true));
                }
            }
        }

        return new CalendarMonthResponse(year, month, List.copyOf(byDay.values()));
    }

    /**
     * 当天详情（Story 2.4 R2 · F9）：某 event_date 当天快乐时刻 + 健康事件，created_at **正序**。
     */
    @Transactional(readOnly = true)
    public DayDetailResponse getDayDetail(long ownerId, LocalDate date) {
        requireProfile(ownerId);
        List<TimelineItemResponse> items = new ArrayList<>();
        for (GrowthMomentView g : contentService.findGrowthMomentsOnDate(ownerId, date)) {
            items.add(TimelineItemResponse.happyMoment(
                    g.id(), g.createdAt(), g.eventDate(), g.imageUrls(), g.text()));
        }
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        if (health != null) {
            Instant dayStart = date.atStartOfDay(ZoneOffset.UTC).toInstant();
            Instant dayEnd = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
            for (HealthEventView h : health.healthEventsOnDay(ownerId, dayStart, dayEnd)) {
                items.add(TimelineItemResponse.healthEvent(h.createdAt(), h.aiLevel(), h.symptomSummary()));
            }
        }
        items.sort(Comparator.comparing(TimelineItemResponse::date)); // created_at 正序
        return new DayDetailResponse(date, List.copyOf(items));
    }

    /**
     * 档案统计栏（Story 2.4 AC5）：快乐时刻数 + 问诊数 + 里程碑（零态 0 / 总数按 pet_type）。
     */
    @Transactional(readOnly = true)
    public ArchiveStatsResponse getStats(long ownerId) {
        PetProfile profile = requireProfile(ownerId);
        long happy = contentService.countGrowthMoments(ownerId);
        HealthEventTimelineSource health = healthSource.getIfAvailable();
        long consult = health == null ? 0L : health.countHealthEvents(ownerId);
        int milestoneTotal = milestoneTotalFor(profile.getPetType());
        return new ArchiveStatsResponse(happy, consult, 0L, milestoneTotal);
    }

    /** 里程碑总数按宠物类型：猫/狗 = 30，其他 = 15（FR-42；本体属 mini-epic，此处仅总数）。 */
    private static int milestoneTotalFor(PetType type) {
        return (type == PetType.CAT || type == PetType.DOG) ? 30 : 15;
    }

    private PetProfile requireProfile(long ownerId) {
        return profileService.findByOwnerId(ownerId)
                .orElseThrow(() -> AppException.notFound("尚未创建宠物档案"));
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
