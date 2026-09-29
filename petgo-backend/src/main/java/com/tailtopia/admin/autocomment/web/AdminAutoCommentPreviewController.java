package com.tailtopia.admin.autocomment.web;

import com.tailtopia.content.autocomment.AutoCommentPreview;
import com.tailtopia.content.autocomment.AutoCommentService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 自动评论预览（测试提示词用）：{@code GET /admin/auto-comment/preview?postId=123} 返回 AI 会为这个帖子写的评论。
 *
 * <p><b>只读</b>：不发评论、不写留档，唯一的副作用是一次 Gemini 调用。超管专属。
 * 路径以 {@code /preview} 结尾，按约定不是页面，不进 AdminPageCatalog（两道守门脚本同口径排除）。
 */
@RestController
public class AdminAutoCommentPreviewController {

    public static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final AutoCommentService service;

    public AdminAutoCommentPreviewController(AutoCommentService service) {
        this.service = service;
    }

    @GetMapping("/admin/auto-comment/preview")
    @PreAuthorize(AUTH)
    public AutoCommentPreview preview(@RequestParam long postId) {
        return service.preview(postId);
    }
}
