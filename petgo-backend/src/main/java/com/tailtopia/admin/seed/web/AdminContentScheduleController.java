package com.tailtopia.admin.seed.web;

import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.shared.error.AppException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 排期管理（V1.1.6 Story 13.5 · AC4）。
 *
 * <p>📌 <b>路径 {@code /admin/content-schedules} 是 Story 12.1 就写好的</b>：
 * 那边"移出发布身份前"的提示要跳到这个按账号过滤的视图
 * （{@code AdminPublishIdentityController.SCHEDULE_LIST_PATH}）。改路径要同时改那个常量。
 *
 * <p>🛡 列表**含失败行**：到点失败的行不自动消失、也不自动重试（AC5）——
 * 它留在这里就是为了让运营看见并处理。
 */
@Controller
public class AdminContentScheduleController {

    private static final String AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    /** 🛡 与 11-1/11-2/11-3、Excel 导入**四处一致**：运营填的墙上时间按 WIB 解释。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    /**
     * 排期列表里显示的状态。⚠️ 含 FAILED（见类注释）。
     *
     * 🛡 包内可见而非私有：批量内容页嵌了同一段排期（bug 20260826），
     * 两处**必须同一口径** —— 各写一份状态清单，迟早出现「这边有那边没有」的行。
     */
    static final List<SeedBatchRowStatus> LISTED =
            List.of(SeedBatchRowStatus.SCHEDULED, SeedBatchRowStatus.FAILED);

    private final SeedBatchRowRepository rows;
    private final SeedBatchService stateMachine;

    public AdminContentScheduleController(SeedBatchRowRepository rows,
            SeedBatchService stateMachine) {
        this.rows = rows;
        this.stateMachine = stateMachine;
    }

    @GetMapping("/admin/content-schedules")
    @PreAuthorize(AUTH)
    public String list(@RequestParam(required = false) Long authorId, Model model) {
        model.addAttribute("active", "content-schedules");
        model.addAttribute("authorId", authorId);
        model.addAttribute("rows", authorId == null
                ? rows.findByStatusInOrderByScheduledAtAsc(LISTED)
                : rows.findByStatusInAndAuthorUserIdOrderByScheduledAtAsc(LISTED, authorId));
        return "admin/content-schedules";
    }

    /**
     * 改计划时间（AC4）。
     *
     * <p>🔴 **不可早于当前时刻**（AC1）：排一个已经过去的时间，下一轮扫描就会立刻发出去 ——
     * 而运营的本意多半是"改到某个更晚的时候"，手滑填成过去的日期就成了立即发布，且不可撤回。
     */
    @PostMapping("/admin/content-schedules/{rowId}/time")
    @PreAuthorize(AUTH)
    public String reschedule(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable long rowId, @RequestParam String scheduledAt,
            @RequestParam(required = false) Long authorId, RedirectAttributes flash) {
        try {
            Instant at = requireFuture(scheduledAt);
            var row = rows.findById(rowId)
                    .orElseThrow(() -> AppException.notFound("排期不存在"));
            if (row.getStatus() == SeedBatchRowStatus.FAILED) {
                // 失败行要先回草稿才能重新排期（13-1 的状态机：FAILED → DRAFT → VALIDATED → SCHEDULED）。
                // 🛡 这里不替运营走完那一串 —— 失败多半有原因（账号被移出、审核拦下），
                //    直接改个时间再排一次只会到点再失败一次。
                throw AppException.validation("这一行已发布失败，请先回工作台修好再重新提交");
            }
            row.setScheduledAt(at);
            rows.save(row);
            flash.addFlashAttribute("notice", "已更新计划发布时间");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return redirect(authorId);
    }

    /** 取消排期 → 回退草稿，不发布（AC4）。 */
    @PostMapping("/admin/content-schedules/{rowId}/cancel")
    @PreAuthorize(AUTH)
    public String cancel(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long rowId,
            @RequestParam(required = false) Long authorId, RedirectAttributes flash) {
        try {
            stateMachine.cancelSchedule(rowId, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", "已取消排期，该行回到草稿");
        } catch (AppException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return redirect(authorId);
    }

    private static String redirect(Long authorId) {
        return "redirect:/admin/content-schedules" + (authorId == null ? "" : "?authorId=" + authorId);
    }

    /**
     * WIB 墙上时间 → UTC，并要求它在未来。
     *
     * <p>⚠️ 面向印尼市场，运营心里那个"明天早上 8 点"是 **WIB**。按服务器时区解释会整体偏 7 小时，
     * 而这种偏差在测试环境（也在 UTC）里看不出来。
     */
    private static Instant requireFuture(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请填写计划发布时间");
        }
        Instant at;
        try {
            at = LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(WIB).toInstant();
        } catch (Exception e) {
            throw AppException.validation("时间格式应为 2026-09-01T08:30");
        }
        if (!at.isAfter(Instant.now())) {
            throw AppException.validation("计划发布时间不能早于当前时刻（印尼时间 WIB）");
        }
        return at;
    }
}
