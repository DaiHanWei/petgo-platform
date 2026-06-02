package com.petgo.auth.web;

import com.petgo.auth.dto.AuthorView;
import com.petgo.auth.dto.MiniProfileResponse;
import com.petgo.auth.service.AccountQueryService;
import com.petgo.content.service.ContentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 他人迷你主页投影端点（Story 3.8，FR-26）。{@code GET /api/v1/users/{userId}/mini-profile}。
 *
 * <p>**只读、游客可见**（点头像即看，无登录要求）。nickname/avatar 经 {@link AccountQueryService}、
 * postCount 经 {@link ContentService}（**不直 join content 表**）。已注销 → isDeactivated=true（前端不弹卡）。
 */
@RestController
public class MiniProfileController {

    private final AccountQueryService accountQueryService;
    private final ContentService contentService;

    public MiniProfileController(AccountQueryService accountQueryService, ContentService contentService) {
        this.accountQueryService = accountQueryService;
        this.contentService = contentService;
    }

    @GetMapping("/api/v1/users/{userId}/mini-profile")
    public MiniProfileResponse miniProfile(@PathVariable long userId) {
        AuthorView author = accountQueryService.findAuthorViews(java.util.List.of(userId)).get(userId);
        if (author.deleted()) {
            return MiniProfileResponse.deactivated(); // 注销不暴露身份信息（NFR-8）
        }
        return MiniProfileResponse.of(author, contentService.countPublishedByAuthor(userId));
    }
}
