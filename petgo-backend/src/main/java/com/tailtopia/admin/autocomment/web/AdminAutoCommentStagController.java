package com.tailtopia.admin.autocomment.web;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.content.autocomment.StagAutoCommentTool;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * 【stag 专用测试工具，只在 stag 分支】评论管理页「立即自动评论（stag）」按钮：
 * {@code POST /admin/auto-comment/stag-run} 对最新 10 条零评论帖跑一次自动评论（忽略 2 小时限制），
 * 返回结果片段（成功 / 跳过及原因 / 失败，逐帖明细）。
 *
 * <p>{@link StagOnly}：生产不注册，路由根本不存在。超管专属。评论是<b>真发</b>到 stag 库的。
 */
@StagOnly
@Controller
public class AdminAutoCommentStagController {

    public static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final StagAutoCommentTool tool;

    public AdminAutoCommentStagController(StagAutoCommentTool tool) {
        this.tool = tool;
    }

    @PostMapping("/admin/auto-comment/stag-run")
    @PreAuthorize(AUTH)
    public String run(Model model) {
        model.addAttribute("result", tool.run(StagAutoCommentTool.DEFAULT_LIMIT));
        return "admin/fragments/auto-comment-stag-result :: result";
    }
}
