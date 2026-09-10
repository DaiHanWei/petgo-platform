package com.tailtopia.admin.seed.web;

import com.tailtopia.admin.seed.domain.SeedBatchRowStatus;
import com.tailtopia.admin.seed.repository.SeedBatchRowRepository;
import com.tailtopia.admin.seed.service.AdminSchedulePageService;
import com.tailtopia.admin.seed.service.SeedBatchService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
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
 * 排期的处置端点（V1.1.6 Story 13.5 · AC4 → V1.3.0 Story 7.5 独立页退役）。
 *
 * <p>📌 <b>{@code GET /admin/content-schedules} 已删除</b>：排期并入批量内容页第二页签
 * （{@code /admin/seed-batches?tab=schedules}）。原先保留独立页的三个理由，本 story 逐条处理了 ——
 * ① 两个 POST 的重定向落点改成页签；② 按发布账号筛选在页签里保留（12.1 的「移出发布身份前」提示
 * 仍带 {@code authorId} 跳进来，见 {@code AdminPublishIdentityController.SCHEDULE_LIST_PATH}）；
 * ③ 6 条集成测试改打新地址。不做旧地址跳转（D-23）。
 *
 * <p>🛡 列表**含失败行**：到点失败的行不自动消失、也不自动重试（AC5）——
 * 它留在那里就是为了让运营看见并处理。口径在 {@link AdminSchedulePageService#LISTED}。
 */
@Controller
public class AdminContentScheduleController {

    private static final String AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('virtual_account.manage')";

    /** 🛡 与 11-1/11-2/11-3、Excel 导入**四处一致**：运营填的墙上时间按 WIB 解释。 */
    private static final ZoneId WIB = ZoneId.of("Asia/Jakarta");

    private final SeedBatchRowRepository rows;
    private final SeedBatchService stateMachine;
    private final AdminSchedulePageService schedules;
    private final Messages msg;

    public AdminContentScheduleController(SeedBatchRowRepository rows,
            SeedBatchService stateMachine, AdminSchedulePageService schedules, Messages msg) {
        this.rows = rows;
        this.stateMachine = stateMachine;
        this.schedules = schedules;
        this.msg = msg;
    }

    /**
     * 排期详情抽屉（AC5）：完整内容预览 + 排期信息 + 操作条。
     *
     * <p>非 htmx 直达 → 回页签并自动开该抽屉（与 B1/B2/B3/B4 同一机制）。
     */
    @GetMapping("/admin/content-schedules/{rowId}/drawer")
    @PreAuthorize(AUTH)
    public String drawer(@PathVariable long rowId, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/seed-batches?tab=schedules&open=" + rowId;
        }
        populateDrawer(rowId, model);
        return "admin/fragments/drawer-schedule :: drawer";
    }

    private void populateDrawer(long rowId, Model model) {
        var row = schedules.row(rowId);
        model.addAttribute("r", row);
        model.addAttribute("author",
                schedules.authorViews(List.of(row)).get(row.getAuthorUserId()));
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
            @RequestParam(required = false) Long authorId,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            doReschedule(rowId, scheduledAt);
            // ⚠️ 不做单行 oob：表按**计划时间升序**，改完时间那一行的位置会变 ——
            //    原位换掉只会让它停在旧位置，看起来像「改了但没排上」。整表按当前筛选重拉。
            AdminFragmentResponses.trigger(response, AdminHxEvents.SCHEDULE_LIST_REFRESH,
                    AdminHxEvents.SCHEDULE_DRAWER_REFRESH);
            model.addAttribute("toast", msg.get("admin.flash.schedule.timeUpdated"));
            return "admin/fragments/schedules-tab :: afterAction";
        }
        try {
            doReschedule(rowId, scheduledAt);
            flash.addFlashAttribute("notice", msg.get("admin.flash.schedule.timeUpdated"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return redirect(authorId);
    }

    private void doReschedule(long rowId, String scheduledAt) {
        Instant at = requireFuture(scheduledAt);
        var row = rows.findById(rowId)
                .orElseThrow(() -> AppException.notFound("排期不存在")
                        .code("admin.err.schedule.notFound"));
        if (row.getStatus() == SeedBatchRowStatus.FAILED) {
            // 失败行要先回草稿才能重新排期（13-1 的状态机：FAILED → DRAFT → VALIDATED → SCHEDULED）。
            // 🛡 这里不替运营走完那一串 —— 失败多半有原因（账号被移出、审核拦下），
            //    直接改个时间再排一次只会到点再失败一次。
            throw AppException.validation("这一行已发布失败，请先回工作台修好再重新提交")
                    .code("admin.err.schedule.failedRowFixFirst");
        }
        row.setScheduledAt(at);
        rows.save(row);
    }

    /** 取消排期 → 回退草稿，不发布（AC4）。 */
    @PostMapping("/admin/content-schedules/{rowId}/cancel")
    @PreAuthorize(AUTH)
    public String cancel(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long rowId,
            @RequestParam(required = false) Long authorId,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            stateMachine.cancelSchedule(rowId, admin.getAdminAccountId());
            // 🔴 取消后这一行回到 DRAFT，**已经不属于这张表** —— 单行 oob 会把一条 DRAFT 行
            //    留在排期表里。整表重拉让它消失，抽屉（若开着）关掉：对象离开了本列表。
            AdminFragmentResponses.trigger(response, AdminHxEvents.SCHEDULE_LIST_REFRESH,
                    AdminHxEvents.DRAWER_CLOSE);
            model.addAttribute("toast", msg.get("admin.flash.schedule.cancelled"));
            return "admin/fragments/schedules-tab :: afterAction";
        }
        try {
            stateMachine.cancelSchedule(rowId, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.schedule.cancelled"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return redirect(authorId);
    }

    /** 非 htmx 的落点：批量内容页的排期页签（独立页已退役），带回发布账号筛选。 */
    private static String redirect(Long authorId) {
        return "redirect:/admin/seed-batches?tab=schedules"
                + (authorId == null ? "" : "&authorId=" + authorId);
    }

    /**
     * WIB 墙上时间 → UTC，并要求它在未来。
     *
     * <p>⚠️ 面向印尼市场，运营心里那个"明天早上 8 点"是 **WIB**。按服务器时区解释会整体偏 7 小时，
     * 而这种偏差在测试环境（也在 UTC）里看不出来。
     */
    private static Instant requireFuture(String raw) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation("请填写计划发布时间")
                    .code("admin.err.schedule.timeRequired");
        }
        Instant at;
        try {
            at = LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(WIB).toInstant();
        } catch (Exception e) {
            throw AppException.validation("时间格式应为 2026-09-01T08:30")
                    .code("admin.err.schedule.timeFormat");
        }
        if (!at.isAfter(Instant.now())) {
            throw AppException.validation("计划发布时间不能早于当前时刻（印尼时间 WIB）")
                    .code("admin.err.schedule.notFuture");
        }
        return at;
    }
}
