package com.tailtopia.admin.consult.web;

import com.tailtopia.admin.consult.service.AdminConsultOrderExpService;
import com.tailtopia.admin.consult.service.AdminConsultOrderService;
import com.tailtopia.admin.shared.web.AdminConfirmModal;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.consult.domain.ConsultOrderVerifyStatus;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

/**
 * 后台兽医咨询订单管理（Story 9.3，AB-8B）。Thymeleaf admin slice，{@code /admin/consult-orders/**}。
 * 门控：查看 {@code order.view}、标记 {@code order.edit}、导出 {@code order.export}、
 * 扣回成长值 {@code order.reverse_exp}（SUPER_ADMIN 隐式全权）。<b>无退款入口</b>。
 */
@Controller
public class AdminConsultOrderController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.view')";
    private static final String EDIT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.edit')";
    private static final String EXPORT_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.export')";
    /** 🔴 超管不注入权限码，只写 hasAuthority 超管会 403。模板 sec 门控与此逐字一致。 */
    private static final String REVERSE_EXP_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('order.reverse_exp')";

    /** 列表筛选「待扣回成长值」的取值；其余一律按全部。 */
    static final String FILTER_EXP_REVERSE = "exp-reverse";

    private final AdminConsultOrderService service;
    private final AdminConsultOrderExpService expService;

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminConsultOrderController(AdminConsultOrderService service,
            AdminConsultOrderExpService expService, Messages msg) {
        this.service = service;
        this.expService = expService;
        this.msg = msg;
    }

    @GetMapping("/admin/consult-orders")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "open", required = false) String open,
            @RequestParam(value = "filter", required = false) String filter,
            HxRequest hx, Model model) {
        model.addAttribute("active", "consult-orders");
        // ⚠️ 取一次列表喂给摘要条与表格两处：各查各的话跨秒时两个数能对不上。
        boolean filtered = FILTER_EXP_REVERSE.equals(filter);
        var rows = filtered ? expService.pendingRows() : service.list();
        model.addAttribute("orders", rows);
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("filtered", filtered);
        model.addAttribute("open", open);
        return hx.isHtmx() ? "admin/fragments/consult-orders-list :: rows(oob=true)" : "admin/consult-orders";
    }

    /**
     * 订单抽屉（Story 8.4 · AC2）：成交快照 + 阶段时间线 + 待核查标记。
     *
     * <p>📌 <b>整页 {@code GET /admin/consult-orders/{orderToken}} 已删除</b>（AC5）：
     * 排查一单不该跳走再回来。不做旧地址跳转（D-23）。非 htmx 直达 → 回列表并自动开该抽屉。
     */
    @GetMapping("/admin/consult-orders/{orderToken}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable String orderToken, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/consult-orders?open=" + orderToken;
        }
        populateDrawer(orderToken, model);
        return "admin/fragments/drawer-consult-order :: drawer";
    }

    private void populateDrawer(String orderToken, Model model) {
        model.addAttribute("active", "consult-orders");
        model.addAttribute("order", service.detail(orderToken));
        model.addAttribute("exp", expService.state(orderToken));
    }

    /**
     * 抽屉内操作成功后换列表（{@code afterAction}）：筛选视图下这单可能已不在结果里，所以整表换，
     * 不能用 {@code orElseThrow} 找行 —— 扣回已提交后抛 500，用户重试只会得到「已扣回」。
     * 筛选取自 {@code HX-Current-URL}：抽屉 URL 和弹层表单都不带它。
     */
    private void populateListing(String orderToken, HxRequest hx, Model model) {
        boolean filtered = FILTER_EXP_REVERSE.equals(filterParam(hx.currentUrl()));
        // ⚠️ 行与摘要条取自**同一次**查询，不是分别查：分两次读的话，两次之间新落一单
        //    就会 oob 出一个「摘要写 11 单、表里数出来 10 行」的组合，而运营只会当成账错了。
        //    （顺带省掉一次全表扫描 —— 本页列表无分页，row() 单独查一次并不便宜。）
        var rows = filtered ? expService.pendingRows() : service.list();
        model.addAttribute("summary", service.summary(rows));
        model.addAttribute("filtered", filtered);
        if (filtered) {
            model.addAttribute("orders", rows);
        } else {
            model.addAttribute("row", rows.stream()
                    .filter(r -> r.orderToken().equals(orderToken)).findFirst().orElseGet(() -> service.row(orderToken)));
        }
    }

    /** 从 {@code HX-Current-URL} 取 {@code filter=}；没有或 URL 非法 → null（全部）。 */
    static String filterParam(String currentUrl) {
        if (currentUrl == null) {
            return null;
        }
        try {
            String q = java.net.URI.create(currentUrl.trim()).getRawQuery();
            if (q != null) {
                for (String kv : q.split("&")) {
                    if (kv.startsWith("filter=")) {
                        return java.net.URLDecoder.decode(kv.substring(7), java.nio.charset.StandardCharsets.UTF_8);
                    }
                }
            }
        } catch (IllegalArgumentException ignore) {
            // 非法 URL 当全部
        }
        return null;
    }

    /** 扣回弹层（服务端算额度，客户端不传金额）。不可扣回时 422 落抽屉错误槽。 */
    @PostMapping("/admin/consult-orders/{orderToken}/exp-reverse/preview")
    @PreAuthorize(REVERSE_EXP_AUTH)
    public String expReversePreview(@PathVariable String orderToken, Model model, HttpServletResponse response) {
        var s = expService.requireReversible(orderToken);
        model.addAttribute("cm", new AdminConfirmModal(msg.get("admin.v142.consultExp.confirm.title"), null, List.of(),
                msg.get("admin.v142.consultExp.confirm.note"), null, null, null,
                "/admin/consult-orders/" + orderToken + "/exp-reverse", Map.of(),
                msg.get("admin.v142.consultExp.confirm.btn"), true, false,
                List.of(new AdminConfirmModal.Fact(msg.get("admin.v142.ol.growth.accrued"), String.valueOf(s.accrued())),
                        new AdminConfirmModal.Fact(msg.get("admin.v142.consultExp.confirm.previously"),
                                String.valueOf(s.reversed())),
                        new AdminConfirmModal.Fact(msg.get("admin.v142.consultExp.confirm.now"),
                                String.valueOf(s.pending())))));
        response.setHeader(AdminFragmentResponses.HEADER_RETARGET, "#confirm-host");
        response.setHeader(AdminFragmentResponses.HEADER_RESWAP, "innerHTML");
        return "admin/fragments/confirm-modal :: modal";
    }

    /** 执行扣回。业务错误 422 由 advice 落弹层内 {@code #confirm-modal-err}，弹层不关。 */
    @PostMapping("/admin/consult-orders/{orderToken}/exp-reverse")
    @PreAuthorize(REVERSE_EXP_AUTH)
    public String expReverse(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable String orderToken,
            HxRequest hx, Model model, HttpServletResponse response) {
        int reversed = expService.reverse(orderToken, admin.getAdminAccountId());
        populateDrawer(orderToken, model);
        populateListing(orderToken, hx, model);
        model.addAttribute("toast", msg.get("admin.v142.consultExp.toast", reversed));
        response.setHeader("HX-Trigger-After-Swap", AdminHxEvents.CONFIRM_CLOSE);
        return "admin/fragments/drawer-consult-order :: afterAction";
    }

    @PostMapping("/admin/consult-orders/{orderToken}/verify")
    @PreAuthorize(EDIT_AUTH)
    public String verify(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String orderToken, @RequestParam(required = false) String status,
            @RequestParam(required = false) String note,
            HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // ⚠️ 非法的 status 在这条路上**不能吞成成功**：交给 advice 回 422 落进行内错误槽。
            //    （枚举值来自页面上的下拉，走到这里多半是有人手改了请求。）
            service.markVerify(orderToken, parseStatus(status), note, admin.getAdminAccountId());
            populateDrawer(orderToken, model);
            // 🔴 摘要条的「待核查数」会因为这次标记而变：只换那一行的话它会停在旧值。
            populateListing(orderToken, hx, model);
            model.addAttribute("toast", msg.get("admin.flash.consultOrder.verifyMarked"));
            return "admin/fragments/drawer-consult-order :: afterAction";
        }
        try {
            service.markVerify(orderToken, parseStatus(status), note, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.consultOrder.verifyMarked"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/consult-orders?open=" + orderToken;
    }

    /** 空 / 空白 = 清除标记（AC2 的三选之一）；其余非法值 → 422（不是静默当成清除）。 */
    private static ConsultOrderVerifyStatus parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        try {
            return ConsultOrderVerifyStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw AppException.validation("待核查标记取值不合法")
                    .code("admin.flash.consultOrder.badVerifyStatus");
        }
    }

    @GetMapping("/admin/consult-orders/export")
    @PreAuthorize(EXPORT_AUTH)
    @ResponseBody
    public ResponseEntity<String> export() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"consult-orders.csv\"")
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                // 🔴 BOM 不能省：表头随界面语言输出后首行是中文/印尼文，
                //    Excel 打开无 BOM 的 UTF-8 CSV 会按本地代码页解，整行乱码
                //    （与内容列表导出同一处理，AdminExportWriter 的约定就是「BOM 由调用方拼」）。
                .body('\uFEFF' + service.exportCsv());
    }
}
