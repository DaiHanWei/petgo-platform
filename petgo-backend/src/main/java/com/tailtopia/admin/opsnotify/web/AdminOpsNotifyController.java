package com.tailtopia.admin.opsnotify.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.opsnotify.dto.OpsNotifyForm;
import com.tailtopia.admin.opsnotify.service.AdminOpsNotifyService;
import com.tailtopia.admin.opsnotify.service.AdminOpsNotifyService.IdsCheck;
import com.tailtopia.admin.opsnotify.service.AdminOpsNotifyService.Prepared;
import com.tailtopia.admin.opsnotify.service.OpsNotifyTypedConfirmRequired;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.time.AdminTime;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.notify.ops.domain.AudienceSpec;
import com.tailtopia.notify.ops.domain.OpsCampaignStatus;
import com.tailtopia.notify.ops.domain.OpsNotificationCampaign;
import com.tailtopia.ops.target.OpsAppPage;
import com.tailtopia.ops.target.OpsTargetType;
import com.tailtopia.profile.domain.PetType;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.domain.ProductCategory;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;
import org.springframework.web.servlet.support.RequestContextUtils;
import org.springframework.web.util.HtmlUtils;

/**
 * 「系统通知发送」页（V1.3.4 AB-31 · FR-138；按后台返工清单 §4 对齐 UI 稿 I1–I11）：
 * 发送记录列表（状态 + 发送时间筛选、分页、待发送行「取消」→ I10 服务端确认弹层）+ 独立的四步新建页
 * {@code GET /admin/ops-notifications/new}（① 内容 ② 选择用户 ③ 发送时间 ④ 确认；I8 大范围手输确认；成功回列表 + toast）。
 *
 * <p>🔒 <b>{@code ops_notify.view}（看）/ {@code ops_notify.send}（新建、取消、预估、ID 校验）</b>（返工清单 §1，
 * 不预授予预置角色）。只有 view 的人看到 I11 只读提示，不渲染新建 / 取消入口；服务端 {@code @PreAuthorize} 兜底。
 *
 * <p>🔴 URL 里一律用 {@code public_token}（D-24）。不提供编辑 / 重发 / 删除；不展示打开 / 点击数（走 PostHog）。
 */
@Controller
public class AdminOpsNotifyController {

    private static final Logger log = LoggerFactory.getLogger(AdminOpsNotifyController.class);

    /** 查看门放 view 或 send 任一（只授了 send 的人也得进得来发）；写端点只认 send（返工清单 §1：发送与查看分权）。 */
    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('ops_notify.view') "
            + "or hasAuthority('ops_notify.send')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('ops_notify.send')";

    static final String BASE = "/admin/ops-notifications";
    /** 列表「发送时间」筛选（I1）：近 7 / 30 / 90 天 / 全部；默认近 30 天。 */
    static final List<String> RANGES = List.of("7", "30", "90", "all");
    static final String DEFAULT_RANGE = "30";
    /** 列表时间短格式（I1：{@code 10/11 09:00}，WIB）。 */
    private static final DateTimeFormatter SHORT = DateTimeFormatter.ofPattern("MM/dd HH:mm").withZone(AdminTime.ZONE);
    /**
     * CSV 上传的文件上限：5 万行 × 每行几十字节（Excel 导出常带姓名等多列、CRLF），4MB 留足余量；再大多半不是 ID 清单。
     * ⚠️ 前端读进 textarea 后按 urlencoded 整表提交，放行靠 application.yml 的 {@code server.tomcat.max-http-form-post-size}
     * （10MB）——改这里要同步看那边。
     */
    static final long MAX_CSV_BYTES = 4L * 1024 * 1024;

    private final AdminOpsNotifyService service;
    private final OpsNotifyLabels labels;
    private final AdminTime adminTime;
    private final Messages msg;

    public AdminOpsNotifyController(AdminOpsNotifyService service, OpsNotifyLabels labels, AdminTime adminTime,
            Messages msg) {
        this.service = service;
        this.labels = labels;
        this.adminTime = adminTime;
        this.msg = msg;
    }

    // ---------- 列表（I1 / I2 / I9 / I11）----------

    /** 列表。筛选栏 / 翻页的 htmx 请求返行片段；非 htmx 返整页。 */
    @GetMapping(BASE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model) {
        model.addAttribute("active", "ops-notifications");
        // 🔒 只读账号不给写入口（新建 / 取消都不渲染，I11）；服务端 @PreAuthorize 照旧兜底。
        model.addAttribute("canEdit", has(admin, AdminPermissions.OPS_NOTIFY_SEND));
        populateList(status, range, page, model);
        return hx.isHtmx() ? "admin/fragments/ops-notifications-list :: rows" : "admin/ops-notifications";
    }

    private void populateList(String status, String range, int page, Model model) {
        OpsCampaignStatus st = AdminOpsNotifyService.parseStatus(status);
        String r = range == null || !RANGES.contains(range) ? DEFAULT_RANGE : range;
        Integer days = "all".equals(r) ? null : Integer.valueOf(r);
        Page<OpsNotificationCampaign> p = service.page(status, days, page);
        if (p.getNumber() > 0 && p.getNumberOfElements() == 0 && p.getTotalPages() > 0) {
            p = service.page(status, days, p.getTotalPages() - 1); // 越界页码落回最后一页
        }
        List<Map<String, Object>> view = new ArrayList<>();
        for (OpsNotificationCampaign c : p.getContent()) {
            view.add(row(c));
        }
        model.addAttribute("campaigns", view);
        model.addAttribute("page", p.getNumber());
        model.addAttribute("hasNext", p.hasNext());
        model.addAttribute("total", p.getTotalElements());
        model.addAttribute("statuses", OpsCampaignStatus.values());
        model.addAttribute("ranges", RANGES);
        model.addAttribute("filterStatus", st == null ? "" : st.name());
        model.addAttribute("filterRange", r);
        // 「还没有发送过」只在库里一条都没有时出（I2）；有记录但被筛光 → 另一句
        model.addAttribute("anyCampaign", !p.isEmpty() || service.hasAny());
    }

    private Map<String, Object> row(OpsNotificationCampaign c) {
        Map<String, Object> m = new LinkedHashMap<>();
        OpsCampaignStatus s = c.getStatus();
        m.put("token", c.getPublicToken());
        m.put("titleId", c.getTitleId());
        m.put("status", s.name());
        m.put("pending", s == OpsCampaignStatus.PENDING);
        m.put("audience", labels.audience(spec(c), c.getIdsSource()));
        m.put("sendLabel", sendAt(c)); // 已按 WIB 短格式化好的字符串（不是时间戳）
        m.put("reach", reach(c));
        m.put("reachMuted", s == OpsCampaignStatus.PENDING || s == OpsCampaignStatus.CANCELED);
        m.put("failed", s == OpsCampaignStatus.PARTIAL_FAILED && c.getFailedCount() > 0
                ? msg.get("admin.opsNotify.reach.failed", c.getFailedCount()) : null);
        return m;
    }

    private static AudienceSpec spec(OpsNotificationCampaign c) {
        try {
            return AudienceSpec.parse(c.getAudienceSpec());
        } catch (RuntimeException e) {
            // 库里有 CHECK，理论上到不了；坏了也只影响这一格显示，不让整页 500（只记 token，人群 JSON 不进日志）
            log.warn("系统通知人群规格解析失败，该格显示为空: token={} err={}", c.getPublicToken(),
                    e.getClass().getSimpleName());
            return null;
        }
    }

    /**
     * 发送时间（I1）：未发出的定时（待发送 / 已取消）显示「10/11 09:00（定时）」；已开始的显示实际发出时间；
     * 立即发送尚未开始的显示创建时间。
     */
    private String sendAt(OpsNotificationCampaign c) {
        OpsCampaignStatus s = c.getStatus();
        if (c.getScheduledAt() != null && (s == OpsCampaignStatus.PENDING || s == OpsCampaignStatus.CANCELED)) {
            return msg.get("admin.opsNotify.sendAt.scheduled", SHORT.format(c.getScheduledAt()));
        }
        Instant t = c.getStartedAt() != null ? c.getStartedAt()
                : c.getScheduledAt() != null ? c.getScheduledAt() : c.getCreatedAt();
        return SHORT.format(t);
    }

    /** 触达人数（I1）：待发送「预计 N」/ 发送中「已写入 / 计划」/ 已完成 N / 部分失败 N（失败另起）/ 已取消「—」。 */
    private String reach(OpsNotificationCampaign c) {
        return switch (c.getStatus()) {
            case PENDING -> c.getEstimatedCount() == null ? "—"
                    : msg.get("admin.opsNotify.reach.estimated", c.getEstimatedCount());
            case SENDING -> c.getPlannedCount() == null
                    ? msg.get("admin.opsNotify.reach.count", c.getRecipientCount())
                    : msg.get("admin.opsNotify.reach.progress", c.getRecipientCount(), c.getPlannedCount());
            case DONE, PARTIAL_FAILED -> msg.get("admin.opsNotify.reach.count", c.getRecipientCount());
            case CANCELED -> "—";
        };
    }

    // ---------- 新建页（I3–I8）----------

    /** 四步新建页（模板 E）。每次打开发一个新的一次性提交键。 */
    @GetMapping(BASE + "/new")
    @PreAuthorize(EDIT_AUTH)
    public String newPage(Model model) {
        model.addAttribute("active", "ops-notifications");
        OpsNotifyForm f = new OpsNotifyForm();
        f.setSendMode(AdminOpsNotifyService.SEND_NOW);
        f.setIdsSource(AdminOpsNotifyService.SOURCE_PASTE);
        f.setSubmitKey(UUID.randomUUID().toString());
        model.addAttribute("form", f);
        model.addAttribute("withinPresets", AdminOpsNotifyService.WITHIN_PRESETS);
        model.addAttribute("speciesValues", PetType.values());
        model.addAttribute("targetTypes", OpsTargetType.values());
        model.addAttribute("categories", ProductCategory.values());
        model.addAttribute("appPages", OpsAppPage.values());
        model.addAttribute("nowWib", adminTime.fmt(Instant.now()));
        model.addAttribute("minScheduleWib", adminTime.nowWibForInput());
        model.addAttribute("maxCsvBytes", MAX_CSV_BYTES);
        return "admin/ops-notification-new";
    }

    /**
     * 预计触达（I4）：人群块一变 300ms 防抖自动调，返回带 N 的片段。人群条件不合法 → 422 由
     * AdminBusinessExceptionAdvice 按 HX-Target 回填到同一个槽。
     */
    @PostMapping(BASE + "/preview")
    @PreAuthorize(EDIT_AUTH)
    public String preview(@ModelAttribute("form") OpsNotifyForm form, Model model) {
        model.addAttribute("count", service.preview(form));
        return "admin/fragments/ops-notification-steps :: estimate";
    }

    /**
     * 指定用户 ID 校验（I5）：粘贴的文本（{@code userIds}）或上传的 CSV（{@code file}，优先）。返回「有效 X 人，跳过 Y 个
     * （按原因）」黄条；非数字行计入「不存在」，不报错。超过 5 万行 → 422。
     */
    @PostMapping(BASE + "/ids/check")
    @PreAuthorize(EDIT_AUTH)
    public String checkIds(@RequestParam(value = "userIds", required = false) String userIds,
            @RequestParam(value = "file", required = false) MultipartFile file, Model model) {
        IdsCheck r = service.checkIds(file != null && !file.isEmpty() ? readCsv(file) : userIds);
        model.addAttribute("valid", r.valid());
        model.addAttribute("skippedTotal", r.skippedTotal());
        model.addAttribute("reasons", reasonSummary(r));
        return "admin/fragments/ops-notification-steps :: idsResult";
    }

    /** 「不存在 9 · 已注销 5 · 已封号 2 · 虚拟号 1」（只列非零项）。 */
    private String reasonSummary(IdsCheck r) {
        List<String> parts = new ArrayList<>();
        r.skippedByReason().forEach((reason, n) -> {
            if (n > 0) {
                parts.add(msg.get("admin.opsNotify.ids.reasonCount",
                        msg.get("admin.opsNotify.ids.reason." + reason.name()), n));
            }
        });
        return String.join(" · ", parts);
    }

    /**
     * 导出跳过清单（I5）：两列「原始内容, 原因」，UTF-8 带 BOM（Excel 直接打开不乱码）。
     * 🔒 只回显运营自己提交的内容 + 原因分类，不带任何用户资料。
     */
    @PostMapping(BASE + "/ids/skipped.csv")
    @PreAuthorize(EDIT_AUTH)
    public ResponseEntity<byte[]> exportSkipped(@RequestParam(value = "userIds", required = false) String userIds) {
        IdsCheck r = service.checkIds(userIds);
        StringBuilder sb = new StringBuilder("﻿");
        sb.append(csv(msg.get("admin.opsNotify.skippedCsv.value"))).append(',')
                .append(csv(msg.get("admin.opsNotify.skippedCsv.reason"))).append("\r\n");
        for (AdminOpsNotifyService.Skipped s : r.skipped()) {
            sb.append(csv(s.value())).append(',')
                    .append(csv(msg.get("admin.opsNotify.ids.reason." + s.reason().name()))).append("\r\n");
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("skipped-user-ids.csv").build().toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    /** CSV 单元格：防公式注入（= + - @ 开头前置单引号）+ 引号转义。 */
    static String csv(String v) {
        String s = v == null ? "" : v;
        if (!s.isEmpty() && "=+-@\t\r".indexOf(s.charAt(0)) >= 0) {
            s = "'" + s;
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private static String readCsv(MultipartFile file) {
        if (file.getSize() > MAX_CSV_BYTES) {
            throw AppException.validation("CSV 文件过大").code(AdminOpsNotifyService.ERR_IDS_TOO_MANY, 50_000);
        }
        try {
            return new String(file.getBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw AppException.validation("CSV 读取失败").code("admin.err.ops-notify.csv-unreadable");
        }
    }

    /**
     * 进入「④ 确认」（I7）：整表提交，与新建同一套校验（按步骤先后报第一处错，422 落进新建页底部错误槽，
     * 前端按错误码跳回对应步骤）。通过则主 swap 清空错误槽，确认面板以 oob 换进 {@code #opsn-review}，
     * 并发 {@code opsn:reviewed} 让前端切到第 ④ 步。
     */
    @PostMapping(BASE + "/review")
    @PreAuthorize(EDIT_AUTH)
    public String review(@ModelAttribute("form") OpsNotifyForm form, HttpServletResponse response, Model model) {
        Prepared p = service.review(form);
        model.addAttribute("p", p);
        model.addAttribute("audienceLabel", labels.audience(p.spec(), p.idsSource()));
        model.addAttribute("targetLabel", labels.target(p.targetType(), p.targetValue()));
        model.addAttribute("sendAtLabel", p.scheduledAt() == null ? msg.get("admin.opsNotify.review.sendNow")
                : msg.get("admin.opsNotify.review.sendScheduled", adminTime.fmt(p.scheduledAt())));
        // swap 之后再切到 ④：先切的话确认面板还是上一次的内容
        response.setHeader("HX-Trigger-After-Swap", "opsn:reviewed");
        return "admin/fragments/ops-notification-steps :: review";
    }

    // ---------- 写入 ----------

    /**
     * 确认发送（I7 → I8 → I9）。htmx：成功 → flash toast + {@code HX-Redirect} 回列表；须手输人数 → 输入确认弹层
     * （{@code #confirm-host}，弹层表单带上整张表单 + {@code confirmCount} 再提交到这里）；校验失败 → 422 落进
     * HX-Target（新建页错误槽 / 弹层内错误槽）。非 htmx：PRG + flash。
     */
    @PostMapping(BASE)
    @PreAuthorize(EDIT_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @ModelAttribute("form") OpsNotifyForm form, HxRequest hx, Model model,
            HttpServletRequest request, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminOpsNotifyService.Created created;
            try {
                created = service.create(form, admin.getAdminAccountId());
            } catch (OpsNotifyTypedConfirmRequired t) {
                model.addAttribute("cm", typedModal(form, t.count()));
                response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
                response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
                return "admin/fragments/confirm-modal :: modal";
            }
            // htmx 收到 HX-Redirect 整页跳转、丢弃响应体：toast 走 flash，列表页由 layout 渲染
            RequestContextUtils.getOutputFlashMap(request).put("toast", createdToast(created));
            RequestContextUtils.saveOutputFlashMap(BASE, request, response);
            response.setHeader("HX-Redirect", request.getContextPath() + BASE);
            return "admin/fragments/ops-notification-steps :: redirecting";
        }
        try {
            ra.addFlashAttribute("toast", createdToast(service.create(form, admin.getAdminAccountId())));
        } catch (OpsNotifyTypedConfirmRequired t) {
            ra.addFlashAttribute("error", msg.get(AdminOpsNotifyService.ERR_CONFIRM_COUNT, t.count()));
            return "redirect:" + BASE + "/new";
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
            return "redirect:" + BASE + "/new";
        }
        return "redirect:" + BASE;
    }

    /** I9 toast：定时「已提交，将于 … WIB 发送」/ 立即「已提交，正在发送」（§0-11）。 */
    private String createdToast(AdminOpsNotifyService.Created c) {
        return c.scheduledAt() == null ? msg.get("admin.flash.ops-notify.created")
                : msg.get("admin.flash.ops-notify.scheduled", adminTime.fmt(c.scheduledAt()));
    }

    /** I8：「确认发送给 38,950 人？」+ 黄条 + 手输人数；弹层表单带上整张表单再提交到 {@code POST BASE}。 */
    private AdminConfirmModal typedModal(OpsNotifyForm f, long count) {
        Map<String, String> hidden = new LinkedHashMap<>();
        put(hidden, "titleId", f.getTitleId());
        put(hidden, "titleEn", f.getTitleEn());
        put(hidden, "bodyId", f.getBodyId());
        put(hidden, "bodyEn", f.getBodyEn());
        put(hidden, "targetType", f.getTargetType());
        put(hidden, "targetValue", f.getTargetValue());
        put(hidden, "audienceMode", f.getAudienceMode());
        put(hidden, "registeredWithin", f.getRegisteredWithin());
        put(hidden, "activeWithin", f.getActiveWithin());
        put(hidden, "hasPet", f.getHasPet());
        put(hidden, "species", f.getSpecies());
        put(hidden, "hasShopOrder", f.getHasShopOrder());
        put(hidden, "hasPhone", f.getHasPhone());
        put(hidden, "userIds", f.getUserIds());
        put(hidden, "idsSource", f.getIdsSource());
        put(hidden, "sendMode", f.getSendMode());
        put(hidden, "scheduledAt", f.getScheduledAt());
        put(hidden, "previewCount", f.getPreviewCount());
        put(hidden, "submitKey", f.getSubmitKey());
        String n = String.valueOf(count);
        return new AdminConfirmModal(msg.get("admin.opsNotify.typed.title", count),
                msg.get("admin.opsNotify.typed.banner"), List.of(), null,
                HtmlUtils.htmlEscape(msg.get("admin.opsNotify.typed.prompt", n)), "confirmCount", n,
                BASE, hidden, msg.get("admin.opsNotify.btn.confirmSend"), false, true);
    }

    private static void put(Map<String, String> m, String k, String v) {
        if (v != null) {
            m.put(k, v);
        }
    }

    // ---------- 取消（I10）----------

    /**
     * 取消确认弹层（I10）。行内「取消」{@code hx-get} 到这里（hx-target = 列表上方的行内错误槽：拉不到（404 / 已不是待发送 409）
     * 时红字落在那里），成功则 HX-Retarget 到页面 {@code #confirm-host}。弹层表单带上当前筛选与页码，处置后按原样重拉。
     */
    @GetMapping(BASE + "/{token}/confirm-cancel")
    @PreAuthorize(EDIT_AUTH)
    public String confirmCancel(@PathVariable String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + BASE;
        }
        OpsNotificationCampaign c = service.require(token);
        if (c.getStatus() != OpsCampaignStatus.PENDING) {
            throw AppException.conflict("这条通知已在发送或已结束，不能取消")
                    .code("admin.err.ops-notify.cancel.not-pending");
        }
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("status", status == null ? "" : status);
        hidden.put("range", range == null ? "" : range);
        hidden.put("page", String.valueOf(page));
        Instant when = c.getScheduledAt() != null ? c.getScheduledAt() : c.getCreatedAt();
        model.addAttribute("cm", new AdminConfirmModal(msg.get("admin.opsNotify.cancel.title"), null, List.of(),
                msg.get("admin.opsNotify.cancel.body", c.getTitleId(), SHORT.format(when)), null, null, null,
                BASE + "/" + token + "/cancel", hidden, msg.get("admin.opsNotify.cancel.confirm"), true));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    /**
     * 取消待发送（弹层内提交）。成功：整表 oob（按原筛选 / 页码）+ toast，swap 之后发 {@code admin:confirm-close} 收弹层；
     * 已在发送 / 已完成 → 409 回填弹层内错误槽。
     */
    @PostMapping(BASE + "/{token}/cancel")
    @PreAuthorize(EDIT_AUTH)
    public String cancel(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "range", required = false) String range,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            service.cancel(token, admin.getAdminAccountId());
            // 在 swap 之后再收弹层：先收的话发请求的表单已不在 DOM 里
            response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
            populateList(status, range, page, model);
            model.addAttribute("canEdit", has(admin, AdminPermissions.OPS_NOTIFY_SEND));
            model.addAttribute("message", msg.get("admin.flash.ops-notify.canceled"));
            return "admin/fragments/ops-notifications-list :: done";
        }
        try {
            service.cancel(token, admin.getAdminAccountId());
            ra.addFlashAttribute("toast", msg.get("admin.flash.ops-notify.canceled"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:" + BASE;
    }

    private static boolean has(AdminUserDetails admin, String permission) {
        if (admin == null) {
            return false;
        }
        for (GrantedAuthority a : admin.getAuthorities()) {
            if ("ROLE_SUPER_ADMIN".equals(a.getAuthority()) || permission.equals(a.getAuthority())) {
                return true;
            }
        }
        return false;
    }
}
