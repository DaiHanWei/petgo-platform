package com.tailtopia.admin.moderation.web;

import com.tailtopia.admin.moderation.dto.AvatarReviewRow;
import com.tailtopia.admin.moderation.dto.NameReviewRow;
import com.tailtopia.admin.moderation.dto.ViolationCounts;
import com.tailtopia.admin.moderation.read.ViolationCountReader;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.avatarmoderation.domain.AvatarDecision;
import com.tailtopia.avatarmoderation.domain.AvatarSubjectType;
import com.tailtopia.avatarmoderation.service.AvatarModerationService;
import com.tailtopia.content.moderation.ModerationDecision;
import com.tailtopia.namemoderation.domain.NameTargetType;
import com.tailtopia.namemoderation.service.NameModerationService;
import com.tailtopia.profile.repository.PetProfileRepository;
import com.tailtopia.shared.error.AppException;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * 名称/头像违规处置控制台（内容审核 story 8，§5.2/§6.2）。SSR（Thymeleaf），{@code /admin/name-avatar-review}。
 *
 * <p><b>增量边界</b>：待审列表数据来自 story 4 {@link NameModerationService#pendingQueue()} / story 5
 * {@link AvatarModerationService#pendingQueue()}（{@code MANUAL_PENDING} 记录）；重置领域逻辑归 story 4/5，
 * 本 story 只提供控制台 + 传 §5.2 决策字段（判定依据/备注）。名称处置 POST 复用 story 4 既有端点
 * {@code /admin/name-moderation/{recordId}/decide}；头像处置端点由本 story 回补（story 5 定稿前缺）。
 *
 * <p>门控：页面 + 处置均 {@code SUPER_ADMIN or content.takedown}（页面展示审核证据——名称原文/头像图，
 * 属受控业务库证据，§5.5）。违规计数（§5.4）经 {@link ViolationCountReader} 只读展示（story 9 未接入 → 「—」）。
 * <b>本版本无申诉通道</b>（§5.5，页面侧栏显式标注）。
 */
@Controller
public class NameAvatarReviewAdminController {

    private static final String AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('content.takedown')";

    private final NameModerationService nameService;
    private final AvatarModerationService avatarService;
    private final ViolationCountReader violationCounts;
    private final PetProfileRepository petProfiles;

    public NameAvatarReviewAdminController(NameModerationService nameService,
            AvatarModerationService avatarService, ViolationCountReader violationCounts,
            PetProfileRepository petProfiles) {
        this.nameService = nameService;
        this.avatarService = avatarService;
        this.violationCounts = violationCounts;
        this.petProfiles = petProfiles;
    }

    @GetMapping("/admin/name-avatar-review")
    @PreAuthorize(AUTH)
    public String page(Model model) {
        model.addAttribute("active", "name-avatar-review");
        model.addAttribute("nameRows", nameRows());
        model.addAttribute("avatarRows", avatarRows());
        return "admin/name-avatar-review";
    }

    /**
     * 头像违规处置（story 8 回补，§5.3/§5.2）。{@code decision} ∈ {PASS, VIOLATION}；委托 story 5
     * {@code AvatarModerationService.decide} + 传 §5.2 决策字段（service 内落 AVATAR_RESET 审计 + 推送）。
     * 与 story 4 名称处置端点对称：{@code @ResponseBody} 文本，供页面 HTMX 表单 POST 后就地回显结果。
     */
    @PostMapping("/admin/avatar-review/{reviewId}/decide")
    @PreAuthorize(AUTH)
    @ResponseBody
    public ResponseEntity<String> decideAvatar(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long reviewId,
            @RequestParam("decision") String decision,
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "note", required = false) String note) {
        AvatarDecision parsed = parseAvatarDecision(decision);
        avatarService.decide(reviewId, parsed, admin.getAdminAccountId(),
                new ModerationDecision(category, note));
        return ResponseEntity.ok(parsed == AvatarDecision.VIOLATION
                ? "已判违规：头像已重置为平台默认头像并通知用户"
                : "已判通过：头像保留");
    }

    // ---------- 视图行构造（含违规计数解析） ----------

    private List<NameReviewRow> nameRows() {
        return nameService.pendingQueue().stream().map(r -> new NameReviewRow(
                r.getId(), r.getTargetType().name(), r.getSubmittedValue(), r.getRiskScore(),
                r.getSubmittedAt(), nameStrikes(r.getTargetType(), r.getTargetRefId()))).toList();
    }

    private List<AvatarReviewRow> avatarRows() {
        return avatarService.pendingQueue().stream().map(r -> new AvatarReviewRow(
                r.getId(), r.getSubjectType().name(), r.getAvatarUrl(), r.getRiskScore(),
                r.getCreatedAt(), avatarStrikes(r.getSubjectType(), r.getSubjectId()))).toList();
    }

    /** 名称行作者账号：昵称 → 目标即 users.id；宠物名 → 解析 owner。空则空计数。 */
    private ViolationCounts nameStrikes(NameTargetType type, long targetRefId) {
        Long account = type == NameTargetType.NICKNAME ? targetRefId : ownerOfPet(targetRefId);
        return strikesFor(account);
    }

    /** 头像行作者账号：用户头像 → subject 即 users.id；宠物头像 → 解析 owner。空则空计数。 */
    private ViolationCounts avatarStrikes(AvatarSubjectType type, long subjectId) {
        Long account = type == AvatarSubjectType.USER_AVATAR ? subjectId : ownerOfPet(subjectId);
        return strikesFor(account);
    }

    private Long ownerOfPet(long petProfileId) {
        return petProfiles.findById(petProfileId).map(p -> p.getOwnerId()).orElse(null);
    }

    private ViolationCounts strikesFor(Long account) {
        return account == null ? ViolationCounts.empty()
                : ViolationCounts.fromMap(violationCounts.countsFor(account));
    }

    private static AvatarDecision parseAvatarDecision(String raw) {
        if (raw == null) {
            throw AppException.validation("处置结论必填（PASS / VIOLATION）");
        }
        try {
            return AvatarDecision.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw AppException.validation("处置结论非法，须为 PASS / VIOLATION 之一");
        }
    }
}
