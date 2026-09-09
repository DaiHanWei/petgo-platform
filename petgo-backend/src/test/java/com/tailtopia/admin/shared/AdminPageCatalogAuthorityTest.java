package com.tailtopia.admin.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * L0：待办中心五页的模板 {@code sec:authorize} 表达式与各 Controller 的 {@code *_AUTH} 常量<b>逐字一致</b>
 * （V1.3.0 Story 2.9 AC4）。模板里出现的每个表达式（含 {@code !(…)} 取反）必须等于某个后台 Controller 的门控常量——
 * 模板自己拼一串权限码就会与端点漂移（按钮显示了点下去 403，或按钮藏了端点其实开着）。
 */
class AdminPageCatalogAuthorityTest {

    /** 五页模板 → 允许引用的 Controller（门控常量来源）。 */
    private static final Map<String, List<Class<?>>> PAGES = new LinkedHashMap<>();

    static {
        PAGES.put("manual-review", List.of(com.tailtopia.admin.moderation.web.ManualReviewAdminController.class,
                com.tailtopia.admin.web.AdminWebController.class,
                com.tailtopia.namemoderation.web.NameModerationAdminController.class,
                com.tailtopia.admin.moderation.web.NameAvatarReviewAdminController.class,
                com.tailtopia.admin.moderation.web.AdminContentManageController.class));
        PAGES.put("tickets", List.of(com.tailtopia.admin.moderation.web.UnifiedTicketController.class,
                com.tailtopia.admin.throttle.web.AdminThrottleController.class,
                com.tailtopia.admin.moderation.web.AdminContentManageController.class));
        PAGES.put("anomalies", List.of(com.tailtopia.admin.anomaly.web.AdminAnomalyController.class,
                com.tailtopia.admin.anomaly.web.AdminConsultSessionController.class));
        PAGES.put("support-tickets", List.of(com.tailtopia.admin.support.web.AdminSupportTicketController.class));
        PAGES.put("refunds", List.of(com.tailtopia.admin.refund.web.AdminRefundController.class));
        // 暖贴跟进右栏「查看帖子 ↗」链到内容详情页，表达式须与 AdminContentManageController.DETAIL_AUTH 逐字一致
        PAGES.put("warm-replies", List.of(com.tailtopia.admin.warmreply.web.AdminWarmReplyController.class,
                com.tailtopia.admin.moderation.web.AdminContentManageController.class));
        // Story 3.5：看板付费卡门控（模板 sec:authorize 须与 AdminPaymentController.VIEW_AUTH / PAYMENT_CARD_AUTH 逐字一致）
        PAGES.put("dashboard", List.of(com.tailtopia.admin.dashboard.web.AdminDashboardController.class,
                com.tailtopia.admin.payment.web.AdminPaymentController.class));
    }

    private static final Map<String, List<String>> TEMPLATES = Map.of(
            "manual-review", List.of("manual-review.html", "fragments/review-queue.html", "fragments/review-detail.html", "fragments/review-done.html"),
            "tickets", List.of("tickets.html", "fragments/tickets-queue.html", "fragments/tickets-detail.html", "fragments/tickets-done.html"),
            "anomalies", List.of("anomalies.html", "fragments/anomaly-queue.html", "fragments/anomaly-panel.html", "fragments/anomaly-done.html"),
            "support-tickets", List.of("support-tickets.html", "fragments/support-queue.html", "fragments/support-panel.html", "fragments/support-done.html"),
            "refunds", List.of("refunds.html", "fragments/refund-queue.html", "fragments/refund-panel.html", "fragments/refund-done.html"),
            "warm-replies", List.of("warm-replies.html", "fragments/warm-reply-queue.html", "fragments/warm-reply-detail.html", "fragments/warm-reply-done.html"),
            "dashboard", List.of("dashboard.html", "fragments/dashboard-charts.html"));

    private static final Pattern SEC = Pattern.compile("sec:authorize=\"([^\"]+)\"");

    @Test
    void everyTemplateAuthorizeExpressionIsAControllerConstant() throws Exception {
        Path root = Path.of("src", "main", "resources", "templates", "admin");
        assertThat(root).isDirectory();
        for (var e : PAGES.entrySet()) {
            Set<String> allowed = new HashSet<>();
            for (Class<?> c : e.getValue()) {
                allowed.addAll(authConstants(c));
            }
            // 隐式全权 + 超管专属开关（齿轮）也是端点上的原文
            allowed.add("hasRole('SUPER_ADMIN')");
            for (String tpl : TEMPLATES.get(e.getKey())) {
                String html = Files.readString(root.resolve(tpl), StandardCharsets.UTF_8);
                Matcher m = SEC.matcher(html);
                while (m.find()) {
                    String expr = m.group(1).trim();
                    if (expr.startsWith("!(") && expr.endsWith(")")) {
                        expr = expr.substring(2, expr.length() - 1);
                    }
                    assertThat(allowed).as("%s 的 %s 里 sec:authorize 表达式不是任何 Controller 的门控常量：%s",
                            e.getKey(), tpl, expr).contains(expr);
                }
            }
        }
    }

    /** 反射读 Controller 里所有 {@code static final String} 且名字以 AUTH / VIEW / MANAGE 结尾的常量原文。 */
    private static Set<String> authConstants(Class<?> c) throws IllegalAccessException {
        Set<String> out = new HashSet<>();
        for (Field f : c.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isFinal(f.getModifiers()) && f.getType() == String.class
                    && (f.getName().endsWith("_AUTH") || f.getName().equals("VIEW") || f.getName().equals("MANAGE")
                    || f.getName().endsWith("AUTH"))) {
                f.setAccessible(true);
                out.add(((String) f.get(null)).trim());
            }
        }
        return out;
    }

    /** 五页都在目录里且归「待办中心」组（角标 / 空态去向按此组遍历）。 */
    @Test
    void fiveInboxPagesAreCatalogued() {
        // 反向：目录里「待办中心」组的每一页都必须登记到本测试（4.4 暖贴跟进进组时会在这里红，防漏检）
        Set<String> inbox = AdminPageCatalog.PAGES.stream().filter(p -> AdminPageCatalog.G_INBOX.equals(p.group()))
                .map(AdminPageCatalog.Page::key).collect(java.util.stream.Collectors.toSet());
        // 目录里已预登记、页面尚未落地的项；落地时从这里移到 PAGES / TEMPLATES（4.4 暖贴跟进已落地）
        Set<String> notBuiltYet = Set.of();
        inbox.removeAll(notBuiltYet);
        // 非待办中心的页面（看板，Story 3.5）也可登记进来复用逐字守卫，故用 containsAll 而非 exactly
        assertThat(PAGES.keySet()).as("待办中心组页面须全部登记到 PAGES / TEMPLATES").containsAll(inbox);
        assertThat(TEMPLATES.keySet()).containsAll(inbox);
        assertThat(TEMPLATES.keySet()).containsExactlyInAnyOrderElementsOf(PAGES.keySet());
        for (String key : PAGES.keySet()) {
            var page = AdminPageCatalog.PAGES.stream().filter(p -> p.key().equals(key)).findFirst();
            assertThat(page).as(key).isPresent();
            // 待办中心页归 G_INBOX；看板（Story 3.5，复用逐字守卫）归概览组
            assertThat(page.get().group()).as(key).isEqualTo(inbox.contains(key) ? AdminPageCatalog.G_INBOX : AdminPageCatalog.G_OVERVIEW);
        }
    }
}
