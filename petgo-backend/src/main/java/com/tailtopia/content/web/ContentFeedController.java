package com.tailtopia.content.web;

import com.tailtopia.content.domain.ContentPin;
import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.dto.PinnedSlotResponse;
import com.tailtopia.content.service.FeedService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Feed 读取端点（Story 3.2）。{@code GET /api/v1/content-posts}：游标分页。
 *
 * <p>**只读对游客可见**（FR-0A/17）——这是 {@code /api/v1} 默认需 JWT 的例外（SecurityConfig 放行 GET）。
 *
 * <p>V1.1.6 Story 16.3：<b>ALL Tab 走推荐序，分类 Tab 仍是纯时间倒序</b>（两条独立路径）。
 * 推荐序需要一个缓存键；游客没有 userId，改用客户端生成的匿名会话 id
 * （{@value #ANON_SESSION_HEADER}）。⚠️ 该头<b>可缺省</b> —— 老版本客户端不带它照样能刷首页，
 * 只是同一批游客会共用一份序列快照（影响的只是翻页体验）。
 */
@RestController
public class ContentFeedController {

    /**
     * 游客匿名会话 id 请求头。
     *
     * <p>🛡 <b>不是身份凭证</b>，只是缓存键的一段：客户端自己生成、可随时重置，
     * 服务端不据此做任何授权判断（{@code FeedRankCacheKey} 还会做一遍字符白名单与截断）。
     */
    public static final String ANON_SESSION_HEADER = "X-Anon-Session";

    private final FeedService feedService;

    public ContentFeedController(FeedService feedService) {
        this.feedService = feedService;
    }

    @GetMapping("/api/v1/content-posts")
    public FeedPageResponse feed(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "category", required = false) String category,
            @RequestHeader(value = ANON_SESSION_HEADER, required = false) String anonSession) {
        // 内容审核 cm-6 §5.4：登录用户传 viewerId → 后端权威排除本人已举报的帖；游客 null = 不过滤。
        return feedService.loadFeed(category, cursor, viewerId(jwt), anonSession);
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
}
