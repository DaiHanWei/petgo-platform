package com.tailtopia.admin.refund.web;

import com.tailtopia.admin.refund.dto.AdminRefundView;
import com.tailtopia.admin.refund.service.AdminRefundProofService;
import com.tailtopia.admin.refund.service.AdminRefundQueryService;
import com.tailtopia.admin.refund.service.AdminRefundQueryService.Stage;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.pay.refund.service.RefundService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台退款管理（Story 4.4 客服判定 + Story 4.6 主管审批/财务打款）。Thymeleaf admin slice，{@code /admin/refunds/**}，
 * redirect+flash，**不返 JSON**。三段职责分离门控（A-1）：客服 {@code refund.submit}（need 判定，4-4）/
 * 主管 {@code refund.approve}（审批通过/驳回，4-6）/财务 {@code refund.payout}（Iris 打款，4-6）；{@code SUPER_ADMIN} 隐式全权。
 * 各段后果编排（订单联动 + ledger + 通知 + disburse）在 {@link RefundService}。
 *
 * <p>V1.3.0 Story 2.8：重构为模板 A 三段流工作台——四页签（待客服判定 / 待主管审批 / 待财务打款 / 已完结·已驳回，先进先出）+
 * 右栏四区（金额卡 / 来源卡 / 三段流程条 / 当前段操作区，仅渲染权限匹配段）。五个写端点路径 / 权限零变更，方法内按 {@link HxRequest}
 * 分叉（D-36 例外：{@code reject} 加必填 {@code reason}，{@code payout} 加出款凭证 {@code proof}）；旧 {@code GET /admin/refunds/{refundToken}}
 * 整页退役（并入 {@code /{refundToken}/detail} fragment）；列表 {@code ?open=<token>} 自动开该条（A5「跳 A6」深链）。
 * <b>职责分离护栏原样在 {@link RefundService#guardDutySeparation}，界面只是镜子</b>。
 */
@Controller
public class AdminRefundController {

    private static final String SUBMIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.submit')";
    private static final String APPROVE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.approve')";
    private static final String PAYOUT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.payout')";
    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.view') "
            + "or hasAuthority('refund.submit') or hasAuthority('refund.approve') or hasAuthority('refund.payout')";

    private static final int PAGE_SIZE = 20;

    private final RefundService refundService;
    private final AdminRefundQueryService query;
    /** V1.3.0 Story 2.8（D-36）：出款凭证上传 / 现签展示。 */
    private final AdminRefundProofService proofs;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminRefundController(RefundService refundService, AdminRefundQueryService query,
            AdminRefundProofService proofs, Messages msg) {
        this.refundService = refundService;
        this.query = query;
        this.proofs = proofs;
        this.msg = msg;
    }

    // ===== 列表 / 详情（Story 4.6 → V1.3.0 三段流工作台）=====

    /** 工作台整页；{@code stage=submit|approve|payout|closed}（默认待客服判定）；{@code open=<token>} 页内深链自动开该条。 */
    @GetMapping("/admin/refunds")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) String open,
            HxRequest hx, Model model) {
        model.addAttribute("active", "refunds");
        Stage st = Stage.of(stage);
        if (open != null && !open.isBlank() && (stage == null || stage.isBlank())) {
            // 深链未指明页签：按该单当前阶段落页签，行才会在左栏出现
            try {
                st = Stage.of(query.find(open.trim()).stage());
            } catch (AppException ignore) {
                // 不存在的 token：留在默认页签，右栏由 JS 请求 detail 时得到 404 → 空态
            }
        }
        populateQueue(st, page, model);
        model.addAttribute("open", open == null || open.isBlank() ? null : open.trim());
        return hx.isHtmx() ? "admin/fragments/refund-queue :: rows" : "admin/refunds";
    }

    /** 左栏队列 fragment（切页签 / 滚动翻页）。 */
    @GetMapping("/admin/refunds/queue")
    @PreAuthorize(VIEW_AUTH)
    public String queueFragment(@RequestParam(value = "stage", required = false) String stage,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        populateQueue(Stage.of(stage), page, model);
        return page > 0 ? "admin/fragments/refund-queue :: rows" : "admin/fragments/refund-queue :: list";
    }

    /** 右栏四区 fragment（取代旧整页详情，已退役）。 */
    @GetMapping("/admin/refunds/{refundToken}/detail")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable String refundToken, Model model) {
        AdminRefundView refund = query.find(refundToken);
        model.addAttribute("refund", refund);
        model.addAttribute("proofUrl", proofs.viewUrl(refund.payoutProofKey()));
        return "admin/fragments/refund-panel :: detail";
    }

    // ===== 客服 need 判定（Story 4.4）=====

    @PostMapping("/admin/refunds/{refundToken}/approve")
    @PreAuthorize(SUBMIT_AUTH)
    public String approveNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, HxRequest hx, Model model, HttpServletResponse response,
            RedirectAttributes flash) {
        if (hx.isHtmx()) {
            refundService.approveNeed(refundToken, admin.getAdminAccountId());
            return done(Stage.SUBMIT, refundToken, msg.get("admin.flash.refund.needApproved"), model, response);
        }
        try {
            refundService.approveNeed(refundToken, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.refund.needApproved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/refunds";
    }

    /** 驳回退款需求。V1.3.0 D-36：正式加必填 {@code reason}（≤200 字，存退款单 reject_reason + 审计）。 */
    @PostMapping("/admin/refunds/{refundToken}/reject")
    @PreAuthorize(SUBMIT_AUTH)
    public String rejectNeed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken,
            @RequestParam(value = "reason", required = false) String reason,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            refundService.rejectNeed(refundToken, admin.getAdminAccountId(), requireText(reason,
                    "驳回退款需求必须填写原因", "admin.err.refund.reasonRequired"));
            return done(Stage.SUBMIT, refundToken, msg.get("admin.flash.refund.needRejected"), model, response);
        }
        try {
            refundService.rejectNeed(refundToken, admin.getAdminAccountId(), requireText(reason,
                    "驳回退款需求必须填写原因", "admin.err.refund.reasonRequired"));
            flash.addFlashAttribute("notice", msg.get("admin.flash.refund.needRejected"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/refunds";
    }

    // ===== 主管审批（Story 4.6）=====

    @PostMapping("/admin/refunds/{refundToken}/approval")
    @PreAuthorize(APPROVE_AUTH)
    public String approveRefund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, @RequestParam("note") String note,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            refundService.approveRefund(refundToken, admin.getAdminAccountId(), note);
            return done(Stage.APPROVE, refundToken, msg.get("admin.flash.refund.approved"), model, response);
        }
        try {
            refundService.approveRefund(refundToken, admin.getAdminAccountId(), note);
            flash.addFlashAttribute("notice", msg.get("admin.flash.refund.approved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/refunds";
    }

    @PostMapping("/admin/refunds/{refundToken}/approval-reject")
    @PreAuthorize(APPROVE_AUTH)
    public String rejectRefund(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken, @RequestParam("reason") String reason,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            refundService.rejectRefund(refundToken, admin.getAdminAccountId(), reason);
            return done(Stage.APPROVE, refundToken, msg.get("admin.flash.refund.rejected"), model, response);
        }
        try {
            refundService.rejectRefund(refundToken, admin.getAdminAccountId(), reason);
            flash.addFlashAttribute("notice", msg.get("admin.flash.refund.rejected"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/refunds";
    }

    // ===== 财务 Iris 打款（Story 4.6）=====

    /**
     * 确认 Iris 打款。V1.3.0 D-36：加出款凭证 {@code proof}（图片，选填→必填由前端 required；服务端只在有文件时上传，
     * objectKey 存退款单 payout_proof_key 并进审计 detail，不落 URL）。职责分离 / 金额校验在 {@link RefundService}。
     */
    @PostMapping("/admin/refunds/{refundToken}/payout")
    @PreAuthorize(PAYOUT_AUTH)
    public String payout(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String refundToken,
            @RequestParam(value = "proof", required = false) MultipartFile proof,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            refundService.payoutRefund(refundToken, admin.getAdminAccountId(), proofKeyOf(refundToken, proof));
            return done(Stage.PAYOUT, refundToken, msg.get("admin.flash.refund.paidOut"), model, response);
        }
        try {
            refundService.payoutRefund(refundToken, admin.getAdminAccountId(), proofKeyOf(refundToken, proof));
            flash.addFlashAttribute("notice", msg.get("admin.flash.refund.paidOut"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/refunds";
    }

    /**
     * 出款凭证上传（既有对象存储通道，私密桶），返回 objectKey；无文件 → null。
     * 上传前先廉价核对单据存在且处于「已审批待打款」（复审 #2）：把孤儿对象窗口缩到 disburse 失败 / 并发一种
     * （F21 不删对象；key 含 uuid，重试不撞）。职责分离 / 金额 / 渠道校验仍在 {@link RefundService}。
     */
    private String proofKeyOf(String refundToken, MultipartFile proof) {
        if (proof == null || proof.isEmpty()) {
            return null;
        }
        AdminRefundView r = query.find(refundToken);
        if (!"APPROVED".equals(r.approvalStatus())) {
            throw AppException.conflict("退款申请未经主管审批通过，不能打款").code("admin.err.refund.notApproved");
        }
        return proofs.upload(refundToken, proof);
    }

    /** 处置成功 fragment（AC6）：data-next-id（同段下一条）+ oob 行删除 + 四页签计数 + toast + HX-Trigger。 */
    private String done(Stage stage, String refundToken, String message, Model model, HttpServletResponse response) {
        model.addAttribute("removedToken", refundToken);
        model.addAttribute("nextId", query.nextToken(stage));
        model.addAttribute("counts", query.counts());
        model.addAttribute("message", message);
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/refund-done :: done";
    }

    private void populateQueue(Stage stage, int page, Model model) {
        model.addAttribute("stage", stage.param());
        model.addAttribute("page", Math.max(page, 0));
        model.addAttribute("queue", query.page(stage, PageRequest.of(Math.max(page, 0), PAGE_SIZE)));
        java.util.Map<String, Long> counts = query.counts();
        model.addAttribute("counts", counts);
        model.addAttribute("stateTabs", java.util.Arrays.stream(Stage.values()).map(st -> new StateTab(
                StateTab.href("/admin/refunds", "stage", st.param()), "admin.v130.refunds.stage." + st.param(),
                "refund-tab-count-" + st.param(), counts == null ? 0L : counts.getOrDefault(st.param(), 0L), st == stage)).toList());
    }

    private static String requireText(String raw, String message, String code) {
        if (raw == null || raw.isBlank()) {
            throw AppException.validation(message).code(code);
        }
        String r = raw.trim();
        return r.length() > 200 ? r.substring(0, 200) : r;
    }
}
