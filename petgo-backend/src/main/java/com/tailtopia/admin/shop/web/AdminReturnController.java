package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.admin.shop.service.AdminReturnService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.RejectDisposal;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnType;
import com.tailtopia.shop.returns.service.ReturnRequestService;
import com.tailtopia.shared.i18n.Messages;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * 退货审核 / 质检 / 退款执行 / 判例库（Story 5.3 AB-12A · 5.4 AB-12B · 5.5 AB-12C · 5.6 AB-12D）。
 *
 * <p>🔴 <b>不新建审核通道</b>（AB-12A）：权限沿用既有退款审批三级职责分离 ——
 * {@code refund.view}（看）/ {@code refund.approve}（批）/ {@code refund.payout}（打款）。
 * 新造一套平行权限会让「谁能批退款」有两个互相矛盾的答案。
 *
 * <p>🔴 所有 POST 端点本地 {@code catch AppException}（仓库统一处置）。
 */
@Controller
public class AdminReturnController {

    private static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.view') "
                    + "or hasAuthority('refund.approve') or hasAuthority('refund.payout')";
    private static final String APPROVE_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.approve')";
    private static final String PAYOUT_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.payout')";

    private static final int PAGE_SIZE = 100;

    /** A7 工作台左栏每页 20 条滚动加载（Story 10.1 AC1）；判例库仍用 {@link #PAGE_SIZE}。 */
    private static final int QUEUE_PAGE_SIZE = 20;

    /**
     * 🔴 非 htmx 处置后回<b>队列</b>而不是 {@code /{token}} —— 整页详情已退役（AC5），
     * 往那里 302 等于把旧地址永久续上（D-23 明确不做旧地址跳转）。
     */
    private static final String REDIRECT_QUEUE = "redirect:/admin/shop/returns";

    private final AdminReturnService adminReturns;
    private final ReturnRequestService requests;
    private final ShopOrderRepository orders;
    private final ShopOrderLineRepository orderLines;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    /** 凭证图是**私有桶**对象（D-10），后台要看必须签短 TTL URL —— 见 detail()。 */
    private final com.tailtopia.shared.media.SignedUrlService signedUrls;

    /** 质检照片直传（D-15）。⚠️ 落**公开桶**，与用户凭证的私有桶不是一回事，见 uploadInspectionImage()。 */
    private final com.tailtopia.admin.seed.service.AdminSeedImageService images;

    public AdminReturnController(AdminReturnService adminReturns, ReturnRequestService requests,
            ShopOrderRepository orders, ShopOrderLineRepository orderLines,
            Messages msg, com.tailtopia.shared.media.SignedUrlService signedUrls,
            com.tailtopia.admin.seed.service.AdminSeedImageService images) {
        this.adminReturns = adminReturns;
        this.requests = requests;
        this.orders = orders;
        this.orderLines = orderLines;
        this.msg = msg;
        this.signedUrls = signedUrls;
        this.images = images;
    }

    /**
     * 库里存的是**逗号拼接的一串** key（见 {@code ReturnRequest.joinKeys}），签名前先拆开。
     *
     * <p>⚠️ 空串要拆成空列表而不是 {@code [""]} —— 后者会签出一个指向桶根的 URL，
     * 页面上表现为一张永远加载不出来的破图。
     */
    private static java.util.List<String> splitKeys(String joined) {
        if (joined == null || joined.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(joined.split("[,\n]"))
                .map(String::trim).filter(k -> !k.isEmpty()).toList();
    }

    // ---------- 5.3 审核队列 → V1.3.0 Story 10.1：A7 模板 A 工作台 ----------

    /**
     * A7 工作台整页；{@code tab=pending|shipback|inspect|refund|closed}（默认待审核）；
     * 筛选 {@code type}（退货类型）/ {@code full}（整单退）；{@code open=<token>} 页内深链自动开该条。
     *
     * <p>htmx 请求返左栏行片段 —— 页签是整页 GET（{@code tpl-a-state-tabs} 约定），
     * 走 htmx 的只有「加载更多」哨兵。<b>不新增 {@code /queue} 端点</b>（AB-19A 零新端点）。
     */
    @GetMapping("/admin/shop/returns")
    @PreAuthorize(VIEW_AUTH)
    public String queue(@RequestParam(value = "tab", required = false) String tab,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            HxRequest hx, Model model) {
        AdminReturnService.Tab t = AdminReturnService.Tab.of(tab);
        String opened = open == null || open.isBlank() ? null : open.trim();
        if (opened != null && (tab == null || tab.isBlank())) {
            // 深链未指明页签：按该申请当前状态落页签，行才会在左栏出现（与 A6 同款）
            try {
                t = AdminReturnService.Tab.of(adminReturns.require(opened).getStatus());
            } catch (AppException ignore) {
                // 不存在的 token：留在默认页签，右栏由 JS 拉 detail 时得到 404 → 空态
            }
        }
        populateQueue(t, type, full, page, model);
        model.addAttribute("open", opened);
        model.addAttribute("active", "shopReturns");
        return hx.isHtmx() ? "admin/fragments/shop-return-queue :: rows" : "admin/shop-returns";
    }

    /**
     * 右栏五区 fragment。
     *
     * <p>🔴 <b>整页详情已退役</b>（Story 10.1 AC5）：非 htmx 请求一律 404，<b>不做旧地址跳转</b>（D-23）。
     * 路径复用同一 mapping 而不是新开 {@code /{token}/detail} —— AB-19A「零新端点」。
     */
    @GetMapping("/admin/shop/returns/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String token,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            throw AppException.notFound("该页面已并入退货审核工作台")
                    .code("admin.err.common.pageRetired");
        }
        // 当前筛选原样带进右栏的处置表单（隐藏字段）—— 处置后 oob 的页签计数才与左栏同口径（AC4）
        model.addAttribute("type", parseType(type) == null ? "" : parseType(type).name());
        model.addAttribute("full", parseFull(full) == null ? "" : String.valueOf(parseFull(full)));
        ReturnRequest r = adminReturns.require(token);
        var order = orders.findById(r.getShopOrderId()).orElseThrow();
        var lines = requests.linesOf(r.getId());
        Map<Long, String> lineNames = new LinkedHashMap<>();
        for (var rl : lines) {
            orderLines.findById(rl.getOrderLineId()).ifPresent(ol -> lineNames.put(rl.getId(),
                    ol.getProductName() + " · " + ol.getSpecName()));
        }
        model.addAttribute("r", r);
        model.addAttribute("order", order);
        model.addAttribute("lines", lines);
        model.addAttribute("lineNames", lineNames);
        model.addAttribute("disposals", RejectDisposal.values());
        // 🔴 D-13（2026-09-02 stag）：此前模板直接 `th:text="${r.evidenceKeys}"`，
        //    页面上渲染出的是 `return-evidence-1,return-evidence-2` 这样一串 key，
        //    **零个 <img>** —— 质检要看封口和保质期标签，而审核界面从来就没有看图的能力。
        //    ⚠️ 凭证进的是**私有桶**（D-10），不能像商品图那样拼公开 URL：
        //       必须签短 TTL URL，与工单附件、兽医资质、异常工单三处同一套路。
        model.addAttribute("evidenceUrls",
                signedUrls.signAll(splitKeys(r.getEvidenceKeys())));
        model.addAttribute("inspectionPhotoUrls",
                signedUrls.signAll(splitKeys(r.getInspectionPhotoKeys())));
        // 🔴 退款单详情【明确列出溢价金额与触发依据】，便于事后审计与客诉复盘（5.5 AC）
        try {
            model.addAttribute("quote", adminReturns.quote(token));
        } catch (AppException e) {
            model.addAttribute("quote", null);
        }
        // ① 五步进度条（Story 10.1 AC1）：状态机是既有的，这里只把它读成五步
        model.addAttribute("steps", adminReturns.steps(r));
        model.addAttribute("active", "shopReturns");
        return "admin/fragments/shop-return-panel :: detail";
    }

    // ---------- 审核动作 ----------

    @PostMapping("/admin/shop/returns/{token}/approve")
    @PreAuthorize(APPROVE_AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            adminReturns.approve(token, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.approved"), model);
        }
        try {
            adminReturns.approve(token, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.approved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    @PostMapping("/admin/shop/returns/{token}/reject")
    @PreAuthorize(APPROVE_AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String reason,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            adminReturns.reject(token, reason, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.rejected"), model);
        }
        try {
            adminReturns.reject(token, reason, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.rejected"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    // ---------- 5.4 寄回登记与质检 ----------

    @PostMapping("/admin/shop/returns/{token}/shipback")
    @PreAuthorize(APPROVE_AUTH)
    public String shipback(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String carrier,
            @RequestParam String trackingNo, @RequestParam(required = false) Long fee,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            adminReturns.registerShipback(token, carrier, trackingNo, fee, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.shipmentRegistered"), model);
        }
        try {
            adminReturns.registerShipback(token, carrier, trackingNo, fee, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.shipmentRegistered"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    /**
     * 质检照片直传（D-15 第 4 条，2026-09-02 产品拍板：<b>落公开桶</b>）。
     *
     * <p>此前质检照片是个**手填 objectKey 的文本框** —— 运营得先去别处传图、抄下 key、再粘回来，
     * 与 D-13 同源（那边是「有 key 但看不到图」，这边是「要人工造 key」）。
     *
     * <h2>⚠️ 公开桶 vs 私有桶：同一页上两种图，别混</h2>
     * <ul>
     *   <li><b>用户凭证</b>（{@code evidenceKeys}）走**私有桶** —— 那是用户拍的实物照，
     *       可能带家里、面单、地址；后台看它要签短 TTL URL（见 {@code detail()}）。</li>
     *   <li><b>质检照片</b>（本端点）走**公开桶** —— 平台自己拍的验货照，
     *       2026-09-02 产品拍板。</li>
     * </ul>
     * 两者在同一页并存，取图方式不同，改任一处前先确认改的是哪一种。
     *
     * <p>回包形状与商品图 / 内容侧**逐字一致**（一次一张、错误 400 + {@code {"error":...}}）——
     * 前端那个上传控件是共用的，形状不一致它就得分叉。
     */
    @PostMapping("/admin/shop/returns/images")
    @PreAuthorize(APPROVE_AUTH)
    @org.springframework.web.bind.annotation.ResponseBody
    public org.springframework.http.ResponseEntity<?> uploadInspectionImage(
            @RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            return org.springframework.http.ResponseEntity.ok(
                    images.upload(file, "shop-return-inspection"));
        } catch (AppException e) {
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", e.getMessage()));
        } catch (Exception e) {
            // 对象存储未配置 / 凭证异常：回人话，不把 500 甩到运营脸上。
            return org.springframework.http.ResponseEntity.badRequest()
                    .body(java.util.Map.of("error", msg.get("admin.shop.form.uploadFailed")));
        }
    }

    @PostMapping("/admin/shop/returns/{token}/inspect-pass")
    @PreAuthorize(APPROVE_AUTH)
    public String inspectPass(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam(required = false) String note,
            @RequestParam(required = false) String photoKeys,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            adminReturns.passInspection(token, note, photoKeys, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.inspectPassed"), model);
        }
        try {
            adminReturns.passInspection(token, note, photoKeys, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.inspectPassed"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    @PostMapping("/admin/shop/returns/{token}/inspect-fail")
    @PreAuthorize(APPROVE_AUTH)
    public String inspectFail(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String note,
            @RequestParam(required = false) String photoKeys,
            @RequestParam String disposal,
            @RequestParam(required = false) String shipBackTrackingNo,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            adminReturns.failInspection(token, note, photoKeys, parseDisposal(disposal),
                    shipBackTrackingNo, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.inspectFailed"), model);
        }
        try {
            adminReturns.failInspection(token, note, photoKeys, parseDisposal(disposal),
                    shipBackTrackingNo, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.inspectFailed"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    // ---------- 5.5 退款执行（财务） ----------

    @PostMapping("/admin/shop/returns/{token}/refund")
    @PreAuthorize(PAYOUT_AUTH)
    public String refund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "full", required = false) String full,
            HxRequest hx, Model model, RedirectAttributes ra) {
        if (hx.isHtmx()) {
            AdminReturnService.Tab from = tabOf(token);
            var out = adminReturns.executeRefund(token, actorOf(admin));
            return done(from, token, type, full,
                    msg.get("admin.flash.return.refunded", out.coinRefunded(),
                            out.cashRefunded(), out.compensationPremium()), model);
        }
        try {
            var out = adminReturns.executeRefund(token, actorOf(admin));
            ra.addFlashAttribute("notice",
                    msg.get("admin.flash.return.refunded", out.coinRefunded(),
                            out.cashRefunded(), out.compensationPremium()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return REDIRECT_QUEUE;
    }

    // ---------- 5.6 判例库 ----------

    @GetMapping("/admin/shop/return-precedents")
    @PreAuthorize(VIEW_AUTH)
    public String precedents(@RequestParam(required = false) String q, Model model) {
        model.addAttribute("rows", adminReturns.searchPrecedents(q, PAGE_SIZE));
        model.addAttribute("q", q == null ? "" : q);
        model.addAttribute("active", "shopReturns");
        return "admin/shop-return-precedents";
    }

    @PostMapping("/admin/shop/return-precedents")
    @PreAuthorize(APPROVE_AUTH)
    public String addPrecedent(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam String situation, @RequestParam boolean judgedOpened,
            @RequestParam String rationale, @RequestParam(required = false) String evidenceKeys,
            RedirectAttributes ra) {
        try {
            adminReturns.addPrecedent(situation, judgedOpened, rationale, evidenceKeys, null,
                    actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.precedentSaved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/return-precedents";
    }

    // ---------- 内部 ----------

    /**
     * 左栏队列 + 五页签计数（Story 10.1 AC1 / AC4「页签计数与页内同源」）。
     *
     * <p>🔴 计数与队列<b>共用同一组筛选</b>：两处各算各的，就会出现「待质检 5」配一张空队列。
     */
    private void populateQueue(AdminReturnService.Tab tab, String type, String full, int page,
            Model model) {
        ReturnType parsedType = parseType(type);
        Boolean parsedFull = parseFull(full);
        var found = adminReturns.page(tab, parsedType, parsedFull, page, QUEUE_PAGE_SIZE);
        // 订单号与退货行随列表一起给出 —— AB-12A 要求列表就能看到「退哪几行、多少件」
        Map<String, String> orderTokens = new LinkedHashMap<>();
        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        for (ReturnRequest r : found.getContent()) {
            orders.findById(r.getShopOrderId())
                    .ifPresent(o -> orderTokens.put(r.getPublicToken(), o.getPublicToken()));
            lineCounts.put(r.getPublicToken(), requests.linesOf(r.getId()).size());
        }
        Map<String, Long> counts = adminReturns.counts(parsedType, parsedFull);
        model.addAttribute("queue", found);
        model.addAttribute("orderTokens", orderTokens);
        model.addAttribute("lineCounts", lineCounts);
        model.addAttribute("tab", tab.param());
        model.addAttribute("page", Math.max(page, 0));
        model.addAttribute("type", parsedType == null ? "" : parsedType.name());
        model.addAttribute("full", parsedFull == null ? "" : String.valueOf(parsedFull));
        model.addAttribute("types", ReturnType.values());
        model.addAttribute("counts", counts);
        model.addAttribute("stateTabs", java.util.Arrays.stream(AdminReturnService.Tab.values())
                .map(t -> new StateTab(
                        StateTab.href("/admin/shop/returns", "tab", t.param(),
                                "type", parsedType == null ? null : parsedType.name(),
                                "full", parsedFull == null ? null : String.valueOf(parsedFull)),
                        "admin.v130.shopReturns.tab." + t.param(),
                        "shop-return-tab-count-" + t.param(),
                        counts.getOrDefault(t.param(), 0L), t == tab))
                .toList());
    }

    /**
     * 处置<b>前</b>该单所在的页签 —— 「下一条」必须从这里取（AC2）。
     *
     * <p>🔴 <b>不按端点硬编码</b>：看着「批准 / 驳回只可能发生在待审核」很合理，但
     * {@code ReturnRequest.reject} 允许的来源状态是 {@code PENDING_REVIEW} <b>或 {@code REFUND_FAILED}</b>。
     * 硬编码成「待审核」的话，在「待退款」页签驳回一单，算出的 {@code data-next-id} 来自另一个队列，
     * JS 会把运营直接弹到那边去（oob 的删行与计数倒是对的，所以这种错很难看出来）。
     * 多一次按 token 的读，换掉一整类「端点与状态机各说各话」的错。
     *
     * <p>顺带：单据不存在时这里就 404，而不是等业务方法走到一半才抛。
     */
    private AdminReturnService.Tab tabOf(String token) {
        return AdminReturnService.Tab.of(adminReturns.require(token).getStatus());
    }

    /**
     * 处置成功 fragment（AC2）：{@code data-next-id}（同页签下一条）+ oob 行删除 + 五页签计数 + toast。
     *
     * <p>{@code from} 由 {@link #tabOf} 在处置<b>前</b>算出 —— 不从请求里读，免得前端传错就跳到别的队列去。
     */
    private String done(AdminReturnService.Tab from, String token, String type, String full,
            String message, Model model) {
        ReturnType parsedType = parseType(type);
        Boolean parsedFull = parseFull(full);
        model.addAttribute("removedToken", token);
        model.addAttribute("nextId", adminReturns.nextToken(from, parsedType, parsedFull, token));
        model.addAttribute("counts", adminReturns.counts(parsedType, parsedFull));
        model.addAttribute("tabs", java.util.Arrays.stream(AdminReturnService.Tab.values())
                .map(AdminReturnService.Tab::param).toList());
        model.addAttribute("message", message);
        return "admin/fragments/shop-return-done :: done";
    }

    /** 宽松解析：值不对（有人手改 URL）当作没筛，不为此让整页 500。 */
    private static ReturnType parseType(String raw) {
        if (raw != null && !raw.isBlank()) {
            for (ReturnType t : ReturnType.values()) {
                if (t.name().equalsIgnoreCase(raw.trim())) {
                    return t;
                }
            }
        }
        return null;
    }

    /** {@code null} = 不筛整单退；只认 {@code true} / {@code false} 两个字面量。 */
    private static Boolean parseFull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim();
        return "true".equalsIgnoreCase(v) ? Boolean.TRUE
                : "false".equalsIgnoreCase(v) ? Boolean.FALSE : null;
    }

    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.unauthorized("需要登录").code("admin.err.common.loginRequired");
        }
        return admin.getAdminAccountId();
    }

    /** 🔴 处置方式不可默认：S-10 要求「不留悬空」，猜一个等于替 CS 决定货去哪。 */
    private static RejectDisposal parseDisposal(String raw) {
        if (raw != null) {
            for (RejectDisposal d : RejectDisposal.values()) {
                if (d.name().equalsIgnoreCase(raw.trim())) {
                    return d;
                }
            }
        }
        throw AppException.validation("请选择商品处置方式（退回用户 / 报损）").code("admin.err.return.dispositionRequired");
    }
}
