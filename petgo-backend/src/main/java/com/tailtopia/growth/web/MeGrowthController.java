package com.tailtopia.growth.web;

import com.tailtopia.growth.domain.FrameChoice;
import com.tailtopia.growth.service.GrowthFrameService;
import com.tailtopia.growth.service.GrowthLedgerPageQuery;
import com.tailtopia.growth.service.GrowthPageQuery;
import com.tailtopia.growth.service.GrowthPopupService;
import com.tailtopia.growth.web.dto.GrowthFrameRequest;
import com.tailtopia.growth.web.dto.GrowthLedgerPage;
import com.tailtopia.growth.web.dto.GrowthPageResponse;
import com.tailtopia.growth.web.dto.GrowthPageResponse.Frames;
import com.tailtopia.growth.web.dto.GrowthPopupsResponse;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.error.ErrorTypes;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人等级页与 EXP 明细（V1.4.0 ① Story 1.4 · AD-16）。<b>只作用于 JWT {@code sub} 本人，不接受任何 userId / token 参数</b>
 * （别人的成长值明细无任何接口可取，NFR9）。SecurityConfig 显式限 {@code ROLE_USER}（兽医 token 的 sub 与 users.id 撞号）。
 */
@RestController
@RequestMapping("/api/v1/me/growth")
public class MeGrowthController {

    private final GrowthPageQuery page;
    private final GrowthLedgerPageQuery ledger;
    private final GrowthPopupService popups;
    private final GrowthFrameService frames;

    public MeGrowthController(GrowthPageQuery page, GrowthLedgerPageQuery ledger, GrowthPopupService popups,
            GrowthFrameService frames) {
        this.page = page;
        this.ledger = ledger;
        this.popups = popups;
        this.frames = frames;
    }

    @GetMapping
    public GrowthPageResponse growth(@AuthenticationPrincipal Jwt jwt) {
        return page.page(currentUserId(jwt));
    }

    @GetMapping("/ledger")
    public GrowthLedgerPage ledger(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(required = false) String cursor) {
        long userId = currentUserId(jwt);
        if (!page.participating(userId)) {
            return GrowthLedgerPage.empty();
        }
        return ledger.page(userId, cursor);
    }

    /** 待弹的升级庆祝 / 存量汇总（Story 2.1 · AD-11）。 */
    @GetMapping("/popups")
    public GrowthPopupsResponse popups(@AuthenticationPrincipal Jwt jwt) {
        return popups.getPopups(currentUserId(jwt));
    }

    /**
     * 确认弹窗（幂等，204）。{@code kind ∈ celebration | backfill}；{@code toLevel} 仅 celebration 用（防弹出期间又升级被吞）。
     */
    @PostMapping("/popups/{kind}/ack")
    public ResponseEntity<Void> ack(@AuthenticationPrincipal Jwt jwt, @PathVariable String kind,
            @RequestParam(required = false) Integer toLevel) {
        long userId = currentUserId(jwt);
        switch (kind) {
            case "celebration" -> popups.ackCelebration(userId, toLevel);
            case "backfill" -> popups.ackBackfill(userId);
            default -> throw new AppException(HttpStatus.BAD_REQUEST, ErrorTypes.VALIDATION, "未知的弹窗类型");
        }
        return ResponseEntity.noContent().build();
    }

    /**
     * 选头像框（Story 3.3 · AD-16）：{@code NONE} 恒允许；未解锁 → 409 {@code frame-locked}；非法值 → 422。
     * 返回与 {@code GET} 同一个头像框四档 DTO，App 直接刷新一排卡片。
     */
    @PutMapping("/frame")
    public Frames frame(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) GrowthFrameRequest body) {
        long userId = currentUserId(jwt);
        return frames.choose(userId, parseFrame(body));
    }

    private static FrameChoice parseFrame(GrowthFrameRequest body) {
        if (body == null || body.frame() == null) {
            throw AppException.validation("frame is required");
        }
        try {
            return FrameChoice.valueOf(body.frame());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("frame must be one of NONE, BASIC, UPGRADED, ANIMATED");
        }
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
