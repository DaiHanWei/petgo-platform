package com.tailtopia.admin.places.web;

import com.tailtopia.admin.moderation.dto.ReviewTab;
import com.tailtopia.admin.moderation.service.ManualReviewWorkbenchService;
import com.tailtopia.admin.places.dto.PlaceEditForm;
import com.tailtopia.admin.places.dto.PlaceFilter;
import com.tailtopia.admin.places.service.AdminPlaceQueryService;
import com.tailtopia.admin.places.service.AdminPlaceService;
import com.tailtopia.admin.places.service.PlaceMergeService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.i18n.Messages;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import java.util.List;

/**
 * B6 场所管理（V1.3.0 Story 5.2，AB-17A ①；模板 B · UI 稿 2-20 / 2-22）。只读：列表 + 详情抽屉；处置端点在 5.3、新建在 5.4。
 * <ul>
 * <li>{@code GET /admin/places}：整页；{@code HX-Request}（筛选 / 翻页）只回 {@code fragments/places-list :: rows}（表格 + 分页，摘要条 oob）。</li>
 * <li>{@code GET /admin/places/{id}/drawer}：抽屉 fragment（五区）；{@code ?commentPage=} 评论翻页只回 {@code :: comments}。</li>
 * <li>权限 {@code place.manage}（查看即管理码，D-9 / D-17 不预授予预置角色）；不存在 / 已删 → 404 fragment（{@code AdminBusinessExceptionAdvice}）。</li>
 * <li>Story 5.3 处置六端点（edit / delist / restore / photos/{id}/remove / comments/{id}/remove / merge）+ 合并候选 GET：
 * 成功 200 抽屉 fragment + oob 列表行 + toast + {@code HX-Trigger: admin:places-refresh}；写操作三件套（@PreAuthorize + 同事务审计 + 三语 key）。</li>
 * </ul>
 */
@Controller
public class AdminPlaceController {

    public static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('place.manage')";
    static final String ROUTE = "/admin/places";

    /** 处置成功后让筛选栏按当前筛选重拉表格 + 摘要条（Story 5.3）。 */
    public static final String PLACES_REFRESH = "admin:places-refresh";

    private final AdminPlaceQueryService query;
    private final AdminPlaceService placeService;
    private final PlaceMergeService mergeService;
    /** Story 5.4：A1 场所举报页签从本控制器的端点处置（form {@code from=review}）→ 回复核工作台的 done / detail fragment。 */
    private final ManualReviewWorkbenchService reviewWorkbench;
    private final Messages msg;

    public AdminPlaceController(AdminPlaceQueryService query, AdminPlaceService placeService, PlaceMergeService mergeService,
            ManualReviewWorkbenchService reviewWorkbench, Messages msg) {
        this.query = query;
        this.placeService = placeService;
        this.mergeService = mergeService;
        this.reviewWorkbench = reviewWorkbench;
        this.msg = msg;
    }

    @GetMapping(ROUTE)
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "q", required = false) String q,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "page", required = false) Integer page,
            @RequestParam(value = "warn", required = false) String warn,
            @RequestParam(value = "create", required = false) String create,
            HxRequest hx, Model model) {
        PlaceFilter filter = PlaceFilter.of(q, type, status, city, page);
        populate(filter, model);
        if (hx.isHtmx()) {
            return "admin/fragments/places-list :: rows(true)"; // 摘要条随 oob 一并换
        }
        model.addAttribute("active", "places");
        if ("outsideJakarta".equals(warn)) {
            model.addAttribute("warn", msg.get("admin.v130.places.warn.outsideJakarta"));
        }
        model.addAttribute("openCreate", create != null);
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

    // ===== Story 5.3：处置六端点（AC5：成功 200 + 抽屉 fragment + oob 列表该行 / 摘要条；失败 422 / 404 fragment；无整页跳转）=====

    /** 合并弹层候选：复用 search，只列 ACTIVE、排除自身（AC4）。 */
    @GetMapping(ROUTE + "/{id:\\d+}/merge-candidates")
    @PreAuthorize(VIEW_AUTH)
    public String mergeCandidates(@PathVariable long id, @RequestParam(value = "q", required = false) String q, Model model) {
        AdminPlaceQueryService.ListResult r = query.search(PlaceFilter.of(q, null, "ACTIVE", null, 0));
        model.addAttribute("mergedId", id);
        model.addAttribute("mergedName", query.row(id).name()); // 校验存在 + confirm 文案「并入 B」（复审 #3）
        model.addAttribute("candidates", r.rows().stream().filter(x -> x.id() != id).toList());
        model.addAttribute("q", q);
        return "admin/fragments/drawer-places :: mergeCandidates";
    }

    @PostMapping(ROUTE + "/{id:\\d+}/edit")
    @PreAuthorize(VIEW_AUTH)
    public String edit(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "name", required = false) String name, @RequestParam(value = "placeType", required = false) String placeType,
            @RequestParam(value = "tags", required = false) String tags, @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "city", required = false) String city, @RequestParam(value = "addressText", required = false) String addressText,
            @RequestParam(value = "lat", required = false) String lat, @RequestParam(value = "lng", required = false) String lng,
            HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        AdminPlaceService.EditResult r = placeService.edit(id, PlaceEditForm.of(name, placeType, tags, description, city, addressText, lat, lng),
                admin.getAdminAccountId());
        if (r.outsideJakarta()) {
            model.addAttribute("warn", msg.get("admin.v130.places.warn.outsideJakarta"));
        }
        return afterAction(id, r.changed() ? "admin.flash.places.edited" : "admin.flash.places.noChange", model, response);
    }

    @PostMapping(ROUTE + "/{id:\\d+}/delist")
    @PreAuthorize(VIEW_AUTH)
    public String delist(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "from", required = false) String from, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        boolean changed = placeService.delist(id, admin.getAdminAccountId());
        if (fromReview(from)) {
            if (!changed) {
                // 已 DELISTED 的场所再下架是 no-op、举报未动（复审 #9）：停留本条、不删行
                return reviewDetail(id, msg.get("admin.flash.places.noChange"), model);
            }
            // A1 场所举报页签（5.4 AC4）：下架顺带把 PENDING 举报置 ACTIONED → 该行终态，自动下一条
            return reviewDone(id, msg.get("admin.flash.places.delistedWithReports"), hx, model, response);
        }
        return afterAction(id, changed ? "admin.flash.places.delisted" : "admin.flash.places.noChange", model, response);
    }

    /** A1 场所举报：驳回该场所全部 PENDING 举报（Story 5.4 AC4）；B6 抽屉不用。 */
    @PostMapping(ROUTE + "/{id:\\d+}/reports/dismiss-all")
    @PreAuthorize(VIEW_AUTH)
    public String dismissReports(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "from", required = false) String from, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:/admin/manual-review?tab=place";
        }
        int n = placeService.dismissReports(id, admin.getAdminAccountId());
        if (fromReview(from)) {
            return reviewDone(id, msg.get("admin.flash.places.reportsDismissed", n), hx, model, response);
        }
        return afterAction(id, "admin.flash.places.reportsDismissed", model, response);
    }

    @PostMapping(ROUTE + "/{id:\\d+}/restore")
    @PreAuthorize(VIEW_AUTH)
    public String restore(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        boolean changed = placeService.restore(id, admin.getAdminAccountId());
        return afterAction(id, changed ? "admin.flash.places.restored" : "admin.flash.places.noChange", model, response);
    }

    @PostMapping(ROUTE + "/{id:\\d+}/photos/{photoId:\\d+}/remove")
    @PreAuthorize(VIEW_AUTH)
    public String removePhoto(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, @PathVariable long photoId,
            @RequestParam(value = "from", required = false) String from, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        boolean changed = placeService.removePhoto(id, photoId, admin.getAdminAccountId());
        if (fromReview(from)) {
            return reviewDetail(id, msg.get(changed ? "admin.flash.places.photoRemoved" : "admin.flash.places.noChange"), model);
        }
        return afterAction(id, changed ? "admin.flash.places.photoRemoved" : "admin.flash.places.noChange", model, response);
    }

    @PostMapping(ROUTE + "/{id:\\d+}/comments/{commentId:\\d+}/remove")
    @PreAuthorize(VIEW_AUTH)
    public String removeComment(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id, @PathVariable long commentId,
            @RequestParam(value = "from", required = false) String from, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        boolean changed = placeService.removeComment(id, commentId, admin.getAdminAccountId());
        if (fromReview(from)) {
            return reviewDetail(id, msg.get(changed ? "admin.flash.places.commentRemoved" : "admin.flash.places.noChange"), model);
        }
        return afterAction(id, changed ? "admin.flash.places.commentRemoved" : "admin.flash.places.noChange", model, response);
    }

    /** 合并：{@code {id}} = 被并入的 B，body {@code keepId} = 保留的 A（AC4）；成功后抽屉停在 B（已并入 → A）。 */
    @PostMapping(ROUTE + "/{id:\\d+}/merge")
    @PreAuthorize(VIEW_AUTH)
    public String merge(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "keepId", required = false) Long keepId, HxRequest hx, Model model, HttpServletResponse response) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?open=" + id;
        }
        if (keepId == null) {
            throw AppException.validation("请选择保留场所").code("admin.err.places.keepRequired");
        }
        mergeService.merge(id, keepId, admin.getAdminAccountId());
        model.addAttribute("mergedKeepId", keepId);
        return afterAction(id, "admin.flash.places.merged", model, response);
    }

    // ===== Story 5.4：新建场所（运营预置录入，手填坐标 D-33；不引地图库 D-32）=====

    /** 新建表单 fragment（抽屉体）。标记人下拉 = 发布身份池；城市 datalist = 已有城市联想（D-39）。 */
    @GetMapping(ROUTE + "/new/drawer")
    @PreAuthorize(VIEW_AUTH)
    public String createDrawer(HxRequest hx, Model model) {
        if (!hx.isHtmx()) {
            return "redirect:" + ROUTE + "?create=1";
        }
        model.addAttribute("markers", placeService.markerOptions());
        model.addAttribute("cities", query.cities());
        model.addAttribute("typeOptions", com.tailtopia.admin.places.domain.PlaceType.KNOWN);
        return "admin/fragments/drawer-places-create :: create-form";
    }

    /**
     * 新建（AC2）：multipart（字段 + photos ≤9）。成功 → {@code HX-Redirect} 到列表并 {@code ?open=<id>} 高亮新行（PRG），坐标在雅加达都会区外
     * 再带 {@code warn=outsideJakarta} 黄条；失败 422 行内 err（{@code AdminBusinessExceptionAdvice}）。
     */
    @PostMapping(ROUTE)
    @PreAuthorize(VIEW_AUTH)
    public String create(@AuthenticationPrincipal AdminUserDetails admin,
            @RequestParam(value = "name", required = false) String name, @RequestParam(value = "placeType", required = false) String placeType,
            @RequestParam(value = "tags", required = false) String tags, @RequestParam(value = "description", required = false) String description,
            @RequestParam(value = "city", required = false) String city, @RequestParam(value = "addressText", required = false) String addressText,
            @RequestParam(value = "lat", required = false) String lat, @RequestParam(value = "lng", required = false) String lng,
            @RequestParam(value = "markerUserId", required = false) Long markerUserId,
            @RequestParam(value = "photos", required = false) List<MultipartFile> photos,
            HxRequest hx, HttpServletResponse response) {
        AdminPlaceService.CreateResult r = placeService.create(PlaceEditForm.of(name, placeType, tags, description, city, addressText, lat, lng),
                markerUserId, photos, admin.getAdminAccountId());
        String target = ROUTE + "?open=" + r.placeId() + (r.outsideJakarta() ? "&warn=outsideJakarta" : "");
        if (hx.isHtmx()) {
            response.setHeader("HX-Redirect", target);
            return "admin/fragments/drawer-places-create :: created";
        }
        return "redirect:" + target;
    }

    private static boolean fromReview(String from) {
        return "review".equals(from);
    }

    /** A1 场所举报页签处置成功：复核工作台 done fragment（data-next-id + oob 删行 + 页签计数 + toast + 角标刷新）。 */
    private String reviewDone(long placeId, String message, HxRequest hx, Model model, HttpServletResponse response) {
        model.addAttribute("done", reviewWorkbench.afterDispose(hx.currentUrl(), ReviewTab.PLACE, placeId));
        model.addAttribute("message", message);
        AdminFragmentResponses.triggerBadgeRefresh(response);
        return "admin/fragments/review-done :: done";
    }

    /** A1 场所举报页签「停留本条」类动作（删照片 / 删评论）：重渲染右栏 + toast。 */
    private String reviewDetail(long placeId, String toast, Model model) {
        model.addAttribute("d", reviewWorkbench.detail(ReviewTab.PLACE, placeId));
        model.addAttribute("toast", toast);
        return "admin/fragments/review-detail :: detail";
    }

    /**
     * 处置成功统一响应（AC5）：抽屉 fragment + oob 列表该行（按 id 原位替换）+ toast，并 {@code HX-Trigger: admin:places-refresh}
     * 让筛选栏按<b>当前筛选</b>重拉表格与摘要条（摘要条随筛选联动，服务端不知道页面当前筛选，故不直接 oob 摘要）。
     */
    private String afterAction(long id, String toastKey, Model model, HttpServletResponse response) {
        model.addAttribute("d", query.drawer(id, 0));
        model.addAttribute("row", query.row(id));
        model.addAttribute("toast", msg.get(toastKey));
        AdminFragmentResponses.trigger(response, PLACES_REFRESH);
        return "admin/fragments/drawer-places :: afterAction";
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
