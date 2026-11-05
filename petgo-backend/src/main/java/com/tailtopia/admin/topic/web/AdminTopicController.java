package com.tailtopia.admin.topic.web;

import com.tailtopia.admin.account.domain.AdminPermissions;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.AdminHxEvents;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.admin.topic.service.AdminTopicService;
import com.tailtopia.admin.topic.service.AdminTopicService.CreateResult;
import com.tailtopia.admin.topic.service.TopicFilter;
import com.tailtopia.admin.topic.service.TopicView;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.topic.domain.TopicNameRules;
import jakarta.servlet.http.HttpServletResponse;
import java.util.function.Supplier;
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
 * 话题管理（V1.4.2 social-topic · Story 6.1 · AB-10B，UI 稿 F1–F9）。模板 B（列表 + 480px 抽屉）。
 *
 * <h2>🛡 双权限码（D-18，均不预授）</h2>
 * 查看 {@code content.topic_view} / 编辑 {@code content.topic_manage}。侧栏可见性由
 * {@code AdminPageCatalog} 的 {@code navCodes}（= {@link #VIEW} 里的码）驱动；页面内写按钮的
 * {@code sec:authorize} 必须与 {@link #MANAGE} <b>逐字一致</b>。
 *
 * <h2>htmx / 整页双分支</h2>
 * htmx 分支不 try/catch：{@link AppException} 冒给 {@code AdminBusinessExceptionAdvice} 回行内 422；
 * 整页分支 PRG + flash（与内容标签页同一写法）。
 *
 * <p>后台 URL 用数字 topicId（后台不对外）；App 对外接口仍只用标准名（D-13），本类不新增任何对外接口。
 */
@Controller
public class AdminTopicController {

    static final String VIEW = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.TOPIC_VIEW + "')";
    static final String MANAGE = "hasRole('SUPER_ADMIN') or hasAuthority('" + AdminPermissions.TOPIC_MANAGE + "')";

    private static final String LIST = "redirect:/admin/topics";

    private final AdminTopicService service;
    private final Messages msg;

    public AdminTopicController(AdminTopicService service, Messages msg) {
        this.service = service;
        this.msg = msg;
    }

    // ================================================================ 列表（F1 / F8 / F9）

    @GetMapping("/admin/topics")
    @PreAuthorize(VIEW)
    public String list(@RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "page", required = false, defaultValue = "0") int page,
            @RequestParam(value = "open", required = false) Long open,
            @RequestParam(value = "focus", required = false) Long focus,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        TopicFilter filter = TopicFilter.of(type, status, q);
        int p = focus != null ? service.pageOf(focus, filter) : Math.max(page, 0);
        var found = service.page(filter, p);
        model.addAttribute("active", "topics");
        model.addAttribute("topics", found.rows());
        model.addAttribute("page", found.page());
        model.addAttribute("hasNext", found.hasNext());
        model.addAttribute("summary", service.summary());
        model.addAttribute("type", filter.typeParam());
        model.addAttribute("status", filter.statusParam());
        model.addAttribute("q", q == null ? "" : q.strip());
        model.addAttribute("filtered", filter.type() != null || filter.status() != null || filter.q() != null);
        model.addAttribute("focus", focus);
        model.addAttribute("open", open);
        model.addAttribute("openCreate", create != null);
        return hx.isHtmx() ? "admin/fragments/topics-list :: rows(oob=true)" : "admin/topics";
    }

    // ================================================================ 抽屉（F2–F7）

    @GetMapping("/admin/topics/new/drawer")
    @PreAuthorize(MANAGE)
    public String newDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/topics?create=1";
        }
        model.addAttribute("active", "topics");
        return "admin/fragments/drawer-topic :: createForm";
    }

    /** 输入即查（F3 黄条 / 行内错误）。空输入回空片段。 */
    @GetMapping("/admin/topics/name-check")
    @PreAuthorize(MANAGE)
    public String nameCheck(@RequestParam(value = "name", required = false) String name, Model model) {
        model.addAttribute("check", service.checkName(name));
        // 黄条上记下「查的是哪个名字」：前端只在提交的名字与它同一标准名时才带「已确认升级」。
        model.addAttribute("checkedName", name == null ? "" : name.strip());
        return "admin/fragments/drawer-topic :: nameCheck";
    }

    @GetMapping("/admin/topics/{id}/drawer")
    @PreAuthorize(VIEW)
    public String drawer(@PathVariable long id, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/topics?open=" + id;
        }
        populateDrawer(id, model);
        return "admin/fragments/drawer-topic :: drawer";
    }

    private void populateDrawer(long id, Model model) {
        TopicView t = service.get(id);
        model.addAttribute("active", "topics");
        model.addAttribute("t", t);
        model.addAttribute("livePosts", service.livePostCount(id));
    }

    /** 抽屉内处置成功：抽屉重渲染 + toast + 列表按当前筛选 / 当前页重拉。 */
    private String afterAction(long id, String toast, Model model, HttpServletResponse response) {
        populateDrawer(id, model);
        model.addAttribute("toast", toast);
        AdminFragmentResponses.trigger(response, AdminHxEvents.TOPIC_LIST_REFRESH);
        return "admin/fragments/drawer-topic :: afterAction";
    }

    // ================================================================ 新建 / 升级（F2 / F3）

    /**
     * 新建官方话题。{@code upgradeConfirmed} = 运营在原生 confirm 里确认升级的那个名字（前端在黄条出现时写入）；
     * 只有它与本次提交的名字是同一标准名才算确认 —— 防止「黄条是 A、提交的是 B」时把 B 静默升级。
     */
    @PostMapping("/admin/topics")
    @PreAuthorize(MANAGE)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "name", required = false) String name,
            @RequestParam(value = "descId", required = false) String descId,
            @RequestParam(value = "descEn", required = false) String descEn,
            @RequestParam(value = "upgradeConfirmed", required = false) String upgradeConfirmed,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        boolean confirmed = sameName(upgradeConfirmed, name);
        if (hx.isHtmx()) {
            CreateResult r = service.create(admin.getAdminAccountId(), name, descId, descEn, confirmed);
            AdminFragmentResponses.trigger(response, AdminHxEvents.DRAWER_CLOSE);
            AdminFragmentResponses.triggerWithPayload(response, AdminHxEvents.TOPIC_LIST_REFRESH,
                    "{\"focus\":" + r.id() + "}");
            model.addAttribute("toast", createdToast(r));
            return "admin/fragments/drawer-topic :: toastOnly";
        }
        try {
            CreateResult r = service.create(admin.getAdminAccountId(), name, descId, descEn, confirmed);
            flash.addFlashAttribute("notice", createdToast(r));
            return "redirect:/admin/topics?focus=" + r.id();
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
            return LIST;
        }
    }

    private String createdToast(CreateResult r) {
        return r.upgraded()
                ? msg.get("admin.flash.topics.upgraded", r.nameDisplay(), r.postCount())
                : msg.get("admin.flash.topics.created", r.nameDisplay());
    }

    static boolean sameName(String confirmedName, String name) {
        if (confirmedName == null || confirmedName.isBlank() || name == null || name.isBlank()) {
            return false;
        }
        return TopicNameRules.normalize(confirmedName.strip()).equals(TopicNameRules.normalize(name.strip()));
    }

    // ================================================================ 编辑 / 状态（F4–F7）

    @PostMapping("/admin/topics/{id}/edit")
    @PreAuthorize(MANAGE)
    public String edit(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "displayName", required = false) String displayName,
            @RequestParam(value = "descId", required = false) String descId,
            @RequestParam(value = "descEn", required = false) String descEn,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return drawerAction(hx, id, model, response, flash, () -> {
            service.update(admin.getAdminAccountId(), id, displayName, descId, descEn);
            return msg.get("admin.flash.topics.updated");
        });
    }

    @PostMapping("/admin/topics/{id}/hide")
    @PreAuthorize(MANAGE)
    public String hide(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return drawerAction(hx, id, model, response, flash, () -> {
            boolean wasFeatured = service.hide(admin.getAdminAccountId(), id);
            return msg.get("admin.flash.topics.hidden")
                    + (wasFeatured ? msg.get("admin.flash.topics.hiddenUnfeaturedSuffix") : "");
        });
    }

    @PostMapping("/admin/topics/{id}/disable")
    @PreAuthorize(MANAGE)
    public String disable(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return drawerAction(hx, id, model, response, flash, () -> {
            service.disable(admin.getAdminAccountId(), id);
            return msg.get("admin.flash.topics.disabled");
        });
    }

    @PostMapping("/admin/topics/{id}/restore")
    @PreAuthorize(MANAGE)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return drawerAction(hx, id, model, response, flash, () -> {
            service.restore(admin.getAdminAccountId(), id);
            return msg.get("admin.flash.topics.restored");
        });
    }

    private String drawerAction(HxRequest hx, long id, Model model, HttpServletResponse response,
            RedirectAttributes flash, Supplier<String> action) {
        if (hx.isHtmx()) {
            String toast = action.get();
            return afterAction(id, toast, model, response);
        }
        try {
            flash.addFlashAttribute("notice", action.get());
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return LIST;
    }

    // ================================================================ 首页话题行（F1 / F8）

    /** 行内开关。成功：toast + 列表重拉（其它行的序号可能前移）；422 → {@code #admin-inline-error}。 */
    @PostMapping("/admin/topics/{id}/featured")
    @PreAuthorize(MANAGE)
    public String featured(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "on", defaultValue = "true") boolean on,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return rowAction(hx, model, response, flash, () -> {
            if (on) {
                int pos = service.feature(admin.getAdminAccountId(), id);
                return msg.get("admin.flash.topics.featured", pos);
            }
            service.unfeature(admin.getAdminAccountId(), id);
            return msg.get("admin.flash.topics.unfeatured");
        });
    }

    /** 行内序号框：与原占该位的话题互换。 */
    @PostMapping("/admin/topics/{id}/featured-order")
    @PreAuthorize(MANAGE)
    public String featuredOrder(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "order", required = false) Integer order,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        return rowAction(hx, model, response, flash, () -> {
            service.reorder(admin.getAdminAccountId(), id, order == null ? 0 : order);
            return msg.get("admin.flash.topics.featureReordered");
        });
    }

    private String rowAction(HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash,
            Supplier<String> action) {
        if (hx.isHtmx()) {
            model.addAttribute("toast", action.get());
            AdminFragmentResponses.trigger(response, AdminHxEvents.TOPIC_LIST_REFRESH);
            return "admin/fragments/drawer-topic :: toastOnly";
        }
        try {
            flash.addFlashAttribute("notice", action.get());
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return LIST;
    }
}
