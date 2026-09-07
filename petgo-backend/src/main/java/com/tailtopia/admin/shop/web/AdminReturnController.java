package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shop.service.AdminReturnService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shop.order.repository.ShopOrderLineRepository;
import com.tailtopia.shop.order.repository.ShopOrderRepository;
import com.tailtopia.shop.returns.domain.RejectDisposal;
import com.tailtopia.shop.returns.domain.ReturnRequest;
import com.tailtopia.shop.returns.domain.ReturnStatus;
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

    // ---------- 5.3 审核队列 ----------

    @GetMapping("/admin/shop/returns")
    @PreAuthorize(VIEW_AUTH)
    public String queue(@RequestParam(required = false) String status, Model model) {
        ReturnStatus filter = parseStatus(status);
        List<ReturnRequest> rows = adminReturns.queue(filter, PAGE_SIZE);
        // 订单号与退货行随列表一起给出 —— AB-12A 要求列表就能看到「退哪几行、多少件」
        Map<String, String> orderTokens = new LinkedHashMap<>();
        Map<String, Integer> lineCounts = new LinkedHashMap<>();
        for (ReturnRequest r : rows) {
            orders.findById(r.getShopOrderId())
                    .ifPresent(o -> orderTokens.put(r.getPublicToken(), o.getPublicToken()));
            lineCounts.put(r.getPublicToken(), requests.linesOf(r.getId()).size());
        }
        model.addAttribute("rows", rows);
        model.addAttribute("orderTokens", orderTokens);
        model.addAttribute("lineCounts", lineCounts);
        model.addAttribute("statuses", ReturnStatus.values());
        model.addAttribute("status", filter == null ? "" : filter.name());
        model.addAttribute("active", "shopReturns");
        return "admin/shop-returns";
    }

    @GetMapping("/admin/shop/returns/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String token, Model model) {
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
        model.addAttribute("active", "shopReturns");
        return "admin/shop-return-detail";
    }

    // ---------- 审核动作 ----------

    @PostMapping("/admin/shop/returns/{token}/approve")
    @PreAuthorize(APPROVE_AUTH)
    public String approve(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, RedirectAttributes ra) {
        try {
            adminReturns.approve(token, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.approved"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
    }

    @PostMapping("/admin/shop/returns/{token}/reject")
    @PreAuthorize(APPROVE_AUTH)
    public String reject(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String reason, RedirectAttributes ra) {
        try {
            adminReturns.reject(token, reason, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.rejected"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
    }

    // ---------- 5.4 寄回登记与质检 ----------

    @PostMapping("/admin/shop/returns/{token}/shipback")
    @PreAuthorize(APPROVE_AUTH)
    public String shipback(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String carrier,
            @RequestParam String trackingNo, @RequestParam(required = false) Long fee,
            RedirectAttributes ra) {
        try {
            adminReturns.registerShipback(token, carrier, trackingNo, fee, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.shipmentRegistered"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
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
            @RequestParam(required = false) String photoKeys, RedirectAttributes ra) {
        try {
            adminReturns.passInspection(token, note, photoKeys, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.inspectPassed"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
    }

    @PostMapping("/admin/shop/returns/{token}/inspect-fail")
    @PreAuthorize(APPROVE_AUTH)
    public String inspectFail(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, @RequestParam String note,
            @RequestParam(required = false) String photoKeys,
            @RequestParam String disposal,
            @RequestParam(required = false) String shipBackTrackingNo, RedirectAttributes ra) {
        try {
            adminReturns.failInspection(token, note, photoKeys, parseDisposal(disposal),
                    shipBackTrackingNo, actorOf(admin));
            ra.addFlashAttribute("notice", msg.get("admin.flash.return.inspectFailed"));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
    }

    // ---------- 5.5 退款执行（财务） ----------

    @PostMapping("/admin/shop/returns/{token}/refund")
    @PreAuthorize(PAYOUT_AUTH)
    public String refund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String token, RedirectAttributes ra) {
        try {
            var out = adminReturns.executeRefund(token, actorOf(admin));
            ra.addFlashAttribute("notice",
                    msg.get("admin.flash.return.refunded", out.coinRefunded(),
                            out.cashRefunded(), out.compensationPremium()));
        } catch (AppException e) {
            ra.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/shop/returns/" + token;
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

    private static long actorOf(AdminUserDetails admin) {
        if (admin == null) {
            throw AppException.unauthorized("需要登录").code("admin.err.common.loginRequired");
        }
        return admin.getAdminAccountId();
    }

    private static ReturnStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        for (ReturnStatus s : ReturnStatus.values()) {
            if (s.name().equalsIgnoreCase(raw.trim())) {
                return s;
            }
        }
        return null;
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
