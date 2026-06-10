package com.petgo.profile.web;

import com.petgo.profile.dto.ArchiveStatsResponse;
import com.petgo.profile.dto.CalendarMonthResponse;
import com.petgo.profile.dto.DayDetailResponse;
import com.petgo.profile.dto.PetProfileCreateRequest;
import com.petgo.profile.dto.PetProfileResponse;
import com.petgo.profile.dto.PetProfileUpdateRequest;
import com.petgo.profile.dto.TimelinePageResponse;
import com.petgo.profile.service.CardRerenderService;
import com.petgo.profile.service.ProfileService;
import com.petgo.profile.service.TimelineService;
import com.petgo.shared.error.AppException;
import com.petgo.shared.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import java.time.Duration;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * 宠物档案端点（Story 2.2）。资源化命名 {@code /api/v1/pet-profiles}。
 *
 * <ul>
 *   <li>{@code POST /pet-profiles}：创建（201）；单账号单宠物，重复 409。owner 取自 JWT。</li>
 *   <li>{@code GET /pet-profiles/me}：当前用户档案（无则 404）——支撑「已有档案直达」。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/pet-profiles")
public class ProfileApiController {

    private static final int CREATE_LIMIT = 10;
    private static final Duration CREATE_WINDOW = Duration.ofMinutes(1);

    private final ProfileService profileService;
    private final TimelineService timelineService;
    private final CardRerenderService cardRerenderService;
    private final RedisRateLimiter rateLimiter;

    public ProfileApiController(ProfileService profileService, TimelineService timelineService,
            CardRerenderService cardRerenderService, RedisRateLimiter rateLimiter) {
        this.profileService = profileService;
        this.timelineService = timelineService;
        this.cardRerenderService = cardRerenderService;
        this.rateLimiter = rateLimiter;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PetProfileResponse create(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PetProfileCreateRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:profile:create:" + ownerId, CREATE_LIMIT, CREATE_WINDOW);
        return profileService.create(ownerId, req);
    }

    @GetMapping("/me")
    public PetProfileResponse myProfile(@AuthenticationPrincipal Jwt jwt) {
        return profileService.getMyProfile(currentUserId(jwt));
    }

    /**
     * 名片分享信号（Story 8.3 · FR-42）：App 触发系统分享面板后回报 → 驱动里程碑 C-S3「第一次分享名片」
     * 自动完成（幂等，仅首次有效）。响应 204。无档案 → 404。
     */
    @PostMapping("/me/card-shares")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordCardShared(@AuthenticationPrincipal Jwt jwt) {
        profileService.recordCardShared(currentUserId(jwt));
    }

    /**
     * 编辑当前用户档案（Story 2.8，部分更新）。owner 取自 JWT，仅改自己档案；cardToken 不变。
     * 成功后异步触发名片 OG 图重渲染（2.6 联动）；名片正文实时读库自动最新。
     */
    @PatchMapping("/me")
    public PetProfileResponse update(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody PetProfileUpdateRequest req) {
        long ownerId = currentUserId(jwt);
        rateLimiter.check("rl:profile:update:" + ownerId, CREATE_LIMIT, CREATE_WINDOW);
        PetProfileResponse updated = profileService.update(ownerId, req);
        cardRerenderService.scheduleRerender(updated.id());
        return updated;
    }

    /**
     * 成长时间线（Story 2.4）：快乐时刻 + 健康事件按 createdAt 倒序游标分页。
     * 无档案 → 404（前端据此渲染空态）。
     */
    @GetMapping("/me/timeline")
    public TimelinePageResponse timeline(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "limit", defaultValue = "20") int limit) {
        return timelineService.getTimeline(currentUserId(jwt), cursor, limit);
    }

    /**
     * 日历月视图（Story 2.4 R2 · F9）：按 event_date 聚合当月有记录日（快乐时刻首图 + 健康事件角标）。
     * 无档案 → 404。
     */
    @GetMapping("/me/calendar")
    public CalendarMonthResponse calendar(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("year") int year,
            @RequestParam("month") int month) {
        return timelineService.getCalendarMonth(currentUserId(jwt), year, month);
    }

    /**
     * 当天详情（Story 2.4 R2 · F9）：某 event_date 当天快乐时刻 + 健康事件，created_at 正序。无档案 → 404。
     */
    @GetMapping("/me/day")
    public DayDetailResponse day(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return timelineService.getDayDetail(currentUserId(jwt), date);
    }

    /**
     * 档案统计栏（Story 2.4 AC5）：快乐时刻数 / 问诊数 / 里程碑（零态）。无档案 → 404。
     */
    @GetMapping("/me/archive-stats")
    public ArchiveStatsResponse archiveStats(@AuthenticationPrincipal Jwt jwt) {
        return timelineService.getStats(currentUserId(jwt));
    }

    private static long currentUserId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            throw AppException.unauthorized("需要登录后访问");
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            throw AppException.unauthorized("无效的登录凭证");
        }
    }
}
