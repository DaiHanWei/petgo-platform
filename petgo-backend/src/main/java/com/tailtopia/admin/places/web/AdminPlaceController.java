package com.tailtopia.admin.places.web;

import com.tailtopia.admin.places.dto.PlaceFilter;
import com.tailtopia.admin.places.service.AdminPlaceQueryService;
import com.tailtopia.admin.shared.web.HxRequest;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * B6 场所管理（V1.3.0 Story 5.2，AB-17A ①；模板 B · UI 稿 2-20 / 2-22）。只读：列表 + 详情抽屉；处置端点在 5.3、新建在 5.4。
 * <ul>
 * <li>{@code GET /admin/places}：整页；{@code HX-Request}（筛选 / 翻页）只回 {@code fragments/places-list :: rows}（表格 + 分页，摘要条 oob）。</li>
 * <li>{@code GET /admin/places/{id}/drawer}：抽屉 fragment（五区）；{@code ?commentPage=} 评论翻页只回 {@code :: comments}。</li>
 * <li>权限 {@code place.manage}（查看即管理码，D-9 / D-17 不预授予预置角色）；不存在 / 已删 → 404 fragment（{@code AdminBusinessExceptionAdvice}）。</li>
 * </ul>
 */
@Controller
public class AdminPlaceController {

    public static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('place.manage')";
    static final String ROUTE = "/admin/places";

    private final AdminPlaceQueryService query;

    public AdminPlaceController(AdminPlaceQueryService query) {
        this.query = query;
    }

    @GetMapping(ROUTE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "page", required = false) Integer page,
            HxRequest hx, Model model) {
        PlaceFilter filter = PlaceFilter.of(q, type, status, city, page);
        populate(filter, model);
        if (hx.isHtmx()) {
            return "admin/fragments/places-list :: rows(true)"; // 摘要条随 oob 一并换
        }
        model.addAttribute("active", "places");
        return "admin/places";
    }

    /** 抽屉 fragment（AC5）；非 htmx 访问回整页并带 {@code ?open=} 深链（admin-drawer.js 自动开）。 */
    @GetMapping(ROUTE + "/{id:\\d+}/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String drawer(@PathVariable long id, @RequestParam(value = "commentPage", required = false) Integer commentPage,
            @RequestParam(value = "part", required = false) String part, HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        boolean commentsOnly = "comments".equals(part);
        model.addAttribute("d", query.drawer(id, commentPage == null ? 0 : commentPage, !commentsOnly));
        return commentsOnly ? "admin/fragments/drawer-places :: comments" : "admin/fragments/drawer-places :: drawer";
    }

    private void populate(PlaceFilter filter, Model model) {
        AdminPlaceQueryService.ListResult r = query.search(filter);
        model.addAttribute("filter", filter);
        model.addAttribute("rows", r.rows());
        model.addAttribute("total", r.total());
        model.addAttribute("hasNext", r.hasNext());
        model.addAttribute("summary", query.summary(filter));
        model.addAttribute("cities", query.cities());
        model.addAttribute("typeOptions", com.tailtopia.admin.places.domain.PlaceType.KNOWN);
    }
}
