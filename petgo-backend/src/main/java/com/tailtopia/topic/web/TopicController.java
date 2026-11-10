package com.tailtopia.topic.web;

import com.tailtopia.shared.error.AppException;
import com.tailtopia.topic.service.TopicQueryService;
import com.tailtopia.topic.web.dto.TopicDetailResponse;
import com.tailtopia.topic.web.dto.TopicSummaryResponse;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 话题读接口（V1.4.2 social-topic · Story 4.2 · AC0 ~ AC5 · D-13）。只调 {@link TopicQueryService}。
 *
 * <ul>
 *   <li>{@code GET /api/v1/topics/{name}}：详情（游客可读）；</li>
 *   <li>{@code GET /api/v1/topics/home-row}：首页话题行（游客可读；字面路径优先于 {@code {name}}，
 *       且含连字符不可能是合法话题名）；</li>
 *   <li>{@code GET /api/v1/topics/{name}/related}：推荐话题（游客可读）；</li>
 *   <li>{@code GET /api/v1/topic-suggestions?q=}：# 联想，仅 {@code role=USER}（SecurityConfig 401 / 403）。
 *       路径不用架构原稿的 {@code /topics/suggest}：{@code suggest} 本身是合法话题名，会让 {@code #suggest} 话题页不可达
 *       （story 待确认第 1 条）。</li>
 * </ul>
 * 帖子列表 {@code /topics/{name}/posts} 在 content 模块的 {@code TopicPostsController}（守依赖方向）。
 * 🔴 对外只出标准名 / 显示写法，不出 {@code topics.id}。
 */
@RestController
public class TopicController {

    private final TopicQueryService topicQuery;

    public TopicController(TopicQueryService topicQuery) {
        this.topicQuery = topicQuery;
    }

    @GetMapping("/api/v1/topics/home-row")
    public List<TopicSummaryResponse> homeRow() {
        return topicQuery.homeRow();
    }

    @GetMapping("/api/v1/topics/{name}")
    public TopicDetailResponse detail(@PathVariable("name") String name,
            @RequestHeader(value = "Accept-Language", required = false) String acceptLanguage) {
        return topicQuery.detail(name, acceptLanguage);
    }

    @GetMapping("/api/v1/topics/{name}/related")
    public List<TopicSummaryResponse> related(@PathVariable("name") String name) {
        return topicQuery.related(name);
    }

    /** # 联想。SecurityConfig 已限 {@code hasRole("USER")}；这里再兜一道非 USER → 403（不依赖规则顺序）。 */
    @GetMapping("/api/v1/topic-suggestions")
    public List<TopicSummaryResponse> suggestions(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "q", required = false) String q) {
        if (jwt == null || !"USER".equals(jwt.getClaimAsString("role"))) {
            throw AppException.forbidden("仅用户可用");
        }
        return topicQuery.suggest(q);
    }
}
