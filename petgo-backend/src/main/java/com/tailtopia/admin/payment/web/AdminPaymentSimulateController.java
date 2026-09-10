package com.tailtopia.admin.payment.web;

import com.tailtopia.admin.audit.service.AdminAuditService;
import com.tailtopia.admin.payment.service.AdminPaymentQueryService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.pay.service.PaymentIntentService;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.shared.pay.GatewayStatus;
import com.tailtopia.shared.pay.PaymentCallback;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Map;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * B12 支付记录的**模拟回调**（V1.3.0 Story 8.5 · AC2，决策 D-41）。
 *
 * <p>🔴 {@link StagOnly} = {@code @Profile("stag")}：**生产不注册这个 Bean，路由根本不存在**
 * （不是 403 —— 一个「拿不到权限就打不开」的按钮仍然是生产上的一条可达写路径，
 * 而这三个端点能凭空把一笔支付置成已付款）。写操作清单里它们单列为 stag-only。
 *
 * <p>🔴 再加一道 {@code SUPER_ADMIN}：staging 上也有普通运营账号在用，
 * 而「把订单刷成已支付」不是日常运营动作。**不新设 permission_code** ——
 * 新码要进权限表就得加迁移，而一个只在 stag 存在的能力不该在生产的权限表里留痕。
 *
 * <p>走的是**真实回调收口** {@link PaymentIntentService#applyCallback}，不是直接改状态：
 * 幂等三闸、{@code gateway_ref} 回填、到账事件（充值入账 / 解锁发放）全都照常发生 ——
 * 模拟的意义正是把下游那一整条链真跑一遍。终态不可再模拟（收口自身会拦，
 * 抽屉里也不渲染按钮）。
 */
@Controller
@StagOnly
public class AdminPaymentSimulateController {

    /** 🔴 与 {@code drawer-payment.html} 里那句 {@code sec:authorize} 逐字一致。 */
    private static final String AUTH = "hasRole('SUPER_ADMIN')";

    private final PaymentIntentService intents;
    private final AdminPaymentQueryService query;
    private final AdminAuditService audit;
    private final Messages msg;

    public AdminPaymentSimulateController(PaymentIntentService intents,
            AdminPaymentQueryService query, AdminAuditService audit, Messages msg) {
        this.intents = intents;
        this.query = query;
        this.audit = audit;
        this.msg = msg;
    }

    @PostMapping("/admin/payments/{intentToken}/simulate-paid")
    @PreAuthorize(AUTH)
    public String simulatePaid(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String intentToken, Model model, HttpServletResponse response) {
        return simulate(admin, intentToken, GatewayStatus.PAID, model, response);
    }

    @PostMapping("/admin/payments/{intentToken}/simulate-failed")
    @PreAuthorize(AUTH)
    public String simulateFailed(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String intentToken, Model model, HttpServletResponse response) {
        return simulate(admin, intentToken, GatewayStatus.FAILED, model, response);
    }

    @PostMapping("/admin/payments/{intentToken}/simulate-expired")
    @PreAuthorize(AUTH)
    public String simulateExpired(@AuthenticationPrincipal AdminUserDetails admin,
            @PathVariable String intentToken, Model model, HttpServletResponse response) {
        return simulate(admin, intentToken, GatewayStatus.EXPIRED, model, response);
    }

    private String simulate(AdminUserDetails admin, String intentToken, GatewayStatus result,
            Model model, HttpServletResponse response) {
        // 先确认这条意图存在且非终态：收口对已终态是**静默返回**（幂等设计如此），
        // 直接放过去的话运营点完什么都没变，还以为是页面卡了。
        var before = query.detail(intentToken);
        if (before.terminal()) {
            throw AppException.validation("该支付已是终态，不能再模拟回调")
                    .code("admin.err.payments.alreadyTerminal");
        }
        // ⚠️ gatewayRef 带 `sim-` 前缀：事后在库里一眼能认出「这笔是测出来的」。
        //    rawMeta 只放这一个标记，不伪造网关字段 —— 伪造出来的字段会被当成真回调读。
        intents.applyCallback(new PaymentCallback(intentToken, "sim-" + intentToken, result,
                Map.of("simulated", true)));
        audit.record(admin.getAdminAccountId(), "PAYMENT_SIMULATE_CALLBACK", "payment_intent",
                intentToken, "result=" + result.name());

        model.addAttribute("active", "payments");
        model.addAttribute("p", query.detail(intentToken));
        model.addAttribute("toast", msg.get("admin.flash.payments.simulated"));
        // 列表整表重拉：状态列与摘要条（已支付笔数 / 现金收入）都会变，而这两个数
        // 依赖当前筛选条件 —— 由页面上的刷新槽带着筛选去拉，比在这里凭空重算靠谱。
        AdminFragmentResponses.trigger(response, AdminHxEvents.PAYMENT_LIST_REFRESH);
        return "admin/fragments/drawer-payment :: afterAction";
    }
}
