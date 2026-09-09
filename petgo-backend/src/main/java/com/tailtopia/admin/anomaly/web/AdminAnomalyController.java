package com.tailtopia.admin.anomaly.web;

import com.tailtopia.admin.anomaly.domain.AnomalyStatus;
import com.tailtopia.admin.anomaly.domain.ConsultAnomaly;
import com.tailtopia.admin.anomaly.dto.AnomalyNoteLine;
import com.tailtopia.admin.anomaly.service.ConsultAnomalyService;
import com.tailtopia.admin.service.AdminUserDetails;
import com.tailtopia.admin.shared.web.AdminFragmentResponses;
import com.tailtopia.admin.shared.web.StateTab;
import com.tailtopia.admin.shared.web.HxRequest;
import com.tailtopia.shared.error.AppException;
import com.tailtopia.shared.media.SignedUrlService;
import com.tailtopia.shared.i18n.Messages;
import com.tailtopia.auth.service.AccountQueryService;
import com.tailtopia.vet.repository.VetAccountRepository;
import jakarta.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
 * 问诊异常工单（Story 5.1，AB-4A）。SSR + HTMX，{@code /admin/anomalies}，不返 JSON。
 * 查看 {@code consult.view_anomalies}；处理（备注/标记已处理）{@code consult.handle}；**无删除端点（AC6）**。
 * 仅展示会话元数据 + 处理图现签 URL（绝不读第三方 IM/AI，NFR5）。
 *
 * <p>V1.3.0 Story 2.6：重构为模板 A 双栏工作台——两态页签（OPEN / RESOLVED）+ 左栏队列（时间倒序、每页 20 滚动）+
 * 右栏四区（工单卡 / 会话卡 + 去取证 / 备注时间线 / 操作区）。两个写端点路径 / 参数 / 权限零变更，方法内按
 * {@link HxRequest} 分叉；旧 {@code GET /admin/anomalies/{id}} 详情页退役（并入 {@code /{id}/detail} fragment，D-23 不做跳转）。
 */
@Controller
public class AdminAnomalyController {

    private static final String VIEW_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('consult.view_anomalies')";
    private static final String HANDLE_AUTH = "hasRole('SUPER_ADMIN') or hasAuthority('consult.handle')";

    static final int PAGE_SIZE = 20;

    private final ConsultAnomalyService anomalyService;
    private final SignedUrlService signedUrlService;
    /** V1.3.0 Story 2.6 AC1：左栏行「用户昵称 × 兽医名」整页一次取（只读，不入实体）。 */
    private final AccountQueryService accountQuery;
    private final VetAccountRepository vets;
    /** V1.3.0 Story 2.9：空态「其他队列还有 N 条」去向（与角标同源）。 */

    /** 后台操作提示与报错按当前语言输出（模板里的静态文案走 Thymeleaf #{...}，不经这里）。 */
    private final Messages msg;

    public AdminAnomalyController(ConsultAnomalyService anomalyService, SignedUrlService signedUrlService,
            AccountQueryService accountQuery, VetAccountRepository vets, Messages msg) {
        this.anomalyService = anomalyService;
        this.signedUrlService = signedUrlService;
        this.accountQuery = accountQuery;
        this.vets = vets;
        this.msg = msg;
    }

    /**
     * 工作台整页。{@code state=open|resolved}（默认 open）；旧 {@code ?status=OPEN|RESOLVED|all|open|resolved} 兼容映射
     * （{@code all} 归待处理页签）。htmx 请求（滚动翻页）只回左栏行片段。
     */
    @GetMapping("/admin/anomalies")
    @PreAuthorize(VIEW_AUTH)
    public String list(@RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page,
            HxRequest hx, Model model) {
        model.addAttribute("active", "anomalies");
        populateQueue(resolveState(state, status), page, model);
        return hx.isHtmx() ? "admin/fragments/anomaly-queue :: rows" : "admin/anomalies";
    }

    /** 左栏队列 fragment（切两态 / 滚动翻页）。 */
    @GetMapping("/admin/anomalies/queue")
    @PreAuthorize(VIEW_AUTH)
    public String queueFragment(@RequestParam(value = "state", required = false) String state,
            @RequestParam(value = "page", defaultValue = "0") int page, Model model) {
        populateQueue(resolveState(state, null), page, model);
        return page > 0 ? "admin/fragments/anomaly-queue :: rows" : "admin/fragments/anomaly-queue :: list";
    }

    /** 右栏四区 fragment（取代旧整页详情 {@code GET /admin/anomalies/{id}}，已退役）。 */
    @GetMapping("/admin/anomalies/{id:\\d+}/detail")
    @PreAuthorize(VIEW_AUTH)
    public String detail(@PathVariable long id, Model model) {
        populateDetail(id, model);
        return "admin/fragments/anomaly-panel :: detail";
    }

    /** 加备注：htmx → 200 备注时间线 fragment（局部追加，不换条）；非 htmx → PRG 回工作台。 */
    @PostMapping("/admin/anomalies/{id}/note")
    @PreAuthorize(HANDLE_AUTH)
    public String note(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam("note") String note, HxRequest hx, Model model, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            // 422（空备注）/ 403 由 AdminBusinessExceptionAdvice 出 fragment，不 try/catch。
            anomalyService.appendNote(id, note, admin.getAdminAccountId(), admin.getDisplayName());
            populateNotes(id, model);
            model.addAttribute("toast", msg.get("admin.flash.anomaly.noteAdded"));
            return "admin/fragments/anomaly-panel :: notes";
        }
        try {
            anomalyService.appendNote(id, note, admin.getAdminAccountId(), admin.getDisplayName());
            flash.addFlashAttribute("notice", msg.get("admin.flash.anomaly.noteAdded"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/anomalies";
    }

    /** 标记已处理：htmx → 200 处置 fragment（data-next-id + oob 行 / 计数 + HX-Trigger）；非 htmx → PRG。 */
    @PostMapping("/admin/anomalies/{id}/resolve")
    @PreAuthorize(HANDLE_AUTH)
    public String resolve(@AuthenticationPrincipal AdminUserDetails admin, @PathVariable long id,
            @RequestParam(value = "resolutionImageKey", required = false) String resolutionImageKey,
            HxRequest hx, Model model, HttpServletResponse response, RedirectAttributes flash) {
        if (hx.isHtmx()) {
            anomalyService.resolve(id, resolutionImageKey, admin.getAdminAccountId());
            model.addAttribute("removedId", id);
            model.addAttribute("nextId", anomalyService.nextOpenId());
            model.addAttribute("counts", counts());
            model.addAttribute("message", msg.get("admin.flash.anomaly.resolved"));
            AdminFragmentResponses.triggerBadgeRefresh(response);
            return "admin/fragments/anomaly-done :: done";
        }
        try {
            anomalyService.resolve(id, resolutionImageKey, admin.getAdminAccountId());
            flash.addFlashAttribute("notice", msg.get("admin.flash.anomaly.resolved"));
        } catch (AppException e) {
            flash.addFlashAttribute("error", msg.resolve(e));
        }
        return "redirect:/admin/anomalies";
    }

    private void populateQueue(AnomalyStatus state, int page, Model model) {
        Page<ConsultAnomaly> queue = anomalyService.page(state, PageRequest.of(Math.max(page, 0), PAGE_SIZE));
        model.addAttribute("state", state.name().toLowerCase());
        model.addAttribute("page", Math.max(page, 0));
        model.addAttribute("queue", queue);
        Map<String, Long> counts = counts();
        model.addAttribute("counts", counts);
        model.addAttribute("stateTabs", List.of(
                new StateTab(StateTab.href("/admin/anomalies", "state", "open"), "admin.v130.anomalies.tab.open",
                        "anomaly-tab-count-open", counts.getOrDefault("open", 0L), state == AnomalyStatus.OPEN),
                new StateTab(StateTab.href("/admin/anomalies", "state", "resolved"), "admin.v130.anomalies.tab.resolved",
                        "anomaly-tab-count-resolved", counts.getOrDefault("resolved", 0L), state == AnomalyStatus.RESOLVED)));
        // 用户昵称 / 兽医名整页一次取（≤20 行；注销用户 / 查不到的行回退 #id）
        List<ConsultAnomaly> rows = queue == null ? List.of() : queue.getContent();
        Map<Long, String> userNames = new HashMap<>();
        Set<Long> userIds = rows.stream().map(ConsultAnomaly::getUserId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!userIds.isEmpty()) {
            accountQuery.findAuthorViews(userIds).forEach((uid, v) -> {
                if (v != null && !v.deleted() && v.nickname() != null) {
                    userNames.put(uid, v.nickname());
                }
            });
        }
        Map<Long, String> vetNames = new HashMap<>();
        Set<Long> vetIds = rows.stream().map(ConsultAnomaly::getVetId).filter(Objects::nonNull).collect(Collectors.toSet());
        if (!vetIds.isEmpty()) {
            vets.findAllById(vetIds).forEach(v -> vetNames.put(v.getId(), v.getDisplayName()));
        }
        model.addAttribute("userNames", userNames);
        model.addAttribute("vetNames", vetNames);
    }

    private ConsultAnomaly populateNotes(long id, Model model) {
        ConsultAnomaly a = anomalyService.find(id)
                .orElseThrow(() -> AppException.notFound("异常工单不存在").code("admin.err.anomaly.notFound"));
        model.addAttribute("anomaly", a);
        model.addAttribute("notes", AnomalyNoteLine.parse(a.getInternalNote()));
        return a;
    }

    private void populateDetail(long id, Model model) {
        ConsultAnomaly a = populateNotes(id, model);
        // 处理图现签短 TTL URL（不缓存、不入库、不落日志）。
        String key = a.getResolutionImageKey();
        model.addAttribute("resolutionImageUrl", (key == null || key.isBlank()) ? null : signedUrlService.sign(key));
    }

    private Map<String, Long> counts() {
        return Map.of("open", anomalyService.count(AnomalyStatus.OPEN),
                "resolved", anomalyService.count(AnomalyStatus.RESOLVED));
    }

    /** {@code state} 优先；旧 {@code status}（OPEN / RESOLVED / open / resolved / all）兼容；其余 → OPEN。 */
    public static AnomalyStatus resolveState(String state, String status) {
        String raw = state != null && !state.isBlank() ? state : status;
        if (raw == null || raw.isBlank()) {
            return AnomalyStatus.OPEN;
        }
        try {
            return AnomalyStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return AnomalyStatus.OPEN;
        }
    }
}
