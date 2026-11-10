package com.tailtopia.admin.shop.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.refund.service.AdminRefundProofService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminConfirmModals;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.shop.service.AdminShopRefundPayoutService;
import com.tailtopia.admin.shop.service.AdminShopRefundPayoutService.Tab;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shop.order.refund.PayoutFailReason;
import com.tailtopia.shop.order.refund.ShopOrderRefund;
import com.tailtopia.shop.order.refund.ShopOrderRefundRepository;
import com.tailtopia.shop.order.refund.ShopOrderRefundService;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;

/**
 * 「退款打款」（V1.4.2 shop-admin Story 5.2 · AB-28 · AD-10 · E1 ~ E5）：查看 {@code refund.view} 或 {@code refund.payout}，
 * 操作只限 {@code refund.payout}（O-8）。🛡 入口门与 {@code AdminPageCatalog} 该页 navCodes 逐字同集。
 *
 * <p>🔴 职责分离（O-12）：操作人 = 退款单发起人时抽屉置灰（E5），服务端 403（超管不豁免），违规审计独立提交。
 * 🔒 列表只给账号末 4 位与户名；完整账号只在抽屉给 {@code refund.payout}；转账截图私有存储 + 签名 URL，查看限 {@code refund.payout}。
 * 本类不记任何账号 / 户名日志。
 */
@Controller
public class AdminShopRefundPayoutController {

    static final String VIEW_AUTH =
            "hasRole('SUPER_ADMIN') or hasAuthority('refund.view') or hasAuthority('refund.payout')";
    static final String PAYOUT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('refund.payout')";

    private final AdminShopRefundPayoutService payouts;
    private final ShopOrderRefundService refundService;
    private final ShopOrderRefundRepository refunds;
    private final AdminRefundProofService proofs;
    private final Messages msg;

    public AdminShopRefundPayoutController(AdminShopRefundPayoutService payouts, ShopOrderRefundService refundService,
            ShopOrderRefundRepository refunds, AdminRefundProofService proofs, Messages msg) {
        this.payouts = payouts;
        this.refundService = refundService;
        this.refunds = refunds;
        this.proofs = proofs;
        this.msg = msg;
    }

    @GetMapping("/admin/shop/refund-payouts")
    @PreAuthorize(VIEW_AUTH)
    public String page(@RequestParam(required = false) String tab, @RequestParam(defaultValue = "0") int page,
            HxRequest hx, Model model) {
        Tab t = Tab.parse(tab);
        model.addAttribute("active", "shopRefundPayouts");
        model.addAttribute("tab", t.name());
        model.addAttribute("tabs", Tab.values());
        model.addAttribute("counts", payouts.counts());
        model.addAttribute("summary", payouts.summary());
        model.addAttribute("rows", payouts.list(t, page));
        model.addAttribute("page", Math.max(0, page));
        model.addAttribute("oob", hx.isHtmx());   // htmx 刷新列表时摘要条以 oob 跟上；整页渲染不重复输出
        return hx.isHtmx() ? "admin/shop-refund-payouts :: listBody" : "admin/shop-refund-payouts";
    }

    @GetMapping("/admin/shop/refund-payouts/{token}")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token, Model model) {
        populateDrawer(admin, token, model);
        return "admin/fragments/drawer-shop-refund-payout :: panel";
    }

    /** E3：上传转账截图（私有桶，只收图片 ≤10MB），回填 key 到表单隐藏域。 */
    @PostMapping("/admin/shop/refund-payouts/{token}/proof")
    @PreAuthorize(PAYOUT_AUTH)
    public String uploadProof(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam("file") MultipartFile file, Model model) {
        ShopOrderRefund r = require(token);
        requireNotInitiator(r, admin);
        if (r.getStatus() != com.tailtopia.shop.order.refund.ShopRefundStatus.PAYOUT_PENDING) {
            throw AppException.conflict("该退款单不在待打款状态").code("admin.err.orderRefund.notPayoutPending", r.getStatus());
        }
        String key = proofs.uploadShopRefundScreenshot(r.getPublicToken(), file);
        model.addAttribute("proofKey", key);
        model.addAttribute("refundToken", r.getPublicToken());
        return "admin/fragments/drawer-shop-refund-payout :: proofSlot";
    }

    /** 查看转账截图：短时效签名 URL（限 refund.payout，不落日志）。 */
    @GetMapping("/admin/shop/refund-payouts/{token}/proof")
    @PreAuthorize(PAYOUT_AUTH)
    public String viewProof(@PathVariable String token, @RequestParam(required = false) String key) {
        ShopOrderRefund r = require(token);
        String k = key != null && AdminRefundProofService.belongsToShopRefund(key, r.getPublicToken()) ? key
                : r.getPayoutProofKey();
        String url = proofs.viewUrl(k);
        if (url == null) {
            throw AppException.notFound("没有转账截图").code("admin.err.v142.payout.proofMissing");
        }
        return "redirect:" + url;
    }

    // ---------- E3 标记已打款 ----------

    @PostMapping("/admin/shop/refund-payouts/{token}/paid/confirm")
    @PreAuthorize(PAYOUT_AUTH)
    public String confirmPaid(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String txnNo, @RequestParam(required = false) String proofKey, Model model,
            HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        requireNotInitiator(r, admin);
        String t = txnNo == null ? "" : txnNo.trim();
        if (t.isEmpty()) {
            throw AppException.validation("请填写转账流水号").code("admin.err.v142.payout.txnRequired");
        }
        String key = cleanProofKey(proofKey, r);
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("txnNo", t);
        hidden.put("proofKey", key == null ? "" : key);
        List<AdminConfirmModal.Fact> facts = List.of(
                new AdminConfirmModal.Fact(msg.get("admin.v142.payout.confirm.payee"), nz(r.getPayoutAccountHolder())),
                new AdminConfirmModal.Fact(msg.get("admin.v142.payout.confirm.account"),
                        (r.getPayoutChannel() == null ? "" : r.getPayoutChannel().name() + " · ") + nz(r.getPayoutAccount())),
                new AdminConfirmModal.Fact(msg.get("admin.v142.payout.txnNo"), t));
        String warn = refundService.payoutTxnNoUsedElsewhere(r.getPublicToken(), t)
                ? msg.get("admin.v142.payout.txnDuplicate") : null;
        var cm = new AdminConfirmModal(msg.get("admin.v142.payout.confirm.paidTitle"), warn, List.of(),
                msg.get("admin.v142.payout.confirm.paidBody"), null, null, null,
                "/admin/shop/refund-payouts/" + r.getPublicToken() + "/paid", hidden,
                msg.get("admin.v142.payout.btn.markPaid"), false, warn != null, facts)
                .withAmounts(List.of(AdminConfirmModal.AmountLine.total(msg.get("admin.v142.payout.confirm.amount"),
                        AdminShopOrderExceptionController.rp(payoutNet(r)))))
                .withSuccessTarget("#payout-drawer-body", "innerHTML");
        return AdminConfirmModals.render(model, response, cm);
    }

    @PostMapping("/admin/shop/refund-payouts/{token}/paid")
    @PreAuthorize(PAYOUT_AUTH)
    public String markPaid(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String txnNo, @RequestParam(required = false) String proofKey, Model model,
            HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        refundService.markPaidOut(r.getPublicToken(), txnNo, cleanProofKey(proofKey, r), actorOf(admin));
        return afterWrite(admin, token, "admin.v142.payout.flash.paid", model, response);
    }

    // ---------- E4 打款失败 ----------

    @PostMapping("/admin/shop/refund-payouts/{token}/failed/confirm")
    @PreAuthorize(PAYOUT_AUTH)
    public String confirmFailed(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String note, Model model,
            HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        requireNotInitiator(r, admin);
        PayoutFailReason fr = PayoutFailReason.parse(reason).orElseThrow(() -> AppException.validation("请选择打款失败原因")
                .code("admin.err.v142.payout.failReasonRequired"));
        Map<String, String> hidden = new LinkedHashMap<>();
        hidden.put("reason", fr.name());
        hidden.put("note", note == null ? "" : note);
        var cm = AdminConfirmModal.simple(msg.get("admin.v142.payout.confirm.failedTitle",
                        msg.get("admin.v142.payout.failReason." + fr.name())), msg.get("admin.v142.payout.confirm.failedBody"),
                "/admin/shop/refund-payouts/" + r.getPublicToken() + "/failed", hidden,
                msg.get("admin.v142.payout.btn.markFailed"), true)
                .withSuccessTarget("#payout-drawer-body", "innerHTML");
        return AdminConfirmModals.render(model, response, cm);
    }

    @PostMapping("/admin/shop/refund-payouts/{token}/failed")
    @PreAuthorize(PAYOUT_AUTH)
    public String markFailed(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String reason, @RequestParam(required = false) String note, Model model,
            HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        refundService.markPayoutFailed(r.getPublicToken(), PayoutFailReason.parse(reason).orElse(null), note,
                actorOf(admin));
        return afterWrite(admin, token, "admin.v142.payout.flash.failed", model, response);
    }

    // ---------- 注销用户线下结案（O-17，无稿） ----------

    @PostMapping("/admin/shop/refund-payouts/{token}/offline/confirm")
    @PreAuthorize(PAYOUT_AUTH)
    public String confirmOffline(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String note, Model model, HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        requireNotInitiator(r, admin);
        if (note == null || note.isBlank()) {
            throw AppException.validation("请填写线下处理说明").code("admin.err.v142.payout.offlineNoteRequired");
        }
        var cm = AdminConfirmModal.simple(msg.get("admin.v142.payout.confirm.offlineTitle"),
                        msg.get("admin.v142.payout.confirm.offlineBody"),
                        "/admin/shop/refund-payouts/" + r.getPublicToken() + "/offline", Map.of("note", note),
                        msg.get("admin.v142.payout.btn.closeOffline"), false)
                .withAmounts(List.of(AdminConfirmModal.AmountLine.total(msg.get("admin.v142.payout.col.amount"),
                        AdminShopOrderExceptionController.rp(r.getCashRefund()))))
                .withSuccessTarget("#payout-drawer-body", "innerHTML");
        return AdminConfirmModals.render(model, response, cm);
    }

    @PostMapping("/admin/shop/refund-payouts/{token}/offline")
    @PreAuthorize(PAYOUT_AUTH)
    public String closeOffline(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String token,
            @RequestParam(required = false) String note, Model model, HttpServletResponse response) {
        ShopOrderRefund r = require(token);
        refundService.closeOffline(r.getPublicToken(), note, actorOf(admin));
        return afterWrite(admin, token, "admin.v142.payout.flash.offline", model, response);
    }

    // ---------- 内部 ----------

    /** 写成功：重渲染抽屉 + toast + 列表刷新事件。 */
    private String afterWrite(AdminUserDetails admin, String token, String toastKey, Model model,
            HttpServletResponse response) {
        populateDrawer(admin, token, model);
        model.addAttribute("toast", msg.get(toastKey));
        response.setHeader("HX-Trigger", AdminHxEvents.PAYOUT_LIST_REFRESH);
        return "admin/fragments/drawer-shop-refund-payout :: saved";
    }

    private void populateDrawer(AdminUserDetails admin, String token, Model model) {
        ShopOrderRefund r = require(token);
        boolean canPayout = has(admin, AdminPermissions.REFUND_PAYOUT);
        model.addAttribute("refund", r);
        model.addAttribute("info", payouts.orderInfo(r.getOrderId()));
        model.addAttribute("initiatorName", payouts.adminName(r.getCreatedByAdminId()));
        model.addAttribute("canPayout", canPayout);
        model.addAttribute("isInitiator", ShopOrderRefundService.isInitiator(r, actorOf(admin)));
        // 🔒 完整账号只给 refund.payout；其余只给末 4 位
        model.addAttribute("accountShown", canPayout ? r.getPayoutAccount()
                : (r.payoutAccountLast4() == null ? null : "••••" + r.payoutAccountLast4()));
        model.addAttribute("failReasons", PayoutFailReason.values());
    }

    /** 实到金额：只经 {@code ShopRefundCalculator.payoutNet}（现金段 − 渠道费）。 */
    private static long payoutNet(ShopOrderRefund r) {
        return r.getPayoutChannel() == null ? r.getCashRefund()
                : com.tailtopia.shop.order.pricing.ShopRefundCalculator.payoutNet(r.getCashRefund(), r.getPayoutChannel());
    }

    private void requireNotInitiator(ShopOrderRefund r, AdminUserDetails admin) {
        if (ShopOrderRefundService.isInitiator(r, actorOf(admin))) {
            // 预检只拒绝、不写违规审计；真正的提交端点由服务层拦截并独立留痕
            throw AppException.forbidden("退款职责分离：发起人不能标记打款结果").code("admin.err.v142.payout.dutySeparation");
        }
    }

    private static String cleanProofKey(String key, ShopOrderRefund r) {
        return key != null && AdminRefundProofService.belongsToShopRefund(key.trim(), r.getPublicToken()) ? key.trim()
                : null;
    }

    private ShopOrderRefund require(String token) {
        return refunds.findByPublicToken(token)
                .orElseThrow(() -> AppException.notFound("退款单不存在").code("admin.err.orderRefund.notFound"));
    }

    private static String nz(String v) {
        return v == null ? "—" : v;
    }

    private static Long actorOf(AdminUserDetails admin) {
        return admin == null ? null : admin.getAdminAccountId();
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
