package com.tailtopia.content.web;

import com.tailtopia.content.dto.FeedPageResponse;
import com.tailtopia.content.service.FeedService;
import com.tailtopia.topic.domain.Topic;
import com.tailtopia.topic.service.TopicQueryService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 话题页帖子（V1.4.2 social-topic · Story 4.2 · AC2 · D-13）：{@code GET /api/v1/topics/{name}/posts?sort=&cursor=&page=}。
 *
 * <p>放在 content 模块（URL 仍在 {@code /topics} 下）守 §5.3 依赖方向：先 {@link TopicQueryService#requireVisible}
 * 拿进程内 id（非法名 / 不存在 / DISABLED → 同一个 404），再交 {@link FeedService#loadTopicPosts}。
 * topic 模块不注入任何 content service。游客可读（SecurityConfig {@code GET /api/v1/topics/**} permitAll）。
 */
@RestController
public class TopicPostsController {

    private final TopicQueryService topicQuery;
    private final FeedService feedService;

    public TopicPostsController(TopicQueryService topicQuery, FeedService feedService) {
        this.topicQuery = topicQuery;
        this.feedService = feedService;
    }

    @GetMapping("/api/v1/topics/{name}/posts")
    public FeedPageResponse posts(@AuthenticationPrincipal Jwt jwt, @PathVariable("name") String name,
            @RequestParam(value = "sort", required = false) String sort,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "page", required = false) Integer page) {
        Topic topic = topicQuery.requireVisible(name);
        return feedService.loadTopicPosts(topic.getId(), sort, cursor, page, viewerId(jwt));
    }

    /**
     * 登录用户 id（游客 null）。口径同 {@code ContentDetailController.viewerId}：只认 {@code role=USER}，
     * 兽医 / 运营 token 的 sub 不是 users.id，一律按游客读。
     */
    static Long viewerId(Jwt jwt) {
        if (jwt == null || jwt.getSubject() == null) {
            return null;
        }
        if (!"USER".equals(jwt.getClaimAsString("role"))) {
            return null;
        }
        try {
            return Long.parseLong(jwt.getSubject());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
