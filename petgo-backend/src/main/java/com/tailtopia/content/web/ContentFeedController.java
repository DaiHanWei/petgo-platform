package com.tailtopia.content.web;

import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.dto.PinnedSlotResponse;
import com.tailtopia.content.service.FeedService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed 读取端点（Story 3.2）。{@code GET /api/v1/content-posts}：时间倒序游标分页 + 宠物状态硬过滤。
 *
 * <p>**只读对游客可见**（FR-0A/17）——这是 {@code /api/v1} 默认需 JWT 的例外（SecurityConfig 放行 GET）。
 * 登录用户从 JWT 取宠物状态做硬过滤；游客视作全显。Redis 不参与（无缓存，状态修改即时刷新）。
 */
@RestController
public class ContentFeedController {

    private final FeedService feedService;
    private final AccountQueryService accountQueryService;

    public ContentFeedController(FeedService feedService, AccountQueryService accountQueryService) {
        this.feedService = feedService;
        this.accountQueryService = accountQueryService;
    }

    @GetMapping("/api/v1/content-posts")
    public FeedPageResponse feed(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "category", required = false) String category) {
        Long viewerId = viewerId(jwt);
        String petStatus = resolvePetStatus(viewerId);
        // 内容审核 cm-6 §5.4：登录用户传 viewerId → 后端权威排除本人已举报的帖；游客 null = 不过滤。
        return feedService.loadFeed(petStatus, category, cursor, viewerId);
    }

    /**
     * 顶置坑位（V1.1.6 Story 4.2 · FR-68）。
     *
     * <p>🛡 **独立取数**：与上面的首页端点分开，首页的游标分页形态一点不变。
     * 无生效配置返回 {@code {}}（pin 为空），**不是错误** —— 客户端什么都不渲染、不留占位。
     *
     * <p>与首页一样对游客开放（顶置内容本就是公开内容）。
     */
    @GetMapping("/api/v1/content-posts/pinned")
    public PinnedSlotResponse pinnedSlot(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "slot", defaultValue = ContentPin.SLOT_HOME_FEED) String slot) {
        return feedService.loadPinnedSlot(slot, viewerId(jwt));
    }

    /** 登录用户 id（游客 / 无效 JWT → null）。 */
    private static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 登录用户取宠物状态（HAS_PET/PLANNING/ENTHUSIAST）；游客（viewerId null）返回 null = 全显。 */
    private String resolvePetStatus(Long viewerId) {
        if (viewerId == null) {
            return null;
        }
        return accountQueryService.petStatusOf(viewerId).orElse(null);
    }
}
