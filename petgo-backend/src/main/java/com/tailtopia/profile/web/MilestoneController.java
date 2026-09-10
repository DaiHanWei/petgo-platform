package com.tailtopia.profile.web;

import com.tailtopia.profile.dto.MilestoneCelebrationReportRequest;
import com.tailtopia.profile.dto.MilestoneCelebrationReportResponse;
import com.tailtopia.profile.dto.MilestoneCheckinCandidateResponse;
import com.tailtopia.profile.dto.MilestoneCheckinRequest;
import com.tailtopia.profile.dto.MilestoneItemResponse;
import com.tailtopia.profile.dto.MilestoneListResponse;
import com.tailtopia.profile.dto.MilestoneShareRequest;
import com.tailtopia.profile.dto.MilestoneShareResponse;
import com.tailtopia.profile.service.MilestoneCelebrationService;
import com.tailtopia.profile.service.MilestoneCheckInService;
import com.tailtopia.profile.service.MilestoneService;
import com.tailtopia.profile.service.MilestoneShareService;
import com.tailtopia.shared.error.AppException;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 里程碑端点（Story 8.1，FR-42 / 决策 F16）。资源化命名 {@code /api/v1/pet-profiles/me/milestones}
 * （当前用户档案子资源，与 /me/timeline、/me/calendar 同范式）。owner 取自 JWT，绝不信任客户端传入。
 *
 * <p>8.4 用户打卡端点后续叠加于此控制器。
 */
@RestController
@RequestMapping("/api/v1/pet-profiles/me/milestones")
public class MilestoneController {

    private final MilestoneService milestoneService;
    private final MilestoneCheckInService checkInService;
    private final MilestoneShareService shareService;
    private final MilestoneCelebrationService celebrationService;

    public MilestoneController(MilestoneService milestoneService,
            MilestoneCheckInService checkInService, MilestoneShareService shareService,
            MilestoneCelebrationService celebrationService) {
        this.milestoneService = milestoneService;
        this.checkInService = checkInService;
        this.shareService = shareService;
        this.celebrationService = celebrationService;
    }

    /** 当前用户里程碑列表（L/M/S 分区 + 完成状态 + 进度）。无档案 → 404。 */
    @GetMapping
    public MilestoneListResponse list(@AuthenticationPrincipal Jwt jwt) {
        return milestoneService.getMilestones(currentUserId(jwt));
    }

    /**
     * 用户打卡「已打卡」内容关联选择器候选（Story 8.4）：本人成长日历内容，已关联其它里程碑的标 linked。
     */
    @GetMapping("/checkin-candidates")
    public MilestoneCheckinCandidateResponse.Page checkinCandidates(@AuthenticationPrincipal Jwt jwt) {
        return checkInService.candidates(currentUserId(jwt));
    }

    /**
     * 用户打卡（Story 8.4）：把一条本人成长日历内容关联到「用户打卡」类里程碑 {@code code} 并完成。
     * 非打卡类 422 / 已完成 409 / 内容非本人成长日历 422 / 内容已关联其它里程碑 409。返回完成后的项。
     */
    @PostMapping("/{code}/check-in")
    public MilestoneItemResponse checkIn(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String code, @Valid @RequestBody MilestoneCheckinRequest req) {
        return checkInService.checkIn(currentUserId(jwt), code, req.contentId());
    }

    /**
     * 创建 / 刷新某已完成里程碑的对外分享（P-35 分享链接），返回不可枚举 {@code shareToken}
     * （URL 由客户端拼，公开页 {@code GET /m/{shareToken}} 直出）。无档案 / 无里程碑 → 404；未完成 → 422。
     * 按 {@code (pet, code)} 幂等：重复分享复用同一 token，仅刷新本地化文案。
     */
    @PostMapping("/{code}/shares")
    public MilestoneShareResponse createShare(@AuthenticationPrincipal Jwt jwt,
            @PathVariable String code, @Valid @RequestBody MilestoneShareRequest req) {
        return shareService.createOrRefresh(currentUserId(jwt), code, req);
    }

    /**
     * 庆祝回报（V1.3.0 Story 1.4 · FR-111）：客户端在庆祝页**展示成功后**回报本次实际展示覆盖的
     * code 列表，服务端按列表幂等置位 {@code celebrated_at}。无档案 → 404。
     *
     * <p>🔴 <b>code 列表由客户端点名，服务端不做 mark-all</b>（AD-A2.3b）：客户端从读取列表到回报
     * 之间的几百毫秒里可能被点赞 / 被评论而新解锁一条，mark-all 会把它静默盖章为已庆祝、永不补弹。
     *
     * <p>回报是 best-effort（AD-A3.1）：客户端**异步发、失败静默、不重试到用户可感知**。
     * 失败的代价只是下次进列表页再补弹一次。**不要把它改成同步阻塞庆祝页或发布流程。**
     *
     * <p>「重温庆祝」（点已完成徽章）**不调本端点**，也不改写 {@code celebrated_at}（AD-A3.3）。
     */
    @PostMapping("/celebrations")
    public MilestoneCelebrationReportResponse reportCelebrations(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody MilestoneCelebrationReportRequest req) {
        return celebrationService.reportCelebrated(currentUserId(jwt), req.codes());
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
