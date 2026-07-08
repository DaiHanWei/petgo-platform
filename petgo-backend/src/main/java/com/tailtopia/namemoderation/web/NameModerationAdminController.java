package com.tailtopia.namemoderation.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.namemoderation.domain.NameDecision;
import com.tailtopia.namemoderation.service.NameModerationService;
import com.tailtopia.shared.error.AppException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 名称审核队列处置入口（内容审核 story 4，§5.8）。<b>本 story 只落受 admin 鉴权的处置端点 + service</b>，
 * <b>不做 Thymeleaf 队列页面</b>（优先级排序/列表页归 story 8，story 8 复用 {@link NameModerationService}）。
 *
 * <p>门控沿用人工审核处置权（{@code SUPER_ADMIN} 或 {@code content.takedown}）。落在 {@code /admin/**}
 * 会话 + CSRF 链下；story 8 的队列页以带 CSRF 表单 POST 本端点。违规重置的名称写入 + 推送由 service 内闭环。
 */
@Controller
public class NameModerationAdminController {

    private static final String DECIDE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')";

    private final NameModerationService service;

    public NameModerationAdminController(NameModerationService service) {
        this.service = service;
    }

    /**
     * 处置一条名称审核队列项。{@code decision} ∈ {PASS, VIOLATION}；VIOLATION → 重置默认编码名 + 推送本人。
     * {@code reason} 为违规类别（仅运营记录，不外泄用户）。
     */
    @PostMapping("/admin/name-moderation/{recordId}/decide")
    @PreAuthorize(DECIDE_AUTH)
    @ResponseBody
    public ResponseEntity<String> decide(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long recordId,
            @RequestParam("decision") String decision,
            @RequestParam(value = "reason", required = false) String reason) {
        NameDecision parsed = parseDecision(decision);
        service.decide(recordId, parsed, admin.getAdminAccountId(), reason);
        return ResponseEntity.ok(parsed == NameDecision.VIOLATION
                ? "已判违规：名称已重置为系统默认编码名并通知用户"
                : "已判通过：名称保留");
    }

    private static NameDecision parseDecision(String raw) {
        if (raw == null) {
            throw AppException.validation("处置结论必填（PASS / VIOLATION）");
        }
        try {
            return NameDecision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("处置结论非法，须为 PASS / VIOLATION 之一");
        }
    }
}
