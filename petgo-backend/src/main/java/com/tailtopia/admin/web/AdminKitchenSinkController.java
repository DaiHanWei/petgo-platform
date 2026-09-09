package com.tailtopia.admin.web;

import com.tailtopia.admin.shared.StagOnly;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * kitchen-sink 演示页（V1.3.0 Story 2.3b AC7）：五套模板壳各一个假数据实例 + 抽屉 + 工作台自动下一条，
 * 供 L2 验收与后续页面对照。<b>仅 stag profile 注册</b>（{@link StagOnly}），生产无此路由；不进导航 / 目录 / 写操作清单。
 */
@Controller
@StagOnly
public class AdminKitchenSinkController {

    /** 假队列（内存常量，无任何持久化）。 */
    public record Item(long id, String title, String meta, String dot) {
    }

    static final List<Item> ITEMS = List.of(
            new Item(1, "帖子举报 · 疑似广告", "user_88 · 3 人举报 · 2h 前", "todo"),
            new Item(2, "头像审核 · 待人工", "user_91 · 机审 0.62 · 40m 前", "wait"),
            new Item(3, "评论举报 · 辱骂", "user_12 · 7 人举报 · 5m 前", "hot"),
            new Item(4, "帖子举报 · 已处理", "user_07 · 下架 · 昨天", "done"));

    @GetMapping("/admin/_kitchen-sink")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String page(Model model) {
        model.addAttribute("active", "_kitchen-sink");
        model.addAttribute("items", ITEMS);
        model.addAttribute("res", "ks");
        return "admin/_kitchen-sink";
    }

    /** 工作台右栏详情 / 模板 B 抽屉体共用的 fragment。 */
    @GetMapping("/admin/_kitchen-sink/{id}/drawer")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String drawer(@PathVariable long id, Model model) {
        model.addAttribute("item", find(id));
        return "admin/_kitchen-sink :: detail";
    }

    /** 处置演示：返回带 data-next-id 的 fragment（自动下一条）+ HX-Trigger 刷角标。 */
    @PostMapping("/admin/_kitchen-sink/{id}/done")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public String done(@PathVariable long id, HxRequest hx, Model model, HttpServletResponse response) {
        Item current = find(id);
        Item next = ITEMS.stream().filter(i -> i.id() > id && !"done".equals(i.dot())).findFirst().orElse(null);
        model.addAttribute("item", current);
        model.addAttribute("nextId", next == null ? "" : String.valueOf(next.id()));
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return hx.isHtmx() ? "admin/_kitchen-sink :: done" : "redirect:/admin/_kitchen-sink";
    }

    private static Item find(long id) {
        return ITEMS.stream().filter(i -> i.id() == id).findFirst().orElse(ITEMS.get(0));
    }
}
