package com.tailtopia.admin.throttle.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.moderation.throttle.domain.ThrottleDuration;
import com.tailtopia.moderation.throttle.domain.ThrottleScope;
import com.tailtopia.moderation.throttle.service.RankThrottleService;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 限流处置动作（V1.1.6 Story 17.2）。内容列表与工单页的限流按钮都打到这里。
 *
 * <h2>为什么两个页面共用一个控制器</h2>
 * AC5 要求侧栏门与 {@code @PreAuthorize} <b>逐字一致</b>。权限表达式收在一处、
 * 且都从 {@link AdminPermissions} 常量拼出来，那条要求就不靠人去对照两段字符串。
 *
 * <h2>🛡 AC6：真表单 POST，CSRF 保持开启</h2>
 * {@code /admin/**} 链路不关 CSRF，测试须 {@code .with(csrf())}。
 *
 * <h2>⚠️ 这里不改限流的生效逻辑</h2>
 * 生效判定、到期、系数全在 17.1 的 {@link RankThrottleService} 里（AC7）。
 * 本类只做「谁能点、点了传什么参数、结果怎么回显」。
 */
@Controller
public class AdminThrottleController {

    private static final Logger log = LoggerFactory.getLogger(AdminThrottleController.class);

    /** 🛡 查看与处置分两个码（AC5）：能看见谁在限流 ≠ 能动手限流。 */
    public static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONTENT_THROTTLE_VIEW + "')";
    public static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('"
            + AdminPermissions.CONTENT_THROTTLE_MANAGE + "')";

    private final RankThrottleService service;

    public AdminThrottleController(RankThrottleService service) {
        this.service = service;
    }

    /**
     * 施加限流（AC1）。粒度与期限都由表单显式选，<b>没有默认值</b> ——
     * 「永久」不该是漏选时的落点。
     */
    @PostMapping("/admin/throttles")
    @PreAuthorize(MANAGE)
    public String apply(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("scope") ThrottleScope scope,
            @RequestParam(value = "targetId", required = false) Long targetId,
            // 内容举报的弹窗里粒度是可选的，两种粒度指向**不同的 id**（帖 id / 作者 id）。
            // ⚠️ 刻意不复用同一个 name 让浏览器"后者覆盖前者"——那是未定义行为，
            //    赌错的后果是把作者 id 当帖 id 限流，降错对象且没人会发现。
            @RequestParam(value = "postTargetId", required = false) Long postTargetId,
            @RequestParam(value = "accountTargetId", required = false) Long accountTargetId,
            @RequestParam("duration") ThrottleDuration duration,
            @RequestParam(value = "reportId", required = false) Long reportId,
            @RequestParam(value = "reason", required = false) String reason,
            @RequestParam(value = "back", required = false) String back,
            RedirectAttributes flash) {
        Long resolved = targetId != null ? targetId
                : (scope == ThrottleScope.POST ? postTargetId : accountTargetId);
        if (resolved == null) {
            // 内容举报里被举报账号已注销时 accountTargetId 会是空 —— 给人话而不是 500。
            flash.addFlashAttribute("error", "这条工单上找不到限流对象（账号可能已注销）");
            return redirect(back);
        }
        Instant now = Instant.now();
        long adminId = admin.getAdminAccountId();
        try {
            if (scope == ThrottleScope.POST) {
                service.throttlePost(resolved, duration, now, adminId, reportId, reason);
            } else {
                service.throttleAccount(resolved, duration, now, adminId, reportId, reason);
            }
            // 🔴 成功文案里也要带上「不是下架」——运营常常只看横幅，不回头看确认弹窗。
            flash.addFlashAttribute("notice",
                    "已限流曝光（只降低 Feed 分发权重，内容仍可通过直链和作者主页访问，不是下架）");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return redirect(back);
    }

    /**
     * 手动解除，🛡 <b>支持批量</b>（AC3）。
     *
     * <p>逐条解除、单条失败不影响其余：解除是幂等的收尾动作，
     * 一条已被别人解掉就让整批回滚，只会让运营重新勾一遍。
     */
    @PostMapping("/admin/throttles/lift")
    @PreAuthorize(MANAGE)
    public String lift(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam("throttleIds") List<Long> throttleIds,
            @RequestParam(value = "back", required = false) String back,
            RedirectAttributes flash) {
        Instant now = Instant.now();
        long adminId = admin.getAdminAccountId();
        int lifted = 0;
        for (Long id : throttleIds) {
            if (id != null && service.lift(id, now, adminId)) {
                lifted++;
            }
        }
        int stale = throttleIds.size() - lifted;
        if (stale > 0) {
            // 已到期或已被别人解除 —— 不是错误，但要说清，否则运营会以为按钮没生效。
            log.info("限流批量解除：成功 {} 条，已非生效态 {} 条", lifted, stale);
        }
        flash.addFlashAttribute("notice", stale == 0
                ? "已解除 " + lifted + " 条限流"
                : "已解除 " + lifted + " 条限流（另有 " + stale + " 条已到期或已被解除，无需处理）");
        return redirect(back);
    }

    /**
     * 回跳目标白名单。
     *
     * <p>🛡 直接把 {@code back} 拼进 redirect 会成为开放重定向 ——
     * 后台是登录态页面，被引到外站的钓鱼页代价不小。只认这几个已知页面。
     */
    private static String redirect(String back) {
        return switch (back == null ? "" : back) {
            case "tickets" -> "redirect:/admin/tickets";
            default -> "redirect:/admin/content";
        };
    }
}
